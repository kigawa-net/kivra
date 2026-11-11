package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.ui.api.ApiCurrentCycle

class OrderCycleRulesTest {
    private val goalId = "01M60G8C7GXDKN8KNM51CQ2F1H"

    @Test
    fun abandonIsOnlyForAnActiveOrderWithACycleInProgress() {
        assertTrue(canAbandonCycle("ACTIVE", goalId))
        assertFalse(canAbandonCycle("ACTIVE", null))
        assertFalse(canAbandonCycle("ACTIVE", ""))
        // PAUSED は画面では出さない。STOP_REQUESTED・STOPPED はサーバーが 409。
        assertFalse(canAbandonCycle("PAUSED", goalId))
        assertFalse(canAbandonCycle("STOP_REQUESTED", goalId))
        assertFalse(canAbandonCycle("STOPPED", goalId))
    }

    @Test
    fun noCycleInProgressShowsNoStatus() {
        assertNull(currentCycleText(null, null))
        assertNull(currentCycleText("", ApiCurrentCycle(goalId, taskMissing = true)))
    }

    @Test
    fun aMissingTaskIsShownAsAnOldGoal() {
        val text = currentCycleText(goalId, ApiCurrentCycle(goalId, taskMissing = true))
        assertEquals(CURRENT_CYCLE_TASK_MISSING_TEXT, text)
        assertTrue(text!!.contains("旧 Goal"))
    }

    @Test
    fun aRunningTaskIsShownByItsStateAndReason() {
        val plain = currentCycleText(goalId, ApiCurrentCycle(goalId, taskState = "QUEUED"))!!
        assertTrue(plain.contains(goalStateText("QUEUED").label))
        val withReason = currentCycleText(goalId, ApiCurrentCycle(goalId, taskState = "BLOCKED", taskReason = "BUDGET_HOLD"))!!
        assertTrue(withReason.contains(goalStateText("BLOCKED").label))
        assertTrue(withReason.contains("BUDGET_HOLD"))
    }

    @Test
    fun anOldServerWithoutTheFieldIsShownAsUnknown() {
        assertEquals("状態は不明です", currentCycleText(goalId, null))
    }

    @Test
    fun emptyCyclesNeverSaysNotStartedWhileACurrentGoalExists() {
        assertEquals(CYCLES_NOT_STARTED_TEXT, emptyCyclesText(null, null))
        val lost = emptyCyclesText(goalId, ApiCurrentCycle(goalId, taskMissing = true))
        assertFalse(lost.contains("まだ開始されていません"))
        assertTrue(lost.contains("旧 Goal"))
        assertFalse(emptyCyclesText(goalId, null).contains("まだ開始されていません"))
    }

    @Test
    fun abandonFailuresUseFixedTexts() {
        assertTrue(abandonFailureText(WriteFailure.BAD_REQUEST, null).contains("500"))
        assertTrue(abandonFailureText(WriteFailure.STALE, "NO_CYCLE_IN_PROGRESS").contains("進行中の周がありません"))
        assertTrue(abandonFailureText(WriteFailure.STALE, "CYCLE_ALREADY_ENDED").contains("成功"))
        assertTrue(abandonFailureText(WriteFailure.STALE, "TERMINAL").contains("停止済み"))
        assertEquals(writeFailureText(WriteFailure.STALE), abandonFailureText(WriteFailure.STALE, null))
        assertEquals(writeFailureText(WriteFailure.FORBIDDEN), abandonFailureText(WriteFailure.FORBIDDEN, "NO_CYCLE_IN_PROGRESS"))
        // OperatorAction からも同じ文言に届く。
        assertEquals(
            abandonFailureText(WriteFailure.STALE, "TERMINAL"),
            operatorWriteFailureText(OperatorAction.ABANDON_CYCLE, WriteFailure.STALE, "TERMINAL"),
        )
    }
}
