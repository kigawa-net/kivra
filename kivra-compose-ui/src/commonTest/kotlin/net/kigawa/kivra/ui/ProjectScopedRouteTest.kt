package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

/** プロジェクト配下の URL(`#/projects/<id>/<page>`)。旧 UI の PROJECT_PAGE_RE と parseProjectRoute に相当する。 */
class ProjectScopedRouteTest {

    @Test
    fun projectPagesParseUnderTheProjectId() {
        assertEquals(Route.ProjectPage("p1", NavDestination.Tasks), parseRoute("#/projects/p1/tasks"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Goals), parseRoute("#/projects/p1/goals"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Orders), parseRoute("#/projects/p1/orders"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Approvals), parseRoute("#/projects/p1/approvals"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Memory), parseRoute("#/projects/p1/memory"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Assignees), parseRoute("#/projects/p1/assignees"))
    }

    @Test
    fun budgetUnderAProjectIsItsDashboard() {
        // 予算はダッシュボードにある(旧 UI は #/projects/<id>/budget をダッシュボードへ置き換える)。
        assertEquals(Route.ProjectDashboard("p1"), parseRoute("#/projects/p1/budget"))
        assertEquals(Route.ProjectDashboard("p1"), parseRoute("#/projects/p1/dashboard"))
    }

    @Test
    fun budgetHashIsCanonicalizedToTheDashboard() {
        assertEquals("#/projects/p1/dashboard", canonicalHash("#/projects/p1/budget"))
        assertEquals("#/projects/p1/dashboard", canonicalHash("#/projects/p1/budget?x=1"))
        assertEquals("#/projects/a%20b/dashboard", canonicalHash("#/projects/a%20b/budget"))
    }

    @Test
    fun otherHashesAreNotRewritten() {
        assertEquals("#/projects/p1/tasks", canonicalHash("#/projects/p1/tasks"))
        assertEquals("#/projects/p1/dashboard", canonicalHash("#/projects/p1/dashboard"))
        assertEquals("#/goals", canonicalHash("#/goals"))
        assertEquals("#/projects/%ZZ/budget", canonicalHash("#/projects/%ZZ/budget"))
    }

    @Test
    fun brokenProjectIdIsInvalid() {
        assertIs<Route.Invalid>(parseRoute("#/projects/%ZZ/tasks"))
        assertIs<Route.Invalid>(parseRoute("#/projects/%ZZ/budget"))
    }

    @Test
    fun unknownOrNonProjectPageIsInvalid() {
        // 旧 UI は未知の page を #/ へ戻す。Compose は既存の方針どおり「リンクが正しくありません」を出す。
        assertIs<Route.Invalid>(parseRoute("#/projects/p1/other"))
        assertIs<Route.Invalid>(parseRoute("#/projects/p1/projects"))
        assertIs<Route.Invalid>(parseRoute("#/projects/p1/autopilot"))
        assertIs<Route.Invalid>(parseRoute("#/projects/p1"))
    }

    @Test
    fun projectIdIsDecodedAndEncodedBothWays() {
        assertEquals(Route.ProjectPage("a b/c", NavDestination.Goals), parseRoute("#/projects/a%20b%2Fc/goals"))
        assertEquals("#/projects/a%20b%2Fc/goals", Route.ProjectPage("a b/c", NavDestination.Goals).toHash())
    }

    @Test
    fun projectPageHashUsesTheDesignFormat() {
        assertEquals("#/projects/p1/tasks", Route.ProjectPage("p1", NavDestination.Tasks).toHash())
        assertEquals("#/projects/p1/assignees", Route.ProjectPage("p1", NavDestination.Assignees).toHash())
        assertEquals("#/projects/p1/dashboard", Route.ProjectDashboard("p1").toHash())
    }

    @Test
    fun projectRoutesRoundTrip() {
        val routes = listOf(
            Route.ProjectPage("p1", NavDestination.Tasks),
            Route.ProjectPage("目標-1", NavDestination.Orders),
            Route.ProjectPage("a b", NavDestination.Memory),
            Route.ProjectDashboard("p1"),
        )
        for (route in routes) {
            assertEquals(route, parseRoute(route.toHash()), "round trip of $route")
        }
    }

    @Test
    fun budgetIsNotAProjectPage() {
        assertFails { Route.ProjectPage("p1", NavDestination.Budget) }
        assertFails { Route.ProjectPage("p1", NavDestination.Home) }
    }

    @Test
    fun legacyHashesKeepTheirMeaning() {
        assertEquals(Route.Page(NavDestination.Goals), parseRoute("#/goals"))
        assertEquals(Route.Page(NavDestination.Budget), parseRoute("#/budget"))
        assertEquals(Route.RunDetail("run-1"), parseRoute("#/runs/run-1"))
        assertEquals(Route.OrderCycles("ord-1"), parseRoute("#/orders/ord-1/cycles"))
    }

    @Test
    fun projectIdOfARouteIsTheProjectsItBelongsTo() {
        assertEquals("p1", Route.ProjectPage("p1", NavDestination.Tasks).projectIdOrNull)
        assertEquals("p1", Route.ProjectDashboard("p1").projectIdOrNull)
        assertNull(Route.Page(NavDestination.Tasks).projectIdOrNull)
        assertNull(Route.GoalDetail("g").projectIdOrNull)
        assertNull(Route.Invalid("#/x/y/z").projectIdOrNull)
    }

    @Test
    fun navOfAProjectPageIsItsListItem() {
        assertEquals(NavDestination.Goals, Route.ProjectPage("p1", NavDestination.Goals).nav)
        assertEquals(NavDestination.Projects, Route.ProjectDashboard("p1").nav)
    }

    @Test
    fun breadcrumbOfAProjectPageIsGroupAndList() {
        assertEquals(
            listOf("作業", "開発目標"),
            Route.ProjectPage("p1", NavDestination.Goals).breadcrumbs().map { it.label },
        )
    }

    @Test
    fun navigationTargetFollowsTheSelectedProject() {
        // 選択中のプロジェクトがあれば、一覧はそのプロジェクトのページ、予算はダッシュボード。
        assertEquals(Route.ProjectPage("p1", NavDestination.Tasks), NavDestination.Tasks.routeFor("p1"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Approvals), NavDestination.Approvals.routeFor("p1"))
        assertEquals(Route.ProjectDashboard("p1"), NavDestination.Budget.routeFor("p1"))
        // ホームと、プロジェクトの一覧・自動化は全体のまま。
        assertEquals(Route.Page(NavDestination.Home), NavDestination.Home.routeFor("p1"))
        assertEquals(Route.Page(NavDestination.Projects), NavDestination.Projects.routeFor("p1"))
        assertEquals(Route.Page(NavDestination.Autopilot), NavDestination.Autopilot.routeFor("p1"))
        // 選択がなければ従来のハッシュ。
        assertEquals(Route.Page(NavDestination.Tasks), NavDestination.Tasks.routeFor(null))
        assertEquals(Route.Page(NavDestination.Budget), NavDestination.Budget.routeFor(null))
    }

    @Test
    fun choosingAProjectKeepsTheSamePage() {
        assertEquals(Route.ProjectPage("p1", NavDestination.Orders), Route.Page(NavDestination.Orders).inProjectContext("p1"))
        assertEquals(Route.ProjectPage("p2", NavDestination.Goals), Route.ProjectPage("p1", NavDestination.Goals).inProjectContext("p2"))
        assertEquals(Route.ProjectDashboard("p2"), Route.ProjectDashboard("p1").inProjectContext("p2"))
        // 詳細は属する一覧へ移る。
        assertEquals(Route.ProjectPage("p1", NavDestination.Goals), Route.GoalDetail("g").inProjectContext("p1"))
        assertEquals(Route.Page(NavDestination.Orders), Route.OrderCycles("o").inProjectContext(null))
    }

    @Test
    fun choosingAllLeavesTheProjectPagesForTheOldOnes() {
        assertEquals(Route.Page(NavDestination.Goals), Route.ProjectPage("p1", NavDestination.Goals).inProjectContext(null))
        assertEquals(Route.Page(NavDestination.Home), Route.ProjectDashboard("p1").inProjectContext(null))
        // 全体の一覧のままなら動かない。
        assertEquals(Route.Page(NavDestination.Tasks), Route.Page(NavDestination.Tasks).inProjectContext(null))
    }

    @Test
    fun invalidLinkChoosingAProjectGoesHome() {
        assertEquals(Route.Page(NavDestination.Home), Route.Invalid("#/x/y/z").inProjectContext("p1"))
    }
}
