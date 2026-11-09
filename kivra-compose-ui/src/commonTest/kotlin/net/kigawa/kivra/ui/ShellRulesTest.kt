package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiMe
import net.kigawa.kivra.ui.api.ApiOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellRulesTest {

    @Test
    fun roleHeaderShowsTheRoleAndTheModeNote() {
        assertEquals(RoleHeader("役割: OPERATOR", null), roleHeaderOf(ApiMe("enforce", "OPERATOR", listOf("READ"))))
        assertEquals(
            RoleHeader("役割: VIEWER", "判定は従来どおり（shadow）。役割の権限はまだ強制されていません"),
            roleHeaderOf(ApiMe("shadow", "VIEWER", listOf("READ"))),
        )
        assertEquals(RoleHeader("役割: ADMIN", "役割による判定は無効です（off）"), roleHeaderOf(ApiMe("off", "ADMIN", emptyList())))
    }

    @Test
    fun roleHeaderIsAbsentWhenTheAnswerIsMissingOrMalformed() {
        assertNull(roleHeaderOf(null))
        assertNull(roleHeaderOf(ApiMe("weird", "ADMIN", listOf("READ"))))
        assertNull(roleHeaderOf(ApiMe("enforce", "admin", listOf("READ"))))
        assertNull(roleHeaderOf(ApiMe("enforce", "ADMIN", listOf("read"))))
    }

    @Test
    fun approvalsHeaderShowsOnlyAtOneOrMore() {
        assertEquals("承認待ち 2 件", headerApprovalsText(2))
        assertNull(headerApprovalsText(0))
        assertNull(headerApprovalsText(null))
        assertEquals("0", approvalsBadgeText(0))
        assertNull(approvalsBadgeText(null))
    }

    @Test
    fun autopilotDependentItemsNeedTheAutopilotToBeOn() {
        val on = AutopilotAvailability.ENABLED
        val off = AutopilotAvailability.HIDDEN
        assertTrue(navVisibleForSections(NavDestination.Goals, on, budgetVisible = false, approvalsPending = null))
        assertFalse(navVisibleForSections(NavDestination.Goals, off, budgetVisible = true, approvalsPending = 0))
        assertFalse(navVisibleForSections(NavDestination.Goals, AutopilotAvailability.LOADING, false, null))
        assertFalse(navVisibleForSections(NavDestination.Orders, off, false, null))
        assertTrue(navVisibleForSections(NavDestination.Orders, on, false, null))
    }

    @Test
    fun approvalsNeedTheAutopilotAndAReadableList() {
        assertTrue(navVisibleForSections(NavDestination.Approvals, AutopilotAvailability.ENABLED, false, 0))
        assertFalse(navVisibleForSections(NavDestination.Approvals, AutopilotAvailability.ENABLED, false, null))
        assertFalse(navVisibleForSections(NavDestination.Approvals, AutopilotAvailability.HIDDEN, false, 3))
    }

    @Test
    fun budgetNeedsAnAnswerFromTheBudgetApi() {
        assertTrue(navVisibleForSections(NavDestination.Budget, AutopilotAvailability.HIDDEN, budgetVisible = true, approvalsPending = null))
        assertFalse(navVisibleForSections(NavDestination.Budget, AutopilotAvailability.ENABLED, budgetVisible = false, approvalsPending = 1))
    }

    @Test
    fun otherItemsAreNotHiddenByTheApi() {
        assertTrue(navVisibleForSections(NavDestination.Tasks, AutopilotAvailability.HIDDEN, false, null))
        assertTrue(navVisibleForSections(NavDestination.Home, AutopilotAvailability.HIDDEN, false, null))
        assertTrue(navVisibleForSections(NavDestination.Memory, AutopilotAvailability.HIDDEN, false, null))
    }

    @Test
    fun titleFollowsThePageAndTheRunDetailRule() {
        assertEquals("Kivra — タスク", documentTitleOf(Route.ProjectPage("p1", NavDestination.Tasks)))
        assertEquals("Kivra — 承認", documentTitleOf(Route.ProjectPage("p1", NavDestination.Approvals)))
        assertEquals("Kivra — プロジェクト", documentTitleOf(Route.ProjectChooser(NavDestination.Goals)))
        assertEquals("Kivra — Run の詳細", documentTitleOf(Route.RunDetail("01ARZ")))
        assertEquals("Kivra — 開発目標", documentTitleOf(Route.GoalDetail("01ARZ", "p1")))
        assertEquals("Kivra — order", documentTitleOf(Route.OrderCycles("01ARZ")))
        assertEquals("Kivra", documentTitleOf(Route.Invalid("#/x/")))
    }
}

class OrderTreeRulesTest {

    private fun order(id: String, parent: String? = null, depth: Int = 0) = ApiOrder(
        orderId = id,
        parentOrderId = parent,
        depth = depth,
        state = "ACTIVE",
        cyclesStarted = 0,
        maxCycles = 1,
        consecutiveFailedCycles = 0,
        expiresAt = "2026-12-01T00:00:00Z",
        version = 1,
        createdAt = "2026-11-01T00:00:00Z",
        updatedAt = "2026-11-01T00:00:00Z",
        orderBudgetJpy = 1000,
        allocatedJpy = 0,
        childCount = 0,
        spentJpy = 0,
        cycleBudgetJpy = 0,
        remainingJpy = 1000,
    )

    @Test
    fun childFollowsItsParentWithOneMoreLevelAndAnOrphanIsTopLevel() {
        val rows = orderTreeOf(
            listOf(
                order("A"),
                order("R2"),
                order("B", parent = "A", depth = 1),
                order("C", parent = "B", depth = 2),
                order("O", parent = "MISSING", depth = 3),
            ),
        )
        assertEquals(listOf("A", "B", "C", "R2", "O"), rows.map { it.order.orderId })
        assertEquals(listOf(0, 1, 2, 0, 0), rows.map { it.level })
    }

    @Test
    fun selfParentIsTopLevelAndACycleIsStillShownOnce() {
        val rows = orderTreeOf(listOf(order("S", parent = "S"), order("X", parent = "Y"), order("Y", parent = "X")))
        // X has no root above it, so the first unreached row (X) is walked as top-level and Y follows it one level down.
        assertEquals(listOf("S", "X", "Y"), rows.map { it.order.orderId })
        assertEquals(listOf(0, 0, 1), rows.map { it.level })
    }

    @Test
    fun siblingsKeepTheListOrder() {
        val rows = orderTreeOf(listOf(order("P"), order("C2", parent = "P"), order("C1", parent = "P")))
        assertEquals(listOf("P", "C2", "C1"), rows.map { it.order.orderId })
    }
}
