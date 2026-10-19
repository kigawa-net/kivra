package net.kigawa.kivra.selfdev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.autopilot.OrderReason
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
 *
 * Every pause here is a SYSTEM pause: it needs no approval, it never stops an order, and nothing resumes it by itself (an operator resumes it).
 * A stop is never done here; it needs the root approver's approval in the HTTP path.
 *
 * Logs carry fixed codes and counts only. No title, policy or request text is logged.
 */
class OrderWatch(
    private val orders: SelfDevOrderRepository,
    /** True when the monthly budget still has the autopilot's share left. Read once per tick. */
    private val budgetAllows: () -> Boolean,
    /** True when the FX rate is expired, so a cost cannot be converted. Read once per tick. */
    private val fxExpired: () -> Boolean,
) {

    /** One pass. Blocking (database calls only). Each step is contained: a failure in one step does not stop the next. */
    fun tick() {
        step("stop request expiry") { orders.expireDueStopRequests(BATCH) }
        step("order expiry") { orders.expireDueOrders(BATCH) }
        step("cycle evaluation") { orders.evaluateEndedCycles(BATCH) }
        step("safety guards") { guardActiveOrders() }
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
