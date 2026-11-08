package net.kigawa.kivra.ui

/**
 * サイドナビのグループ(docs/design/16-site-structure.md)。
 */
enum class NavGroup(val label: String) {
    Work("作業"),
    Decide("判断"),
    Resources("資源"),
    Admin("管理"),
}

/**
 * Navigation destinations for the Kivra app.
 */
sealed class NavDestination(val route: String, val label: String, val group: NavGroup) {
    object Tasks : NavDestination("tasks", "タスク", NavGroup.Work)
    object Goals : NavDestination("goals", "開発目標", NavGroup.Work)
    object Orders : NavDestination("orders", "order", NavGroup.Work)
    object Approvals : NavDestination("approvals", "承認", NavGroup.Decide)
    object Budget : NavDestination("budget", "予算", NavGroup.Resources)
    object Memory : NavDestination("memory", "記憶", NavGroup.Resources)
    object Assignees : NavDestination("assignees", "担当", NavGroup.Admin)

    companion object {
        val all = listOf(Tasks, Goals, Orders, Approvals, Budget, Memory, Assignees)

        /** グループ順に並べた (グループ, 項目一覧)。空のグループは含めない。 */
        val grouped: List<Pair<NavGroup, List<NavDestination>>> =
            NavGroup.entries.map { g -> g to all.filter { it.group == g } }.filter { it.second.isNotEmpty() }

        fun fromRoute(route: String): NavDestination {
            return all.find { it.route == route } ?: Tasks
        }
    }
}
