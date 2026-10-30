package net.kigawa.kivra.selfdev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.autopilot.OrderReason

import net.kigawa.kivra.db.AUTO_CYCLE_STARTED
import net.kigawa.kivra.db.AutoCycleResult
import net.kigawa.kivra.db.AutoCycleSkip

import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevOrderRepository
import org.slf4j.LoggerFactory

/**
 * The periodic guard of the self-development orders (ADR 0015 stage 1). One pass, in a fixed order:
 *
 * 1. Expires the stop requests whose 24 hours are over (each goes to PAUSED with STOP_NOT_APPROVED).
 * 2. Expires the orders whose lifetime is over (PAUSED with EXPIRED).
 * 3. Records the outcome of every cycle whose goal has ended, which may pause the order (LIMIT_REACHED, CYCLE_FAILURES).
 * 4. For each ACTIVE order, checks the safety guards and pauses the order with the first one that holds: the monthly budget share
 *    (BUDGET_HOLD), the FX rate (FX_EXPIRED), an UNKNOWN Run of the current cycle (UNKNOWN_RUN).
 * 5. Only behind its switch (dev only): starts the next cycle of each order that may have one (AUTO_CYCLE_STARTED), after the guards.
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
    /** The automatic child stop is temporarily disabled pending Task model migration. */
    /**
     * Counts each cycle's cost into the orders' `spent_jpy` (ADR 0015 addendum, stage 3g). Off by default, and then the step does nothing.
     * Its switch is `KIVRA_SELFDEV_ORDER_SPEND_ENABLED`.
     */
    private val countSpend: Boolean = false,
    /**
     * The automatic start of cycles (ADR 0015 section 3.1 steps 1 and 8, addendum 2026-10-24, dev only). The value is the cycle cap
     * (`KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`); null (the default) means the switch `KIVRA_SELFDEV_ORDER_AUTO_CYCLE_ENABLED` is off, and then
     * the step does nothing.
     */
    private val autoCycleCap: Int? = null,
) {

    /** One pass. Blocking (database calls only). Each step is contained: a failure in one step does not stop the next. */
    fun tick() {
        step("stop request expiry") { orders.expireDueStopRequests(BATCH) }
        step("order expiry") { orders.expireDueOrders(BATCH) }
        step("cycle evaluation") { orders.evaluateEndedCycles(BATCH) }
        if (countSpend) step("order spend") { countSpendOfCycles() }
        // Automatic child stop temporarily disabled pending Task model migration
        step("safety guards") { guardActiveOrders() }
        // After the guards, so an order a guard has just paused is not started in the same pass.
        autoCycleCap?.let { cap -> step("automatic cycle start") { startCyclesAutomatically(cap) } }
    }

    /**
     * Starts the next cycle of each order that may have one (ADR 0015 section 3.1 step 1). The checks are in the repository, under the locks
     * of an operator's start; at most one cycle per order and [BATCH] orders in a pass. A refusal is the normal case (an order waits for its
     * interval, its budget, its goal's end) and is not logged, except the rarer codes below. Returns the number of cycles started.
     */
    private fun startCyclesAutomatically(cap: Int): Int {
        var started = 0
        for (orderId in orders.autoCycleCandidates(BATCH)) {
            try {
                when (val r = orders.startCycleAutomatically(orderId, cap)) {
                    is AutoCycleResult.Started -> {
                        logger.info("order auto-cycle: code={} orderId={} cycleNo={}", AUTO_CYCLE_STARTED, orderId, r.started.cycleNo)
                        started++
                    }
                    is AutoCycleResult.LimitPaused -> logger.info("order auto-cycle: paused code={} orderId={}", OrderReason.LIMIT_REACHED.name, orderId)
                    is AutoCycleResult.Skipped -> if (r.reason in LOGGED_SKIPS) {
                        logger.info("order auto-cycle: skipped code={} orderId={}", r.reason.name, orderId)
                    }
                    AutoCycleResult.NotFound -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("order auto-cycle: failed code=AUTO_CYCLE_FAILED orderId={} exception={}", orderId, e.javaClass.simpleName)
            }
        }
        return started
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

    /** Temporarily disabled pending Task model migration. */
    @Suppress("UNUSED_PARAMETER")
    private fun stopChildrenAutomatically(deps: Any): Int {
        logger.info("order auto-stop: disabled pending Task model migration")
        return 0
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

        /** The skips worth a log line (budget and unpriced stop the loop for a reason an operator must act on). The others repeat every minute. */
        private val LOGGED_SKIPS = setOf(AutoCycleSkip.ORDER_BUDGET, AutoCycleSkip.ORDER_UNPRICED)

        /** The most rows one step handles in a pass. */
        const val BATCH = 50
    }
}
