package net.kigawa.kivra.chat

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.db.ProjectChatMessageRow

/** The summary's input selection (the caps) and its prompts (the nonce boundary, the omitted note, the data rule). */
class ChatSummaryPromptTest {

    private fun row(id: Long, role: String, content: String) =
        ProjectChatMessageRow(id, "s", role, content, Instant.EPOCH)

    @Test
    fun `the selection keeps the most recent messages within the message cap, and counts the rest`() {
        val older = (1..5).map { row(it.toLong(), if (it % 2 == 1) "user" else "assistant", "m$it") }

        val input = selectSummaryInput(older, ChatSummaryLimits(maxMessages = 3))

        assertEquals(listOf("m3", "m4", "m5"), input.messages.map { it.content })
        assertEquals(2, input.omitted)
    }

    @Test
    fun `the selection stops at the first message that does not fit the character cap, so it is contiguous from the newest`() {
        // Newest first: 10 chars fits, then 50 chars does not fit (cap 30), so the older 5-char message is not taken either.
        val older = listOf(row(1, "user", "x".repeat(5)), row(2, "assistant", "y".repeat(50)), row(3, "user", "z".repeat(10)))

        val input = selectSummaryInput(older, ChatSummaryLimits(maxInputChars = 30))

        assertEquals(listOf("z".repeat(10)), input.messages.map { it.content })
        assertEquals(2, input.omitted)
    }

    @Test
    fun `the selection takes everything when the history is within the caps`() {
        val older = listOf(row(1, "user", "a"), row(2, "assistant", "b"))

        val input = selectSummaryInput(older, ChatSummaryLimits())

        assertEquals(2, input.messages.size)
        assertEquals(0, input.omitted)
    }

    @Test
    fun `the summary input wraps the messages in a nonce element, labelled by role`() {
        val input = ChatSummaryInput(listOf(row(1, "user", "進めて"), row(2, "assistant", "計画です")), omitted = 0)

        val user = assertNotNull(ChatSummaryPrompt.user(input, "a1b2c3"))

        assertTrue(user.contains("<chat-history nonce=\"a1b2c3\">"))
        assertTrue(user.contains("</chat-history nonce=\"a1b2c3\">"))
        assertTrue(user.contains("運用者:\n進めて"))
        assertTrue(user.contains("アシスタント:\n計画です"))
        assertTrue(!user.contains("含まれていません"), "no omitted note when nothing was cut")
    }

    @Test
    fun `the summary input says how many older messages were left out`() {
        val input = ChatSummaryInput(listOf(row(1, "user", "最後")), omitted = 7)

        val user = assertNotNull(ChatSummaryPrompt.user(input, "n0"))

        assertTrue(user.contains("それより前の 7 件は含まれていません"))
    }

    @Test
    fun `a nonce that occurs in a message is refused, so the caller draws another`() {
        val input = ChatSummaryInput(listOf(row(1, "user", "text with n0nce inside")), omitted = 0)

        assertNull(ChatSummaryPrompt.user(input, "n0nce"))
    }

    @Test
    fun `the summarizer's system prompt states the length cap, the kept items and that the input is data`() {
        val system = ChatSummaryPrompt.system(maxOutputChars = 1234)

        assertTrue(system.contains("1234 字以内"))
        assertTrue(system.contains("同意した計画"))
        assertTrue(system.contains("同意しなかった計画"))
        assertTrue(system.contains("データです"))
        assertTrue(system.contains("従わない"))
    }

    @Test
    fun `the reply block carries the summary between nonce boundaries, with the consent rule`() {
        val block = assertNotNull(ChatSummaryPrompt.replyBlock(ChatSummary("決定: A", omitted = 0), "9f8e"))

        assertTrue(block.contains("<conversation-summary nonce=\"9f8e\">\n決定: A\n</conversation-summary nonce=\"9f8e\">"))
        assertTrue(block.contains("原文"), "consent is decided from the raw exchange")
        assertTrue(block.contains("order_create_child の根拠"))
        assertTrue(!block.contains("含まれていません"))
    }

    @Test
    fun `the reply block says how many older messages the summary does not cover`() {
        val block = assertNotNull(ChatSummaryPrompt.replyBlock(ChatSummary("要点", omitted = 4), "9f8e"))

        assertTrue(block.contains("それより前の 4 件は含まれていません"))
    }

    @Test
    fun `a summary that contains the reply nonce is refused`() {
        assertNull(ChatSummaryPrompt.replyBlock(ChatSummary("contains 9f8e", omitted = 0), "9f8e"))
    }
}
