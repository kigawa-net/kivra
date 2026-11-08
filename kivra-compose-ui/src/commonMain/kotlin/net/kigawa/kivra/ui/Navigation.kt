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
sealed class NavDestination(val route: String, val label: String, val group: NavGroup?) {
    /** グループに属さない最上位項目(要対応サマリ)。 */
    object Home : NavDestination("home", "ホーム", null)
    object Tasks : NavDestination("tasks", "タスク", NavGroup.Work)
    object Goals : NavDestination("goals", "開発目標", NavGroup.Work)
    object Orders : NavDestination("orders", "order", NavGroup.Work)
    object Approvals : NavDestination("approvals", "承認", NavGroup.Decide)
    object Budget : NavDestination("budget", "予算", NavGroup.Resources)
    object Memory : NavDestination("memory", "記憶", NavGroup.Resources)
    object Assignees : NavDestination("assignees", "担当", NavGroup.Admin)

    /** 自律運転の状態と操作。自律運転が有効なときだけ出す([navVisibleForAutopilot])。 */
    object Autopilot : NavDestination("autopilot", "自動化", NavGroup.Admin)

    companion object {
        // lazy: the nested objects refer to the class while it is being initialized. Built on first use, every object exists.
        val all: List<NavDestination> by lazy { listOf(Home, Tasks, Goals, Orders, Approvals, Budget, Memory, Assignees, Autopilot) }

        /** グループ順に並べた (グループ, 項目一覧)。空のグループは含めない。 */
        val grouped: List<Pair<NavGroup, List<NavDestination>>> by lazy {
            NavGroup.entries.map { g -> g to all.filter { it.group == g } }.filter { it.second.isNotEmpty() }
        }

        fun fromRoute(route: String): NavDestination {
            return all.find { it.route == route } ?: Home
        }

        /** グループの先頭項目(パンくずのグループ見出しの遷移先)。 */
        fun firstIn(group: NavGroup): NavDestination? = all.firstOrNull { it.group == group }
    }
}
