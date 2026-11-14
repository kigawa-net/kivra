package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNull
import net.kigawa.kivra.ui.api.ApiChatMessage

class ChatStreamRulesTest {
    private val stored = """{"id":7,"sessionId":"p1","role":"assistant","content":"Hello","createdAt":"2026-11-14T00:00:00Z"}"""

    @Test
    fun `the frames decode to their events`() {
        assertEquals(ChatStreamEvent.Delta("Hel"), chatStreamEventOf("delta", """{"text":"Hel"}"""))
        assertEquals(ChatStreamEvent.Failed("OUTCOME_UNKNOWN"), chatStreamEventOf("error", """{"code":"OUTCOME_UNKNOWN"}"""))
        val done = assertIs<ChatStreamEvent.Done>(chatStreamEventOf("done", stored))
        assertEquals(ApiChatMessage(7, "p1", "assistant", "Hello", "2026-11-14T00:00:00Z"), done.message)
        assertIs<ChatStreamEvent.User>(chatStreamEventOf("user", stored))
    }

    @Test
    fun `an unknown name or data that does not decode is not an event`() {
        assertNull(chatStreamEventOf("ping", "{}"))
        assertNull(chatStreamEventOf("delta", "{not json"))
        assertNull(chatStreamEventOf("done", """{"id":"x"}"""))
    }

    @Test
    fun `an unknown error code gets the generic text, never the code itself`() {
        assertEquals("返信を作れませんでした。", chatStreamErrorText("SOMETHING_NEW"))
        assertEquals("予算または設定の制限により、返信を作れませんでした。", chatStreamErrorText("POLICY_DENIED"))
    }

    @Test
    fun `a tool frame decodes to its fixed name, status and code`() {
        assertEquals(ChatStreamEvent.Tool("order_get", "STARTED"), chatStreamEventOf("tool", """{"name":"order_get","status":"STARTED"}"""))
        assertEquals(ChatStreamEvent.Tool("order_get", "OK", "OK"), chatStreamEventOf("tool", """{"name":"order_get","status":"OK","code":"OK"}"""))
        assertNull(chatStreamEventOf("tool", """{"status":"OK"}"""))
    }

    @Test
    fun `a tool's line says what is read, what ran, and that a proposal waits for the button`() {
        assertEquals("order を確認中…", chatToolStatusText(ChatStreamEvent.Tool("order_list", "STARTED")))
        assertEquals("order の一時停止を実行しました", chatToolStatusText(ChatStreamEvent.Tool("order_pause", "OK", "OK")))
        assertEquals("order の停止要求を提案しました（確定は下のボタン）", chatToolStatusText(ChatStreamEvent.Tool("order_stop_request", "PROPOSED", "PROPOSED")))
        assertEquals("メンバーの削除の提案を準備中…", chatToolStatusText(ChatStreamEvent.Tool("member_remove", "STARTED")))
        val denied = chatToolStatusText(ChatStreamEvent.Tool("order_pause", "DENIED", "NOT_OFFERED"))
        assertTrue(denied.contains("実行しませんでした"))
        assertFalse(denied.contains("NOT_OFFERED"), "the code is not shown")
        // A name the server did not know: a generic word, never the raw name.
        assertEquals("操作を実行できませんでした", chatToolStatusText(ChatStreamEvent.Tool("unknown", "FAILED", "X")))
    }

    @Test
    fun `a tool's end replaces its STARTED line, in order`() {
        var lines = chatToolLines(emptyList(), ChatStreamEvent.Tool("order_get", "STARTED"))
        lines = chatToolLines(lines, ChatStreamEvent.Tool("project_get", "STARTED"))
        lines = chatToolLines(lines, ChatStreamEvent.Tool("order_get", "OK", "OK"))
        assertEquals(listOf("order を確認しました", "プロジェクトを確認中…"), lines.map { it.first })
    }

    @Test
    fun `the loop's limits get their own text`() {
        assertTrue(chatStreamErrorText("TOOL_ROUND_LIMIT").contains("上限"))
    }
}
