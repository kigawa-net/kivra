package net.kigawa.kivra.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The cost of one cycle of an order, read from the budget ledger (ADR 0015 addendum 2026-10-23, sections B and C.4). Micro-yen.
 * Each field is the sum over the reservation rows of the cycle's chain, grouped by state. A row is counted once.
 */
data class CycleSpend(
    /** COMMITTED and ACKNOWLEDGED: the settled cost. */
    val committedMicroYen: Long,
    /** LAPSED: charged at the estimate, waiting for the true-up. */
    val lapsedMicroYen: Long,
    /** RESERVED: in progress, not charged yet. */
    val reservedMicroYen: Long,
    /** UNPRICED: charged at the estimate, not verified until an operator acknowledges it. */
    val unpricedMicroYen: Long,
    /** True when an UNPRICED row is in the chain, zero yen included. The cycle must not start while this is true (C.4). */
    val hasUnconfirmedUnpriced: Boolean,
    /** Attempts of the cycle that are QUEUED, CLAIMED or RUNNING. Their cost is not in the ledger yet (C.7, U-37). */
    val attemptsActive: Int,
)

/**
 * Reads the cost of one cycle (order, cycle_no) from the budget ledger. Stage 3f of the addendum: read only. It writes no row and takes
 * no lock, so it cannot change the lock order (order rows first, then the tenant budget rows; see SelfDevBudgetLockOrderTest).
 *
 * The chain (section B): selfdev_goals (order_id, cycle_no) -> selfdev_plans (goal_id) -> selfdev_plan_tasks (plan_id, run_id) ->
 * coding_attempts (run_id). The ledger key of a CLI attempt is `coding:<attempt_id>:<lease_generation>`, an exact match on the chain.
 * The API calls have no column that links them, so they are matched by the key prefix (section C.5, C.6): the Planner's
 * `planner-<goal_id>-` and the Developer's `developer-<run_id>-`. The key forms are pinned by SelfDevCycleSpendContractTest.
 */
class SelfDevCycleSpendReader(private val database: Database) {

    fun read(orderId: String, cycleNo: Int): CycleSpend = transaction(database) {
        var committed = 0L
        var lapsed = 0L
        var reserved = 0L
        var unpriced = 0L
        var hasUnpriced = false
        execQuery(LEDGER_SQL, orderId, cycleNo, orderId, cycleNo, PLANNER_PREFIX, orderId, cycleNo, DEVELOPER_PREFIX) { rs ->
            rs.getString(1) to rs.getLong(2)
        }.forEach { (state, amount) ->
            when (state) {
                DbBudgetStore.COMMITTED, DbBudgetStore.ACKNOWLEDGED -> committed += amount
                DbBudgetStore.LAPSED -> lapsed += amount
                DbBudgetStore.RESERVED -> reserved += amount
                DbBudgetStore.UNPRICED -> {
                    unpriced += amount
                    hasUnpriced = true
                }
                DbBudgetStore.RELEASED -> Unit
                // An unknown state is not counted as zero: a silent zero would under-count the cost (ADR 0015 section E).
                else -> error("unknown reservation state in the budget ledger")
            }
        }
        val attempts = execQuery(ACTIVE_ATTEMPTS_SQL, orderId, cycleNo) { it.getInt(1) }.single()
        CycleSpend(
            committedMicroYen = committed,
            lapsedMicroYen = lapsed,
            reservedMicroYen = reserved,
            unpricedMicroYen = unpriced,
            hasUnconfirmedUnpriced = hasUnpriced,
            attemptsActive = attempts,
        )
    }

    companion object {
        /** The Planner's ledger key starts with `planner-<goalId>-` (AutopilotPlanner.requestIdOf, then the gateway's `/<requestId>-a<n>`). */
        const val PLANNER_PREFIX = "planner-"

        /** The Developer's ledger key starts with `developer-<runId>-` (LlmDeveloper.requestIdOf, then the gateway's key). */
        const val DEVELOPER_PREFIX = "developer-"

        /**
         * Every reservation row of the cycle. The three conditions are ORed as semi-joins (IN and EXISTS), so each row is returned once.
         * The ULID ids in the LIKE patterns contain no wildcard characters.
         */
        private val LEDGER_SQL = """
            SELECT r.state, r.amount_micro_yen
              FROM budget_reservations r
             WHERE r.reservation_key IN (
                   SELECT CONCAT('${DbBudgetStore.CODING_KEY_PREFIX}', a.attempt_id, ':', a.lease_generation)
                     FROM selfdev_goals g
                     JOIN selfdev_plans p ON p.goal_id = g.goal_id
                     JOIN selfdev_plan_tasks t ON t.plan_id = p.plan_id
                     JOIN coding_attempts a ON a.run_id = t.run_id
                    WHERE g.order_id = ? AND g.cycle_no = ?)
                OR EXISTS (
                   SELECT 1 FROM selfdev_goals g
                    WHERE g.order_id = ? AND g.cycle_no = ?
                      AND r.reservation_key LIKE CONCAT(?, g.goal_id, '-%'))
                OR EXISTS (
                   SELECT 1 FROM selfdev_goals g
                     JOIN selfdev_plans p ON p.goal_id = g.goal_id
                     JOIN selfdev_plan_tasks t ON t.plan_id = p.plan_id
                    WHERE g.order_id = ? AND g.cycle_no = ? AND t.run_id IS NOT NULL
                      AND r.reservation_key LIKE CONCAT(?, t.run_id, '-%'))
            """.trimIndent()

        /** Attempts of the cycle that have not finished. Each counts once, even when a Run appears in two tasks. */
        private val ACTIVE_ATTEMPTS_SQL = """
            SELECT COUNT(DISTINCT a.attempt_id)
              FROM selfdev_goals g
              JOIN selfdev_plans p ON p.goal_id = g.goal_id
              JOIN selfdev_plan_tasks t ON t.plan_id = p.plan_id
              JOIN coding_attempts a ON a.run_id = t.run_id
             WHERE g.order_id = ? AND g.cycle_no = ? AND a.active_flag = 1
            """.trimIndent()
    }
}
