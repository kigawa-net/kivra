package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorRulesTest {

    private val runId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

    private fun run(mode: String = "SELF_DEV", state: String, pr: Long? = null, id: String = runId) = ApiRun(
        runId = id,
        taskId = "T",
        title = "t",
        state = state,
        mode = mode,
        executed = null,
        effect = "",
        reasonCode = null,
        createdAt = "",
        updatedAt = "",
        pullRequestNumber = pr,
    )

    @Test
    fun unknownSelfDevRunGetsTheUnknownRelease() {
        assertEquals(ReleaseKind.UNKNOWN, releaseKindOf(run(state = "UNKNOWN")))
    }

    @Test
    fun pullRequestWithoutBindingGetsTheUnboundRelease() {
        assertEquals(ReleaseKind.UNBOUND_PR, releaseKindOf(run(state = "PULL_REQUEST", pr = null)))
        assertNull(releaseKindOf(run(state = "PULL_REQUEST", pr = 42)))
    }

    @Test
    fun otherRunsAndModesGetNoRelease() {
        assertNull(releaseKindOf(run(state = "RUNNING")))
        assertNull(releaseKindOf(run(mode = "SIMULATED", state = "UNKNOWN")))
        assertNull(releaseKindOf(run(state = "UNKNOWN", id = "not-a-run-id")))
    }

    @Test
    fun releasePathsMatchTheServerRoutes() {
        assertEquals("/api/admin/selfdev/runs/$runId/release", releasePath(runId, ReleaseKind.UNKNOWN))
        assertEquals("/api/admin/selfdev/runs/$runId/release-unbound-pr", releasePath(runId, ReleaseKind.UNBOUND_PR))
    }

    @Test
    fun goalCanBeStoppedOnlyWhileNotTerminal() {
        assertTrue(canStopGoal("PENDING_APPROVAL"))
        assertTrue(canStopGoal("QUEUED"))
        assertFalse(canStopGoal("COMPLETED"))
        assertFalse(canStopGoal("SKIPPED"))
        assertFalse(canStopGoal("STOPPED"))
    }

    @Test
    fun actualAmountShape() {
        assertTrue(isValidBudgetActual(""))
        assertTrue(isValidBudgetActual("  "))
        assertTrue(isValidBudgetActual("0"))
        assertTrue(isValidBudgetActual(" 1234.5 "))
        assertTrue(isValidBudgetActual("1.123456"))
        assertFalse(isValidBudgetActual("1.1234567"))
        assertFalse(isValidBudgetActual("1234567890123"))
        assertFalse(isValidBudgetActual("-1"))
        assertFalse(isValidBudgetActual("1,000"))
        assertFalse(isValidBudgetActual("1e3"))
    }

    @Test
    fun releaseFailureTextsAreOperationSpecific() {
        assertTrue(operatorWriteFailureText(OperatorAction.RELEASE_RUN, WriteFailure.BAD_REQUEST).contains("500"))
        assertTrue(operatorWriteFailureText(OperatorAction.RELEASE_RUN, WriteFailure.STALE).contains("解放できる状態"))
        assertEquals(writeFailureText(WriteFailure.FORBIDDEN), operatorWriteFailureText(OperatorAction.RELEASE_RUN, WriteFailure.FORBIDDEN))
    }

    @Test
    fun goalStopFailureTextsAreOperationSpecific() {
        assertTrue(operatorWriteFailureText(OperatorAction.STOP_GOAL, WriteFailure.DISABLED).contains("無効"))
        assertTrue(operatorWriteFailureText(OperatorAction.STOP_GOAL, WriteFailure.BAD_REQUEST).contains("制御文字"))
        assertEquals(writeFailureText(WriteFailure.STALE), operatorWriteFailureText(OperatorAction.STOP_GOAL, WriteFailure.STALE))
    }

    @Test
    fun budgetFailureTextsUseTheMachineCode() {
        assertTrue(operatorWriteFailureText(OperatorAction.ACKNOWLEDGE_BUDGET, WriteFailure.BAD_REQUEST, "TOO_LARGE").contains("100 倍"))
        assertTrue(operatorWriteFailureText(OperatorAction.ACKNOWLEDGE_BUDGET, WriteFailure.BAD_REQUEST, "NOT_COMPARABLE").contains("単位"))
        assertTrue(operatorWriteFailureText(OperatorAction.ACKNOWLEDGE_BUDGET, WriteFailure.BAD_REQUEST, null).contains("入力を確認"))
        assertTrue(operatorWriteFailureText(OperatorAction.ACKNOWLEDGE_BUDGET, WriteFailure.STALE).contains("未確定ではありません"))
        assertTrue(operatorWriteFailureText(OperatorAction.ACKNOWLEDGE_BUDGET, WriteFailure.DISABLED).contains("台帳"))
    }

    @Test
    fun networkFailureSaysToReloadForEveryOperation() {
        for (action in OperatorAction.entries) {
            assertEquals(writeFailureText(WriteFailure.NETWORK), operatorWriteFailureText(action, WriteFailure.NETWORK))
        }
    }

    @Test
    fun reservationLabelShowsTheAttemptForCliKeys() {
        assertEquals("CLI の attempt 01234567…（世代 3）", reservationLabel("coding:01234567890:3"))
        assertEquals("予約 ABCDEFGH…", reservationLabel("ABCDEFGHIJKL"))
    }

    @Test
    fun releaseTextsAreFilledForBothKinds() {
        for (kind in ReleaseKind.entries) {
            val texts = releaseTextsOf(kind)
            assertTrue(texts.title.isNotEmpty())
            assertTrue(texts.explain.contains("CANCELLED"))
        }
    }
}
