package net.kigawa.kivra.selfdev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.AutoStopResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import org.slf4j.LoggerFactory

/** What the automatic child stop needs beyond the order repository: the goals of the stopped part, stopped after the decision. */
class AutoStopDeps(val goals: SelfDevGoalRepository, val operations: SelfDevOperatorRepository)

/**
 * The periodic guard of the self-development orders (ADR 0015 stage 1). One pass, in a fixed order:
 *
 * 1. Expires the stop requests whose 24 hours are over (each goes to PAUSED with STOP_NOT_APPROVED).
 * 2. Expires the orders whose lifetime is over (PAUSED with EXPIRED).
 * 3. Records the outcome of every cycle whose goal has ended, which may pause the order (LIMIT_REACHED, CYCLE_FAILURES).
 * 4. For each ACTIVE order, checks the safety guards and pauses the order with the first one that holds: the monthly budget share
 *    (BUDGET_HOLD), the FX rate (FX_EXPIRED), an UNKNOWN Run of the current cycle (UNKNOWN_RUN).
 *
 * Every pause here is a SYSTEM pause: it needs no approval, it never stops an order, and nothing resumes it by itself (an operator resumes it).
 * A stop is done here only as the automatic child stop (ADR 0015 section 4.4, behind its switch, after the cycle evaluation; the reasons
 * CHILD_CYCLE_FAILURES and CHILD_BUDGET_EXHAUSTED): a parent's
 * decision on one of its children, never the root. A stop of anything else needs an approval in the HTTP path.
 *
 * Logs carry fixed codes and counts only. No title, policy or request text is logged.
 */
class OrderWatch(
    private val orders: SelfDevOrderRepository,
    /** True when the monthly budget still has the autopilot's share left. Read once per tick. */
    private val budgetAllows: () -> Boolean,
    /** True when the FX rate is expired, so a cost cannot be converted. Read once per tick. */
    private val fxExpired: () -> Boolean,
    /** The automatic child stop (ADR 0015 section 4.4). Null when the switch is off, and then the step does nothing. */
    private val autoStop: AutoStopDeps? = null,
    /**
     * Counts each cycle's cost into the orders' `spent_jpy` (ADR 0015 addendum, stage 3g). Off by default, and then the step does nothing.
     * Its switch is `KIVRA_SELFDEV_ORDER_SPEND_ENABLED`.
     */
    private val countSpend: Boolean = false,
) {

    /** One pass. Blocking (database calls only). Each step is contained: a failure in one step does not stop the next. */
    fun tick() {
        step("stop request expiry") { orders.expireDueStopRequests(BATCH) }
        step("order expiry") { orders.expireDueOrders(BATCH) }
        step("cycle evaluation") { orders.evaluateEndedCycles(BATCH) }
        if (countSpend) step("order spend") { countSpendOfCycles() }
        autoStop?.let { deps -> step("automatic child stop") { stopChildrenAutomatically(deps) } }
        step("safety guards") { guardActiveOrders() }
    }

    /**
     * Counts the cost of each unfinished cycle (ADR 0015 addendum, stage 3g). A cycle that fails is logged with a fixed code and skipped; the
     * other cycles and the tick go on. Returns the number of cycles that were written.
     */
    private fun countSpendOfCycles(): Int {
        var written = 0
        for (cycle in orders.unfinishedSpendCycles(BATCH)) {
            try {
                if (orders.countCycleSpend(cycle)) written++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("order spend: failed code=SPEND_FAILED orderId={} exception={}", cycle.orderId, e.javaClass.simpleName)
            }
        }
        return written
    }

    /**
     * The parent's automatic decision for each child whose cycles failed (CHILD_CYCLE_FAILURES) or whose budget is used up
     * (CHILD_BUDGET_EXHAUSTED) (ADR 0015 section 4.4). The decision and its checks are in the repository, under the locks; the goals of the
     * stopped part are stopped after it, as an approved stop does. A child that is both is decided once, by its cycle failures.
     */
    private fun stopChildrenAutomatically(deps: AutoStopDeps): Int {
        var stopped = 0
        val candidates = orders.autoStopCandidates(BATCH).map { it to StopRequestReason.CHILD_CYCLE_FAILURES } +
            orders.budgetExhaustedCandidates(BATCH).map { it to StopRequestReason.CHILD_BUDGET_EXHAUSTED }
        for ((childId, reason) in candidates.distinctBy { it.first }) {
            when (val r = orders.autoStopChild(childId, reason)) {
                is AutoStopResult.Stopped -> {
                    logger.info("order auto-stop: code={} orderId={} parentId={}", reason.name, r.order.orderId, r.order.parentOrderId)
                    orders.subtreeOf(r.order.orderId).forEach { order ->
                        if (stopCycleGoalOf(order, deps.goals, deps.operations) == false) {
                            logger.warn("order auto-stop: goal not stopped, orderId={}", order.orderId)
                        }
                    }
                    stopped++
                }
                is AutoStopResult.RateLimited -> if (r.recorded) {
                    logger.info("order auto-stop: code=AUTO_STOP_RATE_LIMITED orderId={} parentId={}", childId, r.parentOrderId)
                }
                is AutoStopResult.NotDecided -> Unit
            }
        }
        return stopped
    }

    /** Runs [tick] every [INTERVAL] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("order watch: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(INTERVAL.toMillis())
        }
    }

    private fun guardActiveOrders(): Int {
        var paused = 0
        for (order in orders.activeOrders(BATCH)) {
            val reason = when {
                !budgetAllows() -> OrderReason.BUDGET_HOLD
                fxExpired() -> OrderReason.FX_EXPIRED
                order.currentGoalId != null && orders.hasUnknownRun(order.currentGoalId) -> OrderReason.UNKNOWN_RUN
                else -> null
            } ?: continue
            // pauseBySystem checks the state under the row lock: an order paused or stopped meanwhile is left as it is.
            if (orders.pauseBySystem(order.orderId, reason) is OrderResult.Moved) {
                logger.info("order watch: paused code={} orderId={}", reason.name, order.orderId)
                paused++
            }
        }
        return paused
    }

    private fun step(name: String, block: () -> Int) {
        try {
            val n = block()
            if (n > 0) logger.info("order watch: {} count={}", name, n)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("order watch: step failed, step={} exception={}", name, e.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OrderWatch::class.java)

        /** How often the guards run (ADR 0015 stage 1). Short enough that a budget hold is seen within a minute. */
        val INTERVAL = java.time.Duration.ofSeconds(60)

        /** The most rows one step handles in a pass. */
        const val BATCH = 50
    }
}
