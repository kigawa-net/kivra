package net.kigawa.kivra.chat

import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.db.ProjectChatRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.modelgateway.GatewayError
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.ErrorKind
import net.kigawa.kivra.modelgateway.ModelEvent
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The turn's summary (ADR 0023 追補 8) through the replier, on H2 with the real chat repository. The summarizer is a fake: the gateway call
 * itself is tested in [GatewayChatSummarizerTest]. Every failure of the summary must fall back to the raw window, the request the replier
 * would have built without a summary.
 */
class ProjectChatSummaryReplierTest {

    private val nonce = "7d41c0ffee"
    private val long = 1_600

    /** A summarizer that records its inputs and answers with [answer] (which may throw). */
    private class FakeSummarizer(private val answer: suspend (ChatSummaryInput) -> ChatSummary) : ChatSummarizer {
        val inputs = mutableListOf<ChatSummaryInput>()
        override suspend fun summarize(input: ChatSummaryInput): ChatSummary {
            inputs += input
            return answer(input)
        }
    }

    private fun TestDb.session(chats: ProjectChatRepository, messages: List<Pair<String, String>>): String {
        val projectId = ProjectRepository(database, Clock.systemUTC()).create("Summary", null).projectId
        chats.createSession(projectId, "Chat")
        messages.forEach { (role, content) -> chats.appendMessage(projectId, role, content) }
        return projectId
    }

    /** Five messages, long enough to pass the short-history threshold; the newest is the user's. */
    private fun longHistory(): List<Pair<String, String>> = listOf(
        "user" to "A".repeat(long),
        "assistant" to "B".repeat(long),
        "user" to "C".repeat(400),
        "assistant" to "D".repeat(100),
        "user" to "最新の依頼",
    )

    private fun text(m: net.kigawa.kivra.modelgateway.Message) = (m.parts.single() as Part.Text).text

    @Test
    fun `the summary replaces the older history, then the preceding assistant message and the newest user message go verbatim`()= TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val summarizer = FakeSummarizer { ChatSummary("決定事項: 画面を直す", omitted = 0) }
        val replier = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE), summarizer = summarizer, summaryNonce = { nonce })

        val request = runBlocking { replier.turnRequest(sessionId) }

        val history = request.messages.drop(1)
        assertEquals(Role.SYSTEM, request.messages.first().role)
        assertEquals(3, history.size)
        assertEquals(Role.USER, history[0].role)
        assertTrue(text(history[0]).contains("<conversation-summary nonce=\"$nonce\">\n決定事項: 画面を直す\n</conversation-summary nonce=\"$nonce\">"))
        assertEquals(Role.ASSISTANT, history[1].role)
        assertEquals("D".repeat(100), text(history[1]))
        assertEquals(Role.USER, history[2].role)
        assertEquals("最新の依頼", text(history[2]))
        assertTrue(request.messages.none { m -> m.parts.any { p -> p is Part.Text && p.text.contains("AAAA") } }, "the older raw messages are not sent")
        // The summarizer saw every older message (oldest first), and not the newest one.
        val input = summarizer.inputs.single()
        assertEquals(listOf("A".repeat(long), "B".repeat(long), "C".repeat(400), "D".repeat(100)), input.messages.map { it.content })
        assertEquals(0, input.omitted)
    }

    @Test
    fun `the message cap leaves the older messages out, and the reply block says so`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val summarizer = FakeSummarizer { ChatSummary("要点", omitted = it.omitted) }
        val replier = ProjectChatReplier(
            chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE),
            summarizer = summarizer, summaryLimits = ChatSummaryLimits(maxMessages = 2), summaryNonce = { nonce },
        )

        val request = runBlocking { replier.turnRequest(sessionId) }

        assertEquals(listOf("C".repeat(400), "D".repeat(100)), summarizer.inputs.single().messages.map { it.content })
        assertTrue(text(request.messages[1]).contains("それより前の 2 件は含まれていません"))
    }

    @Test
    fun `a failing summarizer falls back to the raw window, whatever the failure`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val raw = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE)).request(sessionId).messages
        val failures: List<suspend (ChatSummaryInput) -> ChatSummary> = listOf(
            { throw ChatSummaryFailure("OUTPUT_LIMIT") },
            { throw GatewayException(GatewayError(ErrorKind.POLICY_DENIED, "budget", code = "SECRET_DETECTED")) },
            { throw IllegalStateException("boom") },
        )
        failures.forEach { failure ->
            val replier = ProjectChatReplier(
                chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE), summarizer = FakeSummarizer(failure), summaryNonce = { nonce },
            )
            assertEquals(raw, runBlocking { replier.turnRequest(sessionId) }.messages)
        }
    }

    @Test
    fun `a summary that does not return in time falls back to the raw window`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val raw = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE)).request(sessionId).messages
        val replier = ProjectChatReplier(
            chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE),
            summarizer = FakeSummarizer { delay(60_000); ChatSummary("late", 0) },
            summaryLimits = ChatSummaryLimits(timeout = 50.milliseconds),
            summaryNonce = { nonce },
        )

        assertEquals(raw, runBlocking { replier.turnRequest(sessionId) }.messages)
    }

    @Test
    fun `a summary that looks like a secret, is too long, or contains the nonce is not used`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val raw = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE)).request(sessionId).messages
        val bad = listOf(
            ChatSummaryLimits() to ChatSummary("token sk-abcdefghijklmnop1234 を使う", 0),
            ChatSummaryLimits(maxOutputChars = 200) to ChatSummary("要".repeat(300), 0),
            ChatSummaryLimits() to ChatSummary("nonce $nonce を含む", 0),
            ChatSummaryLimits() to ChatSummary("   ", 0),
        )
        bad.forEach { (limits, summary) ->
            val replier = ProjectChatReplier(
                chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE),
                summarizer = FakeSummarizer { summary }, summaryLimits = limits, summaryNonce = { nonce },
            )
            assertEquals(raw, runBlocking { replier.turnRequest(sessionId) }.messages, "summary: ${summary.text.take(20)}")
        }
    }

    @Test
    fun `a short history, a session of two messages, or a newest message that is not the user's is sent raw, without a summary`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val short = db.session(chats, listOf("user" to "a", "assistant" to "b", "user" to "c"))
        val two = ProjectRepository(db.database, Clock.systemUTC()).create("Two", null).projectId.also {
            chats.createSession(it, "Two")
            chats.appendMessage(it, "user", "A".repeat(long))
            chats.appendMessage(it, "user", "B".repeat(long))
        }
        val endsWithAssistant = ProjectRepository(db.database, Clock.systemUTC()).create("Ends", null).projectId.also {
            chats.createSession(it, "Ends")
            listOf("user" to "A".repeat(long), "assistant" to "B".repeat(long), "user" to "C".repeat(long), "assistant" to "D".repeat(long))
                .forEach { (role, content) -> chats.appendMessage(it, role, content) }
        }
        val summarizer = FakeSummarizer { ChatSummary("never", 0) }
        val replier = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE), summarizer = summarizer, summaryNonce = { nonce })

        listOf(short, two, endsWithAssistant).forEach { sessionId ->
            assertEquals(
                ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE)).request(sessionId).messages,
                runBlocking { replier.turnRequest(sessionId) }.messages,
            )
        }
        assertEquals(0, summarizer.inputs.size, "the summarizer is never called")
    }

    @Test
    fun `without a summarizer the request is the raw window, and nothing is summarized`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val replier = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE))

        assertEquals(replier.request(sessionId).messages, runBlocking { replier.turnRequest(sessionId) }.messages)
    }

    @Test
    fun `the reply streams with the summary in its request, and completes`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats, longHistory())
        val provider = ScriptedChatProvider(listOf("はい"))
        val replier = ProjectChatReplier(
            chatTestGateway(provider), chats, listOf(TEST_BUDGET_SCOPE),
            summarizer = FakeSummarizer { ChatSummary("要点", 0) }, summaryNonce = { nonce },
        )

        val events = runBlocking { replier.reply(sessionId).toList() }

        assertIs<ModelEvent.Completed>(events.last())
        val sent = provider.requests.single().messages
        assertEquals(Role.USER, sent[1].role)
        assertTrue(text(sent[1]).contains("要点"))
        assertEquals("最新の依頼", text(sent.last()))
    }
}
