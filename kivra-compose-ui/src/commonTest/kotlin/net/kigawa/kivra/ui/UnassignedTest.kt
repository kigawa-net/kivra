package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 未分類(疑似プロジェクト `unassigned`)の規則: 項目、入口、保存値の検証、解釈と表示。 */
class UnassignedTest {
    private val ulid = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

    @Test
    fun onlyTheUnassignedIdIsTheUnassignedProject() {
        assertTrue(isUnassigned("unassigned"))
        assertTrue(!isUnassigned(null))
        assertTrue(!isUnassigned(ulid))
    }

    @Test
    fun savedLastProjectIsValidatedBeforeItIsUsed() {
        assertEquals("unassigned", validLastProjectId("unassigned"))
        assertEquals(ulid, validLastProjectId(ulid))
        assertNull(validLastProjectId(null))
        assertNull(validLastProjectId(""))
        assertNull(validLastProjectId("../../x"))
        assertNull(validLastProjectId("01arz3ndektsv4rrffq69g5fav")) // 小文字は ULID ではない
        assertNull(validLastProjectId("Unassigned"))
    }

    @Test
    fun unassignedHasOnlyTheListPages() {
        assertEquals(
            listOf(NavDestination.Tasks, NavDestination.Goals, NavDestination.Orders, NavDestination.Approvals, NavDestination.Memory),
            pagesOf("unassigned"),
        )
        assertEquals(NavDestination.projectPages, pagesOf(ulid))
    }

    @Test
    fun unassignedOpensOnTasksNotDashboard() {
        assertEquals(NavDestination.Tasks, landingPageOf("unassigned"))
        assertEquals(NavDestination.Dashboard, landingPageOf(ulid))
    }

    @Test
    fun unassignedIsLabelledInJapanese() {
        assertEquals("未分類", projectLabel("unassigned", null))
        assertEquals("未分類", projectLabel("unassigned", "something"))
        assertEquals("名前", projectLabel(ulid, "名前"))
        assertEquals(ulid, projectLabel(ulid, null))
    }

    @Test
    fun parseRouteAcceptsTheUnassignedListPages() {
        assertEquals(Route.ProjectPage("unassigned", NavDestination.Tasks), parseRoute("#/projects/unassigned/tasks"))
        assertEquals(Route.ProjectPage("unassigned", NavDestination.Approvals), parseRoute("#/projects/unassigned/approvals"))
        assertEquals(Route.ProjectPage("unassigned", NavDestination.Tasks), parseRoute("#/projects/unassigned"))
        assertEquals(Route.RunDetail("r1", "unassigned"), parseRoute("#/projects/unassigned/runs/r1"))
        assertEquals(Route.OrderCycles("o1", "unassigned"), parseRoute("#/projects/unassigned/orders/o1/cycles"))
    }

    @Test
    fun parseRouteRefusesThePagesThatNeedARealProject() {
        assertTrue(parseRoute("#/projects/unassigned/dashboard") is Route.Invalid)
        assertTrue(parseRoute("#/projects/unassigned/assignees") is Route.Invalid)
        assertTrue(parseRoute("#/projects/unassigned/budget") is Route.Invalid)
    }

    @Test
    fun unassignedRoutesRoundTrip() {
        val routes = listOf(
            Route.ProjectPage("unassigned", NavDestination.Tasks),
            Route.ProjectPage("unassigned", NavDestination.Memory),
            Route.RunDetail("r1", "unassigned"),
        )
        for (route in routes) assertEquals(route, parseRoute(route.toHash()))
    }

    @Test
    fun legacyListGoesToUnassignedWhenItWasTheLastProject() {
        // 未分類は、プロジェクトが他にあっても、最後に選んでいれば行き先になる。
        assertEquals(
            Route.ProjectPage("unassigned", NavDestination.Goals),
            resolveLegacy(NavDestination.Goals, listOf(ulid, "p2"), "unassigned"),
        )
        // 一覧の取得に失敗したときも、検証済みの最後の値をそのまま使う。
        assertEquals(
            Route.ProjectPage("unassigned", NavDestination.Tasks),
            resolveLegacy(NavDestination.Tasks, null, "unassigned"),
        )
    }

    @Test
    fun legacyListFallsBackWhenLastProjectIsGone() {
        assertEquals(
            Route.ProjectPage(ulid, NavDestination.Goals),
            resolveLegacy(NavDestination.Goals, listOf(ulid), "gone"),
        )
        assertTrue(resolveLegacy(NavDestination.Goals, listOf(ulid, "p2"), "gone") is Route.ProjectChooser)
    }

    @Test
    fun inProjectContextKeepsThePageWhenItExistsForTheTarget() {
        assertEquals(
            Route.ProjectPage("unassigned", NavDestination.Orders),
            Route.ProjectPage(ulid, NavDestination.Orders).inProjectContext("unassigned"),
        )
        // 予算(配下)は未分類には無いので、未分類のタスクへ行く。
        assertEquals(
            Route.ProjectPage("unassigned", NavDestination.Tasks),
            Route.ProjectPage(ulid, NavDestination.ProjectBudget).inProjectContext("unassigned"),
        )
    }

    @Test
    fun breadcrumbsNameTheUnassignedProject() {
        val crumbs = Route.ProjectPage("unassigned", NavDestination.Tasks).breadcrumbs()
        assertEquals(listOf("プロジェクト", "未分類", "タスク"), crumbs.map { it.label })
        assertEquals(Route.ProjectPage("unassigned", NavDestination.Tasks), crumbs[1].route)
    }

    @Test
    fun linksOfUnassignedShowOnlyTheListPages() {
        val links = projectLinkTargets("unassigned")
        assertEquals(NavDestination.unassignedPages, links.map { it.dest })
        assertEquals(Route.ProjectPage("unassigned", NavDestination.Tasks), links[0].target)
    }
}
