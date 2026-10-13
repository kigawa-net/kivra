package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.DevelopmentRunState
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

private val TERMINAL_SQL = DevelopmentRunState.terminalNames.joinToString(",") { "'$it'" }

/**
 * Run Lease on the `development_runs` row (design #10 section 5.2 / #11 section 3.3).
 *
 * - acquire: only for a non-terminal, non-held run (or one whose lease expired more than `grace` ago); bumps `lease_generation`.
 *   In the same transaction it sweeps the IN_FLIGHT operations left by earlier generations to UNKNOWN (restart sweep), so no
 *   caller can take over a run without the sweep.
 * - renew: requires owner + generation match, `lease_expires_at > NOW`, no hold and no cancel request. An expired lease can never be
 *   revived by renew; the only way back is a new acquire (generation + 1).
 * - Expiry is judged by the DB clock only. At exactly `lease_expires_at = NOW` neither acquire nor renew
 *   succeeds (the safe side).
 */
class SqlLeaseStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : LeaseStore {

    override fun acquire(runId: RunId, owner: String, ttl: Duration, grace: Duration): Lease? =
        transaction(database) {
            val lease = acquireInTx(runId, owner, ttl, grace) ?: return@transaction null
            sweepStaleInFlight(lease, clock)
            lease
        }

    private fun Transaction.acquireInTx(runId: RunId, owner: String, ttl: Duration, grace: Duration): Lease? {
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET lease_owner = ?,
                   lease_generation = lease_generation + 1,
                   lease_expires_at = ${DbTime.nowPlusSeconds()},
                   version = version + 1
             WHERE run_id = ?
               AND state NOT IN ($TERMINAL_SQL)
               AND hold_reason IS NULL
               AND (lease_owner IS NULL OR lease_expires_at < ${DbTime.nowMinusSeconds()})
            """.trimIndent(),
            owner, ttl.seconds, runId.value, grace.seconds,
        )
        if (updated == 0) return null
        // The row is locked by our own UPDATE until commit, so this read is the generation we just assigned.
        val generation = execQuery(
            "SELECT lease_generation FROM development_runs WHERE run_id = ? AND lease_owner = ?",
            runId.value, owner,
        ) { it.getLong(1) }.single()
        return Lease(runId, owner, generation)
    }

    override fun renew(lease: Lease, ttl: Duration): Boolean = transaction(database) {
        execUpdate(
            """
            UPDATE development_runs
               SET lease_expires_at = ${DbTime.nowPlusSeconds()}, version = version + 1
             WHERE run_id = ? AND lease_owner = ? AND lease_generation = ?
               AND lease_expires_at > ${DbTime.NOW}
               AND state NOT IN ($TERMINAL_SQL)
               AND hold_reason IS NULL AND cancel_requested = FALSE
            """.trimIndent(),
            ttl.seconds, lease.runId.value, lease.owner, lease.generation,
        ) == 1
    }

    override fun release(lease: Lease): Boolean = transaction(database) {
        execUpdate(
            """
            UPDATE development_runs
               SET lease_owner = NULL, lease_expires_at = NULL, version = version + 1
             WHERE run_id = ? AND lease_owner = ? AND lease_generation = ?
            """.trimIndent(),
            lease.runId.value, lease.owner, lease.generation,
        ) == 1
    }
}

/**
 * Locks the run row and verifies that [lease] is still valid on the DB clock: owner and generation match,
 * `lease_expires_at > NOW`, run not terminal. Every write that hangs off a Run (journal, outbox, state)
 * starts with this inside its transaction; a stale worker gets [LeaseLostException] and the transaction rolls back.
 *
 * Hold / Cancel are enforced here too (fail-closed): if `hold_reason` is set, or `cancel_requested` is set and
 * [allowCancelRequested] is false, the still-valid lease holder gets [RunHaltedException] and nothing is written.
 * [allowCancelRequested] is true only for the worker's acknowledgement of the cancel (transition to CANCELLED).
 */
fun Transaction.requireLease(lease: Lease, allowCancelRequested: Boolean = false) {
    val rows = execQuery(
        """
        SELECT hold_reason, cancel_requested FROM development_runs
         WHERE run_id = ? AND lease_owner = ? AND lease_generation = ?
           AND lease_expires_at > ${DbTime.NOW}
           AND state NOT IN ($TERMINAL_SQL)
         FOR UPDATE
        """.trimIndent(),
        lease.runId.value, lease.owner, lease.generation,
    ) { it.getString(1) to it.getBoolean(2) }
    val (hold, cancel) = rows.singleOrNull()
        ?: throw LeaseLostException("lease lost for run ${lease.runId} (owner=${lease.owner}, generation=${lease.generation})")
    if (hold != null) throw RunHaltedException("run ${lease.runId} is on hold ($hold)")
    if (cancel && !allowCancelRequested) throw RunHaltedException("cancellation was requested for run ${lease.runId}")
}
