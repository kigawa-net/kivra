package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
