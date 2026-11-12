package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectScopedRouteTest {
    @Test
    fun projectPagesParseUnderTheProjectId() {
        assertEquals(Route.ProjectPage("p1", NavDestination.Dashboard), parseRoute("#/projects/p1/dashboard"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Tasks), parseRoute("#/projects/p1/tasks"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Goals), parseRoute("#/projects/p1/goals"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Orders), parseRoute("#/projects/p1/orders"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Approvals), parseRoute("#/projects/p1/approvals"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Memory), parseRoute("#/projects/p1/memory"))
        assertEquals(Route.ProjectPage("p1", NavDestination.Assignees), parseRoute("#/projects/p1/assignees"))
        assertEquals(Route.ProjectPage("p1", NavDestination.ProjectBudget), parseRoute("#/projects/p1/budget"))
    }

    @Test
    fun projectIdAloneOpensTheDashboard() {
        assertEquals(Route.ProjectPage("p1", NavDestination.Dashboard), parseRoute("#/projects/p1"))
    }

    @Test
    fun projectPageHashUsesTheDesignFormatAndRoundTrips() {
        assertEquals("#/projects/p1/tasks", Route.ProjectPage("p1", NavDestination.Tasks).toHash())
        assertEquals("#/projects/p1/budget", Route.ProjectPage("p1", NavDestination.ProjectBudget).toHash())
        assertEquals("#/projects/a%20b/dashboard", Route.ProjectPage("a b", NavDestination.Dashboard).toHash())
        assertEquals(Route.ProjectPage("a b", NavDestination.Dashboard), parseRoute("#/projects/a%20b/dashboard"))
    }

    @Test
    fun projectDetailRoutesCarryTheProject() {
        assertEquals(Route.GoalDetail("g", "p1"), parseRoute("#/projects/p1/goals/g"))
        assertEquals(Route.OrderDetail("o", "p1"), parseRoute("#/projects/p1/orders/o"))
        assertEquals(Route.OrderCycles("o", "p1"), parseRoute("#/projects/p1/orders/o/cycles"))
        assertEquals(Route.RunDetail("r", "p1"), parseRoute("#/projects/p1/runs/r"))
        for (route in listOf(Route.GoalDetail("g", "p1"), Route.OrderCycles("o", "p1"), Route.RunDetail("r", "p1"))) {
            assertEquals(route, parseRoute(route.toHash()), "round trip of $route")
        }
        assertEquals("#/projects/p1/orders/o/cycles", Route.OrderCycles("o", "p1").toHash())
    }

    @Test
    fun brokenProjectIdIsInvalid() {
        assertTrue(parseRoute("#/projects/%ZZ/tasks") is Route.Invalid)
        assertTrue(parseRoute("#/projects/%ZZ") is Route.Invalid)
    }

    @Test
    fun unknownOrOverlongProjectLinksAreInvalid() {
        assertTrue(parseRoute("#/projects/p1/other") is Route.Invalid)
        assertTrue(parseRoute("#/projects/p1/tasks/x") is Route.Invalid)
        assertTrue(parseRoute("#/projects/p1/orders/o/x") is Route.Invalid)
        assertTrue(parseRoute("#/projects/p1/goals") is Route.ProjectPage)
    }

    @Test
    fun globalAndProjectPagesCannotBeMixedUp() {
        assertFailsWith<IllegalArgumentException> { Route.Page(NavDestination.Tasks) }
        assertFailsWith<IllegalArgumentException> { Route.ProjectPage("p1", NavDestination.Budget) }
        assertFailsWith<IllegalArgumentException> { Route.ProjectPage("p1", NavDestination.Projects) }
    }

    @Test
    fun projectIdOfARouteIsTheProjectItBelongsTo() {
        assertEquals("p1", Route.ProjectPage("p1", NavDestination.Dashboard).projectIdOrNull)
        assertEquals("p1", Route.GoalDetail("g", "p1").projectIdOrNull)
        assertNull(Route.GoalDetail("g").projectIdOrNull)
        assertNull(Route.Page(NavDestination.Budget).projectIdOrNull)
        assertNull(Route.Legacy(NavDestination.Goals).projectIdOrNull)
    }

    @Test
    fun legacyListRoutesAreResolvedLaterNotParsedAsProjectPages() {
        assertEquals(Route.Legacy(NavDestination.Goals), parseRoute("#/goals"))
        assertEquals(Route.Legacy(NavDestination.Tasks), parseRoute("#/tasks"))
        assertEquals(Route.Legacy(NavDestination.Tasks), parseRoute("#/runs"))
        assertEquals(Route.Legacy(NavDestination.Approvals), parseRoute("#/approvals"))
    }

    @Test
    fun legacyListResolvesToTheLastChosenProjectWhenItIsStillThere() {
        assertEquals(Route.ProjectPage("b", NavDestination.Goals), resolveLegacy(NavDestination.Goals, listOf("a", "b"), "b"))
    }

    @Test
    fun legacyListResolvesToTheOnlyProject() {
        assertEquals(Route.ProjectPage("a", NavDestination.Orders), resolveLegacy(NavDestination.Orders, listOf("a"), null))
    }

    @Test
    fun legacyListWithoutAProjectGoesToTheChooser() {
        assertEquals(Route.ProjectChooser(NavDestination.Goals), resolveLegacy(NavDestination.Goals, listOf("a", "b"), null))
        // 覚えていたプロジェクトが消えていたら、覚えを使わない。
        assertEquals(Route.ProjectChooser(NavDestination.Goals), resolveLegacy(NavDestination.Goals, listOf("a", "b"), "z"))
    }

    @Test
    fun legacyListWhenTheProjectListFailedUsesTheRememberedProject() {
        assertEquals(Route.ProjectPage("b", NavDestination.Memory), resolveLegacy(NavDestination.Memory, null, "b"))
        assertEquals(Route.ProjectChooser(NavDestination.Memory), resolveLegacy(NavDestination.Memory, null, null))
    }

    @Test
    fun chooserHashIsTheProjectList() {
        assertEquals("#/projects", Route.ProjectChooser(NavDestination.Goals).toHash())
    }

    @Test
    fun aDetailWithoutAProjectTakesTheCurrentProject() {
        val current = Route.ProjectPage("p1", NavDestination.Tasks)
        assertEquals(Route.GoalDetail("g", "p1"), Route.GoalDetail("g").withProjectOf(current))
        assertEquals(Route.RunDetail("r", "p1"), Route.RunDetail("r").withProjectOf(current))
        assertEquals(Route.OrderCycles("o", "p1"), Route.OrderCycles("o").withProjectOf(current))
    }

    @Test
    fun aDetailKeepsItsOwnProjectAndOtherRoutesAreUnchanged() {
        assertEquals(Route.GoalDetail("g", "p2"), Route.GoalDetail("g", "p2").withProjectOf(Route.ProjectPage("p1", NavDestination.Goals)))
        assertEquals(Route.Page(NavDestination.Budget), Route.Page(NavDestination.Budget).withProjectOf(Route.ProjectPage("p1", NavDestination.Goals)))
        assertEquals(Route.GoalDetail("g"), Route.GoalDetail("g").withProjectOf(Route.Page(NavDestination.Home)))
    }

    @Test
    fun switchingTheProjectKeepsThePageKind() {
        assertEquals(Route.ProjectPage("p2", NavDestination.Orders), Route.ProjectPage("p1", NavDestination.Orders).inProjectContext("p2"))
        assertEquals(Route.ProjectPage("p2", NavDestination.ProjectBudget), Route.ProjectPage("p1", NavDestination.ProjectBudget).inProjectContext("p2"))
    }

    @Test
    fun switchingFromAGlobalPageGoesToTheSamePageOrTheDashboard() {
        assertEquals(Route.ProjectPage("p2", NavDestination.Dashboard), Route.Page(NavDestination.Budget).inProjectContext("p2"))
        assertEquals(Route.ProjectPage("p2", NavDestination.Dashboard), Route.Page(NavDestination.Projects).inProjectContext("p2"))
        assertEquals(Route.ProjectPage("p2", NavDestination.Assignees), Route.Page(NavDestination.Assignees).inProjectContext("p2"))
        assertEquals(Route.ProjectPage("p2", NavDestination.Dashboard), Route.Invalid("#/x/y/z/w").inProjectContext("p2"))
    }

    @Test
    fun switchingFromADetailGoesToTheListOfThatKind() {
        assertEquals(Route.ProjectPage("p2", NavDestination.Goals), Route.GoalDetail("g", "p1").inProjectContext("p2"))
        assertEquals(Route.ProjectPage("p2", NavDestination.Orders), Route.OrderCycles("o", "p1").inProjectContext("p2"))
    }

    @Test
    fun breadcrumbOfAProjectPageIsProjectNameAndPage() {
        val names = mapOf("p1" to "基幹")
        val crumbs = Route.ProjectPage("p1", NavDestination.Tasks).breadcrumbs { names[it] }
        assertEquals(listOf("プロジェクト", "基幹", "タスク"), crumbs.map { it.label })
        assertEquals(Route.Page(NavDestination.Projects), crumbs[0].route)
        assertEquals(Route.ProjectPage("p1", NavDestination.Dashboard), crumbs[1].route)
        assertNull(crumbs[2].route)
    }

    @Test
    fun breadcrumbFallsBackToTheIdWhenTheNameIsNotKnown() {
        val crumbs = Route.ProjectPage("p1", NavDestination.Dashboard).breadcrumbs()
        assertEquals(listOf("プロジェクト", "p1", "ダッシュボード"), crumbs.map { it.label })
    }

    @Test
    fun breadcrumbOfAProjectDetailIsProjectPageAndDetail() {
        val names = mapOf("p1" to "基幹")
        val crumbs = Route.GoalDetail("g", "p1").breadcrumbs { names[it] }
        assertEquals(listOf("プロジェクト", "基幹", "開発目標", "g"), crumbs.map { it.label })
        assertEquals(Route.ProjectPage("p1", NavDestination.Goals), crumbs[2].route)

        val cycles = Route.OrderCycles("o", "p1").breadcrumbs { names[it] }
        assertEquals(listOf("プロジェクト", "基幹", "order", "o", "cycles"), cycles.map { it.label })
        assertEquals(Route.OrderDetail("o", "p1"), cycles[3].route)
    }

    @Test
    fun sidebarHasTheGlobalSectionOnly() {
        // #504 removed the top section (Home / Projects): a project is chosen from the project list.
        val sections = sidebarSections()
        assertEquals(listOf("全体"), sections.map { it.title })
        assertEquals(
            listOf(NavDestination.Budget, NavDestination.Autopilot, NavDestination.Assignees),
            sections[0].items.map { it.dest },
        )
    }

    @Test
    fun projectLinksAreTheProjectPagesWithoutTheDashboard() {
        val links = projectLinkTargets("p1")
        assertEquals(NavDestination.projectPages - NavDestination.Dashboard, links.map { it.dest })
        assertTrue(links.all { it.target == Route.ProjectPage("p1", it.dest) })
    }
}
