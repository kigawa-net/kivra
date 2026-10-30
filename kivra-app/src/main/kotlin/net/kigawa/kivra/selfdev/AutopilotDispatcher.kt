package net.kigawa.kivra.selfdev

import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.kigawa.kivra.db.DevelopmentRunRepository
import org.jetbrains.exposed.sql.Database

/**
 * Temporarily disabled pending Task model migration (V40).
 */
class AutopilotDispatcher(
    database: Database,
    runs: DevelopmentRunRepository,
    config: AutopilotConfig,
    maxActiveSelfDev: Int,
    budgetAllows: (Int) -> Boolean,
) {
    fun tick() { }
    fun start(scope: CoroutineScope): Job = scope.launch { }
}

internal fun autopilotBudgetAllows(
    committed: BigDecimal,
    reserved: BigDecimal,
    monthlyLimit: BigDecimal,
    sharePercent: Int,
): Boolean {
    val remaining = monthlyLimit - committed - reserved
    val share = monthlyLimit.multiply(BigDecimal(sharePercent)).divide(BigDecimal(100))
    return remaining >= share
}
