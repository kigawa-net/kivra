package net.kigawa.kivra.exkes

import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.OperationStatus
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Adapter-specific behaviour beyond the shared contract: failure classification, identity checks, auth. */
class ExkesAdapterTest {
    private fun MockRequestHandleScope.reply(status: Int, body: String) =
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))

    private val cfg = ExkesConfig("http://exkes.test/")
    private fun setup(tokens: TokenProvider = TokenProvider.None): Pair<ExkesSimulator, ExkesAdapter> {
        val sim = ExkesSimulator()
        return sim to ExkesAdapter(ExkesAdapter.newClient(sim.engine, cfg), cfg, tokens)
    }

    @Test
    fun `POST timeout is sent exactly once and returned as UNKNOWN`() = runTest {
        val (sim, a) = setup()
        sim.lost.addLast("createWorkspace" to true)
        val r = a.createWorkspace(WorkspaceSpec("kivra-run-1"))
        assertIs<PortResult.Unknown>(r)
        assertEquals(OperationStatus.UNKNOWN, r.toOperationStatus())
        assertEquals(1, sim.requestsOf(HttpMethod.Post).size, "no automatic resend")
    }

    @Test
    fun `5xx on POST is UNKNOWN and on GET is Transient`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Post) reply(500, "boom") else null }
        assertIs<PortResult.Unknown>(a.createWorkspace(WorkspaceSpec("kivra-run-1")))
        assertEquals(1, sim.requestsOf(HttpMethod.Post).size)
        sim.override = { reply(503, "") }
        assertIs<PortResult.Transient>(a.getWorkspace(WorkspaceId("x")))
    }

    @Test
    fun `connection refused is Transient because nothing was sent`() = runTest {
        val (sim, a) = setup()
        sim.override = { throw ConnectException("refused") }
        assertIs<PortResult.Transient>(a.createWorkspace(WorkspaceSpec("kivra-run-1")))
        assertEquals(RETRYABLE, a.createWorkspace(WorkspaceSpec("kivra-run-1")).toOperationStatus())
    }

    private val RETRYABLE = OperationStatus.RETRYABLE

    @Test
    fun `undecodable 202 is UNKNOWN`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Post) reply(202, "not json") else null }
        assertIs<PortResult.Unknown>(a.createWorkspace(WorkspaceSpec("kivra-run-1")))
    }

    @Test
    fun `4xx is Rejected with the exkes error code`() = runTest {
        val (_, a) = setup()
        val r = a.createWorkspace(WorkspaceSpec("bad name!"))
        assertIs<PortResult.Rejected>(r)
        assertEquals("VALIDATION_ERROR", r.code)
        assertEquals(OperationStatus.FAILED, r.toOperationStatus())
    }

    @Test
    fun `409 on POST adopts the workspace that exists under the name`() = runTest {
        val (sim, a) = setup()
        sim.workspaces["ws-x"] = WorkspaceDto("ws-x", "kivra-run-1", "READY")
        // Hide it from the pre-lookup (simulates the race: it appears between lookup and POST).
        var hide = true
        sim.override = { req ->
            if (hide && req.method == HttpMethod.Get && req.url.encodedPath == "/v1/workspaces") {
                hide = false
                reply(200, """{"items":[],"total":0,"limit":100,"offset":0}""")
            } else null
        }
        val r = a.createWorkspace(WorkspaceSpec("kivra-run-1"))
        assertIs<PortResult.Ok<Workspace>>(r)
        assertEquals("ws-x", r.value.id.value)
    }

    @Test
    fun `409 without any workspace behind it stays Conflict`() = runTest {
        val (sim, a) = setup()
        sim.override = { req ->
            when {
                req.method == HttpMethod.Post -> reply(409, """{"code":"CONFLICT","message":"dup"}""")
                else -> null
            }
        }
        assertIs<PortResult.Conflict>(a.createWorkspace(WorkspaceSpec("kivra-run-1")))
    }

    @Test
    fun `response id and parent are verified`() = runTest {
        val (sim, a) = setup()
        sim.override = { req ->
            val p = req.url.encodedPath
            when {
                p == "/v1/workspaces/ws-1" -> reply(200, """{"id":"ws-OTHER","name":"n","status":"READY"}""")
                p == "/v1/workspaces/ws-1/runtimes/rt-1" ->
                    reply(200, """{"id":"rt-1","workspaceId":"ws-OTHER","templateId":"t","status":"RUNNING"}""")
                p == "/v1/runtimes/rt-1/executions/ex-1" ->
                    reply(200, """{"id":"ex-OTHER","runtimeId":"rt-1","type":"COMMAND","status":"RUNNING"}""")
                p == "/v1/runtimes/rt-1/executions" && req.method == HttpMethod.Get ->
                    reply(200, """{"items":[{"id":"ex-1","runtimeId":"rt-OTHER","type":"COMMAND","status":"RUNNING"}],"total":1}""")
                else -> null
            }
        }
        val ws = WorkspaceId("ws-1")
        val rt = RuntimeId(ws, "rt-1")
        assertIs<PortResult.IdentityMismatch>(a.getWorkspace(ws))
        assertIs<PortResult.IdentityMismatch>(a.getRuntime(rt))
        assertIs<PortResult.IdentityMismatch>(a.getExecution(ExecutionId(rt, "ex-1")))
        assertIs<PortResult.IdentityMismatch>(a.listExecutions(rt))
        assertEquals(OperationStatus.UNKNOWN, a.getRuntime(rt).toOperationStatus())
    }

    @Test
    fun `created resource must echo the requested name, workspace and type`() = runTest {
        val (sim, a) = setup()
        sim.override = { req ->
            val p = req.url.encodedPath
            when {
                req.method == HttpMethod.Post && p == "/v1/workspaces" ->
                    reply(202, """{"id":"ws-9","name":"someone-else","status":"CREATING"}""")
                req.method == HttpMethod.Post && p.endsWith("/executions") ->
                    reply(202, """{"id":"ex-9","runtimeId":"rt-1","type":"AGENT","status":"QUEUED"}""")
                else -> null
            }
        }
        assertIs<PortResult.IdentityMismatch>(a.createWorkspace(WorkspaceSpec("kivra-run-1")))
        sim.runtimes["rt-1"] = RuntimeDto("rt-1", "ws-1", "t", "RUNNING")
        sim.workspaces["ws-1"] = WorkspaceDto("ws-1", "w", "READY")
        val r = a.startExecution(RuntimeId(WorkspaceId("ws-1"), "rt-1"), ExecutionSpec(ExecutionType.COMMAND, commandJson = "{}"))
        assertIs<PortResult.IdentityMismatch>(r)
    }

    @Test
    fun `Bearer token comes from the TokenProvider`() = runTest {
        val (sim, a) = setup(TokenProvider.static("tok-123"))
        a.getWorkspace(WorkspaceId("x"))
        assertEquals("Bearer tok-123", sim.requests.single().headers[HttpHeaders.Authorization])

        val (sim2, a2) = setup()
        a2.getWorkspace(WorkspaceId("x"))
        assertNull(sim2.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a client with HttpRequestRetry is refused`() {
        val sim = ExkesSimulator()
        val client = ExkesAdapter.newClient(sim.engine, cfg) { install(HttpRequestRetry) }
        assertFailsWith<IllegalArgumentException> { ExkesAdapter(client, cfg) }
    }

    @Test
    fun `workspace names are deterministic and validated`() {
        assertEquals("kivra-run-01HX", KivraNaming.workspaceName("01HX"))
        assertFailsWith<IllegalArgumentException> { KivraNaming.workspaceName("a b") }
        assertEquals(mapOf("kivra.run-id" to "01HX"), KivraNaming.runLabels("01HX"))
    }
}
