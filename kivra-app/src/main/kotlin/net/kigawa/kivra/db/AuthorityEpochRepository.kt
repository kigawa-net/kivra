package net.kigawa.kivra.db

import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import net.kigawa.kivra.core.ProviderCapability
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** The Authority Epoch values for one (tenant, provider) as recorded in Kivra MariaDB. */
data class AuthorityEpochSnapshot(
    val providerContractVersion: String,
    val capability: ProviderCapability,
    val capabilityRevision: Long,
    val authorityEpoch: Long,
    val grantGeneration: Long,
)

/** A publish that would move the recorded epoch backwards: an older epoch, or a lower grant / capability revision. */
open class AuthorityEpochRejectedException(message: String) : RuntimeException(message)

/** The incoming snapshot is strictly older than the recorded one (out-of-order or replayed sync). Nothing is written. */
class StaleAuthorityEpochException(message: String) : AuthorityEpochRejectedException(message)

/**
 * The incoming snapshot conflicts with the recorded one: the same authority epoch with different content, or a contract /
 * capability change that does not advance the capability revision. Nothing is written. This is the signal to alert on.
 */
class ConflictingAuthorityEpochException(message: String) : AuthorityEpochRejectedException(message)

/** No usable epoch copy: missing, never published with an issuer (pre-V8), or older than the freshness bound. */
class AuthorityEpochNotFreshException(message: String) : RuntimeException(message)

/** Freshness bound for an Authority Epoch copy. A copy published longer ago than this must not drive a transition. */
val AUTHORITY_EPOCH_FRESHNESS_BOUND: Duration = Duration.ofMinutes(5)

private const val ISSUER_MAX_LENGTH = 128
private const val MAX_PUBLISH_ATTEMPTS = 3

/**
 * Kivra MariaDB copy of the Trusted Evidence Authority's current epoch (Issues #94, #100, table `evidence_authority_epochs`).
 *
 * Stream identity is `(tenant_id, provider_id)`. The copy only moves forward:
 * - [publish] runs in one transaction. It locks the current row (`SELECT ... FOR UPDATE`) and writes only if the incoming
 *   snapshot is accepted:
 *   - `authority_epoch` lower than recorded: [StaleAuthorityEpochException], nothing written.
 *   - same `authority_epoch`: accepted only when the whole snapshot is identical (idempotent). Different content:
 *     [ConflictingAuthorityEpochException], nothing written.
 *   - higher `authority_epoch`: `grant_generation` and `capability_revision` must not decrease (otherwise
 *     [StaleAuthorityEpochException]). A change of contract version or capability must advance `capability_revision`
 *     (otherwise [ConflictingAuthorityEpochException]).
 *   A replay of the same snapshot (for example after a restart) is therefore a no-op on the epoch values.
 * - The first insert is race-safe: a unique-key or deadlock conflict on the first insert rolls the attempt back and the
 *   whole attempt is repeated, at most [MAX_PUBLISH_ATTEMPTS] times, re-reading the row each time.
 * - [publish] records `published_by` (the issuer) and `published_at` (the clock time of the accepted publish). Neither is a
 *   secret. Trust in the issuer is NOT verified here: the caller must be the authenticated Authority sync. That verification
 *   belongs to the future real Authority integration and is out of scope for this class.
 * - [requireFresh] fails closed when the copy is missing, has no `published_at`, or is older than the freshness bound.
 * - [lockFreshForTransition] is used by [OperationJournalRepository] inside the transition transaction. It takes a row lock
 *   (`SELECT ... FOR UPDATE`) and applies the same freshness check, so that a concurrent [publish] and a transition are
 *   serialized on this row and a stale copy refuses the transition before the Journal CAS. The commit sees either the old
 *   epoch or the new one, never a mix.
 *
 * Not a cross-store guarantee. The value compared is the copy in Kivra MariaDB, not the Authority's own state. An
 * Authority change that has not been synced here is invisible to the commit. A Permit / Revocation ACK protocol with a
 * short TTL would be needed to bound that window; it is a separate design (#94, #102) and is not implemented here.
 */
class AuthorityEpochRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Test seam: runs after the locked read of each publish attempt, inside that attempt's transaction. */
    internal var afterRead: ((attempt: Int) -> Unit)? = null

    /**
     * Publishes [snapshot] as the current Authority Epoch of ([tenantId], [providerId]) on behalf of [issuer].
     * Throws [StaleAuthorityEpochException] or [ConflictingAuthorityEpochException] when the snapshot is not accepted.
     */
    fun publish(tenantId: String, providerId: String, snapshot: AuthorityEpochSnapshot, issuer: String) {
        require(issuer.isNotBlank() && issuer.length <= ISSUER_MAX_LENGTH) { "issuer must be a non-blank identifier of at most $ISSUER_MAX_LENGTH characters" }
        var attempt = 1
        while (true) {
            try {
                publishOnce(tenantId, providerId, snapshot, issuer, attempt)
                return
            } catch (e: Exception) {
                if (attempt >= MAX_PUBLISH_ATTEMPTS || !e.isRaceConflict()) throw e
                attempt++
            }
        }
    }

    private fun publishOnce(tenantId: String, providerId: String, snapshot: AuthorityEpochSnapshot, issuer: String, attempt: Int) {
        transaction(database) {
            val current = readStored(tenantId, providerId, lock = true)?.snapshot
            afterRead?.invoke(attempt)
            val publishedAt = utcNow(clock)
            if (current == null) {
                execUpdate(
                    """
                    INSERT INTO evidence_authority_epochs
                        (tenant_id, provider_id, provider_contract_version, capability, capability_revision,
                         authority_epoch, grant_generation, updated_at, published_by, published_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), ?, ?)
                    """.trimIndent(),
                    tenantId, providerId, snapshot.providerContractVersion, snapshot.capability.name,
                    snapshot.capabilityRevision, snapshot.authorityEpoch, snapshot.grantGeneration, issuer, publishedAt,
                )
            } else {
                checkAccepted(current, snapshot)
                // Idempotent accept (identical snapshot) still refreshes published_at: the authenticated sync re-asserting the
                // current state is the freshness heartbeat.
                execUpdate(
                    """
                    UPDATE evidence_authority_epochs
                       SET provider_contract_version = ?, capability = ?, capability_revision = ?,
                           authority_epoch = ?, grant_generation = ?, updated_at = NOW(6),
                           published_by = ?, published_at = ?
                     WHERE tenant_id = ? AND provider_id = ?
                    """.trimIndent(),
                    snapshot.providerContractVersion, snapshot.capability.name, snapshot.capabilityRevision,
                    snapshot.authorityEpoch, snapshot.grantGeneration, issuer, publishedAt, tenantId, providerId,
                )
            }
        }
    }

    fun current(tenantId: String, providerId: String): AuthorityEpochSnapshot? = transaction(database) {
        readStored(tenantId, providerId, lock = false)?.snapshot
    }

    /**
     * Returns the copy only when it was published within [maxAge] of the clock time, and fails closed otherwise. A
     * negative age (published in the future relative to this clock) is also refused.
     */
    fun requireFresh(
        tenantId: String,
        providerId: String,
        maxAge: Duration = AUTHORITY_EPOCH_FRESHNESS_BOUND,
    ): AuthorityEpochSnapshot {
        val stored = transaction(database) { readStored(tenantId, providerId, lock = false) }
            ?: throw AuthorityEpochNotFreshException("no authority epoch copy for this provider")
        checkFresh(stored, clock.instant(), maxAge)
        return stored.snapshot
    }

    /**
     * Row-locked, freshness-checked read for a transition transaction (the same transaction as the Journal CAS, Issue #100).
     * Fails with [AuthorityEpochNotFreshException] when the copy is missing, has no publish time, or is older than [maxAge]
     * at [now]. [now] is the transition's own clock reading, so freshness and the rest of the evidence gate use one instant.
     */
    internal fun Transaction.lockFreshForTransition(
        tenantId: String,
        providerId: String,
        now: Instant,
        maxAge: Duration = AUTHORITY_EPOCH_FRESHNESS_BOUND,
    ): AuthorityEpochSnapshot {
        val stored = readStored(tenantId, providerId, lock = true)
            ?: throw AuthorityEpochNotFreshException("no authority epoch copy for this provider")
        checkFresh(stored, now, maxAge)
        return stored.snapshot
    }

    /** Fail-closed freshness: a copy without publish time, a negative age (published in the future), or an age over [maxAge]. */
    private fun checkFresh(stored: StoredEpoch, now: Instant, maxAge: Duration) {
        val publishedAt = stored.publishedAt
            ?: throw AuthorityEpochNotFreshException("authority epoch copy has no publish time (fail-closed)")
        val age = Duration.between(publishedAt.toInstant(ZoneOffset.UTC), now)
        if (age.isNegative || age > maxAge) {
            throw AuthorityEpochNotFreshException("authority epoch copy is not fresh (age $age, bound $maxAge)")
        }
    }

    private data class StoredEpoch(val snapshot: AuthorityEpochSnapshot, val publishedAt: LocalDateTime?)

    private fun Transaction.readStored(tenantId: String, providerId: String, lock: Boolean): StoredEpoch? {
        val sql = """
            SELECT provider_contract_version, capability, capability_revision, authority_epoch, grant_generation, published_at
              FROM evidence_authority_epochs
             WHERE tenant_id = ? AND provider_id = ?
            """.trimIndent() + if (lock) " FOR UPDATE" else ""
        return execQuery(sql, tenantId, providerId) {
            StoredEpoch(
                snapshot = AuthorityEpochSnapshot(
                    providerContractVersion = it.getString(1),
                    capability = ProviderCapability.valueOf(it.getString(2)),
                    capabilityRevision = it.getLong(3),
                    authorityEpoch = it.getLong(4),
                    grantGeneration = it.getLong(5),
                ),
                publishedAt = it.getObject(6, LocalDateTime::class.java),
            )
        }.singleOrNull()
    }

    /** Throws unless [next] may replace [current]. Returns normally for an identical snapshot (idempotent). */
    private fun checkAccepted(current: AuthorityEpochSnapshot, next: AuthorityEpochSnapshot) {
        when {
            next.authorityEpoch < current.authorityEpoch -> throw StaleAuthorityEpochException(
                "authority epoch ${next.authorityEpoch} is older than the recorded ${current.authorityEpoch}",
            )
            next.authorityEpoch == current.authorityEpoch -> if (next != current) throw ConflictingAuthorityEpochException(
                "authority epoch ${next.authorityEpoch} is already recorded with different content",
            )
            next.grantGeneration < current.grantGeneration -> throw StaleAuthorityEpochException(
                "grant generation ${next.grantGeneration} would roll back from ${current.grantGeneration}",
            )
            next.capabilityRevision < current.capabilityRevision -> throw StaleAuthorityEpochException(
                "capability revision ${next.capabilityRevision} would roll back from ${current.capabilityRevision}",
            )
            (next.providerContractVersion != current.providerContractVersion || next.capability != current.capability) &&
                next.capabilityRevision <= current.capabilityRevision -> throw ConflictingAuthorityEpochException(
                "contract or capability changed without advancing the capability revision (${current.capabilityRevision})",
            )
        }
    }

    /** Unique-key violation (MariaDB 1062 / 23000, H2 23505) or deadlock (40001 / 1213) anywhere in the cause chain. */
    private fun Throwable.isRaceConflict(): Boolean =
        generateSequence(this) { it.cause }.any { t ->
            t is SQLException && (
                t.sqlState == "23000" || t.sqlState == "23505" || t.sqlState == "40001" ||
                    t.errorCode == 1062 || t.errorCode == 1213 || t.errorCode == 23505
                )
        }
}
