package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * The single control row of the autopilot (V18, ADR 0011 sections 2.4 and 5.2): the whole-autopilot pause, the circuit breaker's
 * consecutive-failure count, and the number of Runs submitted on the current day.
 *
 * Each change is one atomic UPDATE, so no read-modify-write window exists. The day is the UTC date (provisional, ADR U-3).
 * Counters are written in a fixed order inside one statement, because MariaDB evaluates SET assignments left to right and H2 uses
 * the old values: the expressions below are written so both give the same result.
 */

/** Exposed mirror of V18 `selfdev_autopilot_control`. */
internal object SelfDevAutopilotControlTable : Table("selfdev_autopilot_control") {
    val controlId = byte("control_id")
    val paused = bool("paused")
    val pausedBy = varchar("paused_by", 255).nullable()
    val pausedAt = datetime("paused_at").nullable()
    val consecutiveFailures = integer("consecutive_failures")
    val breakerOpen = bool("breaker_open")
    val dayUtc = date("day_utc").nullable()
    val runsToday = integer("runs_today")
    val version = long("version")
    override val primaryKey = PrimaryKey(controlId)
}

data class AutopilotControl(
    val paused: Boolean,
    val pausedBy: String?,
    val pausedAt: LocalDateTime?,
    val consecutiveFailures: Int,
    val breakerOpen: Boolean,
    val dayUtc: LocalDate?,
    val runsToday: Int,
    val version: Long,
)

class SelfDevAutopilotControlRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun read(): AutopilotControl = transaction(database) {
        SelfDevAutopilotControlTable.selectAll().where { SelfDevAutopilotControlTable.controlId eq CONTROL_ID }
            .single().let {
                AutopilotControl(
                    paused = it[SelfDevAutopilotControlTable.paused],
                    pausedBy = it[SelfDevAutopilotControlTable.pausedBy],
                    pausedAt = it[SelfDevAutopilotControlTable.pausedAt],
                    consecutiveFailures = it[SelfDevAutopilotControlTable.consecutiveFailures],
                    breakerOpen = it[SelfDevAutopilotControlTable.breakerOpen],
                    dayUtc = it[SelfDevAutopilotControlTable.dayUtc],
                    runsToday = it[SelfDevAutopilotControlTable.runsToday],
                    version = it[SelfDevAutopilotControlTable.version],
                )
            }
    }

    /**
     * Sets the whole-autopilot pause. Pausing records who and when; resuming keeps the last pause record. Returns the row after the
     * change.
     */
    fun setPaused(paused: Boolean, by: String): AutopilotControl {
        transaction(database) {
        require(!paused || (by.isNotEmpty() && by.length <= 255)) { "the operator who pauses must be 1 to 255 characters" }
        val now = utcNow(clock)
        execUpdate(
            """
            UPDATE selfdev_autopilot_control
               SET paused = ?, paused_by = CASE WHEN ? THEN ? ELSE paused_by END,
                   paused_at = CASE WHEN ? THEN ? ELSE paused_at END, version = version + 1
             WHERE control_id = 1
            """.trimIndent(),
            paused, paused, by.takeIf { paused }, paused, now.takeIf { paused },
        )
        }
        // Read after the commit: a read inside the transaction could miss the change under REPEATABLE READ.
        return read()
    }

    /**
     * Records the outcome of one Run: a failure adds one to the consecutive count and opens the breaker when the count reaches
     * [maxConsecutiveFailures]; a success resets the count to 0. A success does not close an open breaker (an operator resumes it).
     * The breaker assignment is listed before the count so that both databases compare against the old count.
     */
    fun recordRunOutcome(succeeded: Boolean, maxConsecutiveFailures: Int) {
        require(maxConsecutiveFailures >= 1) { "the failure limit must be at least 1" }
        transaction(database) {
            if (succeeded) {
                execUpdate(
                    "UPDATE selfdev_autopilot_control SET consecutive_failures = 0, version = version + 1 WHERE control_id = 1",
                )
            } else {
                execUpdate(
                    """
                    UPDATE selfdev_autopilot_control
                       SET breaker_open = CASE WHEN consecutive_failures + 1 >= ? THEN TRUE ELSE breaker_open END,
                           consecutive_failures = consecutive_failures + 1, version = version + 1
                     WHERE control_id = 1
                    """.trimIndent(),
                    maxConsecutiveFailures,
                )
            }
        }
    }

    /** Operator resume of the circuit breaker: the count goes back to 0 and the breaker closes (ADR section 5.4). */
    fun resetBreaker() {
        transaction(database) {
            execUpdate(
                "UPDATE selfdev_autopilot_control SET consecutive_failures = 0, breaker_open = FALSE, version = version + 1 WHERE control_id = 1",
            )
        }
    }

    /**
     * Takes one of the day's Run slots if fewer than [dailyLimit] have been taken on the current UTC day. The day rolls over inside
     * the same statement: a row from an earlier day restarts at 1. Returns false, and changes nothing, when the cap is reached.
     */
    fun tryReserveRun(dailyLimit: Int): Boolean {
        require(dailyLimit >= 1) { "the daily limit must be at least 1" }
        return transaction(database) {
            val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            execUpdate(
                """
                UPDATE selfdev_autopilot_control
                   SET runs_today = CASE WHEN day_utc = ? THEN runs_today + 1 ELSE 1 END,
                       day_utc = ?, version = version + 1
                 WHERE control_id = 1 AND (day_utc IS NULL OR day_utc <> ? OR runs_today < ?)
                """.trimIndent(),
                today, today, today, dailyLimit,
            ) == 1
        }
    }

    private companion object {
        const val CONTROL_ID: Byte = 1
    }
}
