package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiMe
import net.kigawa.kivra.ui.api.ApiStopRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmergencyStopRulesTest {

    // サーバーの ROLE_PERMISSIONS(auth/Permissions.kt)と同じ組み立ての抜粋(PermissionRulesTest と同じ)。
    private val viewer = listOf("READ")
    private val approver = listOf("READ", "STOP_REQUEST", "STOP_DECIDE")
    private val operator = listOf("READ", "READ_TEXT", "ORDER_MANAGE", "STOP_REQUEST", "GOAL_WRITE", "SPEND_START")

    private fun enforced(permissions: List<String>) = CallerPermissions.of(ApiMe("enforce", "OPERATOR", permissions))

    private fun pending(state: String = "PENDING") = ApiStopRequest(
        requestId = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        orderId = "01ARZ3NDEKTSV4RRFFQ69G5FAW",
        requesterKind = "HUMAN",
        reasonCode = "OPERATOR_REQUEST",
        state = state,
        orderVersion = 3,
        expiresAt = "2026-11-10T00:00:00Z",
        createdAt = "2026-11-09T00:00:00Z",
        targetIsRoot = true,
    )

    // ---- 自律運転の判定と表示 ----

    @Test
    fun onlyAnEnabledAnswerShowsTheAutopilot() {
        assertEquals(AutopilotAvailability.ENABLED, autopilotAvailabilityOf(true))
        assertEquals(AutopilotAvailability.HIDDEN, autopilotAvailabilityOf(false))
        // 取得失敗・形が違う応答(null)も隠す。
        assertEquals(AutopilotAvailability.HIDDEN, autopilotAvailabilityOf(null))
    }

    @Test
    fun theAutomationItemIsOnlyInTheNavWhenEnabled() {
        assertTrue(navVisibleForAutopilot(NavDestination.Autopilot, AutopilotAvailability.ENABLED))
        assertFalse(navVisibleForAutopilot(NavDestination.Autopilot, AutopilotAvailability.HIDDEN))
        assertFalse(navVisibleForAutopilot(NavDestination.Autopilot, AutopilotAvailability.LOADING))
        // 他の項目は自律運転の有無で隠さない。
        assertTrue(navVisibleForAutopilot(NavDestination.Tasks, AutopilotAvailability.HIDDEN))
    }

    @Test
    fun autopilotRouteIsInTheGlobalSection() {
        assertEquals(Route.Page(NavDestination.Autopilot), parseRoute("#/autopilot"))
        assertEquals("#/autopilot", Route.Page(NavDestination.Autopilot).toHash())
        val global = sidebarSections().first { it.title == "全体" }
        assertTrue(global.items.any { it.dest == NavDestination.Autopilot })
    }

    @Test
    fun overallStateShowsPauseOrTheRunCount() {
        assertEquals("全体: 稼働中（今日の Run 2 / 5 件）", autopilotOverallText(paused = false, runsToday = 2, maxRunsPerDay = 5))
        assertTrue(autopilotOverallText(paused = true, runsToday = 2, maxRunsPerDay = 5).contains("一時停止中"))
        assertEquals("自動マージ: 一時停止していません", autoMergeText(false))
        assertTrue(autoMergeText(true).contains("一時停止中"))
    }

    @Test
    fun toggleLabelSaysWhatItWillDo() {
        assertEquals("自律運転を一時停止", autopilotToggleLabel(paused = false))
        assertEquals("自律運転を再開", autopilotToggleLabel(paused = true))
    }

    @Test
    fun breakerTextShowsTheFailureCount() {
        assertTrue(breakerOpenText(3).contains("連続で 3 件失敗"))
    }

    // ---- order の一時停止・再開・停止要求・取り消し ----

    @Test
    fun pauseIsOnlyForAnActiveOrder() {
        assertTrue(canPauseOrder("ACTIVE"))
        assertFalse(canPauseOrder("PAUSED"))
        assertFalse(canPauseOrder("STOP_REQUESTED"))
        assertFalse(canPauseOrder("STOPPED"))
    }

    @Test
    fun resumeNeedsPausedAndAResumableReason() {
        assertTrue(canResumeOrder("PAUSED", "OPERATOR_PAUSED"))
        assertTrue(canResumeOrder("PAUSED", "STOP_NOT_APPROVED"))
        assertTrue(canResumeOrder("PAUSED", null))
        // 周の上限と期限切れは運用者が再開できない(サーバーの NOT_RESUMABLE と同じ)。
        assertFalse(canResumeOrder("PAUSED", "LIMIT_REACHED"))
        assertFalse(canResumeOrder("PAUSED", "EXPIRED"))
        assertFalse(canResumeOrder("ACTIVE", null))
        assertFalse(canResumeOrder("STOP_REQUESTED", "STOP_REQUESTED"))
        assertFalse(canResumeOrder("STOPPED", "OPERATOR_STOPPED"))
    }

    @Test
    fun stopRequestNeedsActiveOrPausedAndNoWaitingRequest() {
        assertTrue(canRequestOrderStop("ACTIVE", hasPendingRequest = false))
        assertTrue(canRequestOrderStop("PAUSED", hasPendingRequest = false))
        assertFalse(canRequestOrderStop("ACTIVE", hasPendingRequest = true))
        assertFalse(canRequestOrderStop("STOP_REQUESTED", hasPendingRequest = false))
        assertFalse(canRequestOrderStop("STOPPED", hasPendingRequest = false))
    }

    @Test
    fun cancelOnlyForAWaitingRequest() {
        assertTrue(canCancelOrderStop(pending("PENDING")))
        assertFalse(canCancelOrderStop(pending("APPROVED")))
        assertFalse(canCancelOrderStop(null))
    }

    @Test
    fun requestableReasonsAreTheFourFixedCodes() {
        assertEquals(
            listOf("OPERATOR_REQUEST", "RUNAWAY_SUSPECTED", "BUDGET_CONCERN", "SCOPE_CONCERN"),
            REQUESTABLE_STOP_REASONS.map { it.first },
        )
        // 自動の子の停止の理由は、人も AI も要求できない。
        assertFalse(isRequestableStopReason("CHILD_CYCLE_FAILURES"))
        assertFalse(isRequestableStopReason("CHILD_BUDGET_EXHAUSTED"))
        assertFalse(isRequestableStopReason(null))
        assertTrue(isRequestableStopReason("SCOPE_CONCERN"))
    }

    // ---- 権限の対応 ----

    @Test
    fun emergencyOperationsFollowTheRoles() {
        // 閲覧だけの利用者には、どの緊急停止の操作も出さない。
        val v = enforced(viewer)
        assertFalse(v.can(GatedOperation.MANAGE_AUTOPILOT))
        assertFalse(v.can(GatedOperation.MANAGE_ORDER))
        assertFalse(v.can(GatedOperation.REQUEST_ORDER_STOP))
        assertFalse(v.can(GatedOperation.EXTEND_ORDER))

        // 承認者は停止の要求と取り消しはできるが、一時停止・再開・延長(ORDER_MANAGE)はできない。
        val a = enforced(approver)
        assertTrue(a.can(GatedOperation.REQUEST_ORDER_STOP))
        assertFalse(a.can(GatedOperation.MANAGE_AUTOPILOT))
        assertFalse(a.can(GatedOperation.MANAGE_ORDER))
        assertFalse(a.can(GatedOperation.EXTEND_ORDER))

        // 運用者は一時停止・再開・延長と、停止の要求ができる。
        val o = enforced(operator)
        assertTrue(o.can(GatedOperation.MANAGE_AUTOPILOT))
        assertTrue(o.can(GatedOperation.MANAGE_ORDER))
        assertTrue(o.can(GatedOperation.REQUEST_ORDER_STOP))
        assertTrue(o.can(GatedOperation.EXTEND_ORDER))
    }

    @Test
    fun unknownPermissionsShowEverything() {
        // 取得できないときは何も隠さない(サーバーが判定する。旧 UI と同じ)。
        assertTrue(CallerPermissions.UNKNOWN.can(GatedOperation.MANAGE_AUTOPILOT))
        assertTrue(CallerPermissions.UNKNOWN.can(GatedOperation.REQUEST_ORDER_STOP))
    }

    // ---- 失敗の文言 ----

    @Test
    fun failuresUseTheFixedTexts() {
        // 409 は状態の競合の固定文言。サーバーの本文は出さない。
        assertEquals(writeFailureText(WriteFailure.STALE), operatorWriteFailureText(OperatorAction.PAUSE_ORDER, WriteFailure.STALE))
        assertEquals(writeFailureText(WriteFailure.STALE), operatorWriteFailureText(OperatorAction.REQUEST_STOP, WriteFailure.STALE))
        assertEquals(writeFailureText(WriteFailure.STALE), operatorWriteFailureText(OperatorAction.CANCEL_STOP, WriteFailure.STALE))
        assertTrue(operatorWriteFailureText(OperatorAction.RESUME_ORDER, WriteFailure.STALE).contains("再開できる状態"))
        // 403 は権限、404 は未検出、400 は入力の確認。
        assertEquals(writeFailureText(WriteFailure.FORBIDDEN), operatorWriteFailureText(OperatorAction.REQUEST_STOP, WriteFailure.FORBIDDEN))
        assertEquals(writeFailureText(WriteFailure.NOT_FOUND), operatorWriteFailureText(OperatorAction.CANCEL_STOP, WriteFailure.NOT_FOUND))
        assertEquals(writeFailureText(WriteFailure.BAD_REQUEST), operatorWriteFailureText(OperatorAction.REQUEST_STOP, WriteFailure.BAD_REQUEST))
        // 503 は自律運転の無効(自律運転の操作)、order の操作は order の無効。
        assertTrue(operatorWriteFailureText(OperatorAction.PAUSE_AUTOPILOT, WriteFailure.DISABLED).contains("自律運転"))
        assertTrue(operatorWriteFailureText(OperatorAction.RESET_BREAKER, WriteFailure.DISABLED).contains("自律運転"))
        assertEquals(writeFailureText(WriteFailure.DISABLED), operatorWriteFailureText(OperatorAction.PAUSE_ORDER, WriteFailure.DISABLED))
    }

    @Test
    fun everyEmergencyActionHasAFixedTextForEachFailure() {
        val actions = listOf(
            OperatorAction.PAUSE_AUTOPILOT,
            OperatorAction.RESUME_AUTOPILOT,
            OperatorAction.RESET_BREAKER,
            OperatorAction.PAUSE_ORDER,
            OperatorAction.RESUME_ORDER,
            OperatorAction.REQUEST_STOP,
            OperatorAction.CANCEL_STOP,
        )
        val failures = WriteFailure.entries
        for (action in actions) {
            for (failure in failures) {
                val text = operatorWriteFailureText(action, failure)
                assertTrue(text.isNotBlank(), "$action $failure")
            }
        }
    }
}
