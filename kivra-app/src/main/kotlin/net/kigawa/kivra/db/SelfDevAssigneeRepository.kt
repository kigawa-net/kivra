package net.kigawa.kivra.db

import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.SQLException
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.routes.validatedText
import net.kigawa.kivra.selfdev.isOwnerKeyShape
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Persistence of assignees (V33): a name and a role, with owner keys bound to it. Nothing calls this yet, so no behaviour changes.
 * Raw SQL through [execUpdate] and [execQuery], as in [SelfDevOrderRepository]: a change is a conditional UPDATE (compare and set on
 * `version`), and a refusal after a write is rolled back by throwing [Rollback].
 *
 * Invariants kept here (not in the database): the last ACTIVE ADMIN cannot be demoted, disabled or unbound, and a caller cannot demote or
 * disable the assignee it belongs to. Both checks run in the transaction that has locked every ACTIVE ADMIN row with FOR UPDATE (in
 * assignee_id order, so two such transactions queue instead of deadlocking), so two concurrent demotions cannot both pass.
 * The owner key and the display name are never logged and never written to the event table; the event table has hashed references.
 */

enum class AssigneeRole { ADMIN, OPERATOR, APPROVER, AI, VIEWER }

enum class AssigneeState { ACTIVE, DISABLED }

/** A fixed code for a refusal. Never free text. */
enum class AssigneeRefusal {
    /** The display name is empty, too long, or holds a control or bidi control character. */
    INVALID_DISPLAY_NAME,
    /** The owner key is not of the form `<length>:<issuer>:<subject>`, or is too long. */
    INVALID_OWNER_KEY,
    /** The owner key already belongs to an assignee (the same one or another). */
    OWNER_KEY_ALREADY_BOUND,
    /** The owner key is not bound to this assignee. */
    NOT_BOUND,
    /** The change would leave no ACTIVE ADMIN (or no ACTIVE ADMIN with a bound owner key). */
    LAST_ADMIN,
    /** The caller belongs to the target assignee, and may not change its role or state. */
    SELF_CHANGE,
    /** The role or state already is the requested one. */
    NO_CHANGE,
}

sealed interface AssigneeResult<out T> {
    data class Moved<out T>(val value: T) : AssigneeResult<T>
    data object NotFound : AssigneeResult<Nothing>
    /** The expected version is not the current one. Nothing changed. */
    data object StaleVersion : AssigneeResult<Nothing>
    data class Refused(val reason: AssigneeRefusal) : AssigneeResult<Nothing>
}

data class Assignee(
    val assigneeId: String,
    val displayName: String,
    val role: AssigneeRole,
    val state: AssigneeState,
    val version: Long,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** What an owner key may do: the assignee it belongs to, its role and its state (a DISABLED assignee is returned as DISABLED). */
data class AssigneeAccess(val assigneeId: String, val role: AssigneeRole, val state: AssigneeState)

class SelfDevAssigneeRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    // ---- reads -------------------------------------------------------------------------------------------------------------------

    fun find(assigneeId: String): Assignee? = transaction(database) { assigneeIn(assigneeId, lock = false) }

    fun list(): List<Assignee> = transaction(database) {
        execQuery("SELECT $COLUMNS FROM selfdev_assignees ORDER BY created_at, assignee_id") { it.toAssignee() }
    }

    /** The hashed references of the owner keys bound to an assignee (never the keys). */
    fun memberRefs(assigneeId: String): List<String> = transaction(database) {
        execQuery("SELECT member_ref FROM selfdev_assignee_members WHERE assignee_id = ? ORDER BY member_ref", assigneeId) { it.getString(1) }
    }

    /** The access of an owner key, or null when it is bound to no assignee. A DISABLED assignee is returned with state DISABLED. */
    fun accessOf(ownerKey: String): AssigneeAccess? {
        if (!isOwnerKeyShape(ownerKey)) return null
        return transaction(database) {
            execQuery(
                """
                SELECT a.assignee_id, a.role, a.state FROM selfdev_assignee_members m
                JOIN selfdev_assignees a ON a.assignee_id = m.assignee_id
                WHERE m.member_ref = ? AND m.owner_key = ?
                """.trimIndent(),
                memberRef(ownerKey), ownerKey,
            ) { AssigneeAccess(it.getString(1), AssigneeRole.valueOf(it.getString(2)), AssigneeState.valueOf(it.getString(3))) }.firstOrNull()
        }
    }

    // ---- writes ------------------------------------------------------------------------------------------------------------------

    fun create(displayName: String, role: AssigneeRole, actorKey: String): AssigneeResult<Assignee> {
        val name = normalizedName(displayName) ?: return AssigneeResult.Refused(AssigneeRefusal.INVALID_DISPLAY_NAME)
        return transaction(database) { insertAssignee(name, role, actorRef(actorKey)) }.let { AssigneeResult.Moved(it) }
    }

    /**
     * Startup seeding: makes the assignee and binds [ownerKey] only when that owner key is bound to nothing. If it is bound already, even
     * to an assignee that was demoted or disabled since, nothing is written and the existing assignee is returned: a demotion is never
     * undone. A concurrent seeding by another instance ends the same way. The rows are marked 'bootstrap' and the events have no actor.
     */
    fun seedIfAbsent(displayName: String, role: AssigneeRole, ownerKey: String): AssigneeResult<Assignee> {
        val name = normalizedName(displayName) ?: return AssigneeResult.Refused(AssigneeRefusal.INVALID_DISPLAY_NAME)
        if (!isOwnerKeyShape(ownerKey)) return AssigneeResult.Refused(AssigneeRefusal.INVALID_OWNER_KEY)
        return try {
            transaction(database) {
                val existing = boundAssigneeId(ownerKey)
                if (existing != null) return@transaction assigneeIn(existing, lock = false)!!
                val made = insertAssignee(name, role, null)
                insertMember(made.assigneeId, ownerKey, null, made.createdAt)
                made
            }.let { AssigneeResult.Moved(it) }
        } catch (e: Exception) {
            if (!e.hasUniqueViolation()) throw e
            transaction(database) { boundAssigneeId(ownerKey)?.let { assigneeIn(it, lock = false) } }
                ?.let { AssigneeResult.Moved(it) } ?: throw e
        }
    }

    /** What [recoverAdmin] did. Counts only; no key and no name. */
    data class AdminRecovery(val created: Boolean, val bound: Boolean, val reenabled: Boolean, val promoted: Boolean)

    /**
     * The recovery flag (ADR 0020): makes the assignee of [ownerKey] an ACTIVE ADMIN. A DISABLED one is enabled and a demoted one is made
     * ADMIN again. When the key is bound to nothing, it is bound to the assignee that startup seeding made for [displayName] (created_by
     * 'bootstrap'), or to a new one. No other assignee is touched. The events have no actor ('bootstrap' rows), as in [seedIfAbsent].
     */
    fun recoverAdmin(displayName: String, ownerKey: String): AssigneeResult<AdminRecovery> {
        val name = normalizedName(displayName) ?: return AssigneeResult.Refused(AssigneeRefusal.INVALID_DISPLAY_NAME)
        if (!isOwnerKeyShape(ownerKey)) return AssigneeResult.Refused(AssigneeRefusal.INVALID_OWNER_KEY)
        return transaction(database) {
            lockActiveAdmins()
            var created = false
            var bound = false
            var id = boundAssigneeId(ownerKey)
            if (id == null) {
                val seeded = execQuery(
                    "SELECT assignee_id FROM selfdev_assignees WHERE created_by = ? AND display_name = ? ORDER BY created_at, assignee_id",
                    BOOTSTRAP, name,
                ) { it.getString(1) }.firstOrNull()
                if (seeded == null) {
                    val made = insertAssignee(name, AssigneeRole.ADMIN, null)
                    insertMember(made.assigneeId, ownerKey, null, made.createdAt)
                    return@transaction AssigneeResult.Moved(AdminRecovery(created = true, bound = true, reenabled = false, promoted = false))
                }
                insertMember(seeded, ownerKey, null, utcNow(clock))
                id = seeded
                bound = true
            }
            val current = assigneeIn(id, lock = true)!!
            val reenable = current.state != AssigneeState.ACTIVE
            val promote = current.role != AssigneeRole.ADMIN
            if (reenable || promote) {
                val now = utcNow(clock)
                val changed = execUpdate(
                    "UPDATE selfdev_assignees SET role = 'ADMIN', state = 'ACTIVE', version = version + 1, updated_at = ? " +
                        "WHERE assignee_id = ? AND version = ?",
                    now, id, current.version,
                )
                if (changed != 1) throw IllegalStateException("assignee recovery lost a race")
                if (promote) appendEvent(id, "ROLE_CHANGED", null, current.role.name, AssigneeRole.ADMIN.name, null, now)
                if (reenable) appendEvent(id, "ENABLED", null, null, null, null, now)
            }
            AssigneeResult.Moved(AdminRecovery(created = created, bound = bound, reenabled = reenable, promoted = promote))
        }
    }

    fun bindMember(assigneeId: String, ownerKey: String, actorKey: String): AssigneeResult<Unit> {
        if (!isOwnerKeyShape(ownerKey)) return AssigneeResult.Refused(AssigneeRefusal.INVALID_OWNER_KEY)
        return try {
            transaction(database) {
                assigneeIn(assigneeId, lock = true) ?: throw Rollback(AssigneeResult.NotFound)
                if (boundAssigneeId(ownerKey) != null) throw Rollback(AssigneeResult.Refused(AssigneeRefusal.OWNER_KEY_ALREADY_BOUND))
                insertMember(assigneeId, ownerKey, actorRef(actorKey), utcNow(clock))
                AssigneeResult.Moved(Unit)
            }
        } catch (r: Rollback) {
            r.outcome
        } catch (e: Exception) {
            // Two binds of the same key at once: the UNIQUE key refuses the second.
            if (e.hasUniqueViolation()) AssigneeResult.Refused(AssigneeRefusal.OWNER_KEY_ALREADY_BOUND) else throw e
        }
    }

    fun unbindMember(assigneeId: String, ownerKey: String, actorKey: String): AssigneeResult<Unit> {
        if (!isOwnerKeyShape(ownerKey)) return AssigneeResult.Refused(AssigneeRefusal.INVALID_OWNER_KEY)
        return try {
            transaction(database) {
                val admins = lockActiveAdmins()
                val target = assigneeIn(assigneeId, lock = true) ?: throw Rollback(AssigneeResult.NotFound)
                if (boundAssigneeId(ownerKey) != assigneeId) throw Rollback(AssigneeResult.Refused(AssigneeRefusal.NOT_BOUND))
                if (target.role == AssigneeRole.ADMIN && target.state == AssigneeState.ACTIVE) {
                    // Another ACTIVE ADMIN with a bound owner key must remain, or nobody could manage the assignees.
                    val remaining = admins.count { it != assigneeId && memberCount(it) > 0 } + (if (memberCount(assigneeId) > 1) 1 else 0)
                    if (remaining == 0) throw Rollback(AssigneeResult.Refused(AssigneeRefusal.LAST_ADMIN))
                }
                val ref = memberRef(ownerKey)
                execUpdate("DELETE FROM selfdev_assignee_members WHERE member_ref = ? AND owner_key = ?", ref, ownerKey)
                appendEvent(assigneeId, "MEMBER_UNBOUND", actorRef(actorKey), null, null, ref, utcNow(clock))
                AssigneeResult.Moved(Unit)
            }
        } catch (r: Rollback) {
            r.outcome
        }
    }

    fun changeRole(assigneeId: String, newRole: AssigneeRole, expectedVersion: Long, actorKey: String): AssigneeResult<Assignee> =
        change(assigneeId, expectedVersion, actorKey) { current ->
            if (current.role == newRole) return@change Change.Refuse(AssigneeRefusal.NO_CHANGE)
            Change.Apply(role = newRole, state = current.state, kind = "ROLE_CHANGED")
        }

    fun setState(assigneeId: String, state: AssigneeState, expectedVersion: Long, actorKey: String): AssigneeResult<Assignee> =
        change(assigneeId, expectedVersion, actorKey) { current ->
            if (current.state == state) return@change Change.Refuse(AssigneeRefusal.NO_CHANGE)
            Change.Apply(role = current.role, state = state, kind = if (state == AssigneeState.DISABLED) "DISABLED" else "ENABLED")
        }

    // ---- internals ---------------------------------------------------------------------------------------------------------------

    private sealed interface Change {
        data class Refuse(val reason: AssigneeRefusal) : Change
        data class Apply(val role: AssigneeRole, val state: AssigneeState, val kind: String) : Change
    }

    /** A role or state change by CAS, with the last-ADMIN and self checks made under the lock on the ACTIVE ADMIN rows. */
    private fun change(
        assigneeId: String,
        expectedVersion: Long,
        actorKey: String,
        decide: (Assignee) -> Change,
    ): AssigneeResult<Assignee> = try {
        transaction(database) {
            val admins = lockActiveAdmins()
            val current = assigneeIn(assigneeId, lock = true) ?: throw Rollback(AssigneeResult.NotFound)
            if (current.version != expectedVersion) throw Rollback(AssigneeResult.StaleVersion)
            val next = when (val d = decide(current)) {
                is Change.Refuse -> throw Rollback(AssigneeResult.Refused(d.reason))
                is Change.Apply -> d
            }
            if (boundAssigneeId(actorKey) == assigneeId) throw Rollback(AssigneeResult.Refused(AssigneeRefusal.SELF_CHANGE))
            val staysActiveAdmin = next.role == AssigneeRole.ADMIN && next.state == AssigneeState.ACTIVE
            if (current.role == AssigneeRole.ADMIN && current.state == AssigneeState.ACTIVE && !staysActiveAdmin && admins.size <= 1) {
                throw Rollback(AssigneeResult.Refused(AssigneeRefusal.LAST_ADMIN))
            }
            val now = utcNow(clock)
            val changed = execUpdate(
                "UPDATE selfdev_assignees SET role = ?, state = ?, version = version + 1, updated_at = ? WHERE assignee_id = ? AND version = ?",
                next.role.name, next.state.name, now, assigneeId, expectedVersion,
            )
            if (changed != 1) throw Rollback(AssigneeResult.StaleVersion)
            val from = if (next.kind == "ROLE_CHANGED") current.role.name else null
            val to = if (next.kind == "ROLE_CHANGED") next.role.name else null
            appendEvent(assigneeId, next.kind, actorRef(actorKey), from, to, null, now)
            AssigneeResult.Moved(assigneeIn(assigneeId, lock = false)!!)
        }
    } catch (r: Rollback) {
        r.outcome
    }

    private fun Transaction.insertAssignee(name: String, role: AssigneeRole, actor: String?): Assignee {
        val now = utcNow(clock)
        val id = Ulid.generate(clock)
        execUpdate(
            "INSERT INTO selfdev_assignees (assignee_id, display_name, role, state, version, created_by, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?, ?)",
            id, name, role.name, actor ?: BOOTSTRAP, now, now,
        )
        appendEvent(id, "CREATED", actor, null, role.name, null, now)
        return Assignee(id, name, role, AssigneeState.ACTIVE, 0, actor ?: BOOTSTRAP, now, now)
    }

    private fun Transaction.insertMember(assigneeId: String, ownerKey: String, actor: String?, now: LocalDateTime) {
        val ref = memberRef(ownerKey)
        execUpdate(
            "INSERT INTO selfdev_assignee_members (owner_key, member_ref, assignee_id, created_at) VALUES (?, ?, ?, ?)",
            ownerKey, ref, assigneeId, now,
        )
        appendEvent(assigneeId, "MEMBER_BOUND", actor, null, null, ref, now)
    }

    private fun Transaction.appendEvent(
        assigneeId: String,
        kind: String,
        actor: String?,
        from: String?,
        to: String?,
        memberRef: String?,
        now: LocalDateTime,
    ) {
        execUpdate(
            "INSERT INTO selfdev_assignee_events (event_id, assignee_id, kind, actor_ref, from_role, to_role, member_ref, occurred_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            Ulid.generate(clock), assigneeId, kind, actor, from, to, memberRef, now,
        )
    }

    /** Locks every ACTIVE ADMIN row, in assignee_id order, and returns their IDs. */
    private fun Transaction.lockActiveAdmins(): List<String> = execQuery(
        "SELECT assignee_id FROM selfdev_assignees WHERE role = 'ADMIN' AND state = 'ACTIVE' ORDER BY assignee_id FOR UPDATE",
    ) { it.getString(1) }

    private fun Transaction.assigneeIn(assigneeId: String, lock: Boolean): Assignee? =
        execQuery("SELECT $COLUMNS FROM selfdev_assignees WHERE assignee_id = ?${if (lock) " FOR UPDATE" else ""}", assigneeId) { it.toAssignee() }
            .firstOrNull()

    private fun Transaction.boundAssigneeId(ownerKey: String): String? = execQuery(
        "SELECT assignee_id FROM selfdev_assignee_members WHERE member_ref = ? AND owner_key = ?", memberRef(ownerKey), ownerKey,
    ) { it.getString(1) }.firstOrNull()

    private fun Transaction.memberCount(assigneeId: String): Int =
        execQuery("SELECT COUNT(*) FROM selfdev_assignee_members WHERE assignee_id = ?", assigneeId) { it.getInt(1) }.first()

    private class Rollback(val outcome: AssigneeResult<Nothing>) : RuntimeException(null, null, false, false)

    private fun ResultSet.toAssignee() = Assignee(
        assigneeId = getString("assignee_id"),
        displayName = getString("display_name"),
        role = AssigneeRole.valueOf(getString("role")),
        state = AssigneeState.valueOf(getString("state")),
        version = getLong("version"),
        createdBy = getString("created_by"),
        createdAt = getObject("created_at", LocalDateTime::class.java),
        updatedAt = getObject("updated_at", LocalDateTime::class.java),
    )

    companion object {
        const val MAX_DISPLAY_NAME_CODE_POINTS = 64
        const val BOOTSTRAP = "bootstrap"
        private const val COLUMNS = "assignee_id, display_name, role, state, version, created_by, created_at, updated_at"

        /** NFC, then trim and the shared text rule: 1..64 code points, no control or bidi control character. Null when refused. */
        internal fun normalizedName(raw: String): String? =
            validatedText(Normalizer.normalize(raw, Normalizer.Form.NFC), MAX_DISPLAY_NAME_CODE_POINTS)

        /** The first 12 hex digits of the SHA-256 of an owner key, as in [SelfDevOrderRepository]. */
        fun actorRef(key: String): String = sha256Hex(key).take(12)

        /** The first 16 hex digits of the SHA-256 of an owner key. Safe to log; it is not the key. */
        fun memberRef(key: String): String = sha256Hex(key).take(16)

        private fun sha256Hex(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

private fun Throwable.hasUniqueViolation(): Boolean = generateSequence(this) { it.cause }.take(5).any {
    it is SQLException && (it.sqlState == "23505" || it.errorCode == 1062 || it.errorCode == 23505)
}
