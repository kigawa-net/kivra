package net.kigawa.kivra.selfdev.incident

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SELF_DEV_TASK_PREFIX
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.selfdev.llmwiring.isDuplicateKey
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** The state of one incident row (`selfdev_incident_issues`, V46). */
enum class IncidentState { CLAIMED, OPENED, FAILED, UNKNOWN }

/** The rows the detector writes: the claim that makes a cause idempotent, and the outcome of its issue. */
interface IncidentStore {
    /** How many incidents were claimed since [since] (every state counts: a failed or unknown create also uses the hourly budget). */
    fun issuesSince(since: Instant): Int

    /**
     * Claims the cause of [incident]. True when no row exists for its fingerprint, or when the row was claimed before [cutoff] (its window
     * has passed). False while the cause is inside its window: it is then never reported again.
     */
    fun claim(fingerprint: String, incident: Incident, cutoff: Instant): Boolean

    /** Sets the outcome of a claimed cause: OPENED with the issue number, or FAILED / UNKNOWN without one. */
    fun finish(fingerprint: String, state: IncidentState, issueNumber: Long?)
}

/** Failed or unknown self-development Runs, counted by state and reason code. The reason is the stored code, as it is in the table. */
data class RunFailureCount(val state: String, val reason: String?, val count: Long)

/** The facts the detector reads. Read-only. */
interface IncidentSource {
    /** The self-development Runs that ended FAILED or UNKNOWN with `updated_at` at or after [since], counted per state and reason. */
    fun failedRunCounts(since: Instant): List<RunFailureCount>

    /** Whether the autopilot's circuit breaker is open. */
    fun breakerOpen(): Boolean
}

/** The database side of the claim, over the V46 table. Every write is one statement; the primary key and a compare-and-set guard it. */
class DbIncidentStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : IncidentStore {

    override fun issuesSince(since: Instant): Int = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM selfdev_incident_issues WHERE created_at >= ?",
            LocalDateTime.ofInstant(since, ZoneOffset.UTC),
        ) { it.getLong(1) }.firstOrNull()?.toInt() ?: 0
    }

    override fun claim(fingerprint: String, incident: Incident, cutoff: Instant): Boolean {
        val now = utcNow(clock)
        val inserted = try {
            transaction(database) {
                execUpdate(
                    "INSERT INTO selfdev_incident_issues (fingerprint, incident_code, reason_code, state, issue_number, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'CLAIMED', NULL, ?, ?)",
                    fingerprint, incident.code.name, incident.reason, now, now,
                )
            }
            true
        } catch (e: Exception) {
            if (!e.isDuplicateKey()) throw e
            false
        }
        if (inserted) return true
        // A row exists: it is claimed again only when its window has passed. The compare-and-set makes two Pods agree on one claim.
        return transaction(database) {
            execUpdate(
                "UPDATE selfdev_incident_issues SET state = 'CLAIMED', issue_number = NULL, incident_code = ?, reason_code = ?, " +
                    "created_at = ?, updated_at = ? WHERE fingerprint = ? AND created_at < ?",
                incident.code.name, incident.reason, now, now, fingerprint, LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC),
            )
        } == 1
    }

    override fun finish(fingerprint: String, state: IncidentState, issueNumber: Long?) {
        transaction(database) {
            execUpdate(
                "UPDATE selfdev_incident_issues SET state = ?, issue_number = ?, updated_at = ? WHERE fingerprint = ?",
                state.name, issueNumber, utcNow(clock), fingerprint,
            )
        }
    }
}

/** The database side of the detection. Reads the Runs (self-development only) and the autopilot's control row. */
class DbIncidentSource(
    private val database: Database,
    private val control: SelfDevAutopilotControlRepository,
) : IncidentSource {

    override fun failedRunCounts(since: Instant): List<RunFailureCount> = transaction(database) {
        execQuery(
            "SELECT state, state_reason, COUNT(*) FROM development_runs " +
                "WHERE task_id LIKE ? AND state IN ('FAILED', 'UNKNOWN') AND updated_at >= ? GROUP BY state, state_reason",
            "$SELF_DEV_TASK_PREFIX%", LocalDateTime.ofInstant(since, ZoneOffset.UTC),
        ) { RunFailureCount(it.getString(1), it.getString(2), it.getLong(3)) }
    }

    override fun breakerOpen(): Boolean = control.read().breakerOpen
}
