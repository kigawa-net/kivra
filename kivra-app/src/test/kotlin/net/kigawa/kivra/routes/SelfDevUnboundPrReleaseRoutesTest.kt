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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.UnboundPrReleaseResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.FakeGitHub
import org.jetbrains.exposed.sql.Transaction
import org.slf4j.LoggerFactory

/**
 * Operator release of a PULL_REQUEST self-development run with no pull request binding (ADR 0009 addendum), on H2 with the real
 * repository. The route is `POST /api/admin/selfdev/runs/{id}/release-unbound-pr`.
 */
class SelfDevUnboundPrReleaseRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }
    private val operator = "op-visible-2"
    private val ttl = Duration.ofSeconds(30)
    private val github = FakeGitHub()

    private fun ApplicationTestBuilder.setUp(db: TestDb, maxActive: Int = 1) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureSelfDevAdminRoutes(db.runs, identity)
                configureSelfDevRoutes(SelfDevRouteDeps(db.runs, identity, RepoRef("kigawa-net", "kivra"), maxActive, github))
            }
        }
    }

    private suspend fun ApplicationTestBuilder.releaseUnbound(id: String, body: String, principal: String? = operator): HttpResponse =
        client.post("/api/admin/selfdev/runs/$id/release-unbound-pr") {
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

    private fun TestDb.submitSelfDev(principal: String, key: String): RunId =
        runs.submitTask(principal, key, "README を直す", selfDev = true, maxActiveSelfDev = null).run.runId

    /**
     * A self-development run in PULL_REQUEST with no binding: the state before the binding was recorded (ADR 0008 item 6 and the
     * binding record, Issue #53). It is the run the runner reports as BINDING_MISSING once its pull request is merged.
     */
    private fun TestDb.unboundPullRequestRun(principal: String = "alice", key: String = "k-${System.nanoTime()}"): RunId {
        val created = submitSelfDev(principal, key)
        toPullRequest(created)
        assertNull(runs.loadPullRequestBinding(created))
        return created
    }

    /** A self-development run in PULL_REQUEST with a binding (the normal path after the binding record). */
    private fun TestDb.boundPullRequestRun(principal: String = "alice", key: String = "k-${System.nanoTime()}"): RunId {
        val created = submitSelfDev(principal, key)
        val lease = assertNotNull(leases.acquire(created, "worker", ttl))
        var v = runs.get(created)!!.version
        for (to in pathToPullRequest) v = runs.transition(lease, v, to, "worker").version
        runs.bindPullRequest(
            lease, v,
            PullRequestBinding(created, "kigawa-net/kivra", 987654321L, 7L, "PR_kwDOExample7", "main", HEAD, "policy-1", "manifest-1"),
            "worker",
        )
        assertNotNull(runs.loadPullRequestBinding(created))
        return created
    }

    private fun TestDb.toPullRequest(created: RunId) {
        val lease = assertNotNull(leases.acquire(created, "worker", ttl))
        var v = runs.get(created)!!.version // acquire bumps the row version
        for (to in pathToPullRequest) v = runs.transition(lease, v, to, "worker").version
    }

    /** An UNKNOWN self-development run, for the "not a target state" cases. */
    private fun TestDb.unknownRun(key: String = "k-${System.nanoTime()}"): RunId {
        val created = submitSelfDev("alice", key)
        val lease = assertNotNull(leases.acquire(created, "worker", ttl))
        runs.transition(lease, runs.get(created)!!.version, DevelopmentRunState.DEVELOPING, "worker")
        runs.transition(lease, runs.get(created)!!.version, DevelopmentRunState.UNKNOWN, "worker", reason = "DEVELOPER_OUTCOME_UNKNOWN")
        return created
    }

    private fun TestDb.stateOf(runId: RunId) = runs.get(runId)!!.state

    private fun TestDb.auditCount(runId: RunId, action: String) = count("audit_events", "run_id = '$runId' AND action = '$action'")

    private fun TestDb.releasedOutboxCount(runId: RunId) =
        count("outbox_events", "aggregate_id = '$runId' AND event_type = 'RunStateChanged' AND payload LIKE '%\"to\":\"CANCELLED\"%'")

    @Test
    fun `an unbound PULL_REQUEST run is released to CANCELLED with the audit and outbox in one transaction`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        testApplication {
            setUp(db)
            val response = releaseUnbound(runId.value, """{"reason":"束縛の記録より前に作られた Run"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("CANCELLED", obj["state"]!!.jsonPrimitive.content)
            assertEquals("OPERATOR_RELEASED_UNBOUND_PR", obj["reasonCode"]!!.jsonPrimitive.content)
        }
        val run = db.runs.get(runId)!!
        assertEquals(DevelopmentRunState.CANCELLED, run.state)
        assertEquals("OPERATOR_RELEASED_UNBOUND_PR", run.stateReason)
        assertEquals(1, db.auditCount(runId, "RUN_RELEASED"))
        // The audit row names the authenticated operator and carries the reason and the from / to states.
        assertEquals(
            1,
            db.count(
                "audit_events",
                "run_id = '$runId' AND action = 'RUN_RELEASED' AND actor = '$operator' AND payload LIKE '%束縛の記録より前に作られた Run%' " +
                    "AND payload LIKE '%\"from\":\"PULL_REQUEST\"%'",
            ),
        )
        assertEquals(1, db.releasedOutboxCount(runId))
        assertEquals(0, db.count("outbox_events", "aggregate_id = '$runId' AND payload LIKE '%束縛の記録%'"))
    }

    @Test
    fun `a PULL_REQUEST run with a binding is refused with 409 and nothing is written`() = TestDb().use { db ->
        val runId = db.boundPullRequestRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Conflict, releaseUnbound(runId.value, """{"reason":"bound"}""").status)
        }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.stateOf(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
        assertEquals(0, db.releasedOutboxCount(runId))
    }

    @Test
    fun `the unbound release refuses an UNKNOWN run and a run that is not a PULL_REQUEST, with 409`() = TestDb().use { db ->
        val unknown = db.unknownRun()
        val created = db.submitSelfDev("alice", "k-created")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Conflict, releaseUnbound(unknown.value, """{"reason":"unknown"}""").status)
            assertEquals(HttpStatusCode.Conflict, releaseUnbound(created.value, """{"reason":"created"}""").status)
        }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(unknown))
        assertEquals(DevelopmentRunState.CREATED, db.stateOf(created))
        assertEquals(0, db.auditCount(unknown, "RUN_RELEASED"))
        assertEquals(0, db.auditCount(created, "RUN_RELEASED"))
    }

    @Test
    fun `a second release is 409, and an unknown run id or a Task API run is 404, with no writes`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        val api = db.runs.submitTask("alice", "api-key", "title", selfDev = false).run.runId
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, releaseUnbound(runId.value, """{"reason":"first"}""").status)
            assertEquals(HttpStatusCode.Conflict, releaseUnbound(runId.value, """{"reason":"second"}""").status)
            assertEquals(HttpStatusCode.NotFound, releaseUnbound(api.value, """{"reason":"not selfdev"}""").status)
            assertEquals(HttpStatusCode.NotFound, releaseUnbound(RunId("01ARZ3NDEKTSV4RRFFQ69G5FAZ").value, """{"reason":"missing"}""").status)
        }
        assertEquals(1, db.auditCount(runId, "RUN_RELEASED"))
        assertEquals(DevelopmentRunState.CREATED, db.stateOf(api))
    }

    @Test
    fun `requests without an authenticated operator are refused with 401 and change nothing`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, releaseUnbound(runId.value, """{"reason":"ok"}""", principal = null).status)
        }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.stateOf(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `a request that is not application json is refused with 415 and changes nothing`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        testApplication {
            setUp(db)
            val response = client.post("/api/admin/selfdev/runs/${runId.value}/release-unbound-pr") {
                header("X-Test-Principal", operator)
                contentType(ContentType.Text.Plain)
                setBody("""{"reason":"text"}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.stateOf(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `invalid bodies are refused with 400 and nothing is written`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        testApplication {
            setUp(db)
            for (body in listOf("""{}""", """{"reason":""}""", """{"reason":"   "}""", """{"reason":"${"a".repeat(501)}"}""", """{"reason":"x","operatorId":"y"}""", "not json")) {
                assertEquals(HttpStatusCode.BadRequest, releaseUnbound(runId.value, body).status, body.take(40))
            }
            assertEquals(HttpStatusCode.OK, releaseUnbound(runId.value, """{"reason":"${"a".repeat(500)}"}""").status)
        }
    }

    @Test
    fun `after the release the run no longer counts toward the active cap, so a new submission is accepted`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun(principal = "alice", key = "k-old")
        testApplication {
            setUp(db, maxActive = 1)
            // The unbound run holds the one slot: a new submission is refused.
            assertEquals(HttpStatusCode.TooManyRequests, submit("bob", "k-new-before").status)
            assertEquals(HttpStatusCode.OK, releaseUnbound(runId.value, """{"reason":"free the slot"}""").status)
            assertEquals(HttpStatusCode.Accepted, submit("bob", "k-new-after").status)
        }
    }

    @Test
    fun `the release never writes to GitHub`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, releaseUnbound(runId.value, """{"reason":"no github"}""").status)
        }
        assertTrue(github.writes.isEmpty(), "GitHub writes: ${github.writes}")
    }

    @Test
    fun `the release reason is never logged`() = TestDb().use { db ->
        val secret = "SECRET-UNBOUND-REASON-ZZZ"
        val runId = db.unboundPullRequestRun()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        val oldLevel = root.level
        root.level = Level.TRACE
        try {
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.OK, releaseUnbound(runId.value, """{"reason":"$secret"}""").status)
            }
            // Application loggers only; third-party SQL tracing is driver diagnostics (same scope as SelfDevAdminRoutesTest).
            val appEvents = appender.list.filter { it.loggerName.startsWith("net.kigawa") }
            assertFalse(appEvents.any { it.formattedMessage.contains(secret) || (it.throwableProxy?.message ?: "").contains(secret) })
        } finally {
            root.detachAppender(appender)
            root.level = oldLevel
        }
        assertEquals(1, db.count("audit_events", "run_id = '$runId' AND payload LIKE '%$secret%'"))
    }

    @Test
    fun `a failing audit write rolls back the release, the run stays PULL_REQUEST and no outbox event is written`() = TestDb().use { db ->
        val runId = db.unboundPullRequestRun()
        val failingAudit = object : AuditRepository() {
            override fun append(tx: Transaction, runId: RunId, entry: AuditEntry): String = throw IllegalStateException("audit store down")
        }
        val failing = DevelopmentRunRepository(db.database, db.outbox, failingAudit, db.prAuthority)
        assertFailsWith<IllegalStateException> { failing.releaseUnboundPullRequestSelfDev(runId, operator, "reason") }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.stateOf(runId))
        assertEquals(0, db.releasedOutboxCount(runId))
        assertEquals(0, db.auditCount(runId, "RUN_RELEASED"))
    }

    @Test
    fun `a bound run seen by the repository is NotEligible and the stored state is untouched`() = TestDb().use { db ->
        val runId = db.boundPullRequestRun()
        val result = db.runs.releaseUnboundPullRequestSelfDev(runId, operator, "bound")
        assertIs<UnboundPrReleaseResult.NotEligible>(result)
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.stateOf(runId))
    }

    private companion object {
        const val HEAD = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        val pathToPullRequest = listOf(
            DevelopmentRunState.PLANNING,
            DevelopmentRunState.PREPARING,
            DevelopmentRunState.DEVELOPING,
            DevelopmentRunState.TESTING,
            DevelopmentRunState.REVIEWING,
            DevelopmentRunState.PULL_REQUEST,
        )
    }
}
