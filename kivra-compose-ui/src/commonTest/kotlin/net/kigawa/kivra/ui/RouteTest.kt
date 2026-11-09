package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTest {
    @Test
    fun emptyAndRootHashAreHome() {
        assertEquals(Route.Page(NavDestination.Home), parseRoute(""))
        assertEquals(Route.Page(NavDestination.Home), parseRoute("#"))
        assertEquals(Route.Page(NavDestination.Home), parseRoute("#/"))
    }

    @Test
    fun globalListRoutesKeepTheirNames() {
        assertEquals(Route.Page(NavDestination.Projects), parseRoute("#/projects"))
        assertEquals(Route.Page(NavDestination.Budget), parseRoute("#/budget"))
        assertEquals(Route.Page(NavDestination.Autopilot), parseRoute("#/autopilot"))
        assertEquals(Route.Page(NavDestination.Assignees), parseRoute("#/assignees"))
    }

    @Test
    fun unknownListNameFallsBackToHome() {
        assertEquals(Route.Page(NavDestination.Home), parseRoute("#/nope"))
    }

    @Test
    fun detailRoutesWithoutAProjectParseTheirIds() {
        assertEquals(Route.RunDetail("r1"), parseRoute("#/runs/r1"))
        assertEquals(Route.GoalDetail("g1"), parseRoute("#/goals/g1"))
        assertEquals(Route.OrderDetail("o1"), parseRoute("#/orders/o1"))
        assertEquals(Route.OrderCycles("o1"), parseRoute("#/orders/o1/cycles"))
    }

    @Test
    fun queryStringIsIgnored() {
        assertEquals(Route.GoalDetail("g1"), parseRoute("#/goals/g1?x=1"))
    }

    @Test
    fun percentEncodedIdIsDecoded() {
        assertEquals(Route.GoalDetail("a b"), parseRoute("#/goals/a%20b"))
    }

    @Test
    fun brokenEncodingOrExtraSegmentIsInvalid() {
        assertTrue(parseRoute("#/goals/%ZZ") is Route.Invalid)
        assertTrue(parseRoute("#/goals/g1/extra") is Route.Invalid)
        assertTrue(parseRoute("#/orders/o1/cycles/x") is Route.Invalid)
    }

    @Test
    fun toHashAndParseRouteRoundTrip() {
        val routes = listOf(
            Route.Page(NavDestination.Home),
            Route.Page(NavDestination.Projects),
            Route.Page(NavDestination.Budget),
            Route.RunDetail("r 1"),
            Route.GoalDetail("g/1"),
            Route.OrderDetail("o1"),
            Route.OrderCycles("o1"),
        )
        for (route in routes) {
            assertEquals(route, parseRoute(route.toHash()), "round trip of $route")
        }
    }

    @Test
    fun toHashUsesTheDesignFormat() {
        assertEquals("#/", Route.Page(NavDestination.Home).toHash())
        assertEquals("#/budget", Route.Page(NavDestination.Budget).toHash())
        assertEquals("#/projects", Route.Page(NavDestination.Projects).toHash())
        assertEquals("#/goals/g%2F1", Route.GoalDetail("g/1").toHash())
        assertEquals("#/orders/o1/cycles", Route.OrderCycles("o1").toHash())
    }

    @Test
    fun breadcrumbOfAGlobalPageIsTheGroupAndThePage() {
        assertEquals(listOf(Crumb("全体", null), Crumb("予算・FX", null)), Route.Page(NavDestination.Budget).breadcrumbs())
        assertEquals(listOf(Crumb("プロジェクト", null)), Route.Page(NavDestination.Projects).breadcrumbs())
        assertTrue(Route.Page(NavDestination.Home).breadcrumbs().isEmpty())
    }

    @Test
    fun breadcrumbOfAProjectlessOrderCyclesIsProjectListDetailCycles() {
        val crumbs = Route.OrderCycles("o1").breadcrumbs()
        assertEquals("プロジェクト", crumbs[0].label)
        assertEquals("order", crumbs[1].label)
        assertEquals("o1", crumbs[2].label)
        assertEquals("cycles", crumbs.last().label)
    }

    @Test
    fun invalidHasNoCrumbsAndNoNav() {
        assertTrue(Route.Invalid("#/x/y/z/w").breadcrumbs().isEmpty())
        assertEquals(null, Route.Invalid("#/x").nav)
    }
}
