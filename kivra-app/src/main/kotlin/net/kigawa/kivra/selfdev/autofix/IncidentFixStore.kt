package net.kigawa.kivra.selfdev.autofix

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.selfdev.llmwiring.isDuplicateKey
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** The state of one fix row (`selfdev_incident_fixes`, V47). */
enum class FixState { CLAIMED, SUBMITTING, SUBMITTED, ENDED, FAILED }

/** One fix row as the watch reads it. Only fixed fields. */
data class FixRow(
    val fingerprint: String,
    val incidentCode: String,
    val reasonCode: String,
    val issueNumber: Long,
    val state: FixState,
    val rootTaskId: String?,
    val attempts: Int,
)

/** An incident the detector opened on GitHub that no fix row names yet. */
data class FixCandidate(val fingerprint: String, val incidentCode: String, val reasonCode: String, val issueNumber: Long)

/** The rows the fix watch writes. Each write is one statement; the primary key and the unique active slot guard them. */
interface IncidentFixStore {
    /** The oldest opened incident created at or after [since] that no fix row names, or null. */
    fun nextCandidate(since: Instant): FixCandidate?

    /** Claims [candidate] (a CLAIMED row). False when the fingerprint already has a row. */
    fun claim(candidate: FixCandidate): Boolean

    /** The oldest CLAIMED row, or null. */
    fun pending(): FixRow?

    /** The row that holds the active slot (SUBMITTING or SUBMITTED), or null. */
    fun holder(): FixRow?

    /**
     * CLAIMED -> SUBMITTING with the active slot and one more attempt. False when the slot is held by another fix, or the row is not
     * CLAIMED any more. Nothing else changes.
     */
    fun claimSlot(fingerprint: String): Boolean

    /** SUBMITTING -> SUBMITTED with the root task id. */
    fun markSubmitted(fingerprint: String, rootTaskId: String)

    /** Ends the claim: the state becomes [to] (CLAIMED to retry, ENDED or FAILED) and the active slot is released. */
    fun release(fingerprint: String, to: FixState)
}

/** The database side of the fix, over V47. */
class DbIncidentFixStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : IncidentFixStore {

    override fun nextCandidate(since: Instant): FixCandidate? = transaction(database) {
        execQuery(
            "SELECT i.fingerprint, i.incident_code, i.reason_code, i.issue_number " +
                "FROM selfdev_incident_issues i LEFT JOIN selfdev_incident_fixes f ON f.fingerprint = i.fingerprint " +
                "WHERE i.state = 'OPENED' AND i.issue_number IS NOT NULL AND i.created_at >= ? AND f.fingerprint IS NULL " +
                "ORDER BY i.created_at, i.fingerprint LIMIT 1",
            LocalDateTime.ofInstant(since, ZoneOffset.UTC),
        ) { FixCandidate(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) }.firstOrNull()
    }

    override fun claim(candidate: FixCandidate): Boolean {
        val now = utcNow(clock)
        return try {
            transaction(database) {
                execUpdate(
                    "INSERT INTO selfdev_incident_fixes (fingerprint, incident_code, reason_code, issue_number, state, root_task_id, attempts, " +
                        "active_slot, created_at, updated_at) VALUES (?, ?, ?, ?, 'CLAIMED', NULL, 0, NULL, ?, ?)",
                    candidate.fingerprint, candidate.incidentCode, candidate.reasonCode, candidate.issueNumber, now, now,
                )
            }
            true
        } catch (e: Exception) {
            if (!e.isDuplicateKey()) throw e
            false
        }
    }

    override fun pending(): FixRow? = transaction(database) {
        rowsWhere("state = 'CLAIMED'", "ORDER BY created_at, fingerprint LIMIT 1").firstOrNull()
    }

    override fun holder(): FixRow? = transaction(database) {
        rowsWhere("active_slot = 'A'", "LIMIT 1").firstOrNull()
    }

    override fun claimSlot(fingerprint: String): Boolean {
        val now = utcNow(clock)
        return try {
            transaction(database) {
                execUpdate(
                    "UPDATE selfdev_incident_fixes SET state = 'SUBMITTING', active_slot = 'A', attempts = attempts + 1, updated_at = ? " +
                        "WHERE fingerprint = ? AND state = 'CLAIMED'",
                    now, fingerprint,
                )
            } == 1
        } catch (e: Exception) {
            // The unique active slot is held by another fix: this one waits. Any other failure is raised.
            if (!e.isDuplicateKey()) throw e
            false
        }
    }

    override fun markSubmitted(fingerprint: String, rootTaskId: String) {
        transaction(database) {
            execUpdate(
                "UPDATE selfdev_incident_fixes SET state = 'SUBMITTED', root_task_id = ?, updated_at = ? WHERE fingerprint = ? AND state = 'SUBMITTING'",
                rootTaskId, utcNow(clock), fingerprint,
            )
        }
    }

    override fun release(fingerprint: String, to: FixState) {
        transaction(database) {
            execUpdate(
                "UPDATE selfdev_incident_fixes SET state = ?, active_slot = NULL, updated_at = ? WHERE fingerprint = ?",
                to.name, utcNow(clock), fingerprint,
            )
        }
    }

    private fun Transaction.rowsWhere(predicate: String, tail: String): List<FixRow> = execQuery(
        "SELECT fingerprint, incident_code, reason_code, issue_number, state, root_task_id, attempts " +
            "FROM selfdev_incident_fixes WHERE $predicate $tail",
    ) {
        FixRow(
            fingerprint = it.getString(1),
            incidentCode = it.getString(2),
            reasonCode = it.getString(3),
            issueNumber = it.getLong(4),
            state = FixState.valueOf(it.getString(5)),
            rootTaskId = it.getString(6),
            attempts = it.getInt(7),
        )
    }
}

/** The fix of a Run, read by the runner: the incident issue whose fix the Run belongs to, or null. Read only. */
fun interface IncidentRunOrigin {
    fun issueOf(runId: String): Long?
}

/**
 * The Run to incident link: a Run's task (`selfdev_tasks.run_id`) has a plan root (`parent_task_id`), and that root is the root of a fix row.
 * Null for every other Run. The runner gets this only when the fix is on.
 */
class DbIncidentRunOrigin(private val database: Database) : IncidentRunOrigin {
    override fun issueOf(runId: String): Long? = transaction(database) {
        execQuery(
            "SELECT f.issue_number FROM selfdev_tasks t JOIN selfdev_incident_fixes f ON f.root_task_id = t.parent_task_id " +
                "WHERE t.run_id = ? LIMIT 1",
            runId,
        ) { it.getLong(1) }.firstOrNull()
    }
}
