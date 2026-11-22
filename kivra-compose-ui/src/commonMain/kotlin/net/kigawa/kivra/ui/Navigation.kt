package net.kigawa.kivra.ui

/**
 * 画面の項目(docs/design/16-site-structure.md §2〜3)。
 *
 * - 全体の項目([globalPages]): プロジェクトを持たない。ホーム、プロジェクト一覧、全体の予算・FX、自動化、全体の担当。
 * - プロジェクト配下の項目([projectPages]): `#/projects/<id>/<page>` で開く。ダッシュボード、タスク、開発目標、order、承認、記憶、担当、予算。
 *
 * 「担当」は全体(トークンと全プロジェクト横断の管理)とプロジェクト配下(そのプロジェクトのメンバー)の両方にある。
 * 同じ object を使い、どちらかは [Route] の projectId で決める。
 */
sealed class NavDestination(val route: String, val label: String) {
    object Home : NavDestination("home", "ホーム")
    object Projects : NavDestination("projects", "プロジェクト")
    object Budget : NavDestination("budget", "予算・FX")
    object Operations : NavDestination("operations", "Operation")
    object Autopilot : NavDestination("autopilot", "自動化")
    object Assignees : NavDestination("assignees", "担当")

    object Dashboard : NavDestination("dashboard", "ダッシュボード")
    object Tasks : NavDestination("tasks", "タスク")
    object Goals : NavDestination("goals", "開発目標")
    object Orders : NavDestination("orders", "order")
    object Approvals : NavDestination("approvals", "承認")
    object Memory : NavDestination("memory", "記憶")

    /** プロジェクトの予算(今月の上限と使用量)。運用者だけが見られる。 */
    object ProjectBudget : NavDestination("budget", "予算")

    companion object {
        /** 全体の項目(route が一意になる。`#/<route>` で開く)。 */
        val globalPages: List<NavDestination> by lazy { listOf(Home, Projects, Budget, Operations, Autopilot, Assignees) }

        /** プロジェクト配下の項目(`#/projects/<id>/<route>` で開く)。サイドナビの並び順でもある。 */
        val projectPages: List<NavDestination> by lazy {
            listOf(Dashboard, Tasks, Goals, Orders, Approvals, Memory, Assignees, ProjectBudget, Operations)
        }

        /**
         * 旧 URL(`#/tasks` 等)でプロジェクトを持っていた一覧。プロジェクトが決まるまでの間だけ使う([resolveLegacy])。
         * 「タスク」は Run の一覧なので、`#/runs`(旧 Run の一覧)もこの一覧として扱う。
         */
        val legacyProjectPages: List<NavDestination> by lazy { listOf(Tasks, Goals, Orders, Approvals, Memory) }

        /**
         * 未分類(疑似プロジェクト)の項目。データの一覧だけ。ダッシュボード・担当・予算は、プロジェクトの実体が要るので出さない([pagesOf])。
         */
        val unassignedPages: List<NavDestination> by lazy { listOf(Tasks, Goals, Orders, Approvals, Memory) }
    }
}

/** サイドナビの 1 項目。[target] は押したときのルート。 */
data class NavItem(val dest: NavDestination, val target: Route)

/** サイドナビの節。[title] が null の節は見出しを出さない。 */
data class NavSection(val title: String?, val items: List<NavItem>)

/**
 * サイドナビ(docs/design/16-site-structure.md §3)。
 * ホーム、プロジェクト一覧の 2 項目だけを出す。
 * 他の項目([globalPages] など)の画面・route・権限はそのまま残す。
 * 権限や API の結果による出し分けは呼び出し側が行う([CallerPermissions.navVisible] など)。
 */
fun sidebarSections(current: Route? = null): List<NavSection> {
    val top = NavSection(
        title = null,
        items = listOf(NavDestination.Home, NavDestination.Projects).map { NavItem(it, Route.Page(it)) },
    )
    return listOf(top)
}

/**
 * プロジェクトの画面に並べるリンク(ダッシュボード自身を除く[pagesOf])。未分類の画面の上に出す(プロジェクトのダッシュボードには出さない)。
 * 権限や API の結果による出し分けは呼び出し側が行う。
 */
fun projectLinkTargets(projectId: String): List<NavItem> =
    pagesOf(projectId).filter { it != NavDestination.Dashboard }.map { NavItem(it, Route.ProjectPage(projectId, it)) }
