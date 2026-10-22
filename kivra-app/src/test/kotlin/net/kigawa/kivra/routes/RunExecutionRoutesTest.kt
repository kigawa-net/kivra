package net.kigawa.kivra.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AgentTestTokens
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.coding.MonitorEvent
import net.kigawa.kivra.coding.MonitorKind
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingAttemptUsageRepository
import net.kigawa.kivra.db.CodingMonitorEventRepository
import net.kigawa.kivra.db.CodingUsageRow
import net.kigawa.kivra.db.EnqueueResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.auth.TaskApiIdentity
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.slf4j.LoggerFactory

private const val AGENT_ID = "11111111-1111-4111-8111-111111111111"
private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val TITLE_MARKER = "TITLE-MARKER 本文の見出し"
private const val OPERATOR_SUB = "sub-operator-marker"
private const val OWNER_SUB = "sub-owner-marker"
private const val STRANGER_SUB = "sub-stranger-marker"
private const val UNKNOWN_RUN = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
private const val TICKET_HEADER = "X-Test-Ticket"

/**
 * The run execution read API (ADR 0012 PR 3) over HTTP: the snapshot, the event page, the authorization (401, 403, 404), the stream's
 * cursor, resync, heartbeat, the limits, the lost right, and the logs. On H2 with the real repositories and the real OIDC verifier.
 */
class RunExecutionRoutesTest {
    companion object {
        /**
         * One H2 database for the class. A [TestDb] stays in memory for the life of the test JVM (`DB_CLOSE_DELAY=-1`), so one per test
         * exhausted the test heap of the whole suite. Each test works on its own Runs and attempts, so they do not share state.
         */
        private val sharedDbLazy = lazy { TestDb() }
        private val sharedDb: TestDb by sharedDbLazy

        /** Closes the class's pool (and its MariaDB schema) when the class ends; the TestDb is created only if a test used it. */
        @JvmStatic
        @AfterAll
        fun closeSharedDb() {
            if (sharedDbLazy.isInitialized()) sharedDbLazy.value.close()
        }
    }

    private val issuer = OidcTestTokens.ISSUER
    private val verifier = OidcJwtVerifier(
        OidcTestTokens.config,
        OidcTestTokens.jwks(),
        RequiredRole(AgentTestTokens.CLIENT, AgentTestTokens.ROLE),
    )

    private fun ownerKey(sub: String) = assertNotNull(oidcOwnerKey(issuer, sub))
    private fun bearer(sub: String, roles: List<String>? = null) = "Bearer " + OidcTestTokens.token(
        subject = sub,
        roles = roles ?: listOf(OidcTestTokens.ROLE),
    )
    private fun operator() = bearer(OPERATOR_SUB)
    private fun member(sub: String) = bearer(sub, roles = emptyList())
    private fun agent() = "Bearer " + AgentTestTokens.token()

    private fun oidcGate(db: TestDb): RunExecutionGate = DefaultRunExecutionGate(db.runs, verifier, verifier.asIdentity())

    /**
     * The real gate, allowed [passes] checks and refused after that. A stream checks once at connect and once before each pass, so a bounded
     * gate ends a stream by itself. (The test engine does not report a client that stops reading, so an endless stream would never end.)
     */
    private fun boundedGate(db: TestDb, passes: Int): RunExecutionGate {
        val real = oidcGate(db)
        // Counted per connection (by the test's ticket header), so each stream of a test gets its own passes.
        val calls = ConcurrentHashMap<String, Int>()
        return RunExecutionGate { headers, runId ->
            val ticket = headers[TICKET_HEADER].orEmpty()
            val n = calls.merge(ticket, 1, Int::plus)!!
            if (n <= passes) real.check(headers, runId) else RunAccess.Unauthenticated
        }
    }

    private fun deps(
        db: TestDb,
        gate: RunExecutionGate = oidcGate(db),
        staleAfter: Duration = Duration.ofDays(1),
        limiter: MonitorStreamLimiter = MonitorStreamLimiter(),
        heartbeat: Duration = Duration.ofSeconds(15),
    ) = RunExecutionDeps(
        runs = db.runs,
        attempts = CodingAttemptRepository(db.database),
        monitor = CodingMonitorEventRepository(db.database),
        usage = CodingAttemptUsageRepository(db.database),
        gate = gate,
        reserveUsd = BigDecimal("0.50"),
        staleAfter = staleAfter,
        limiter = limiter,
        streamTick = Duration.ofMillis(20),
        heartbeat = heartbeat,
        timer = VirtualTimer(),
    )

    /** A clock that moves only when the stream pauses, so the stream's heartbeat and the test's waits share one virtual time. */
    private class VirtualTimer : StreamTimer() {
        private var now = 0L
        override fun nanos(): Long = now

        override suspend fun pause(duration: Duration) {
            now += duration.toNanos()
            delay(1)
        }
    }

    private fun ApplicationTestBuilder.setUp(deps: RunExecutionDeps) {
        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { configureRunExecutionRoutes(deps) }
        }
    }

    private fun TestDb.run(owner: String, title: String = TITLE_MARKER): String =
        (runs.submitTask(owner, "key-${System.nanoTime()}", title) as TaskSubmitResult.Created).run.runId.value

    /** A DEVELOPING Run with one claimed attempt held by [AGENT_ID] at generation 1. */
    private fun TestDb.claimed(runId: String): String {
        forceState(runId, DevelopmentRunState.DEVELOPING)
        val attempts = CodingAttemptRepository(database)
        val attempt = (attempts.enqueue(runId, BASE_SHA, Duration.ofHours(1)) as EnqueueResult.Enqueued).attempt
        assertTrue(attempts.claim(attempt.attemptId, AGENT_ID, Duration.ofMinutes(2)) is CodingAttemptResult.Ok)
        return attempt.attemptId
    }

    private fun TestDb.append(attemptId: String, vararg events: MonitorEvent) {
        val result = CodingMonitorEventRepository(database).append(attemptId, AGENT_ID, 1, events.toList())
        assertTrue(result is net.kigawa.kivra.db.MonitorAppendResult.Accepted, result.toString())
    }

    private fun turn(seq: Int, count: Int = 1) = MonitorEvent(seq = seq, kind = MonitorKind.TURN, count = count)

    private fun TestDb.forceCancelRequested(runId: String) = transaction(database) {
        execUpdate("UPDATE development_runs SET cancel_requested = TRUE WHERE run_id = ?", runId)
    }

    private suspend fun ApplicationTestBuilder.get(path: String, bearer: String?): HttpResponse =
        client.get(path) { bearer?.let { header(HttpHeaders.Authorization, it) } }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun snapshotPath(runId: String) = "/api/admin/runs/$runId/execution"
    private fun eventsPath(runId: String, query: String) = "/api/admin/runs/$runId/execution/events?$query"
    private fun streamPath(runId: String) = "/api/admin/runs/$runId/execution/stream"

    /** Reads frames until [done] says so or the stream ends. Returns the lines and whether the stream ended. */
    private suspend fun HttpResponse.readUntil(done: (List<String>) -> Boolean): Pair<List<String>, Boolean> {
        val channel = bodyAsChannel()
        val lines = mutableListOf<String>()
        var ended = false
        withTimeout(10_000) {
            while (!done(lines)) {
                val line = channel.readUTF8Line()
                if (line == null) {
                    ended = true
                    break
                }
                lines += line
            }
        }
        return lines to ended
    }

    private fun List<String>.dataCount() = count { it.startsWith("data:") }

    /** The root logger's messages from the moment of creation on, across coroutines. Call [close] when done. */
    private class LogCapture {
        private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>().apply { start() }

        init {
            root.addAppender(appender)
        }

        fun messages(): List<String> = synchronized(appender.list) { appender.list.map { it.formattedMessage } }

        /** Waits until a message starts with [prefix] (the server logs when a stream ends, which can be after the client has read its body). */
        suspend fun awaitMessage(prefix: String): String = withTimeout(10_000) {
            var found: String? = null
            while (found == null) {
                found = messages().firstOrNull { it.startsWith(prefix) }
                if (found == null) delay(20)
            }
            found
        }

        fun close() = root.detachAppender(appender)
    }

    // ---- authorization ----

    @Test
    fun `every read needs a caller (401), an agent is refused (403) before the run is read, even when it also has the operator role`() =
        TestDb().use { db ->
            testApplication {
                setUp(deps(db))
                val runId = db.run(ownerKey(OWNER_SUB))
                val attemptId = db.claimed(runId)
                val agentWithOperatorRole = "Bearer " + AgentTestTokens.token(
                    resourceAccess = mapOf(
                        AgentTestTokens.CLIENT to mapOf("roles" to listOf(AgentTestTokens.ROLE)),
                        OidcTestTokens.CLIENT to mapOf("roles" to listOf(OidcTestTokens.ROLE)),
                    ),
                )
                for (path in listOf(snapshotPath(runId), eventsPath(runId, "attemptId=$attemptId"), streamPath(runId))) {
                    assertEquals(HttpStatusCode.Unauthorized, get(path, null).status, path)
                    assertEquals(HttpStatusCode.Forbidden, get(path, agent()).status, path)
                    assertEquals(HttpStatusCode.Forbidden, get(path, agentWithOperatorRole).status, path)
                }
                // The agent is refused even for a Run that does not exist: the agent learns nothing about the Run.
                assertEquals(HttpStatusCode.Forbidden, get(snapshotPath(UNKNOWN_RUN), agent()).status)
                assertEquals(HttpStatusCode.Unauthorized, get(snapshotPath(UNKNOWN_RUN), null).status)
            }
        }

    @Test
    fun `an operator reads any run, the creator reads their own run, and anyone else gets 404`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db))
            val runId = db.run(ownerKey(OWNER_SUB))
            db.claimed(runId)
            assertEquals(HttpStatusCode.OK, get(snapshotPath(runId), operator()).status, "operator")
            assertEquals(HttpStatusCode.OK, get(snapshotPath(runId), member(OWNER_SUB)).status, "creator without the operator role")
            assertEquals(HttpStatusCode.NotFound, get(snapshotPath(runId), member(STRANGER_SUB)).status, "stranger")
            assertEquals(HttpStatusCode.NotFound, get(snapshotPath(UNKNOWN_RUN), operator()).status, "missing run")
            assertEquals(HttpStatusCode.NotFound, get(snapshotPath("not-a-run-id"), operator()).status, "malformed run id")
            assertEquals(HttpStatusCode.NotFound, get(streamPath(runId), member(STRANGER_SUB)).status, "stream: stranger")
        }
    }

    @Test
    fun `in the development header modes only the creator reads a run`() = sharedDb.let { db ->
        val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }
        testApplication {
            setUp(deps(db, gate = DefaultRunExecutionGate(db.runs, verifier = null, identity = identity)))
            val runId = db.run("dev-creator")
            db.claimed(runId)
            suspend fun status(principal: String?) = client.get(snapshotPath(runId)) {
                principal?.let { header("X-Test-Principal", it) }
            }.status
            assertEquals(HttpStatusCode.OK, status("dev-creator"))
            assertEquals(HttpStatusCode.NotFound, status("someone-else"))
            assertEquals(HttpStatusCode.Unauthorized, status(null))
        }
    }

    // ---- snapshot ----

    @Test
    fun `the snapshot counts the events, splits the cost, and shows the stop stage and a LIVE freshness`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db, staleAfter = Duration.ofDays(1)))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            db.append(
                attemptId,
                MonitorEvent(seq = 1, kind = MonitorKind.ATTEMPT_STARTED),
                MonitorEvent(seq = 2, kind = MonitorKind.PHASE, phase = "TESTING"),
                MonitorEvent(seq = 3, kind = MonitorKind.TOOL_USE, toolName = "Read"),
                MonitorEvent(seq = 4, kind = MonitorKind.TOOL_RESULT, count = 1),
                turn(5),
                turn(6),
                MonitorEvent(seq = 7, kind = MonitorKind.PERMISSION_DENIED, count = 1),
            )
            CodingAttemptUsageRepository(db.database).insert(CodingUsageRow(attemptId, 1, BigDecimal("0.250000"), "REPORTED"))
            db.forceCancelRequested(runId)

            val response = get(snapshotPath(runId), operator())
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val json = parse(body)
            assertEquals(runId, json.getValue("runId").jsonPrimitive.content)
            assertEquals("LIVE", json.getValue("freshness").jsonPrimitive.content)
            assertEquals(1L, json.getValue("tools").jsonObject.getValue("byName").jsonObject.getValue("Read").jsonPrimitive.content.toLong())
            assertEquals(1, json.getValue("tools").jsonObject.getValue("errors").jsonPrimitive.content.toInt())
            assertEquals(2, json.getValue("turns").jsonPrimitive.content.toInt())
            assertEquals(1, json.getValue("permissionDenied").jsonPrimitive.content.toInt())
            assertEquals("TESTING", json.getValue("lastPhase").jsonPrimitive.content)
            assertEquals("REQUESTED", json.getValue("stop").jsonObject.getValue("stage").jsonPrimitive.content)
            assertEquals("0.250000", json.getValue("cost").jsonObject.getValue("confirmedUsd").jsonPrimitive.content)
            // The attempt has a reported cost, so it is not estimated again: the reserve is only for active attempts with no usage row yet.
            assertEquals("0.000000", json.getValue("cost").jsonObject.getValue("estimatedUsd").jsonPrimitive.content)
            assertEquals("CLAIMED", json.getValue("attempts").jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content)
            assertFalse(body.contains(TITLE_MARKER), "the task title is never in a snapshot")
        }
    }

    @Test
    fun `a run whose last receipt is older than the threshold is STALE`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db, staleAfter = Duration.ZERO))
            val runId = db.run(ownerKey(OWNER_SUB))
            db.append(db.claimed(runId), turn(1))
            val json = parse(get(snapshotPath(runId), operator()).bodyAsText())
            assertEquals("STALE", json.getValue("freshness").jsonPrimitive.content)
        }
    }

    // ---- events ----

    @Test
    fun `the event page follows the cursor, honours the limit, and says whether more rows exist`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            db.append(attemptId, *(1..5).map { turn(it) }.toTypedArray())

            val first = parse(get(eventsPath(runId, "attemptId=$attemptId&limit=2"), operator()).bodyAsText())
            assertEquals(listOf(1, 2), first.getValue("events").jsonArray.map { it.jsonObject.getValue("seq").jsonPrimitive.content.toInt() })
            assertEquals("true", first.getValue("hasMore").jsonPrimitive.content)
            assertEquals("2", first.getValue("nextAfterSeq").jsonPrimitive.content)

            // The cursor is (lease generation, seq): the next page starts after the last row's pair, which the response gives back.
            val second = parse(get(eventsPath(runId, "attemptId=$attemptId&limit=2&afterGeneration=1&afterSeq=2"), operator()).bodyAsText())
            assertEquals(listOf(3, 4), second.getValue("events").jsonArray.map { it.jsonObject.getValue("seq").jsonPrimitive.content.toInt() })
            assertEquals("true", second.getValue("hasMore").jsonPrimitive.content)

            val last = parse(get(eventsPath(runId, "attemptId=$attemptId&limit=2&afterGeneration=1&afterSeq=4"), operator()).bodyAsText())
            assertEquals(listOf(5), last.getValue("events").jsonArray.map { it.jsonObject.getValue("seq").jsonPrimitive.content.toInt() })
            assertEquals("false", last.getValue("hasMore").jsonPrimitive.content)

            // A full page at the end of the log is not "more": one more row is looked for.
            val exact = parse(get(eventsPath(runId, "attemptId=$attemptId&limit=5"), operator()).bodyAsText())
            assertEquals("false", exact.getValue("hasMore").jsonPrimitive.content)

            val past = parse(get(eventsPath(runId, "attemptId=$attemptId&afterGeneration=1&afterSeq=5"), operator()).bodyAsText())
            assertEquals(0, past.getValue("events").jsonArray.size)
            assertEquals(JsonNull, past.getValue("nextAfterSeq"))
        }
    }

    @Test
    fun `the event page refuses a bad query and an attempt that is not of the run`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            val otherRun = db.run(ownerKey(OWNER_SUB))
            val otherAttempt = db.claimed(otherRun)
            val base = "/api/admin/runs/$runId/execution/events"
            assertEquals(HttpStatusCode.BadRequest, get("$base?limit=5", operator()).status, "no attempt id")
            assertEquals(HttpStatusCode.BadRequest, get("$base?attemptId=$attemptId&limit=201", operator()).status)
            assertEquals(HttpStatusCode.BadRequest, get("$base?attemptId=$attemptId&limit=0", operator()).status)
            assertEquals(HttpStatusCode.BadRequest, get("$base?attemptId=$attemptId&limit=ten", operator()).status)
            assertEquals(HttpStatusCode.BadRequest, get("$base?attemptId=$attemptId&afterSeq=-2", operator()).status)
            assertEquals(HttpStatusCode.NotFound, get("$base?attemptId=$otherAttempt", operator()).status, "another run's attempt")
            assertEquals(HttpStatusCode.NotFound, get("$base?attemptId=$UNKNOWN_RUN", operator()).status, "unknown attempt")
            assertEquals(HttpStatusCode.OK, get("$base?attemptId=$attemptId&limit=200", operator()).status)
        }
    }

    // ---- stream ----

    /** The body of a finite stream: the test ends it with a bounded gate, so the whole body arrives. */
    private suspend fun ApplicationTestBuilder.streamBody(path: String, lastEventId: String? = null): List<String> =
        client.get(path) {
            header(HttpHeaders.Authorization, operator())
            header(TICKET_HEADER, UUID.randomUUID().toString())
            lastEventId?.let { header("Last-Event-ID", it) }
        }.bodyAsText().lines()

    private fun List<String>.ids() = filter { it.startsWith("id:") }.map { it.removePrefix("id:").trim() }

    @Test
    fun `the stream sends each row with its id, and Last-Event-ID resumes after that row from the database`() = sharedDb.let { db ->
        testApplication {
            // Connect plus two passes: the rows are sent in the first pass, the second pass ends the stream.
            setUp(deps(db, gate = boundedGate(db, passes = 3)))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            db.append(attemptId, turn(1), turn(2), turn(3))

            val all = streamBody(streamPath(runId))
            assertEquals(listOf("$attemptId:1:1", "$attemptId:1:2", "$attemptId:1:3"), all.ids())
            assertEquals(3, all.dataCount())
            assertTrue(all.contains("event: monitor"))

            val resumed = streamBody(streamPath(runId), lastEventId = "$attemptId:1:1")
            assertEquals(listOf("$attemptId:1:2", "$attemptId:1:3"), resumed.ids())
        }
    }

    @Test
    fun `a cursor whose row was purged, or that names another run, gets resync and the stream ends`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            db.append(attemptId, turn(1))
            // The stream ends after resync by itself: nothing is bounded here.
            val lines = streamBody(streamPath(runId), lastEventId = "$attemptId:1:99")
            assertTrue(lines.any { it == "event: resync" }, lines.toString())
            assertFalse(lines.any { it.startsWith("event: monitor") }, lines.toString())
            assertTrue(lines.none { it.contains(TITLE_MARKER) })

            val malformed = client.get(streamPath(runId)) {
                header(HttpHeaders.Authorization, operator())
                header("Last-Event-ID", "not-a-cursor")
            }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
        }
    }

    @Test
    fun `the stream sends a heartbeat comment when nothing else is sent`() = sharedDb.let { db ->
        testApplication {
            // The virtual pass is 20 ms and the heartbeat is due at 100 ms: about the sixth pass. Ten checks in all end the stream after it.
            setUp(deps(db, gate = boundedGate(db, passes = 10), heartbeat = Duration.ofMillis(100)))
            val runId = db.run(ownerKey(OWNER_SUB))
            db.claimed(runId)
            val lines = streamBody(streamPath(runId))
            assertTrue(lines.any { it == ": heartbeat" }, lines.toString())
            assertEquals(0, lines.dataCount())
        }
    }

    @Test
    fun `the stream ends when the right is lost, and sends nothing after that`() = sharedDb.let { db ->
        testApplication {
            // The connect check and the first pass are allowed; from the next pass on, the right is gone.
            setUp(deps(db, gate = boundedGate(db, passes = 2)))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            db.append(attemptId, turn(1), turn(2))
            val lines = streamBody(streamPath(runId))
            assertEquals(2, lines.dataCount(), lines.toString())
        }
    }

    @Test
    fun `a caller over its own stream limit gets 429, and the slot is freed when a stream ends`() = sharedDb.let { db ->
        val limiter = MonitorStreamLimiter(perPrincipal = 1, total = 2)
        testApplication {
            setUp(deps(db, gate = boundedGate(db, passes = 2), limiter = limiter))
            val runId = db.run(ownerKey(OWNER_SUB))
            db.append(db.claimed(runId), turn(1))
            // A slot held by the operator (as an open stream would) makes the next stream a 429.
            val held = assertNotNull(limiter.tryAcquire(ownerKey(OPERATOR_SUB)))
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get(streamPath(runId)) {
                    header(HttpHeaders.Authorization, operator())
                    header(TICKET_HEADER, UUID.randomUUID().toString())
                }.status,
            )
            held.release()
            // The finished stream releases its own slot: a second one is admitted, and then the limit is back to one.
            assertEquals(1, streamBody(streamPath(runId)).dataCount())
            assertEquals(1, streamBody(streamPath(runId)).dataCount(), "a finished stream frees its slot")
        }
    }

    @Test
    fun `the logs and the stream carry no title, no principal and no event value`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db, gate = boundedGate(db, passes = 2)))
            val runId = db.run(ownerKey(OWNER_SUB))
            val attemptId = db.claimed(runId)
            db.append(attemptId, turn(1, count = 4242))
            val capture = LogCapture()
            try {
                val body = get(snapshotPath(runId), operator()).bodyAsText()
                val lines = streamBody(streamPath(runId))
                val closed = capture.awaitMessage("run execution stream closed")
                val logs = capture.messages().joinToString("\n")
                for (text in listOf(body, lines.joinToString("\n"), logs)) {
                    assertFalse(text.contains(TITLE_MARKER), "title")
                    assertFalse(text.contains(OPERATOR_SUB), "operator principal")
                    assertFalse(text.contains(OWNER_SUB), "owner principal")
                }
                assertTrue(closed.contains(runId), closed)
                // The turn count (4242) is shown by the snapshot body, never by a log line.
                assertFalse(logs.contains("4242"), logs)
            } finally {
                capture.close()
            }
        }
    }

    @Test
    fun `the stream limiter counts per caller and in total, and frees a slot on release`() {
        val limiter = MonitorStreamLimiter(perPrincipal = 2, total = 3)
        val a1 = limiter.tryAcquire("a")
        val a2 = limiter.tryAcquire("a")
        assertNotNull(a1)
        assertNotNull(a2)
        assertNull(limiter.tryAcquire("a"), "per-caller limit")
        val b1 = limiter.tryAcquire("b")
        assertNotNull(b1)
        assertNull(limiter.tryAcquire("b"), "total limit")
        a1.release()
        a1.release()
        assertNotNull(limiter.tryAcquire("b"), "one released slot is reusable, and a second release of the same lease changes nothing")
        assertNull(limiter.tryAcquire("b"))
    }

    @Test
    fun `the cursor parser accepts the id form only`() {
        val id = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        assertEquals(StreamPosition(id, 3, 17), parseCursor("$id:3:17"))
        assertEquals(StreamPosition(id, 1, 0), parseCursor("$id:1:0"))
        assertNull(parseCursor("$id:0:1"), "a generation starts at 1")
        assertNull(parseCursor("$id:1:-1"))
        assertNull(parseCursor("short:1:1"))
        assertNull(parseCursor("$id:1"))
        assertNull(parseCursor("$id:1:1:1"))
    }

    @Test
    fun `the stream closes with a log line that carries the run and the frame count only`() = sharedDb.let { db ->
        testApplication {
            setUp(deps(db, gate = boundedGate(db, passes = 2)))
            val runId = db.run(ownerKey(OWNER_SUB))
            db.append(db.claimed(runId), turn(1))
            val capture = LogCapture()
            try {
                streamBody(streamPath(runId))
                val closed = capture.awaitMessage("run execution stream closed")
                assertTrue(closed.contains("frames=1"), closed)
                assertTrue(closed.contains("reason=ACCESS_ENDED"), closed)
            } finally {
                capture.close()
            }
        }
    }
}
