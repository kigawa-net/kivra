package net.kigawa.kivra.mcp

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.db.SELF_DEV_TASK_PREFIX
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef

/** The `/mcp` routes on H2 with the real repository and the real JWT verifier (test keys, no network). */
class McpRoutesTest {
    private val title = "SECRET-TITLE-MARKER README を直す"
    private val bobOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-bob")!!

    private fun settings(rate: Int = 60, origins: String = McpTestTokens.ORIGIN): McpSettings = McpSettings.fromEnv(
        mapOf(
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            McpSettings.ENV_ENABLED to "true",
            McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
            McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
            McpSettings.ENV_ALLOWED_ORIGINS to origins,
            McpSettings.ENV_RATE_PER_MINUTE to rate.toString(),
        ),
    )

    private fun deps(db: TestDb, rate: Int = 60, origins: String = McpTestTokens.ORIGIN, agentRole: RequiredRole? = RequiredRole("kivra-agent", "agent")) =
        McpRouteDeps(
            settings = settings(rate, origins),
            verifier = McpJwtVerifier(McpTestTokens.config, OidcTestTokens.jwks(), agentRole),
            issuer = OidcTestTokens.ISSUER,
            dispatcher = McpDispatcher(McpRunTools(db.runs, RepoRef("kigawa-net", "kivra")).tools()),
            rateLimiter = McpRateLimiter(rate),
        )

    private fun ApplicationTestBuilder.setUp(db: TestDb, rate: Int = 60, origins: String = McpTestTokens.ORIGIN) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureMcpRoutes(deps(db, rate, origins)) }
        }
    }

    private fun seed(db: TestDb, principal: String, n: Int, prefix: String = "task"): List<String> = (1..n).map { i ->
        val r = db.runs.submitTask(principal, "$prefix-$i-${System.nanoTime()}", "$title $i", selfDev = true, maxActiveSelfDev = 100)
        (r as TaskSubmitResult.Created).run.runId.value
    }

    private suspend fun ApplicationTestBuilder.rpc(
        body: String,
        token: String = McpTestTokens.token(),
        contentType: ContentType = ContentType.Application.Json,
        protocolVersion: String? = null,
        accept: String? = null,
        origin: String? = null,
        host: String = McpTestTokens.HOST,
    ): HttpResponse = client.post("/mcp") {
        header(HttpHeaders.Host, host)
        header(HttpHeaders.Authorization, "Bearer $token")
        origin?.let { header(HttpHeaders.Origin, it) }
        protocolVersion?.let { header("MCP-Protocol-Version", it) }
        accept?.let { header(HttpHeaders.Accept, it) }
        contentType(contentType)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.errorCode(): Int = Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    private fun call(name: String, args: String, id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""

    private suspend fun ApplicationTestBuilder.toolResult(
        name: String,
        args: String,
        token: String = McpTestTokens.token(),
    ): JsonObject = rpc(call(name, args), token = token).json()["result"]!!.jsonObject

    // ---- authentication and transport -------------------------------------------------------------------

    @Test
    fun `an unauthenticated request is 401 with the protected resource metadata challenge and a JSON-RPC error`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = client.post("/mcp") {
                header(HttpHeaders.Host, McpTestTokens.HOST)
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                "Bearer resource_metadata=\"https://kivra-dev.kigawa.net/.well-known/oauth-protected-resource\"",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
            assertEquals(-32001, response.errorCode())
        }
    }

    @Test
    fun `a REST-audience token and an agent-audience token are both 401`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = McpTestTokens.token(audience = OidcTestTokens.AUDIENCE)).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = McpTestTokens.token(audience = "kivra-agent", client = "kivra-agent", roles = listOf("agent"))).status,
            )
        }
    }

    @Test
    fun `an agent token with the MCP audience is 403`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = McpTestTokens.token(client = "kivra-agent", roles = listOf("agent")))
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `a host that is not on the allow-list is 403, on the MCP endpoint and on the metadata`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Forbidden, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", host = "evil.example").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/.well-known/oauth-protected-resource") { header(HttpHeaders.Host, "evil.example") }.status)
        }
    }

    @Test
    fun `an Origin that is not on the allow-list is 403, and an allowed or absent Origin passes`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Forbidden, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "https://evil.example").status)
            assertEquals(HttpStatusCode.OK, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = McpTestTokens.ORIGIN).status)
            assertEquals(HttpStatusCode.OK, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        }
    }

    @Test
    fun `GET mcp is 405 and POST is the only method`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = client.get("/mcp") {
                header(HttpHeaders.Host, McpTestTokens.HOST)
                header(HttpHeaders.Authorization, "Bearer ${McpTestTokens.token()}")
            }
            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
            assertEquals("POST", response.headers[HttpHeaders.Allow])
        }
    }

    @Test
    fun `the protected resource metadata (well-known) names the MCP resource, the issuer and the three scopes`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = client.get("/.well-known/oauth-protected-resource") { header(HttpHeaders.Host, McpTestTokens.HOST) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.json()
            assertEquals(McpTestTokens.MCP_AUDIENCE, body["resource"]!!.jsonPrimitive.content)
            assertEquals(OidcTestTokens.ISSUER, body["authorization_servers"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals(
                listOf("kivra.runs.read", "kivra.selfdev.start", "kivra.selfdev.stop"),
                body["scopes_supported"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(listOf("header"), body["bearer_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    // ---- JSON-RPC over HTTP ----------------------------------------------------------------------------

    @Test
    fun `initialize, ping and tools list answer 200, and a notification answers 202 with no body`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val init = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
            assertEquals(HttpStatusCode.OK, init.status)
            assertEquals("2025-06-18", init.json()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
            val ping = rpc("""{"jsonrpc":"2.0","id":2,"method":"ping"}""").json()
            assertEquals(2, ping["id"]!!.jsonPrimitive.content.toInt())
            val list = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").json()["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(listOf("kivra_run_list", "kivra_run_get"), list)
            val note = rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertEquals(HttpStatusCode.Accepted, note.status)
            assertEquals("", note.bodyAsText())
        }
    }

    @Test
    fun `invalid JSON is a parse error, a batch is refused, and a scalar is an invalid request`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val parse = rpc("{not json")
            assertEquals(HttpStatusCode.BadRequest, parse.status)
            assertEquals(-32700, parse.errorCode())
            val batch = rpc("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
            assertEquals(HttpStatusCode.BadRequest, batch.status)
            assertEquals(-32600, batch.errorCode())
            assertTrue(batch.bodyAsText().contains("batch requests are not supported"))
            assertEquals(-32600, rpc("42").errorCode())
        }
    }

    @Test
    fun `an unknown method is -32601 and a wrong content type is 415`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(-32601, rpc("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""").json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
            assertEquals(HttpStatusCode.UnsupportedMediaType, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", contentType = ContentType.Text.Plain).status)
        }
    }

    @Test
    fun `an Accept header without json or event-stream is 406, and an unsupported MCP-Protocol-Version is 400`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotAcceptable, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", accept = "text/html").status)
            assertEquals(HttpStatusCode.OK, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", accept = "application/json, text/event-stream").status)
            assertEquals(HttpStatusCode.BadRequest, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", protocolVersion = "1999-01-01").status)
            assertEquals(HttpStatusCode.OK, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", protocolVersion = "2025-03-26").status)
        }
    }

    @Test
    fun `a body over 64 KiB is 413`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val big = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":"${"a".repeat(MCP_MAX_BODY_BYTES)}"}}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, rpc(big).status)
        }
    }

    @Test
    fun `requests beyond the per-principal rate are 429`() = TestDb().use { db ->
        testApplication {
            setUp(db, rate = 2)
            assertEquals(HttpStatusCode.OK, rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
            assertEquals(HttpStatusCode.OK, rpc("""{"jsonrpc":"2.0","id":2,"method":"ping"}""").status)
            assertEquals(HttpStatusCode.TooManyRequests, rpc("""{"jsonrpc":"2.0","id":3,"method":"ping"}""").status)
        }
    }

    // ---- tools -----------------------------------------------------------------------------------------

    @Test
    fun `run list shows only the caller's own runs, and run get returns not found for another caller's run`() = TestDb().use { db ->
        val mine = seed(db, McpTestTokens.aliceOwner, 2)
        val theirs = seed(db, bobOwner, 1)
        testApplication {
            setUp(db)
            val list = toolResult("kivra_run_list", "{}")
            assertEquals(false, list["isError"]!!.jsonPrimitive.content.toBoolean())
            val ids = list["structuredContent"]!!.jsonObject["runs"]!!.jsonArray.map { it.jsonObject["run_id"]!!.jsonPrimitive.content }
            assertEquals(mine.toSet(), ids.toSet())
            assertFalse(ids.contains(theirs[0]))

            val foreign = toolResult("kivra_run_get", """{"run_id":"${theirs[0]}"}""")
            assertEquals(true, foreign["isError"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("NOT_FOUND", foreign["structuredContent"]!!.jsonObject["reason_code"]!!.jsonPrimitive.content)

            val missing = toolResult("kivra_run_get", """{"run_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV"}""")
            assertEquals(foreign["structuredContent"], missing["structuredContent"])

            val own = toolResult("kivra_run_get", """{"run_id":"${mine[0]}"}""")
            assertEquals(false, own["isError"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(mine[0], own["structuredContent"]!!.jsonObject["run_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `run list pages with a cursor that is one of the caller's own runs`() = TestDb().use { db ->
        seed(db, McpTestTokens.aliceOwner, 3)
        val bobRun = seed(db, bobOwner, 1)[0]
        testApplication {
            setUp(db)
            val first = toolResult("kivra_run_list", """{"limit":2}""")["structuredContent"]!!.jsonObject
            val firstIds = first["runs"]!!.jsonArray.map { it.jsonObject["run_id"]!!.jsonPrimitive.content }
            assertEquals(2, firstIds.size)
            val cursor = assertNotNull(first["next_cursor"]!!.jsonPrimitive.content)
            val second = toolResult("kivra_run_list", """{"limit":2,"cursor":"$cursor"}""")["structuredContent"]!!.jsonObject
            val secondIds = second["runs"]!!.jsonArray.map { it.jsonObject["run_id"]!!.jsonPrimitive.content }
            assertEquals(1, secondIds.size)
            assertEquals(3, (firstIds + secondIds).toSet().size)
            assertEquals("null", second["next_cursor"].toString())

            // Another caller's run as a cursor is the same invalid cursor (-32602), so it does not reveal the run.
            val foreignCursor = rpc(call("kivra_run_list", """{"cursor":"$bobRun"}""")).json()
            assertEquals(-32602, foreignCursor["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `run list and run get never return the title, the body or a secret marker`() = TestDb().use { db ->
        val mine = seed(db, McpTestTokens.aliceOwner, 1)
        testApplication {
            setUp(db)
            val text = rpc(call("kivra_run_get", """{"run_id":"${mine[0]}"}""")).bodyAsText() +
                rpc(call("kivra_run_list", "{}")).bodyAsText()
            assertFalse(text.contains("SECRET-TITLE-MARKER"))
            assertTrue(text.contains("\"kind\":\"selfdev\""))
            assertFalse(text.contains(SELF_DEV_TASK_PREFIX))
        }
    }

    @Test
    fun `a run id of the wrong shape and an unknown argument are invalid params`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(-32602, rpc(call("kivra_run_get", """{"run_id":"short"}""")).errorCode())
            assertEquals(-32602, rpc(call("kivra_run_get", """{"run_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","x":1}""")).errorCode())
            assertEquals(-32602, rpc(call("kivra_run_list", """{"limit":51}""")).errorCode())
            assertEquals(-32602, rpc(call("kivra_run_list", """{"limit":0}""")).errorCode())
        }
    }

    @Test
    fun `a token without the read scope gets 403 insufficient_scope on a tool call`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = rpc(call("kivra_run_list", "{}"), token = McpTestTokens.token(scope = "kivra.selfdev.start"))
            assertEquals(HttpStatusCode.Forbidden, response.status)
            val challenge = assertNotNull(response.headers[HttpHeaders.WWWAuthenticate])
            assertTrue(challenge.contains("error=\"insufficient_scope\""))
            assertTrue(challenge.contains("scope=\"kivra.runs.read\""))
            assertEquals(-32003, response.errorCode())
        }
    }

    @Test
    fun `a token without the read scope sees no tools, and a write tool is not there at all`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val list = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = McpTestTokens.token(scope = null)).json()
            assertEquals(0, list["result"]!!.jsonObject["tools"]!!.jsonArray.size)
            val start = rpc(call("kivra_selfdev_start", """{"title":"x"}"""), token = McpTestTokens.token(scope = "kivra.selfdev.start")).json()
            assertEquals(-32602, start["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `the audit line of a request holds no title, argument value or token`() {
        val line = mcpAuditLine("tools/call", "kivra_run_get", McpTestTokens.aliceOwner, "OK")
        assertFalse(line.contains("SECRET"))
        assertFalse(line.contains("Bearer"))
        assertFalse(line.contains("sub-alice"))
        assertFalse(line.contains(McpTestTokens.aliceOwner))
    }

    @Test
    fun `the tools list result holds the arrays it should`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val arr: JsonArray = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").json()["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(2, arr.size)
        }
    }
}
