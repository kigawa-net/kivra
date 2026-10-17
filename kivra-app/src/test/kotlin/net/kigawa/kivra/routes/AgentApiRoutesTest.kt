package net.kigawa.kivra.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.math.BigDecimal
import java.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AgentIdentity
import net.kigawa.kivra.auth.AgentTestTokens
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.coding.COST_TENANT
import net.kigawa.kivra.coding.CodingClaimOutcome
import net.kigawa.kivra.coding.CodingCostPort
import net.kigawa.kivra.coding.CodingCostUnavailableException
import net.kigawa.kivra.coding.CodingEnqueueOutcome
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.UnmeteredCodingCost
import net.kigawa.kivra.coding.CliUsage
import net.kigawa.kivra.coding.costFixture
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.selfdev.FakeGitHub
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CLAIM = "/api/agent/v1/claim"
private const val UNKNOWN_ATTEMPT = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

/**
 * The agent API over HTTP (PR 4a), on H2 with the real [CodingRuntimeService] and the real authenticators. The operator routes are
 * mounted next to it with the operator's real [OidcJwtVerifier], so the cross-tests (an agent token on an operator route, and the
 * reverse) run against production code. No network: the JWKS is in memory.
 */
class AgentApiRoutesTest {
    private val agentA = "11111111-1111-4111-8111-111111111111"
    private val agentB = "22222222-2222-4222-8222-222222222222"
    private val title = "TITLE-MARKER README を直す"
    private val summaryMarker = "SUMMARY-MARKER 説明"
    private val contentMarker = "CONTENT-MARKER body"
    private val pathMarker = ".github/path-marker.yml"

    private inner class Env(val db: TestDb, cost: CodingCostPort = UnmeteredCodingCost) {
        val service = CodingRuntimeService(
            attempts = CodingAttemptRepository(db.database, audit = db.audit),
            runs = DbCodingRunPort(db.runs, db.leases),
            results = DbCodingResultStore(db.database),
            tasks = CodingTaskSource { it.taskTitle },
            cost = cost,
        )
        val agents = AgentIdentity(AgentTestTokens.config(), OidcTestTokens.jwks())
        val operators = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks()).asIdentity()
        val budget = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), Clock.systemUTC())

        fun newRun(state: DevelopmentRunState = DevelopmentRunState.DEVELOPING): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            transaction(db.database) { execUpdate("UPDATE development_runs SET task_title = ? WHERE run_id = ?", title, runId) }
            db.forceState(runId, state)
            return runId
        }

        fun enqueue(runId: String): CodingAttempt =
            (service.enqueue(runId, BASE_SHA) as CodingEnqueueOutcome.Queued).attempt
    }

    private fun ApplicationTestBuilder.setUp(env: Env) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureAgentApiRoutes(AgentApiDeps(env.service, env.agents))
                configureTaskApiRoutes(env.db.runs, env.operators)
                configureSelfDevRoutes(SelfDevRouteDeps(env.db.runs, env.operators, RepoRef("kigawa-net", "kivra"), 1, FakeGitHub()))
                configureSelfDevAdminRoutes(env.db.runs, env.operators)
                configureBudgetAdminRoutes(env.budget, env.operators)
            }
        }
    }

    private fun token(sub: String) = AgentTestTokens.token(subject = sub)

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        token: String? = token(agentA),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = client.post(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        extraHeaders.forEach { (k, v) -> header(k, v) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.postRaw(path: String, body: String, contentType: ContentType, token: String = token(agentA)): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(contentType)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String? = token(agentA), extraHeaders: Map<String, String> = emptyMap()): HttpResponse =
        client.get(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }

    private fun heartbeatPath(id: String) = "/api/agent/v1/attempts/$id/heartbeat"
    private fun resultPath(id: String) = "/api/agent/v1/attempts/$id/result"
    private fun failPath(id: String) = "/api/agent/v1/attempts/$id/fail"
    private fun statusPath(id: String) = "/api/agent/v1/attempts/$id/status"

    private fun resultBody(generation: Long, path: String = "docs/a.md", content: String = "new text\n", summary: String = "docs を更新する") =
        """{"generation":$generation,"result":{"summary":"$summary","changes":[{"path":"$path","kind":"WRITE","content":"$content"}],"exitStatus":0,"sessionId":"session-1"}}"""

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    /** Claims one attempt as [sub] over HTTP and returns the attempt id and generation. */
    private suspend fun ApplicationTestBuilder.claimAs(sub: String): Pair<String, Long> {
        val response = post(CLAIM, "{}", token = token(sub))
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = parse(response.bodyAsText())
        return obj["attemptId"]!!.jsonPrimitive.content to obj["generation"]!!.jsonPrimitive.content.toLong()
    }

    // ---- authentication and authorization ----

    @Test
    fun `no credential, a forwarded header alone, or a bad credential is 401 on every agent route`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val expired = AgentTestTokens.token(expiresInSeconds = -120)
            val other = AgentTestTokens.token(audiences = listOf(AgentTestTokens.ANTHROPIC_AUDIENCE))
            val wrongKey = AgentTestTokens.token(key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())
            for (bad in listOf<String?>(null, expired, other, wrongKey)) {
                assertEquals(HttpStatusCode.Unauthorized, post(CLAIM, "{}", token = bad).status, "bad=${bad?.take(10)}")
            }
            // oauth2-proxy's forwarded header carries a valid agent token, and is still ignored.
            val forwarded = post(CLAIM, "{}", token = null, extraHeaders = mapOf("X-Forwarded-Access-Token" to token(agentA)))
            assertEquals(HttpStatusCode.Unauthorized, forwarded.status)
            assertEquals("""{"error":"unauthorized"}""", forwarded.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, get(statusPath(UNKNOWN_ATTEMPT), token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, post(heartbeatPath(UNKNOWN_ATTEMPT), """{"generation":1}""", token = null).status)
        }
    }

    @Test
    fun `a valid token of the wrong client, azp, or role is 403 with a fixed body`() = TestDb().use { db ->
        testApplication {
            setUp(Env(db))
            val wrongAzp = AgentTestTokens.token(azp = "admin-panel")
            val noAgentRole = AgentTestTokens.token(resourceAccess = mapOf("admin-panel" to mapOf("roles" to listOf("admin"))))
            val operator = AgentTestTokens.operatorToken()
            for (t in listOf(wrongAzp, noAgentRole, operator)) {
                val response = post(CLAIM, "{}", token = t)
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals("""{"error":"forbidden"}""", response.bodyAsText())
            }
            assertEquals(HttpStatusCode.Forbidden, get(statusPath(UNKNOWN_ATTEMPT), token = operator).status)
        }
    }

    @Test
    fun `an agent token is refused by the operator routes, and an operator token still works there`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val agent = token(agentA)
            val runId = env.newRun(DevelopmentRunState.UNKNOWN)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/selfdev/tasks", """{"title":"x","idempotencyKey":"k1"}""", token = agent).status)
            assertEquals(HttpStatusCode.Unauthorized, get("/api/selfdev/runs", token = agent).status)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/admin/selfdev/runs/$runId/release", """{"reason":"x"}""", token = agent).status)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/admin/selfdev/runs/$runId/release-unbound-pr", """{"reason":"x"}""", token = agent).status)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/admin/budget/acknowledge", "{}", token = agent).status)
            assertEquals(HttpStatusCode.Unauthorized, get("/api/runs", token = agent).status)
            // The same routes accept the operator's own token, so the refusals above are the authorization and not a broken setup.
            val operator = OidcTestTokens.token()
            assertEquals(HttpStatusCode.OK, get("/api/runs", token = operator).status)
        }
    }

    // ---- ownership, fencing and state ----

    @Test
    fun `another agent's attempt is 404 for every attempt route, the same as a missing one`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val missing = get(statusPath(UNKNOWN_ATTEMPT), token = token(agentB)).bodyAsText()
            val responses = listOf(
                get(statusPath(attemptId), token = token(agentB)),
                post(heartbeatPath(attemptId), """{"generation":$generation}""", token = token(agentB)),
                post(failPath(attemptId), """{"generation":$generation,"errorCode":"TIMEOUT"}""", token = token(agentB)),
                post(resultPath(attemptId), resultBody(generation, path = pathMarker, content = contentMarker), token = token(agentB)),
            )
            for (r in responses) {
                assertEquals(HttpStatusCode.NotFound, r.status)
                assertEquals(missing, r.bodyAsText())
            }
            // The holder's attempt is untouched by the other agent's calls.
            assertEquals(CodingAttemptState.CLAIMED, env.db.let { CodingAttemptRepository(it.database).get(attemptId)!!.state })
            assertEquals(HttpStatusCode.OK, get(statusPath(attemptId), token = token(agentA)).status)
        }
    }

    @Test
    fun `a stale generation is 409 for heartbeat, result and fail, and the holder's generation is accepted`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            assertEquals(1L, generation)
            for (r in listOf(
                post(heartbeatPath(attemptId), """{"generation":0}"""),
                post(resultPath(attemptId), resultBody(0)),
                post(failPath(attemptId), """{"generation":0,"errorCode":"TIMEOUT"}"""),
            )) {
                assertEquals(HttpStatusCode.Conflict, r.status)
                assertEquals("""{"error":"conflict","reason":"STALE_GENERATION"}""", r.bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, post(heartbeatPath(attemptId), """{"generation":1,"progress":"EDITING"}""").status)
        }
    }

    @Test
    fun `after a result is accepted the attempt is terminal, and the holder gets 409 NOT_ACTIVE`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val accepted = post(resultPath(attemptId), resultBody(generation))
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals("""{"attemptId":"$attemptId","state":"RESULT_RECEIVED"}""", accepted.bodyAsText())
            val late = post(heartbeatPath(attemptId), """{"generation":$generation}""")
            assertEquals(HttpStatusCode.Conflict, late.status)
            assertEquals("""{"error":"conflict","reason":"NOT_ACTIVE"}""", late.bodyAsText())
        }
    }

    @Test
    fun `status tells the holder whether the Run asked to cancel`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val runId = env.newRun()
            env.enqueue(runId)
            val (attemptId, _) = claimAs(agentA)
            assertEquals("""{"attemptId":"$attemptId","state":"CLAIMED","cancelRequested":false}""", get(statusPath(attemptId)).bodyAsText())
            env.service.cancel(runId)
            assertEquals("""{"attemptId":"$attemptId","state":"CLAIMED","cancelRequested":true}""", get(statusPath(attemptId)).bodyAsText())
        }
    }

    @Test
    fun `two agents racing for one attempt - exactly one gets 200`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            repeat(3) {
                env.enqueue(env.newRun())
                val statuses = coroutineScope {
                    val a = async { post(CLAIM, "{}", token = token(agentA)).status.value }
                    val b = async { post(CLAIM, "{}", token = token(agentB)).status.value }
                    listOf(a.await(), b.await()).sorted()
                }
                assertEquals(listOf(200, 204), statuses)
            }
        }
    }

    @Test
    fun `the claim body carries the input, with the task marked untrusted, the limits and the base sha, and then 204`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val runId = env.newRun()
            val attempt = env.enqueue(runId)
            val response = post(CLAIM, "{}")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            // The task text is data for the agent and is shown here, and only here. The holder's identity is never in the body.
            assertFalse(text.contains(agentA), "the sub must not appear in the claim body")
            val obj = parse(text)
            assertEquals(attempt.attemptId, obj["attemptId"]!!.jsonPrimitive.content)
            assertEquals(runId, obj["runId"]!!.jsonPrimitive.content)
            assertEquals(1L, obj["generation"]!!.jsonPrimitive.content.toLong())
            val input = obj["input"]!!.jsonObject
            assertEquals(BASE_SHA, input["baseSha"]!!.jsonPrimitive.content)
            val task = input["task"]!!.jsonObject
            assertEquals("UNTRUSTED", task["trust"]!!.jsonPrimitive.content)
            assertEquals(title, task["value"]!!.jsonPrimitive.content)
            assertEquals(1800L, input["limits"]!!.jsonObject["maxRuntimeSeconds"]!!.jsonPrimitive.content.toLong())
            assertTrue(input["allowedPathPatterns"]!!.jsonArray.isNotEmpty())
            val next = post(CLAIM, "{}", token = token(agentB))
            assertEquals(HttpStatusCode.NoContent, next.status)
            assertEquals("", next.bodyAsText())
        }
    }

    // ---- browser, content type, size and shape ----

    @Test
    fun `a request that a browser sends is 403, whatever the claim`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            for (header in listOf("Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Site" to "none", "Sec-Fetch-Site" to "cross-site", "Origin" to "https://kivra-dev.kigawa.net")) {
                assertEquals(
                    HttpStatusCode.Forbidden,
                    post(heartbeatPath(attemptId), """{"generation":$generation}""", extraHeaders = mapOf(header)).status,
                    header.first,
                )
                assertEquals(HttpStatusCode.Forbidden, get(statusPath(attemptId), extraHeaders = mapOf(header)).status, header.first)
            }
            assertEquals(HttpStatusCode.Forbidden, post(CLAIM, "{}", extraHeaders = mapOf("Sec-Fetch-Site" to "same-origin")).status)
        }
    }

    @Test
    fun `a POST whose body is not application json is 415`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            assertEquals(HttpStatusCode.UnsupportedMediaType, postRaw(CLAIM, "{}", ContentType.Text.Plain).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, postRaw(heartbeatPath(attemptId), """{"generation":$generation}""", ContentType.Text.Plain).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, postRaw(resultPath(attemptId), resultBody(generation), ContentType.Application.FormUrlEncoded).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, postRaw(failPath(attemptId), """{"generation":$generation,"errorCode":"TIMEOUT"}""", ContentType.Text.Plain).status)
            // A charset parameter is still application/json.
            assertEquals(
                HttpStatusCode.OK,
                postRaw(heartbeatPath(attemptId), """{"generation":$generation}""", ContentType.parse("application/json; charset=utf-8")).status,
            )
        }
    }

    @Test
    fun `a body over 2 MiB is 413, both with a Content-Length and as a chunked stream`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val big = ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() }
            val declared = client.post(resultPath(attemptId)) {
                header(HttpHeaders.Authorization, "Bearer ${token(agentA)}")
                contentType(ContentType.Application.Json)
                setBody(big)
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
            val chunked = client.post(resultPath(attemptId)) {
                header(HttpHeaders.Authorization, "Bearer ${token(agentA)}")
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.Json
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(big)
                        channel.flush()
                    }
                })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
            assertEquals("""{"error":"payload too large"}""", chunked.bodyAsText())
            // The attempt is unchanged by the refused bodies.
            assertEquals(CodingAttemptState.CLAIMED, CodingAttemptRepository(db.database).get(attemptId)!!.state)
            assertEquals(HttpStatusCode.OK, post(heartbeatPath(attemptId), """{"generation":$generation}""").status)
        }
    }

    @Test
    fun `an invalid body is 400 - an unknown key, a free-text progress or error code, or broken JSON`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val invalid = listOf(
                heartbeatPath(attemptId) to """{"generation":$generation,"note":"free text"}""",
                heartbeatPath(attemptId) to """{"generation":$generation,"progress":"my own words"}""",
                heartbeatPath(attemptId) to "not json",
                failPath(attemptId) to """{"generation":$generation,"errorCode":"something went wrong"}""",
                failPath(attemptId) to """{"generation":$generation,"errorCode":"TIMEOUT","reason":"free"}""",
                resultPath(attemptId) to """{"generation":$generation,"result":{"summary":"s","changes":[{"path":"a.md","kind":"MOVE"}],"exitStatus":0,"sessionId":null}}""",
                CLAIM to """{"unexpected":true}""",
            )
            for ((path, body) in invalid) {
                val response = post(path, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("""{"error":"invalid request body"}""", response.bodyAsText())
            }
            // None of them changed the attempt.
            assertEquals(CodingAttemptState.CLAIMED, CodingAttemptRepository(db.database).get(attemptId)!!.state)
            assertEquals(HttpStatusCode.OK, post(failPath(attemptId), """{"generation":$generation,"errorCode":"TIMEOUT"}""").status)
        }
    }

    @Test
    fun `a result the PatchPolicy refuses is 422 with fixed reason names, and nothing of its content comes back`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val body = resultBody(generation, path = pathMarker, content = contentMarker, summary = summaryMarker)
            val response = post(resultPath(attemptId), body)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val text = response.bodyAsText()
            assertFalse(text.contains(contentMarker) || text.contains(pathMarker) || text.contains(summaryMarker), text)
            val obj = parse(text)
            assertEquals("rejected", obj["error"]!!.jsonPrimitive.content)
            assertTrue(obj["policyReasons"]!!.jsonArray.isNotEmpty() || obj["reasons"]!!.jsonArray.isNotEmpty())
            // A refused result changes nothing: the holder may send a corrected one.
            assertEquals(CodingAttemptState.CLAIMED, CodingAttemptRepository(db.database).get(attemptId)!!.state)
            assertEquals(HttpStatusCode.OK, post(resultPath(attemptId), resultBody(generation)).status)
        }
    }

    // ---- logs, responses and audit ----

    @Test
    fun `logs and responses carry no token, sub, task text, path or content`() = TestDb().use { db ->
        val logger = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            testApplication {
                val env = Env(db)
                setUp(env)
                env.enqueue(env.newRun())
                val tokenA = token(agentA)
                val responses = mutableListOf<String>()
                val claim = post(CLAIM, "{}", token = tokenA)
                val (attemptId, generation) = parse(claim.bodyAsText()).let {
                    it["attemptId"]!!.jsonPrimitive.content to it["generation"]!!.jsonPrimitive.content.toLong()
                }
                responses += post(heartbeatPath(attemptId), """{"generation":$generation,"progress":"bogus"}""", token = tokenA).bodyAsText()
                responses += post(resultPath(attemptId), resultBody(generation, path = pathMarker, content = contentMarker, summary = summaryMarker), token = tokenA).bodyAsText()
                responses += post(CLAIM, "{}", token = AgentTestTokens.operatorToken()).bodyAsText()
                responses += post(CLAIM, "{}", token = tokenA.dropLast(4) + "xxxx").bodyAsText()
                responses += get(statusPath(attemptId), token = tokenA).bodyAsText()
                responses += post(failPath(attemptId), """{"generation":$generation,"errorCode":"TIMEOUT"}""", token = tokenA).bodyAsText()

                val logs = appender.list.joinToString("\n") { event ->
                    listOf(event.formattedMessage, event.throwableProxy?.message.orEmpty()).joinToString(" ")
                }
                val secrets = listOf(tokenA, agentA, title, contentMarker, pathMarker, summaryMarker)
                for (secret in secrets) {
                    assertFalse(logs.contains(secret), "a log line carries a secret")
                    for (r in responses) assertFalse(r.contains(secret), "a response carries a secret")
                }
                assertTrue(logs.contains(attemptId), "the logs do carry the attempt id")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `the audit chain records the claim, the rejection and the accepted result under the agent's sub - fixed codes only`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val runId = env.newRun()
            env.enqueue(runId)
            val (attemptId, generation) = claimAs(agentA)
            post(resultPath(attemptId), resultBody(generation, path = pathMarker, content = contentMarker, summary = summaryMarker))
            assertEquals(HttpStatusCode.OK, post(resultPath(attemptId), resultBody(generation)).status)

            val rows = transaction(db.database) {
                execQuery("SELECT action, actor, payload FROM audit_events WHERE run_id = ? ORDER BY seq", runId) {
                    Triple(it.getString(1), it.getString(2), it.getString(3))
                }
            }
            val coding = rows.filter { it.first.startsWith("CODING_") }
            assertEquals(listOf("CODING_CLAIMED", "CODING_RESULT_REJECTED", "CODING_RESULT_RECEIVED"), coding.map { it.first })
            assertTrue(coding.all { it.second == agentA }, "the actor is the agent's sub")
            val payloads = coding.joinToString("\n") { it.third.orEmpty() }
            for (secret in listOf(contentMarker, pathMarker, summaryMarker, title)) assertFalse(payloads.contains(secret))
        }
    }

    // ---- the optional usage (ADR 0010 addendum 4) ----

    private val usageJson = """{"totalCostUsd":"0.004268","model":"claude-haiku-5-5","numTurns":4,"inputTokens":1200,"outputTokens":300,"cacheCreationTokens":5000,"cacheReadTokens":800}"""

    @Test
    fun `a valid usage on a result is booked as reported, and the result is accepted`() = TestDb().use { db ->
        val fixture = costFixture(db)
        testApplication {
            val env = Env(db, fixture.ledger)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val body = """{"generation":$generation,"result":{"summary":"docs","changes":[{"path":"docs/a.md","kind":"WRITE","content":"x\n"}],"exitStatus":0,"sessionId":"session-1"},"usage":$usageJson}"""
            assertEquals(HttpStatusCode.OK, post(resultPath(attemptId), body).status)
        }
        val u = fixture.budget.usage(COST_TENANT)
        assertEquals(0, BigDecimal("0.774642").compareTo(u.committed))
        assertEquals(0L, u.unpricedCalls)
    }

    @Test
    fun `an invalid usage is ignored - the fail is still accepted, and the reserve is booked as unpriced`() = TestDb().use { db ->
        val fixture = costFixture(db)
        testApplication {
            val env = Env(db, fixture.ledger)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val bad = """{"generation":$generation,"errorCode":"TIMEOUT","usage":{"totalCostUsd":"SECRET-USAGE-MARKER"}}"""
            val response = post(failPath(attemptId), bad)
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(response.bodyAsText().contains("SECRET-USAGE-MARKER"))
        }
        val u = fixture.budget.usage(COST_TENANT)
        assertEquals(0, BigDecimal("90.75").compareTo(u.committed))
        assertEquals(1L, u.unpricedCalls)
    }

    @Test
    fun `a usage is optional - a result without it is accepted and booked at the reserve`() = TestDb().use { db ->
        val fixture = costFixture(db)
        testApplication {
            val env = Env(db, fixture.ledger)
            setUp(env)
            env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            assertEquals(HttpStatusCode.OK, post(resultPath(attemptId), resultBody(generation)).status)
        }
        assertEquals(1L, fixture.budget.usage(COST_TENANT).unpricedCalls)
    }

    @Test
    fun `when the ledger cannot record the cost, the fail is 503 and the attempt stays claimed`() = TestDb().use { db ->
        val refusing = object : CodingCostPort {
            override fun claimDenial(): String? = null
            override fun recordReported(attemptId: String, leaseGeneration: Long, usage: CliUsage): Boolean = false
            override fun recordEstimated(attemptId: String, leaseGeneration: Long): Boolean = false
        }
        testApplication {
            val env = Env(db, refusing)
            setUp(env)
            val attempt = env.enqueue(env.newRun())
            val (attemptId, generation) = claimAs(agentA)
            val response = post(failPath(attemptId), """{"generation":$generation,"errorCode":"TIMEOUT"}""")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("""{"error":"unavailable"}""", response.bodyAsText())
            assertEquals(CodingAttemptState.CLAIMED, env.service.latest(attempt.runId)!!.state)
        }
    }

    // ---- the task body in the claim (Issue #229, addendum) ----

    private suspend fun ApplicationTestBuilder.claimWithBody(env: Env, runId: String, body: String?): kotlinx.serialization.json.JsonObject {
        if (body != null) transaction(env.db.database) { execUpdate("UPDATE development_runs SET task_body = ? WHERE run_id = ?", body, runId) }
        env.enqueue(runId)
        return parse(post(CLAIM, "{}").bodyAsText()).getValue("input").jsonObject.getValue("task").jsonObject
    }

    @Test
    fun `the claim carries the stored body, marked untrusted, and the flag stays false for a body kivra did not cut`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val task = claimWithBody(env, env.newRun(), "BODY-MARKER 手順\n1. 直す")
            assertEquals("BODY-MARKER 手順\n1. 直す", task.getValue("body").jsonPrimitive.content)
            assertEquals(false, task.getValue("bodyTruncated").jsonPrimitive.content.toBoolean())
            assertEquals("UNTRUSTED", task.getValue("trust").jsonPrimitive.content)
            assertEquals(title, task.getValue("value").jsonPrimitive.content)
        }
    }

    @Test
    fun `the claim without a stored body carries an empty body, and a body that kivra cut is flagged`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val none = claimWithBody(env, env.newRun(), null)
            assertEquals("", none.getValue("body").jsonPrimitive.content)
            assertEquals(false, none.getValue("bodyTruncated").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `a body that ends with the truncation mark is reported as truncated`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val cut = "x".repeat(10) + net.kigawa.kivra.coding.TaskBodyText.TRUNCATION_MARK
            val task = claimWithBody(env, env.newRun(), cut)
            assertEquals(cut, task.getValue("body").jsonPrimitive.content)
            assertEquals(true, task.getValue("bodyTruncated").jsonPrimitive.content.toBoolean())
        }
    }
}
