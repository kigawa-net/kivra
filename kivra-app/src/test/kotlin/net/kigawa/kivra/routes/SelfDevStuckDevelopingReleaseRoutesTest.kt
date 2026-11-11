package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.EnqueueResult
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.StuckReleaseResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.FakeGitHub
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Operator release of a stalled DEVELOPING self-development run (ADR 0009 addendum 2026-11-11), on H2 with the real repository.
 * The route is `POST /api/admin/selfdev/runs/{id}/release-stuck-developing`. Row locking itself is the same `FOR UPDATE` as the
 * other releases and only differs on MariaDB (`KIVRA_TEST_DB=mariadb` runs this class there as well).
 */
class SelfDevStuckDevelopingReleaseRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }
    private val operator = "op-visible-3"
    private val github = FakeGitHub()
    private var projectId = ""

    private fun ApplicationTestBuilder.setUp(db: TestDb, maxActive: Int = 1) {
        projectId = ProjectRepository(db.database, java.time.Clock.systemUTC()).create("Route project", null).projectId
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureSelfDevAdminRoutes(db.runs, identity)
                configureSelfDevRoutes(SelfDevRouteDeps(db.runs, identity, RepoRef("kigawa-net", "kivra"), maxActive, github, ProjectRepository(db.database, java.time.Clock.systemUTC())))
            }
        }
    }

    private suspend fun ApplicationTestBuilder.release(id: String, body: String = """{"reason":"止まっている"}""", principal: String? = operator): HttpResponse =
        client.post("/api/admin/selfdev/runs/$id/release-stuck-developing") {
            principal?.let { header("X-Test-Principal", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.submit(principal: String, key: String): HttpResponse =
        client.post("/api/selfdev/tasks") {
            header("X-Test-Principal", principal)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"README を直す","idempotencyKey":"$key","projectId":"$projectId"}""")
        }

    /** A self-development run in DEVELOPING, with no lease, updated an hour ago and with no attempt: the releasable case. */
    private fun TestDb.stuckRun(key: String = "k-${System.nanoTime()}", ageMinutes: Long = 60): RunId {
        val id = runs.submitTask("alice", key, "README を直す", selfDev = true, maxActiveSelfDev = null).run.runId
        forceState(id.value, DevelopmentRunState.DEVELOPING)
        setLeaseAndAge(id, owner = null, ageMinutes = ageMinutes)
        return id
    }

    private fun TestDb.setLeaseAndAge(id: RunId, owner: String?, ageMinutes: Long) = transaction(database) {
        val updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(ageMinutes)
        if (owner == null) {
            execUpdate("UPDATE development_runs SET lease_owner = NULL, lease_expires_at = NULL, updated_at = ? WHERE run_id = ?", updatedAt, id.value)
        } else {
            execUpdate(
                "UPDATE development_runs SET lease_owner = ?, lease_expires_at = TIMESTAMPADD(SECOND, 300, NOW(6)), updated_at = ? WHERE run_id = ?",
                owner, updatedAt, id.value,
            )
        }
    }

    private fun TestDb.stateOf(id: RunId) = runs.get(id)!!.state
    private fun TestDb.auditCount(id: RunId) = count("audit_events", "run_id = '$id' AND action = 'RUN_RELEASED'")
    private fun TestDb.cancelOutboxCount(id: RunId) =
        count("outbox_events", "aggregate_id = '$id' AND event_type = 'RunStateChanged' AND payload LIKE '%\"to\":\"CANCELLED\"%'")

    private fun TestDb.assertUntouched(id: RunId) {
        assertEquals(DevelopmentRunState.DEVELOPING, stateOf(id))
        assertEquals(0, auditCount(id))
        assertEquals(0, cancelOutboxCount(id))
    }

    private suspend fun ApplicationTestBuilder.assertRefused(id: RunId, code: String) {
        val response = release(id.value)
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(code, Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a stalled DEVELOPING run is released to CANCELLED with the audit and outbox`() = TestDb().use { db ->
        val id = db.stuckRun()
        testApplication {
            setUp(db)
            val response = release(id.value, """{"reason":"エージェントが動いていない"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("CANCELLED", obj["state"]!!.jsonPrimitive.content)
            assertEquals("OPERATOR_RELEASED_STUCK", obj["reasonCode"]!!.jsonPrimitive.content)
        }
        val run = db.runs.get(id)!!
        assertEquals(DevelopmentRunState.CANCELLED, run.state)
        assertEquals("OPERATOR_RELEASED_STUCK", run.stateReason)
        assertEquals(
            1,
            db.count(
                "audit_events",
                "run_id = '$id' AND action = 'RUN_RELEASED' AND actor = '$operator' AND payload LIKE '%エージェントが動いていない%' " +
                    "AND payload LIKE '%\"from\":\"DEVELOPING\"%' AND payload LIKE '%OPERATOR_RELEASED_STUCK%'",
            ),
        )
        assertEquals(1, db.cancelOutboxCount(id))
        assertEquals(0, db.count("outbox_events", "aggregate_id = '$id' AND payload LIKE '%エージェントが動いていない%'"))
    }

    @Test
    fun `a run with an active coding attempt is refused with ACTIVE_ATTEMPT`() = TestDb().use { db ->
        val id = db.stuckRun()
        val queued = CodingAttemptRepository(db.database).enqueue(id.value, "a".repeat(40), Duration.ofMinutes(5))
        assertIs<EnqueueResult.Enqueued>(queued)
        testApplication {
            setUp(db)
            assertRefused(id, "ACTIVE_ATTEMPT")
        }
        db.assertUntouched(id)
    }

    @Test
    fun `a run whose attempts all ended is not blocked by them`() = TestDb().use { db ->
        val id = db.stuckRun()
        val queued = CodingAttemptRepository(db.database).enqueue(id.value, "a".repeat(40), Duration.ofMinutes(5))
        val attemptId = assertIs<EnqueueResult.Enqueued>(queued).attempt.attemptId
        transaction(db.database) { execUpdate("UPDATE coding_attempts SET state = 'FAILED' WHERE attempt_id = ?", attemptId) }
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, release(id.value).status)
        }
    }

    @Test
    fun `a run updated within the idle window is refused with TOO_RECENT`() = TestDb().use { db ->
        val id = db.stuckRun(ageMinutes = 2)
        testApplication {
            setUp(db)
            assertRefused(id, "TOO_RECENT")
        }
        db.assertUntouched(id)
    }

    @Test
    fun `a run held by a worker lease is refused with LEASE_HELD even when it is old`() = TestDb().use { db ->
        val id = db.stuckRun()
        db.setLeaseAndAge(id, owner = "worker-1", ageMinutes = 60)
        testApplication {
            setUp(db)
            assertRefused(id, "LEASE_HELD")
        }
        db.assertUntouched(id)
    }

    @Test
    fun `an expired lease does not block the release`() = TestDb().use { db ->
        val id = db.stuckRun()
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET lease_owner = 'worker-1', lease_expires_at = TIMESTAMPADD(SECOND, -300, NOW(6)) WHERE run_id = ?", id.value)
        }
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, release(id.value).status)
        }
    }

    @Test
    fun `a run with a pull request bound is refused with PR_BOUND`() = TestDb().use { db ->
        val id = db.stuckRun()
        transaction(db.database) { execUpdate("UPDATE development_runs SET pr_number = 7, pr_repository = 'kigawa-net/kivra' WHERE run_id = ?", id.value) }
        testApplication {
            setUp(db)
            assertRefused(id, "PR_BOUND")
        }
        db.assertUntouched(id)
    }

    @Test
    fun `runs that are not DEVELOPING are refused with NOT_DEVELOPING`() = TestDb().use { db ->
        val created = db.runs.submitTask("alice", "k-created", "t", selfDev = true, maxActiveSelfDev = null).run.runId
        val unknown = db.stuckRun("k-unknown").also { db.forceState(it.value, DevelopmentRunState.UNKNOWN) }
        val cancelled = db.stuckRun("k-cancelled").also { db.forceState(it.value, DevelopmentRunState.CANCELLED) }
        testApplication {
            setUp(db)
            assertRefused(created, "NOT_DEVELOPING")
            assertRefused(unknown, "NOT_DEVELOPING")
            assertRefused(cancelled, "NOT_DEVELOPING")
        }
        assertEquals(DevelopmentRunState.UNKNOWN, db.stateOf(unknown))
        assertEquals(0, db.auditCount(created) + db.auditCount(unknown) + db.auditCount(cancelled))
    }

    @Test
    fun `a Task API run and an unknown id are 404, and a second release is 409`() = TestDb().use { db ->
        val api = db.runs.submitTask("alice", "api-key", "title", selfDev = false).run.runId
        val id = db.stuckRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotFound, release(api.value).status)
            assertEquals(HttpStatusCode.NotFound, release("01ARZ3NDEKTSV4RRFFQ69G5FAZ").status)
            assertEquals(HttpStatusCode.OK, release(id.value).status)
            assertRefused(id, "NOT_DEVELOPING")
        }
        assertEquals(1, db.auditCount(id))
        assertEquals(DevelopmentRunState.CREATED, db.stateOf(api))
    }

    @Test
    fun `without an operator it is 401, a non-json body is 415, and invalid bodies are 400, with nothing written`() = TestDb().use { db ->
        val id = db.stuckRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, release(id.value, principal = null).status)
            val text = client.post("/api/admin/selfdev/runs/${id.value}/release-stuck-developing") {
                header("X-Test-Principal", operator)
                contentType(ContentType.Text.Plain)
                setBody("""{"reason":"x"}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, text.status)
            for (body in listOf("{}", """{"reason":""}""", """{"reason":"   "}""", """{"reason":"${"a".repeat(501)}"}""", """{"reason":"x","y":"z"}""")) {
                assertEquals(HttpStatusCode.BadRequest, release(id.value, body).status, body.take(40))
            }
        }
        db.assertUntouched(id)
    }

    @Test
    fun `after the release the run no longer holds the active cap, so a new submission is accepted`() = TestDb().use { db ->
        val id = db.stuckRun()
        testApplication {
            setUp(db, maxActive = 1)
            assertEquals(HttpStatusCode.TooManyRequests, submit("bob", "k-before").status)
            assertEquals(HttpStatusCode.OK, release(id.value).status)
            assertEquals(HttpStatusCode.Accepted, submit("bob", "k-after").status)
        }
    }

    @Test
    fun `the release never writes to GitHub`() = TestDb().use { db ->
        val id = db.stuckRun()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, release(id.value).status)
        }
        assertEquals(emptyList(), github.writes.toList())
    }

    @Test
    fun `the repository refuses a run inside a custom idle window and releases it once the window passes`() = TestDb().use { db ->
        val id = db.stuckRun(ageMinutes = 30)
        assertEquals(StuckReleaseResult.TooRecent, db.runs.releaseStuckDevelopingSelfDev(id, operator, "r", minIdle = Duration.ofHours(1)))
        val released = db.runs.releaseStuckDevelopingSelfDev(id, operator, "r", minIdle = Duration.ofMinutes(10))
        assertNotNull(assertIs<StuckReleaseResult.Released>(released).run)
    }
}
