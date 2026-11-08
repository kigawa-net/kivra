package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class RouteTest {

    @Test
    fun emptyAndRootHashAreHome() {
        assertEquals(Route.Page(NavDestination.Home), parseRoute(""))
        assertEquals(Route.Page(NavDestination.Home), parseRoute("#"))
        assertEquals(Route.Page(NavDestination.Home), parseRoute("#/"))
    }

    @Test
    fun listRoutesKeepTheirNames() {
        assertEquals(Route.Page(NavDestination.Tasks), parseRoute("#/tasks"))
        assertEquals(Route.Page(NavDestination.Goals), parseRoute("#/goals"))
        assertEquals(Route.Page(NavDestination.Orders), parseRoute("#/orders"))
        assertEquals(Route.Page(NavDestination.Approvals), parseRoute("#/approvals"))
        assertEquals(Route.Page(NavDestination.Budget), parseRoute("#/budget"))
        assertEquals(Route.Page(NavDestination.Memory), parseRoute("#/memory"))
        assertEquals(Route.Page(NavDestination.Assignees), parseRoute("#/assignees"))
        // The run list lives inside the task screen.
        assertEquals(Route.Page(NavDestination.Tasks), parseRoute("#/runs"))
    }

    @Test
    fun unknownListNameFallsBackToHome() {
        assertEquals(Route.Page(NavDestination.Home), parseRoute("#/nope"))
    }

    @Test
    fun detailRoutesParseTheirIds() {
        assertEquals(Route.RunDetail("run-1"), parseRoute("#/runs/run-1"))
        assertEquals(Route.GoalDetail("goal-1"), parseRoute("#/goals/goal-1"))
        assertEquals(Route.OrderDetail("ord-1"), parseRoute("#/orders/ord-1"))
        assertEquals(Route.OrderCycles("ord-1"), parseRoute("#/orders/ord-1/cycles"))
    }

    @Test
    fun queryStringIsIgnored() {
        assertEquals(Route.RunDetail("run-1"), parseRoute("#/runs/run-1?scope=all"))
        assertEquals(Route.Page(NavDestination.Orders), parseRoute("#/orders?project=p1"))
    }

    @Test
    fun percentEncodedIdIsDecoded() {
        assertEquals(Route.GoalDetail("a b/c"), parseRoute("#/goals/a%20b%2Fc"))
        assertEquals(Route.GoalDetail("目標"), parseRoute("#/goals/%E7%9B%AE%E6%A8%99"))
    }

    @Test
    fun brokenEncodingOrExtraSegmentIsInvalid() {
        assertIs<Route.Invalid>(parseRoute("#/goals/%ZZ"))
        assertIs<Route.Invalid>(parseRoute("#/goals/%4"))
        assertIs<Route.Invalid>(parseRoute("#/orders/ord-1/unknown"))
        assertIs<Route.Invalid>(parseRoute("#/runs/run-1/extra/more"))
    }

    @Test
    fun toHashAndParseRouteRoundTrip() {
        val routes = listOf(
            Route.Page(NavDestination.Home),
            Route.Page(NavDestination.Tasks),
            Route.Page(NavDestination.Assignees),
            Route.RunDetail("run 1/x"),
            Route.GoalDetail("目標-1"),
            Route.OrderDetail("ord-1"),
            Route.OrderCycles("ord-1"),
        )
        for (route in routes) {
            assertEquals(route, parseRoute(route.toHash()), "round trip of $route")
        }
    }

    @Test
    fun toHashUsesTheDesignFormat() {
        assertEquals("#/", Route.Page(NavDestination.Home).toHash())
        assertEquals("#/tasks", Route.Page(NavDestination.Tasks).toHash())
        assertEquals("#/runs/run-1", Route.RunDetail("run-1").toHash())
        assertEquals("#/goals/goal-1", Route.GoalDetail("goal-1").toHash())
        assertEquals("#/orders/ord-1", Route.OrderDetail("ord-1").toHash())
        assertEquals("#/orders/ord-1/cycles", Route.OrderCycles("ord-1").toHash())
        assertEquals("#/goals/a%20b%2Fc", Route.GoalDetail("a b/c").toHash())
    }

    @Test
    fun navBelongsToTheListItem() {
        assertEquals(NavDestination.Tasks, Route.RunDetail("r").nav)
        assertEquals(NavDestination.Goals, Route.GoalDetail("g").nav)
        assertEquals(NavDestination.Orders, Route.OrderDetail("o").nav)
        assertEquals(NavDestination.Orders, Route.OrderCycles("o").nav)
        assertNull(Route.Invalid("#/x/y/z").nav)
    }

    @Test
    fun breadcrumbForOrderCyclesIsGroupListDetailCycles() {
        val crumbs = Route.OrderCycles("ord-1").breadcrumbs()
        assertEquals(
            listOf("作業", "order", "ord-1", "cycles"),
            crumbs.map { it.label },
        )
        // Each element except the current one leads somewhere.
        assertEquals(Route.Page(NavDestination.Tasks), crumbs[0].route)
        assertEquals(Route.Page(NavDestination.Orders), crumbs[1].route)
        assertEquals(Route.OrderDetail("ord-1"), crumbs[2].route)
        assertNull(crumbs[3].route)
    }

    @Test
    fun breadcrumbForRunDetailEndsWithTheRunId() {
        val crumbs = Route.RunDetail("run-1").breadcrumbs()
        assertEquals(listOf("作業", "タスク", "run-1"), crumbs.map { it.label })
        assertNull(crumbs.last().route)
    }

    @Test
    fun homeAndInvalidHaveNoDetailCrumbs() {
        assertEquals(emptyList(), Route.Invalid("#/x/y/z").breadcrumbs())
    }
}
