package net.kigawa.kivra.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AgentIdentity
import net.kigawa.kivra.auth.AgentTestTokens
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.coding.MonitorRateLimiter
import net.kigawa.kivra.coding.UnmeteredCodingCost
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.CodingMonitorEventRepository
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CLAIM = "/api/agent/v1/claim"
private const val UNKNOWN_ATTEMPT = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val TITLE = "TITLE-MARKER README を直す"

/**
 * The monitor event route (ADR 0012, PR 1) over HTTP: the acceptance, the resend, the conflict and the gap, the fencing, the holder check,
 * the strict schema, the size and rate limits, the logs, and the isolation of the monitor from the heartbeat. On H2 or MariaDB, with the
 * real [CodingRuntimeService] and the real authenticator.
 */
class AgentApiMonitorRoutesTest {
    private val agentA = "11111111-1111-4111-8111-111111111111"
    private val agentB = "22222222-2222-4222-8222-222222222222"

    /** The default limit is generous here so that the tests making many requests are not limited; the 429 test sets its own. */
    private inner class Env(val db: TestDb, limiter: MonitorRateLimiter = MonitorRateLimiter(limitPerSecond = 1_000)) {
        val monitor = CodingMonitorEventRepository(db.database)
        val service = CodingRuntimeService(
            attempts = CodingAttemptRepository(db.database, audit = db.audit),
            runs = DbCodingRunPort(db.runs, db.leases),
            results = DbCodingResultStore(db.database),
            tasks = CodingTaskSource { it.taskTitle },
            cost = UnmeteredCodingCost,
        )
        val agents = AgentIdentity(AgentTestTokens.config(), OidcTestTokens.jwks())
        val operators = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks()).asIdentity()
        val deps = AgentApiDeps(service, agents, monitor, limiter)

        /** A DEVELOPING Run with a title, and one claimed attempt held by [agentA] at generation 1. */
        fun claimedAttempt(): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            transaction(db.database) { execUpdate("UPDATE development_runs SET task_title = ? WHERE run_id = ?", TITLE, runId) }
            db.forceState(runId, DevelopmentRunState.DEVELOPING)
            service.enqueue(runId, BASE_SHA)
            return (service.claim(agentA) as net.kigawa.kivra.coding.CodingClaimOutcome.Claimed).input.attemptId
        }

        fun storedRows(attemptId: String): Int = transaction(db.database) {
            execQuery("SELECT COUNT(*) FROM coding_monitor_events WHERE attempt_id = ?", attemptId) { it.getInt(1) }.single()
        }
    }

    private fun ApplicationTestBuilder.setUp(env: Env) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureAgentApiRoutes(env.deps)
            }
        }
    }

    private fun token(sub: String) = AgentTestTokens.token(subject = sub)

    private fun eventsPath(id: String) = "/api/agent/v1/attempts/$id/events"

    private suspend fun ApplicationTestBuilder.postEvents(
        attemptId: String,
        body: String,
        token: String = token(agentA),
    ): HttpResponse = client.post(eventsPath(attemptId)) {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun batch(generation: Long, vararg events: String) = """{"generation":$generation,"events":[${events.joinToString(",")}]}"""

    private fun turn(seq: Int, count: Int = 1) = """{"seq":$seq,"kind":"TURN","count":$count}"""

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    /** Runs [block] with the root logger capturing every message. Returns what was logged. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    // ---- acceptance ----

    @Test
    fun `a batch is accepted, a resend is accepted, a conflict and a gap are 409 with the last accepted seq`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            val first = postEvents(attemptId, batch(1, turn(1), turn(2)))
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("""{"attemptId":"$attemptId","lastAcceptedSeq":2,"truncated":false}""", first.bodyAsText())
            assertEquals(HttpStatusCode.OK, postEvents(attemptId, batch(1, turn(1), turn(2))).status, "a resend of the same content")
            assertEquals(2, env.storedRows(attemptId))

            val conflict = postEvents(attemptId, batch(1, turn(2, count = 5)))
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("""{"error":"conflict","reason":"SEQ_CONFLICT","lastAcceptedSeq":2}""", conflict.bodyAsText())

            val gap = postEvents(attemptId, batch(1, turn(4)))
            assertEquals(HttpStatusCode.Conflict, gap.status)
            assertEquals("""{"error":"conflict","reason":"SEQ_GAP","lastAcceptedSeq":2}""", gap.bodyAsText())
            assertEquals(2, env.storedRows(attemptId))
        }
    }

    @Test
    fun `an older generation is 409 STALE_GENERATION and stores nothing`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            // Generation 2 is well-formed but not the holder's: the lease generation is 1.
            val stale = postEvents(attemptId, batch(2, turn(1)))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("""{"error":"conflict","reason":"STALE_GENERATION"}""", stale.bodyAsText())
            assertEquals(0, env.storedRows(attemptId))
        }
    }

    @Test
    fun `a terminal attempt takes no events, and its earlier events stay`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            assertEquals(HttpStatusCode.OK, postEvents(attemptId, batch(1, turn(1))).status)
            assertEquals(HttpStatusCode.OK, client.post("/api/agent/v1/attempts/$attemptId/fail") {
                header(HttpHeaders.Authorization, "Bearer ${token(agentA)}")
                contentType(ContentType.Application.Json)
                setBody("""{"generation":1,"errorCode":"TIMEOUT"}""")
            }.status)
            val late = postEvents(attemptId, batch(1, turn(2)))
            assertEquals(HttpStatusCode.Conflict, late.status)
            assertEquals("""{"error":"conflict","reason":"NOT_ACTIVE"}""", late.bodyAsText())
            assertEquals(1, env.storedRows(attemptId))
        }
    }

    @Test
    fun `the cap of events per attempt is reported with truncated true`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            // Fill to the cap through the repository (2,000 HTTP requests would hit the rate limit by design), then check the response.
            for (start in 1..2000 step 50) {
                env.monitor.append(attemptId, agentA, 1, (start until start + 50).map { net.kigawa.kivra.coding.MonitorEvent(it, net.kigawa.kivra.coding.MonitorKind.TURN, count = 0) })
            }
            val response = postEvents(attemptId, batch(1, turn(2001)))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"attemptId":"$attemptId","lastAcceptedSeq":2000,"truncated":true}""", response.bodyAsText())
            assertEquals(2001, env.storedRows(attemptId))
        }
    }

    // ---- authorization ----

    @Test
    fun `another agent's attempt is 404, the same as a missing one, and the operator and another client are 401 or 403`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            val missing = postEvents(UNKNOWN_ATTEMPT, batch(1, turn(1)), token = token(agentB))
            val other = postEvents(attemptId, batch(1, turn(1)), token = token(agentB))
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(HttpStatusCode.NotFound, other.status)
            assertEquals(missing.bodyAsText(), other.bodyAsText())

            assertEquals(HttpStatusCode.Forbidden, postEvents(attemptId, batch(1, turn(1)), token = AgentTestTokens.operatorToken()).status)
            assertEquals(HttpStatusCode.Forbidden, postEvents(attemptId, batch(1, turn(1)), token = AgentTestTokens.token(azp = "admin-panel")).status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(eventsPath(attemptId)) { setBody(batch(1, turn(1))) }.status)
            assertEquals(0, env.storedRows(attemptId))
        }
    }

    // ---- the strict schema ----

    @Test
    fun `a body that breaks the schema is 400 with a fixed body, and nothing is stored`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            val refused = listOf(
                batch(1, """{"seq":1,"kind":"TURN","count":1,"prompt":"x"}"""),
                batch(1, """{"seq":1,"kind":"TOOL_USE","toolName":"Bash(rm -rf /)"}"""),
                batch(1, """{"seq":"1","kind":"TURN","count":1}"""),
                batch(1, """{"seq":1,"kind":"TURN","count":10001}"""),
                batch(1, """{"seq":1,"kind":"COST","costUsd":100.000001}"""),
                batch(1, """{"seq":1,"kind":"TRUNCATED"}"""),
                batch(1, """{"seq":1,"kind":"PHASE","phase":"THINKING"}"""),
                batch(1, """{"seq":1,"kind":"API_RETRY","code":"Too many, see the log"}"""),
                batch(1, """{"seq":0,"kind":"TURN","count":1}"""),
                """{"generation":1,"events":[{"seq":1,"kind":"TURN","count":1}],"extra":true}""",
                """{"generation":1,"events":[]}""",
                "not json",
            )
            for (body in refused) {
                val response = postEvents(attemptId, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("""{"error":"invalid request body"}""", response.bodyAsText(), body)
            }
            assertEquals(0, env.storedRows(attemptId))
        }
    }

    @Test
    fun `a body over 8 KiB is 413, a fifty-first event is 400, and a non-JSON content type is 415`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            val big = batch(1, """{"seq":1,"kind":"TOOL_USE","toolName":"Read"}""", "{" + "\"x\":\"" + "a".repeat(9000) + "\"}")
            assertEquals(HttpStatusCode.PayloadTooLarge, postEvents(attemptId, big).status)
            val fiftyOne = batch(1, *(1..51).map { turn(it) }.toTypedArray())
            assertEquals(HttpStatusCode.BadRequest, postEvents(attemptId, fiftyOne).status)
            val plain = client.post(eventsPath(attemptId)) {
                header(HttpHeaders.Authorization, "Bearer ${token(agentA)}")
                contentType(ContentType.Text.Plain)
                setBody(batch(1, turn(1)))
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, plain.status)
            assertEquals(0, env.storedRows(attemptId))
        }
    }

    // ---- the frequency limit ----

    @Test
    fun `more than five requests in one second for one attempt is 429 with Retry-After`() = TestDb().use { db ->
        testApplication {
            val fixed = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC)
            val env = Env(db, limiter = MonitorRateLimiter(fixed))
            setUp(env)
            val attemptId = env.claimedAttempt()
            repeat(5) { assertEquals(HttpStatusCode.OK, postEvents(attemptId, batch(1, turn(1))).status) }
            val limited = postEvents(attemptId, batch(1, turn(1)))
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("1", limited.headers[HttpHeaders.RetryAfter])
            assertEquals("""{"error":"rate limited"}""", limited.bodyAsText())
        }
    }

    // ---- logs and isolation ----

    @Test
    fun `refused bodies and conflicts log no value, no field name and no sub`() = TestDb().use { db ->
        val marker = "MARKER-VALUE-7f3a"
        val fieldMarker = "MARKER-FIELD-9c1d"
        val logs = captureLogs {
            testApplication {
                val env = Env(db)
                setUp(env)
                val attemptId = env.claimedAttempt()
                postEvents(attemptId, batch(1, """{"seq":1,"kind":"TOOL_USE","toolName":"$marker"}"""))
                postEvents(attemptId, batch(1, """{"seq":1,"kind":"TURN","count":1,"$fieldMarker":"x"}"""))
                postEvents(attemptId, batch(1, turn(1, count = 1)))
                postEvents(attemptId, batch(1, turn(1, count = 2)))
                postEvents(attemptId, batch(1, turn(5)))
            }
        }
        for (line in logs) {
            assertTrue(!line.contains(marker) && !line.contains(fieldMarker), "a refused value reached the log")
            assertTrue(!line.contains(agentA), "the sub reached the log")
        }
    }

    @Test
    fun `a failing monitor answers 503 and does not change the heartbeat`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            transaction(db.database) { execUpdate("ALTER TABLE coding_monitor_events RENAME TO coding_monitor_events_off") }
            try {
                assertEquals(HttpStatusCode.ServiceUnavailable, postEvents(attemptId, batch(1, turn(1))).status)
                val heartbeat = client.post("/api/agent/v1/attempts/$attemptId/heartbeat") {
                    header(HttpHeaders.Authorization, "Bearer ${token(agentA)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"generation":1,"progress":"EDITING"}""")
                }
                assertEquals(HttpStatusCode.OK, heartbeat.status)
                val status = client.get("/api/agent/v1/attempts/$attemptId/status") {
                    header(HttpHeaders.Authorization, "Bearer ${token(agentA)}")
                }
                assertEquals(HttpStatusCode.OK, status.status)
                assertEquals("CLAIMED", parse(status.bodyAsText())["state"]!!.jsonPrimitive.content)
            } finally {
                transaction(db.database) { execUpdate("ALTER TABLE coding_monitor_events_off RENAME TO coding_monitor_events") }
            }
        }
    }

    @Test
    fun `the attempt row is untouched by a monitor batch`() = TestDb().use { db ->
        testApplication {
            val env = Env(db)
            setUp(env)
            val attemptId = env.claimedAttempt()
            val before = CodingAttemptRepository(db.database).get(attemptId)!!
            postEvents(attemptId, batch(1, turn(1), turn(2)))
            val after: CodingAttempt = CodingAttemptRepository(db.database).get(attemptId)!!
            assertEquals(before.version, after.version)
            assertEquals(CodingAttemptState.CLAIMED, after.state)
        }
    }
}
