package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatActionRulesTest {
    @Test
    fun theConfirmTextNamesTheServerLabelAndWhatRuns() {
        val start = chatActionConfirmMessage("周を開始する", "ORDER_START_CYCLE")
        assertTrue(start.contains("「周を開始する」"))
        assertTrue(start.contains("新しい周を 1 つ開始"))
        val abandon = chatActionConfirmMessage("進行中の周を放棄する", "ORDER_ABANDON_CYCLE")
        assertTrue(abandon.contains("動いている Task を止めます"))
        assertTrue(abandon.contains("order の状態は変わりません"))
    }

    @Test
    fun theReplanConfirmTextSaysWhatRunsAndThatItSpendsBudget() {
        val text = chatActionConfirmMessage("計画に失敗した周を再計画する", "TASK_REPLAN")
        assertTrue(text.contains("「計画に失敗した周を再計画する」"))
        assertTrue(text.contains("Planner に新しい計画を作らせます"))
        assertTrue(text.contains("予算"))
    }

    @Test
    fun theReplanResultCodesHaveTheirOwnText() {
        assertTrue(chatActionResultText("FAILED", "TASK_NOT_FOUND").contains("Task が見つからない"))
        assertTrue(chatActionResultText("FAILED", "TASK_NOT_REPLANNABLE").contains("再計画できる状態ではない"))
        assertTrue(chatActionResultText("EXPIRED", "STALE_VERSION").contains("対象（order または Task）が変わっていた"))
    }

    @Test
    fun anUnknownCodeGetsOnlyTheLabel() {
        assertEquals("「x」を確定します。", chatActionConfirmMessage("x", "SOMETHING_NEW"))
    }

    @Test
    fun theResultTextMapsTheFixedCodes() {
        assertEquals("実行しました。", chatActionResultText("EXECUTED", "OK"))
        assertEquals(CHAT_ACTION_DISMISSED_TEXT, chatActionResultText("DISMISSED", "OPERATOR_DISMISSED"))
        assertTrue(chatActionResultText("EXPIRED", "STALE_VERSION").contains("対象（order または Task）が変わっていた"))
        assertTrue(chatActionResultText("FAILED", "CYCLE_IN_PROGRESS").contains("進行中の周"))
        assertTrue(chatActionResultText("FAILED", "ORDER_NOT_FOUND").contains("見つからない"))
    }

    @Test
    fun aCodeThisClientDoesNotNameIsNeverShownRaw() {
        val text = chatActionResultText("FAILED", "SOME_NEW_REFUSAL")
        assertFalse(text.contains("SOME_NEW_REFUSAL"))
        assertTrue(text.isNotBlank())
        assertEquals("実行しました。", chatActionResultText("EXECUTED", null))
    }

    @Test
    fun aFailedRequestGetsItsWriteFailureText() {
        assertEquals("既に処理されています。チャットを読み直してください。", chatActionFailureText(WriteFailure.STALE))
        assertEquals(writeFailureText(WriteFailure.NETWORK), chatActionFailureText(WriteFailure.NETWORK))
    }
}
