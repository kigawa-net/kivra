package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.FakeGitHub
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/** Operator release of an UNKNOWN self-development run (ADR 0009) on H2 with the real repository. */
class SelfDevAdminRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }
    private val operator = "op-visible-1"
    private val ttl = Duration.ofSeconds(30)

    private fun ApplicationTestBuilder.setUp(db: TestDb, maxActive: Int = 1) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureSelfDevAdminRoutes(db.runs, identity)
                configureSelfDevRoutes(SelfDevRouteDeps(db.runs, identity, RepoRef("kigawa-net", "kivra"), maxActive, FakeGitHub()))
            }
        }
    }

    private suspend fun ApplicationTestBuilder.release(id: String, body: String, principal: String? = operator): HttpResponse =
        client.post("/api/admin/selfdev/runs/$id/release") {
            principal?.let { header("X-Test-Principal", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.submit(principal: String, key: String): HttpResponse =
        client.post("/api/selfdev/tasks") {
            header("X-Test-Principal", principal)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"README を直す","idempotencyKey":"$key"}""")
        }

    /** A self-development run that ended UNKNOWN, as the runner leaves it. */
    private fun TestDb.unknownRun(principal: String = "alice", key: String = "k-${System.nanoTime()}"): RunId {
        val created = submitSelfDev(principal, key)
        val lease = assertNotNull(leases.acquire(created, "worker", ttl))
        runs.transition(lease, runs.get(created)!!.version, DevelopmentRunState.DEVELOPING, "worker")
        runs.transition(lease, runs.get(created)!!.version, DevelopmentRunState.UNKNOWN, "worker", reason = "DEVELOPER_OUTCOME_UNKNOWN")
        assertEquals(DevelopmentRunState.UNKNOWN, runs.get(created)!!.state)
        return created
    }

    private fun TestDb.submitSelfDev(principal: String, key: String): RunId {
        val result = runs.submitTask(principal, key, "README を直す", selfDev = true, maxActiveSelfDev = null)
        return result.run.runId
    }

    private fun TestDb.stateOf(runId: RunId) = runs.get(runId)!!.state

    private fun TestDb.auditCount(runId: RunId, action: String) = count("audit_events", "run_id = '$runId' AND action = '$action'")

    private fun TestDb.releasedOutboxCount(runId: RunId) =
        count("outbox_events", "aggregate_id = '$runId' AND event_type = 'RunStateChanged' AND payload LIKE '%\"to\":\"CANCELLED\"%'")

    @Test
    fun `release requires authentication and writes nothing`() = TestDb().use { db ->
        val runId = db.unknownRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, release(runId.value, """{"reason":"ok"}""", principal = null).status)
        }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `an UNKNOWN self-development run is released to CANCELLED with the audit and outbox in the same transaction`() = TestDb().use { db ->
        val runId = db.unknownRun()
        testApplication {
            setUp(db)
            val response = release(runId.value, """{"reason":"dev の再現で不明になった"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("CANCELLED", obj["state"]!!.jsonPrimitive.content)
        }
        val run = db.runs.get(runId)!!
        assertEquals(DevelopmentRunState.CANCELLED, run.state)
        assertEquals("OPERATOR_RELEASED", run.stateReason)
        assertEquals(1, db.auditCount(runId, "RUN_RELEASED"))
        // The audit row names the authenticated operator and keeps the reason; the outbox row records the transition.
        assertEquals(1, db.count("audit_events", "run_id = '$runId' AND action = 'RUN_RELEASED' AND actor = '$operator' AND payload LIKE '%dev の再現で不明になった%'"))
        assertEquals(1, db.releasedOutboxCount(runId))
        assertFalse(db.count("outbox_events", "aggregate_id = '$runId' AND payload LIKE '%dev の再現%'") > 0)
    }

    @Test
    fun `a second release, a run that is not UNKNOWN, and an unknown run id are refused without writes`() = TestDb().use { db ->
        val unknown = db.unknownRun()
        val created = db.submitSelfDev("alice", "k-created")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, release(unknown.value, """{"reason":"first"}""").status)
            assertEquals(HttpStatusCode.Conflict, release(unknown.value, """{"reason":"second"}""").status)
            assertEquals(HttpStatusCode.Conflict, release(created.value, """{"reason":"not unknown"}""").status)
            assertEquals(HttpStatusCode.NotFound, release(RunId("01ARZ3NDEKTSV4RRFFQ69G5FAZ").value, """{"reason":"missing"}""").status)
        }
        assertEquals(1, db.auditCount(unknown, "RUN_RELEASED"))
        assertEquals(DevelopmentRunState.CREATED, db.stateOf(created))
    }

    @Test
    fun `a Task API run is not released by this route`() = TestDb().use { db ->
        val api = db.runs.submitTask("alice", "api-key", "title", selfDev = false).run.runId
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotFound, release(api.value, """{"reason":"not selfdev"}""").status)
        }
        assertEquals(DevelopmentRunState.CREATED, db.stateOf(api))
    }

    @Test
    fun `invalid bodies are refused with 400 and nothing is written`() = TestDb().use { db ->
        val runId = db.unknownRun()
        testApplication {
            setUp(db)
            for (body in listOf("""{}""", """{"reason":""}""", """{"reason":"   "}""", """{"reason":"${"a".repeat(501)}"}""", """{"reason":"x","operatorId":"y"}""", "not json")) {
                assertEquals(HttpStatusCode.BadRequest, release(runId.value, body).status, body.take(40))
            }
        }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `a reason of 500 characters is accepted`() = TestDb().use { db ->
        val runId = db.unknownRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, release(runId.value, """{"reason":"${"a".repeat(500)}"}""").status)
        }
    }

    @Test
    fun `after a release the active cap no longer counts the run, so a new submission is accepted`() = TestDb().use { db ->
        val runId = db.unknownRun(principal = "alice", key = "k-old")
        testApplication {
            setUp(db, maxActive = 1)
            // The unknown run holds the one slot: a new submission is refused.
            assertEquals(HttpStatusCode.TooManyRequests, submit("bob", "k-new-before").status)
            assertEquals(HttpStatusCode.OK, release(runId.value, """{"reason":"free the slot"}""").status)
            assertEquals(HttpStatusCode.Accepted, submit("bob", "k-new-after").status)
        }
    }

    @Test
    fun `the release reason is never logged`() = TestDb().use { db ->
        val secret = "SECRET-OPERATOR-REASON-QQQ"
        val runId = db.unknownRun()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        val oldLevel = root.level
        root.level = Level.TRACE
        try {
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.OK, release(runId.value, """{"reason":"$secret"}""").status)
            }
            // Application loggers only. Third-party SQL tracing (Exposed) prints bound parameters when enabled, which is driver
            // diagnostics and not an application log line (the same scope as BudgetAdminLogTest).
            val appEvents = appender.list.filter { it.loggerName.startsWith("net.kigawa") }
            assertFalse(appEvents.any { it.formattedMessage.contains(secret) || (it.throwableProxy?.message ?: "").contains(secret) })
        } finally {
            root.detachAppender(appender)
            root.level = oldLevel
        }
        assertEquals(1, db.count("audit_events", "run_id = '$runId' AND payload LIKE '%$secret%'"))
    }

    @Test
    fun `a failing audit write rolls back the release, the run stays UNKNOWN and no outbox event is written`() = TestDb().use { db ->
        val runId = db.unknownRun()
        val failingAudit = object : AuditRepository() {
            override fun append(tx: Transaction, runId: RunId, entry: AuditEntry): String = throw IllegalStateException("audit store down")
        }
        val failing = DevelopmentRunRepository(db.database, db.outbox, failingAudit, db.prAuthority)
        assertFailsWith<IllegalStateException> { failing.releaseUnknownSelfDev(runId, operator, "reason") }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(runId))
        assertEquals(0, db.releasedOutboxCount(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `a failing outbox write rolls back the release and its audit row too`() = TestDb().use { db ->
        val runId = db.unknownRun()
        val version = db.runs.get(runId)!!.version
        // Occupy the (run, version + 1, RunStateChanged) slot, so the release's own outbox insert violates the unique key.
        transaction(db.database) { db.outbox.append(this, runId, version + 1, "RunStateChanged", "{}") }
        assertFails { db.runs.releaseUnknownSelfDev(runId, operator, "outbox down") }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `the generic transition still refuses UNKNOWN to CANCELLED, so only the operator path releases a run`() = TestDb().use { db ->
        val created = db.submitSelfDev("alice", "k-generic")
        val lease = assertNotNull(db.leases.acquire(created, "worker", ttl))
        db.runs.transition(lease, db.runs.get(created)!!.version, DevelopmentRunState.DEVELOPING, "worker")
        db.runs.transition(lease, db.runs.get(created)!!.version, DevelopmentRunState.UNKNOWN, "worker", reason = "DEVELOPER_OUTCOME_UNKNOWN")
        assertFailsWith<InvalidTransitionException> {
            db.runs.transition(lease, db.runs.get(created)!!.version, DevelopmentRunState.CANCELLED, "worker")
        }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(created))
    }
}
