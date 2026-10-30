package net.kigawa.kivra.selfdev

import net.kigawa.kivra.db.SelfDevOrder

/**
 * Temporarily disabled pending Task model migration (V40). The selfdev_goals table was dropped.
 * Returns null (no-op) until the order system is migrated to use selfdev_tasks.
 */
@Suppress("UNUSED_PARAMETER")
internal fun stopCycleGoalOf(order: SelfDevOrder, goals: Any, operations: Any): Boolean? {
    return null
}
