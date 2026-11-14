package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.chat.ProjectChatReplier
import net.kigawa.kivra.chat.ScriptedChatProvider
import net.kigawa.kivra.chat.ScriptedRound
import net.kigawa.kivra.chat.ToolRoundsProvider
import net.kigawa.kivra.chat.ChatToolDeps
import net.kigawa.kivra.chat.chatTestGateway
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectChatMessageRow
import net.kigawa.kivra.db.ProjectChatRepository
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.newDbStore
import net.kigawa.kivra.modelgateway.ErrorKind
import net.kigawa.kivra.modelgateway.GatewayError
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.SendState
import kotlinx.coroutines.CancellationException

/**
 * `POST /api/projects/{id}/chat/stream` on H2: the frames of a completed reply, the stored messages (the assistant message only when the model
 * completed), the failure frame, the checks shared with the POST, and the route is absent without a reply service.
 */
class ProjectChatStreamRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private fun ApplicationTestBuilder.setUp(db: TestDb, gateway: ModelGateway?, withTools: Boolean = false) {
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val chats = ProjectChatRepository(db.database)
        val reply = gateway?.let { ProjectChatReplier(it, chats, listOf(TEST_TENANT_SCOPE)) }
        val members = ProjectMemberRepository(db.database, Clock.systemUTC())
        // The tools without the order routes: the project and run tools only (enough for the frames).
        val tools = if (withTools) ChatToolDeps(projects, db.runs, members, offPermissionGuard()) else null
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    projects, db.runs, identity, offPermissionGuard(),
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, Clock.systemUTC()),
                    budgets = newDbStore(db, Clock.systemUTC()),
                    drain = ProjectDrainRepository(db.database, newDbStore(db, Clock.systemUTC())),
                    chatRepo = chats,
                    chatReply = reply,
                    chatTools = tools,
                )
            }
        }
    }

    private fun TestDb.project(): String = ProjectRepository(database, Clock.systemUTC()).create("Stream", null).projectId

    private suspend fun ApplicationTestBuilder.stream(
        projectId: String,
        body: String?,
        principal: String? = "operator",
        mediaType: ContentType = ContentType.Application.Json,
    ): HttpResponse = client.request("/api/projects/$projectId/chat/stream") {
        method = HttpMethod.Post
        principal?.let { header("X-Test-Principal", it) }
        contentType(mediaType)
        body?.let { setBody(it) }
    }

    private fun TestDb.messages(projectId: String): List<ProjectChatMessageRow> =
        ProjectChatRepository(database).let { chats ->
            chats.getSession(projectId)?.let { chats.getMessages(it.projectId, 100) } ?: emptyList()
        }

    /** The `event` names of an SSE body, in order. */
    private fun eventsOf(body: String): List<String> =
        body.lines().filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }

    @Test
    fun `a completed reply streams user, deltas and done, and stores the user and the whole assistant message`() = TestDb().use { db ->
        testApplication {
            setUp(db, chatTestGateway(ScriptedChatProvider(listOf("Hel", "lo"))))
            val projectId = db.project()

            val response = stream(projectId, """{"content":"What is the status?","title":"Status"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"].orEmpty().startsWith("text/event-stream"))
            val body = response.bodyAsText()
            assertEquals(listOf("user", "delta", "delta", "done"), eventsOf(body))
            assertTrue(body.contains("data: {\"text\":\"Hel\"}"))
            assertTrue(body.contains("data: {\"text\":\"lo\"}"))
            val stored = db.messages(projectId)
            assertEquals(listOf("user", "assistant"), stored.map { it.role })
            assertEquals("What is the status?", stored[0].content)
            assertEquals("Hello", stored[1].content)
        }
    }

    @Test
    fun `a reply that fails before any text sends an error frame, stores no assistant message, and keeps the user message`() = TestDb().use { db ->
        testApplication {
            setUp(db, chatTestGateway(ScriptedChatProvider(failure = ErrorKind.TRANSIENT)))
            val projectId = db.project()

            val body = stream(projectId, """{"content":"hello"}""").bodyAsText()

            assertEquals(listOf("user", "error"), eventsOf(body))
            assertTrue(body.contains("data: {\"code\":\"OUTCOME_UNKNOWN\"}"), body)
            assertEquals(listOf("user"), db.messages(projectId).map { it.role })
        }
    }

    @Test
    fun `a reply that fails after some text stores the text generated so far as the assistant message`() = TestDb().use { db ->
        testApplication {
            setUp(db, chatTestGateway(ScriptedChatProvider(listOf("Hel"), tail = GatewayException(GatewayError(ErrorKind.TRANSIENT, "cut", sendState = SendState.MAYBE_SENT)))))
            val projectId = db.project()

            val body = stream(projectId, """{"content":"hello"}""").bodyAsText()

            assertEquals(listOf("user", "delta", "error"), eventsOf(body))
            assertEquals(listOf("user", "assistant"), db.messages(projectId).map { it.role })
            assertEquals("Hel", db.messages(projectId)[1].content)
        }
    }

    @Test
    fun `a reply that fails after only whitespace stores no assistant message`() = TestDb().use { db ->
        testApplication {
            setUp(db, chatTestGateway(ScriptedChatProvider(listOf("  \n"), tail = GatewayException(GatewayError(ErrorKind.TRANSIENT, "cut", sendState = SendState.MAYBE_SENT)))))
            val projectId = db.project()

            assertEquals(listOf("user", "delta", "error"), eventsOf(stream(projectId, """{"content":"hello"}""").bodyAsText()))
            assertEquals(listOf("user"), db.messages(projectId).map { it.role })
        }
    }

    @Test
    fun `a reply whose client goes away mid-stream stores the text generated so far`() = TestDb().use { db ->
        testApplication {
            // The model call is cancelled after two deltas, as when the client disconnects. The response may end without its frames.
            setUp(db, chatTestGateway(ScriptedChatProvider(listOf("Hel", "lo"), tail = CancellationException("client gone"))))
            val projectId = db.project()

            runCatching { stream(projectId, """{"content":"hello"}""").bodyAsText() }

            assertEquals(listOf("user", "assistant"), db.messages(projectId).map { it.role })
            assertEquals("Hello", db.messages(projectId)[1].content)
        }
    }

    @Test
    fun `an empty reply sends an EMPTY_REPLY error frame and stores no assistant message`() = TestDb().use { db ->
        testApplication {
            setUp(db, chatTestGateway(ScriptedChatProvider(emptyList())))
            val projectId = db.project()

            val body = stream(projectId, """{"content":"hello"}""").bodyAsText()

            assertEquals(listOf("user", "error"), eventsOf(body))
            assertTrue(body.contains("data: {\"code\":\"EMPTY_REPLY\"}"), body)
            assertEquals(listOf("user"), db.messages(projectId).map { it.role })
        }
    }

    @Test
    fun `a second message gets its own reply, and the model sees the first exchange in its history`() = TestDb().use { db ->
        val provider = ScriptedChatProvider(listOf("ok"))
        testApplication {
            setUp(db, chatTestGateway(provider))
            val projectId = db.project()

            assertEquals(listOf("user", "delta", "done"), eventsOf(stream(projectId, """{"content":"one"}""").bodyAsText()))
            assertEquals(listOf("user", "delta", "done"), eventsOf(stream(projectId, """{"content":"two"}""").bodyAsText()))

            assertEquals(listOf("user", "assistant", "user", "assistant"), db.messages(projectId).map { it.role })
            val secondRequest = provider.requests.last().messages.drop(1).map { (it.parts.single() as Part.Text).text }
            assertEquals(listOf("one", "ok", "two"), secondRequest)
        }
    }

    @Test
    fun `a tool call streams tool frames of fixed codes, and only the assistant's text is stored`() = TestDb().use { db ->
        val provider = ToolRoundsProvider(
            listOf(
                ScriptedRound(listOf("確認します。"), calls = listOf("project_get" to emptyMap())),
                ScriptedRound(listOf("名前は Stream です。")),
            ),
        )
        testApplication {
            setUp(db, chatTestGateway(provider), withTools = true)
            val projectId = db.project()

            val body = stream(projectId, """{"content":"このプロジェクトの名前は?"}""").bodyAsText()

            assertEquals(listOf("user", "delta", "tool", "tool", "delta", "delta", "done"), eventsOf(body))
            assertTrue(body.contains("data: {\"name\":\"project_get\",\"status\":\"STARTED\"}"), body)
            assertTrue(body.contains("data: {\"name\":\"project_get\",\"status\":\"OK\",\"code\":\"OK\"}"), body)
            assertFalse(body.contains("Stream\",\"description"), "the tool's data is not streamed")
            val stored = db.messages(projectId)
            assertEquals(listOf("user", "assistant"), stored.map { it.role })
            assertEquals("確認します。\n\n名前は Stream です。", stored[1].content)
        }
    }

    @Test
    fun `a client that goes away after a tool call keeps the text so far`() = TestDb().use { db ->
        val provider = ToolRoundsProvider(
            listOf(
                ScriptedRound(listOf("確認します。"), calls = listOf("project_get" to emptyMap())),
                ScriptedRound(listOf("途中"), tail = CancellationException("client gone")),
            ),
        )
        testApplication {
            setUp(db, chatTestGateway(provider), withTools = true)
            val projectId = db.project()

            runCatching { stream(projectId, """{"content":"hello"}""").bodyAsText() }

            assertEquals(listOf("user", "assistant"), db.messages(projectId).map { it.role })
            assertEquals("確認します。\n\n途中", db.messages(projectId)[1].content)
        }
    }

    @Test
    fun `the route is not registered without a reply service`() = TestDb().use { db ->
        testApplication {
            setUp(db, gateway = null)
            val projectId = db.project()

            assertEquals(HttpStatusCode.NotFound, stream(projectId, """{"content":"hello"}""").status)
            assertTrue(db.messages(projectId).isEmpty())
        }
    }

    @Test
    fun `the checks of the POST apply (a principal, the JSON media type, a project, and a non-blank bounded message)`() = TestDb().use { db ->
        testApplication {
            setUp(db, chatTestGateway(ScriptedChatProvider()))
            val projectId = db.project()

            assertEquals(HttpStatusCode.Unauthorized, stream(projectId, """{"content":"hello"}""", principal = null).status)
            assertEquals(
                HttpStatusCode.UnsupportedMediaType,
                stream(projectId, "content=hello", mediaType = ContentType.Text.Plain).status,
            )
            assertEquals(HttpStatusCode.NotFound, stream("01AAAAAAAAAAAAAAAAAAAAAAAA", """{"content":"hello"}""").status)
            assertEquals(HttpStatusCode.BadRequest, stream(projectId, """{"content":"   "}""").status)
            val tooLong = "x".repeat(ProjectChatReplier.CONTENT_MAX + 1)
            val refused = stream(projectId, """{"content":"$tooLong"}""")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("content_too_long"))
            // Nothing was stored by the refused requests.
            assertFalse(db.messages(projectId).any { it.role == "user" })
        }
    }

    private companion object {
        const val TEST_TENANT_SCOPE = "tenant"
    }
}
