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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Adapter-specific behaviour beyond the shared contract: failure classification, identity checks, auth. */
class ExkesAdapterTest {
    private fun MockRequestHandleScope.reply(status: Int, body: String) =
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))

    private val cfg = ExkesConfig("http://exkes.test/")
    private fun setup(tokens: TokenProvider = TokenProvider.None): Pair<ExkesSimulator, ExkesAdapter> {
        val sim = ExkesSimulator()
        return sim to ExkesAdapter(ExkesAdapter.newClient(sim.engine, cfg), cfg, tokens)
    }

    private val secret = "Bearer DO-NOT-KEEP-ADAPTER"

    private fun assertNoSecret(r: PortResult<*>) {
        assertFalse(r.toString().contains("DO-NOT-KEEP"), "toString leaked: ${r::class.simpleName}")
        assertFalse(r.kindCode().contains("DO-NOT-KEEP"), "kindCode leaked")
        val text = when (r) {
            is PortResult.Rejected -> listOf(r.code, r.message)
            is PortResult.Conflict -> listOf(r.message)
            is PortResult.Transient -> listOf(r.message)
            is PortResult.Retryable -> listOf(r.message)
            is PortResult.Unknown -> listOf(r.message)
            else -> emptyList()
        }
        text.forEach { assertFalse(it?.contains("DO-NOT-KEEP") == true, "message field leaked") }
    }

    @Test
    fun `provider error bodies and exception text never become PortResult fields`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Post) reply(400, """{"code":"VALIDATION_ERROR","message":"echo $secret"}""") else null }
        val rejected = a.createWorkspace(WorkspaceSpec("kivra-run-1"))
        assertIs<PortResult.Rejected>(rejected)
        assertEquals("VALIDATION_ERROR", rejected.code)
        assertNoSecret(rejected)

        sim.override = { req -> if (req.method == HttpMethod.Post) reply(409, """{"code":"CONFLICT","message":"dup $secret"}""") else null }
        assertNoSecret(a.createWorkspace(WorkspaceSpec("kivra-run-2")).also { assertIs<PortResult.Conflict>(it) })

        // A code that is not a plain token is dropped instead of copied.
        sim.override = { req -> if (req.method == HttpMethod.Post) reply(400, """{"code":"$secret","message":"x"}""") else null }
        assertNull((a.createWorkspace(WorkspaceSpec("kivra-run-3")) as PortResult.Rejected).code)
    }

    @Test
    fun `transport and token exceptions never cross the PortResult boundary`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Post) throw java.io.IOException("socket reset $secret") else null }
        val unknown = a.createWorkspace(WorkspaceSpec("kivra-run-4"))
        assertIs<PortResult.Unknown>(unknown)
        assertEquals("TRANSPORT_EXCEPTION", unknown.message)
        assertNoSecret(unknown)
        // No Throwable or provider text is carried by an adapter error.
        assertFalse(PortResult.Unknown(secret).toString().contains("DO-NOT-KEEP"))

        val (_, tokenFailing) = setup(TokenProvider { throw IllegalStateException("token endpoint said $secret") })
        val transient = tokenFailing.createWorkspace(WorkspaceSpec("kivra-run-5"))
        assertIs<PortResult.Transient>(transient)
        assertEquals("TOKEN_PROVIDER_FAILED", transient.message)
        assertNoSecret(transient)
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
    fun `5xx on POST is UNKNOWN and on GET is Retryable`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Post) reply(500, "boom") else null }
        assertIs<PortResult.Unknown>(a.createWorkspace(WorkspaceSpec("kivra-run-1")))
        assertEquals(1, sim.requestsOf(HttpMethod.Post).size)
        sim.override = { reply(503, "") }
        val read = a.getWorkspace(WorkspaceId("x"))
        assertIs<PortResult.Retryable>(read)
        assertFalse(read is PortResult.Transient, "a sent GET is not a not-sent proof")
    }

    @Test
    fun `5xx on a mutating DELETE is UNKNOWN and sent exactly once`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Delete) reply(503, "") else null }
        assertIs<PortResult.Unknown>(a.deleteWorkspace(WorkspaceId("x")))
        assertEquals(1, sim.requestsOf(HttpMethod.Delete).size)
    }

    @Test
    fun `transport loss after the GET was sent is Retryable and never Transient`() = runTest {
        val (sim, a) = setup()
        sim.override = { throw java.io.IOException("socket reset $secret") }
        val r = a.getWorkspace(WorkspaceId("x"))
        assertIs<PortResult.Retryable>(r)
        assertEquals("TRANSPORT_EXCEPTION", r.message)
        assertNoSecret(r)
        assertEquals(OperationStatus.UNKNOWN, r.toOperationStatus(), "Retryable never maps to RETRYABLE (no NotSentProof)")
    }

    @Test
    fun `refused GET before send is Transient`() = runTest {
        val (sim, a) = setup()
        sim.override = { throw ConnectException("refused") }
        assertIs<PortResult.Transient>(a.getWorkspace(WorkspaceId("x")))
    }

    @Test
    fun `429 on a mutating POST is UNKNOWN and sent exactly once`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Post) reply(429, "slow down") else null }
        val r = a.createWorkspace(WorkspaceSpec("kivra-run-1"))
        assertIs<PortResult.Unknown>(r)
        assertEquals(OperationStatus.UNKNOWN, r.toOperationStatus())
        assertEquals(1, sim.requestsOf(HttpMethod.Post).size, "exkes saw the POST, so it must not be resent")
    }

    @Test
    fun `429 on a mutating DELETE is UNKNOWN`() = runTest {
        val (sim, a) = setup()
        sim.override = { req -> if (req.method == HttpMethod.Delete) reply(429, "") else null }
        assertIs<PortResult.Unknown>(a.deleteWorkspace(WorkspaceId("x")))
        assertEquals(1, sim.requestsOf(HttpMethod.Delete).size)
    }

    @Test
    fun `429 on a GET is Retryable`() = runTest {
        val (sim, a) = setup()
        sim.override = { reply(429, "") }
        assertIs<PortResult.Retryable>(a.getWorkspace(WorkspaceId("x")))
        assertEquals(1, sim.requestsOf(HttpMethod.Get).size)
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
    fun `ambiguous runtime labels are a Conflict and no third runtime is created`() = runTest {
        val (sim, a) = setup()
        val labels = KivraNaming.runLabels("01HRUN")
        sim.workspaces["ws-1"] = WorkspaceDto("ws-1", "w", "READY")
        sim.runtimes["rt-1"] = RuntimeDto("rt-1", "ws-1", "t", "RUNNING", labels = labels)
        sim.runtimes["rt-2"] = RuntimeDto("rt-2", "ws-1", "t", "RUNNING", labels = labels)
        val r = a.createRuntime(WorkspaceId("ws-1"), RuntimeSpec("t", labels))
        assertIs<PortResult.Conflict>(r)
        assertEquals(0, sim.requestsOf(HttpMethod.Post).size, "lookup found two: the adapter must not POST")
    }

    @Test
    fun `redirects are not followed - a POST is never resent to another origin`() = runTest {
        val (sim, a) = setup(TokenProvider.static("tok-redirect"))
        val elsewhere = headersOf(HttpHeaders.Location, "http://evil.test/v1/workspaces")
        sim.override = { req ->
            when {
                req.method == HttpMethod.Post -> respond("", HttpStatusCode.TemporaryRedirect, elsewhere)
                req.method == HttpMethod.Get && req.url.encodedPath == "/v1/workspaces" ->
                    reply(200, """{"items":[],"total":0,"limit":100,"offset":0}""")
                else -> respond("", HttpStatusCode.Found, elsewhere)
            }
        }
        // 307 keeps the method and body: following it would re-send the mutation (and the bearer token) elsewhere.
        assertIs<PortResult.Unknown>(a.createWorkspace(WorkspaceSpec("kivra-run-r1")))
        assertEquals(1, sim.requestsOf(HttpMethod.Post).size, "redirect must not cause a second POST")
        // A GET redirect is not followed either; it is an ordinary non-success for a read.
        assertIs<PortResult.Retryable>(a.getWorkspace(WorkspaceId("x")))
        assertEquals(1, sim.requests.count { it.url.encodedPath == "/v1/workspaces/x" }, "GET redirect must not be followed")
        assertTrue(sim.requests.all { it.url.host == "exkes.test" }, "every request stays on the configured origin")
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
