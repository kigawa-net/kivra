package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TaskRunState
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

private val TERMINAL_SQL = TaskRunState.terminalNames.joinToString(",") { "'$it'" }

/**
 * Run Lease on the `task_runs` row (design #10 section 5.2 / #11 section 3.3).
 *
 * - acquire: only for a non-terminal, non-held run (or one whose lease expired more than `grace` ago); bumps `lease_generation`.
 * - renew: requires owner + generation match and `lease_expires_at > NOW`. An expired lease can never be
 *   revived by renew; the only way back is a new acquire (generation + 1).
 * - Expiry is judged by the DB clock only. At exactly `lease_expires_at = NOW` neither acquire nor renew
 *   succeeds (the safe side).
 */
class SqlLeaseStore(
    private val database: Database,
    @Suppress("unused") private val clock: Clock = Clock.systemUTC(),
) : LeaseStore {

    override fun acquire(runId: RunId, owner: String, ttl: Duration, grace: Duration): Lease? =
        transaction(database) {
            val updated = execUpdate(
                """
                UPDATE task_runs
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
            if (updated == 0) return@transaction null
            // The row is locked by our own UPDATE until commit, so this read is the generation we just assigned.
            val generation = execQuery(
                "SELECT lease_generation FROM task_runs WHERE run_id = ? AND lease_owner = ?",
                runId.value, owner,
            ) { it.getLong(1) }.single()
            Lease(runId, owner, generation)
        }

    override fun renew(lease: Lease, ttl: Duration): Boolean = transaction(database) {
        execUpdate(
            """
            UPDATE task_runs
               SET lease_expires_at = ${DbTime.nowPlusSeconds()}, version = version + 1
             WHERE run_id = ? AND lease_owner = ? AND lease_generation = ?
               AND lease_expires_at > ${DbTime.NOW}
               AND state NOT IN ($TERMINAL_SQL)
            """.trimIndent(),
            ttl.seconds, lease.runId.value, lease.owner, lease.generation,
        ) == 1
    }

    override fun release(lease: Lease): Boolean = transaction(database) {
        execUpdate(
            """
            UPDATE task_runs
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
 */
fun Transaction.requireLease(lease: Lease) {
    val valid = execQuery(
        """
        SELECT 1 FROM task_runs
         WHERE run_id = ? AND lease_owner = ? AND lease_generation = ?
           AND lease_expires_at > ${DbTime.NOW}
           AND state NOT IN ($TERMINAL_SQL)
         FOR UPDATE
        """.trimIndent(),
        lease.runId.value, lease.owner, lease.generation,
    ) { it.getInt(1) }
    if (valid.isEmpty()) throw LeaseLostException("lease lost for run ${lease.runId} (owner=${lease.owner}, generation=${lease.generation})")
}
