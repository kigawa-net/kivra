package net.kigawa.kivra.db

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.selfdev.isOwnerKeyShape
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * API tokens of assignees (V34). Nothing calls this yet, so no behaviour changes. Raw SQL as in [SelfDevAssigneeRepository].
 *
 * A token is `kvt_` + base64url (no padding) of 32 random bytes (43 characters). The database keeps only the lowercase hex SHA-256 of it
 * (`token_hash`, UNIQUE, the lookup key) and `token_ref` (its first 16 hex digits, the name used in events and lists). The plaintext is
 * returned once by [issue] and appears nowhere else: not in a row, an event, a log, an exception message or a toString.
 * Only an ACTIVE assignee with the role AI or VIEWER can hold a token, checked when it is issued and on every [verify], so a role or
 * state change takes effect on the next request. A token lives 1..365 days (default 90); an assignee has at most two valid (not revoked,
 * not expired) tokens, so one can be rotated in before the old one is revoked. The limit is kept under a FOR UPDATE lock of the assignee row.
 * That the caller of [issue] and [revoke] is an ADMIN is checked by the caller, not here.
 * [verify] returns an owner key made of the assignee id (see [ownerKeyOf]); the first [issue] binds it to the assignee, in the same transaction.
 */

/** A fixed code for a refusal. Never free text. */
enum class TokenRefusal {
    ASSIGNEE_NOT_FOUND,
    /** The assignee is not ACTIVE, or its role is not AI or VIEWER. */
    ROLE_NOT_ALLOWED,
    TOO_MANY_ACTIVE,
    TTL_OUT_OF_RANGE,
    /** No such token (or the identifier is not a token id or ref). */
    NOT_FOUND,
    ALREADY_REVOKED,
    /** The actor key is not an owner key. */
    INVALID_ACTOR,
    /** The synthetic owner key of the assignee is bound to another assignee. */
    OWNER_KEY_CONFLICT,
}

sealed interface TokenResult<out T> {
    data class Ok<out T>(val value: T) : TokenResult<T>
    data class Refused(val reason: TokenRefusal) : TokenResult<Nothing>
}

/** The reason a token was refused, for internal logging only. It is never part of what [verify] returns. */
enum class InvalidReason { UNKNOWN, EXPIRED, REVOKED, ASSIGNEE_DISABLED, ROLE_NOT_ALLOWED, MALFORMED, OWNER_KEY_CONFLICT }

/**
 * Revokes every not yet revoked, not expired token of [assigneeId] inside the caller's transaction: `revoked_at`, `revoked_by` and one
 * TOKEN_REVOKED event each (actor_ref and token_ref only). Returns how many. Used by the assignee repository on disable and role change.
 */
internal fun org.jetbrains.exposed.sql.Transaction.revokeActiveTokens(assigneeId: String, actor: String, at: LocalDateTime, clock: Clock): Int {
    val rows = execQuery(
        "SELECT token_id, token_ref FROM selfdev_assignee_tokens WHERE assignee_id = ? AND revoked_at IS NULL AND expires_at > ? " +
            "ORDER BY token_id FOR UPDATE",
        assigneeId, at,
    ) { it.getString(1) to it.getString(2) }
    for ((id, ref) in rows) {
        execUpdate("UPDATE selfdev_assignee_tokens SET revoked_at = ?, revoked_by = ? WHERE token_id = ? AND revoked_at IS NULL", at, actor, id)
        execUpdate(
            "INSERT INTO selfdev_assignee_events (event_id, assignee_id, kind, actor_ref, token_ref, occurred_at) VALUES (?, ?, 'TOKEN_REVOKED', ?, ?, ?)",
            Ulid.generate(clock), assigneeId, actor, ref, at,
        )
    }
    return rows.size
}

/** [token] is the plaintext, shown once. [toString] hides it. */
data class IssuedToken(val tokenId: String, val token: String, val tokenRef: String, val expiresAt: LocalDateTime) {
    override fun toString(): String = "IssuedToken(tokenId=$tokenId, token=<redacted>, tokenRef=$tokenRef, expiresAt=$expiresAt)"
}

/** What [list] shows. No hash and no plaintext. [createdBy] is the actor_ref (12 hex). */
data class TokenInfo(
    val tokenId: String,
    val tokenRef: String,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val revoked: Boolean,
)

/** The answer of [verify]. Every failure is the same [Invalid]: the caller cannot tell why. */
sealed interface TokenVerification {
    data class Valid(val assigneeId: String, val ownerKey: String, val role: AssigneeRole, val tokenRef: String) : TokenVerification {
        /** The owner key is left out. */
        override fun toString(): String = "Valid(assigneeId=$assigneeId, role=$role, tokenRef=$tokenRef)"
    }

    data object Invalid : TokenVerification
}

class SelfDevAssigneeTokenRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    /** Called with a fixed code (never the token) for each refused [verify], for a counter or a log line. Exceptions are swallowed. */
    private val onInvalid: (InvalidReason) -> Unit = {},
) {
    private val assignees = SelfDevAssigneeRepository(database, clock)

    fun issue(assigneeId: String, ttlDays: Int = DEFAULT_TTL_DAYS, actorKey: String, now: Instant? = null): TokenResult<IssuedToken> {
        if (ttlDays !in 1..MAX_TTL_DAYS) return TokenResult.Refused(TokenRefusal.TTL_OUT_OF_RANGE)
        if (!isOwnerKeyShape(actorKey)) return TokenResult.Refused(TokenRefusal.INVALID_ACTOR)
        val actor = SelfDevAssigneeRepository.actorRef(actorKey)
        return try {
            transaction(database) {
                val locked = execQuery("SELECT role, state FROM selfdev_assignees WHERE assignee_id = ? FOR UPDATE", assigneeId) {
                    it.getString(1) to it.getString(2)
                }.firstOrNull() ?: throw Rollback(TokenRefusal.ASSIGNEE_NOT_FOUND)
                if (!roleAllowed(locked.first) || locked.second != "ACTIVE") throw Rollback(TokenRefusal.ROLE_NOT_ALLOWED)
                val at = timeOf(now)
                val active = execQuery(
                    "SELECT COUNT(*) FROM selfdev_assignee_tokens WHERE assignee_id = ? AND revoked_at IS NULL AND expires_at > ?",
                    assigneeId, at,
                ) { it.getInt(1) }.first()
                if (active >= MAX_ACTIVE) throw Rollback(TokenRefusal.TOO_MANY_ACTIVE)
                with(assignees) {
                    when (bindMemberIfAbsent(assigneeId, ownerKeyOf(assigneeId), actor, at)) {
                        SelfDevAssigneeRepository.BindOutcome.BOUND_TO_OTHER -> throw Rollback(TokenRefusal.OWNER_KEY_CONFLICT)
                        else -> Unit
                    }
                }
                val plain = newToken()
                val hash = hashOf(plain)
                val ref = hash.take(16)
                val id = Ulid.generate(clock)
                val expires = at.plusDays(ttlDays.toLong())
                execUpdate(
                    "INSERT INTO selfdev_assignee_tokens (token_id, assignee_id, token_hash, token_ref, created_by, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, assigneeId, hash, ref, actor, at, expires,
                )
                appendEvent(assigneeId, "TOKEN_ISSUED", actor, ref, at)
                TokenResult.Ok(IssuedToken(id, plain, ref, expires))
            }
        } catch (r: Rollback) {
            TokenResult.Refused(r.reason)
        }
    }

    /** Revokes a token, named by its token id (26 characters) or its token ref (16 hex). Takes effect at once. */
    fun revoke(identifier: String, actorKey: String, now: Instant? = null): TokenResult<Unit> {
        if (!isOwnerKeyShape(actorKey)) return TokenResult.Refused(TokenRefusal.INVALID_ACTOR)
        val column = when {
            TOKEN_ID_SHAPE.matches(identifier) -> "token_id"
            TOKEN_REF_SHAPE.matches(identifier) -> "token_ref"
            else -> return TokenResult.Refused(TokenRefusal.NOT_FOUND)
        }
        val actor = SelfDevAssigneeRepository.actorRef(actorKey)
        return try {
            transaction(database) {
                val row = execQuery(
                    "SELECT token_id, assignee_id, token_ref, revoked_at FROM selfdev_assignee_tokens WHERE $column = ? FOR UPDATE", identifier,
                ) { arrayOf<Any?>(it.getString(1), it.getString(2), it.getString(3), it.getObject(4)) }.firstOrNull()
                    ?: throw Rollback(TokenRefusal.NOT_FOUND)
                if (row[3] != null) throw Rollback(TokenRefusal.ALREADY_REVOKED)
                val at = timeOf(now)
                val changed = execUpdate(
                    "UPDATE selfdev_assignee_tokens SET revoked_at = ?, revoked_by = ? WHERE token_id = ? AND revoked_at IS NULL",
                    at, actor, row[0],
                )
                if (changed != 1) throw Rollback(TokenRefusal.ALREADY_REVOKED)
                appendEvent(row[1] as String, "TOKEN_REVOKED", actor, row[2] as String, at)
                TokenResult.Ok(Unit)
            }
        } catch (r: Rollback) {
            TokenResult.Refused(r.reason)
        }
    }

    /** The tokens of an assignee, oldest first. Neither the hash nor the plaintext is read. */
    fun list(assigneeId: String): List<TokenInfo> = transaction(database) {
        execQuery(
            "SELECT token_id, token_ref, created_by, created_at, expires_at, revoked_at FROM selfdev_assignee_tokens " +
                "WHERE assignee_id = ? ORDER BY created_at, token_id",
            assigneeId,
        ) {
            TokenInfo(
                tokenId = it.getString(1),
                tokenRef = it.getString(2),
                createdBy = it.getString(3),
                createdAt = it.getObject(4, LocalDateTime::class.java),
                expiresAt = it.getObject(5, LocalDateTime::class.java),
                revoked = it.getObject(6) != null,
            )
        }
    }

    /**
     * Checks a presented token. A malformed value is refused without touching the database. Otherwise the token must be known, not revoked,
     * not expired, and its assignee must be ACTIVE with the role AI or VIEWER right now. Every failure is [TokenVerification.Invalid].
     */
    fun verify(presentedToken: String, now: Instant? = null): TokenVerification {
        if (!TOKEN_SHAPE.matches(presentedToken)) return invalid(InvalidReason.MALFORMED)
        return lookup(presentedToken, now)
    }

    private fun lookup(presentedToken: String, now: Instant?): TokenVerification {
        val hash = hashOf(presentedToken)
        val at = timeOf(now)
        val row = transaction(database) {
            execQuery(
                """
                SELECT t.token_ref, t.assignee_id, t.expires_at, t.revoked_at, a.role, a.state
                FROM selfdev_assignee_tokens t JOIN selfdev_assignees a ON a.assignee_id = t.assignee_id
                WHERE t.token_hash = ?
                """.trimIndent(),
                hash,
            ) {
                VerifyRow(
                    ref = it.getString(1),
                    assigneeId = it.getString(2),
                    expiresAt = it.getObject(3, LocalDateTime::class.java),
                    revoked = it.getObject(4) != null,
                    role = it.getString(5),
                    state = it.getString(6),
                )
            }.firstOrNull()
        } ?: return invalid(InvalidReason.UNKNOWN)
        if (row.revoked) return invalid(InvalidReason.REVOKED)
        if (!row.expiresAt.isAfter(at)) return invalid(InvalidReason.EXPIRED)
        if (row.state != "ACTIVE") return invalid(InvalidReason.ASSIGNEE_DISABLED)
        if (!roleAllowed(row.role)) return invalid(InvalidReason.ROLE_NOT_ALLOWED)
        // The owner key must still lead to this assignee: a binding moved by hand makes the token unusable.
        val ownerKey = ownerKeyOf(row.assigneeId)
        if (assignees.accessOf(ownerKey)?.assigneeId != row.assigneeId) return invalid(InvalidReason.OWNER_KEY_CONFLICT)
        return TokenVerification.Valid(row.assigneeId, ownerKey, AssigneeRole.valueOf(row.role), row.ref)
    }

    private class VerifyRow(
        val ref: String,
        val assigneeId: String,
        val expiresAt: LocalDateTime,
        val revoked: Boolean,
        val role: String,
        val state: String,
    )

    private fun invalid(reason: InvalidReason): TokenVerification {
        try {
            onInvalid(reason)
        } catch (_: Exception) {
        }
        return TokenVerification.Invalid
    }

    private fun timeOf(now: Instant?): LocalDateTime =
        if (now == null) utcNow(clock) else LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun org.jetbrains.exposed.sql.Transaction.appendEvent(assigneeId: String, kind: String, actor: String, tokenRef: String, at: LocalDateTime) {
        execUpdate(
            "INSERT INTO selfdev_assignee_events (event_id, assignee_id, kind, actor_ref, token_ref, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
            Ulid.generate(clock), assigneeId, kind, actor, tokenRef, at,
        )
    }

    private class Rollback(val reason: TokenRefusal) : RuntimeException(null, null, false, false)

    companion object {
        const val TOKEN_PREFIX = "kvt_"
        const val DEFAULT_TTL_DAYS = 90
        const val MAX_TTL_DAYS = 365
        const val MAX_ACTIVE = 2
        const val ISSUER = "kivra-assignee"

        private val TOKEN_SHAPE = Regex("kvt_[A-Za-z0-9_-]{43}")
        private val TOKEN_ID_SHAPE = Regex("[0-9A-HJKMNP-TV-Z]{26}")
        private val TOKEN_REF_SHAPE = Regex("[0-9a-f]{16}")
        private val random = SecureRandom()

        private fun roleAllowed(role: String): Boolean = role == AssigneeRole.AI.name || role == AssigneeRole.VIEWER.name

        /** The owner key of an assignee's tokens: `<utf8 length of the issuer>:kivra-assignee:<assignee_id>`. */
        fun ownerKeyOf(assigneeId: String): String =
            requireNotNull(oidcOwnerKey(ISSUER, assigneeId)) { "assignee id is not usable in an owner key" }

        private fun newToken(): String {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun hashOf(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
