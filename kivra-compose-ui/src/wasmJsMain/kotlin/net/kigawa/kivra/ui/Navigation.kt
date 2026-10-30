package net.kigawa.kivra.ui

/**
 * Navigation destinations for the Kivra app.
 */
sealed class NavDestination(val route: String, val label: String) {
    object Tasks : NavDestination("tasks", "タスク")
    object Goals : NavDestination("goals", "開発目標")
    object Orders : NavDestination("orders", "order")
    object Approvals : NavDestination("approvals", "承認")
    object Budget : NavDestination("budget", "予算")
    object Memory : NavDestination("memory", "記憶")
    object Assignees : NavDestination("assignees", "担当")

    companion object {
        val all = listOf(Tasks, Goals, Orders, Approvals, Budget, Memory, Assignees)

        fun fromRoute(route: String): NavDestination {
            return all.find { it.route == route } ?: Tasks
        }
    }
}
