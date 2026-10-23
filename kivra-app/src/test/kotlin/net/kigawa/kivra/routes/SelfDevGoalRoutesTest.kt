package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ClaimResult
import net.kigawa.kivra.db.CommitResult
import net.kigawa.kivra.db.GoalClaim
import net.kigawa.kivra.db.NewPlan
import net.kigawa.kivra.db.NewTask
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlan
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration

private const val OPERATOR = "op-1"
private const val AI_PRINCIPAL = "1:i:ai"
private const val AGENT_HEADER = "X-Test-Agent"
private const val PRINCIPAL_HEADER = "X-Test-Principal"
private val TTL = Duration.ofSeconds(60)
private val SHA = "c".repeat(64)
private const val ULID_NONE = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The autopilot's operator API (ADR 0011 section 1) on H2 with the real repositories. The authenticator is a stub that reads the
 * principal from a header, so the authentication rules are exercised here; the verifier itself is covered by OperatorAuthTest.
 */
class SelfDevGoalRoutesTest {

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: Headers): OperatorAuth = when {
            headers[AGENT_HEADER] != null -> OperatorAuth.AgentRefused
            headers[PRINCIPAL_HEADER]?.isNotBlank() == true -> OperatorAuth.Authenticated(headers[PRINCIPAL_HEADER]!!)
            else -> OperatorAuth.Unauthenticated
        }
    }

    private fun ApplicationTestBuilder.setUp(db: TestDb, enabled: Boolean = true, aiPrincipal: String? = null) {
        val autopilot = if (enabled) AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev) else AutopilotSettings.disabled()
        val deps = SelfDevGoalRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            targetRepo = "kigawa-net/kivra",
            goals = SelfDevGoalRepository(db.database),
            plans = SelfDevPlanRepository(db.database),
            operations = SelfDevOperatorRepository(db.database),
            control = SelfDevAutopilotControlRepository(db.database),
            aiPrincipal = aiPrincipal,
        )
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevGoalRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = null,
        principal: String? = OPERATOR,
        headers: Map<String, String> = emptyMap(),
        type: ContentType = ContentType.Application.Json,
    ): HttpResponse = client.request(path) {
        this.method = method
        principal?.let { header(PRINCIPAL_HEADER, it) }
        headers.forEach { (k, v) -> header(k, v) }
        if (body != null) {
            contentType(type)
            setBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String?, principal: String? = OPERATOR) =
        send(HttpMethod.Post, path, body, principal)

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun text(response: HttpResponse): String = response.bodyAsText()

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    // ---- authentication and CSRF -----------------------------------------------------------------------------------------

    private val goalEndpoints: List<Triple<HttpMethod, String, String?>> = listOf(
        Triple(HttpMethod.Get, "/api/selfdev/autopilot", null),
        Triple(HttpMethod.Post, "/api/selfdev/autopilot/pause", "{}"),
        Triple(HttpMethod.Post, "/api/selfdev/autopilot/resume", "{}"),
        Triple(HttpMethod.Post, "/api/selfdev/autopilot/reset-breaker", "{}"),
        Triple(HttpMethod.Post, "/api/selfdev/autopilot/auto-merge/pause", "{}"),
        Triple(HttpMethod.Post, "/api/selfdev/autopilot/auto-merge/resume", "{}"),
        Triple(HttpMethod.Post, "/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}"""),
        Triple(HttpMethod.Get, "/api/selfdev/goals", null),
        Triple(HttpMethod.Get, "/api/selfdev/goals/$ULID_NONE", null),
        Triple(HttpMethod.Post, "/api/selfdev/goals/$ULID_NONE/approve-plan", """{"version":0}"""),
        Triple(HttpMethod.Post, "/api/selfdev/goals/$ULID_NONE/replan", """{"version":0}"""),
        Triple(HttpMethod.Post, "/api/selfdev/goals/$ULID_NONE/stop", """{"version":0,"reason":"r"}"""),
        Triple(HttpMethod.Post, "/api/selfdev/tasks/$ULID_NONE-1/approve", """{"version":0}"""),
        Triple(HttpMethod.Post, "/api/selfdev/tasks/$ULID_NONE-1/reject", """{"version":0}"""),
    )

    @Test
    fun `every endpoint answers 401 without a credential and 403 for an agent token, and changes nothing`() {
        TestDb().use { db ->
            testApplication {
                setUp(db)
                for ((method, path, body) in goalEndpoints) {
                    assertEquals(HttpStatusCode.Unauthorized, send(method, path, body, principal = null).status, "$method $path")
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        send(method, path, body, principal = null, headers = mapOf(AGENT_HEADER to "1")).status,
                        "$method $path (agent)",
                    )
                }
            }
            assertEquals(0, db.count("selfdev_goals"))
            assertEquals(false, db.count("selfdev_autopilot_control", "paused = TRUE") > 0)
        }
    }

    @Test
    fun `a changing request needs a JSON body from the same origin`() {
        TestDb().use { db ->
            testApplication {
                setUp(db)
                assertEquals(
                    HttpStatusCode.UnsupportedMediaType,
                    send(HttpMethod.Post, "/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}""", type = ContentType.Text.Plain).status,
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    send(HttpMethod.Post, "/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}""", headers = mapOf("Sec-Fetch-Site" to "cross-site")).status,
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    send(HttpMethod.Post, "/api/selfdev/autopilot/pause", "{}", headers = mapOf("Origin" to "https://evil.example")).status,
                )
                assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/autopilot/pause", "{}").status)
            }
            assertEquals(1, db.count("selfdev_autopilot_control", "paused = TRUE"))
        }
    }

    @Test
    fun `while the autopilot is disabled every endpoint answers 503 and nothing is stored or switched`() {
        TestDb().use { db ->
            testApplication {
                setUp(db, enabled = false)
                for ((method, path, body) in goalEndpoints) {
                    assertEquals(HttpStatusCode.ServiceUnavailable, send(method, path, body).status, "$method $path")
                }
            }
            assertEquals(0, db.count("selfdev_goals"))
            assertEquals(0, db.count("selfdev_autopilot_control", "paused = TRUE"))
        }
    }

    // ---- goal input -----------------------------------------------------------------------------------------------------

    @Test
    fun `a goal is accepted once per key, a repeat with the same content replays it, and other content is 409`() {
        TestDb().use { db ->
            testApplication {
                setUp(db)
                val first = post("/api/selfdev/goals", """{"title":"CI を速くする","body":"背景","idempotencyKey":"key-1"}""")
                assertEquals(HttpStatusCode.Accepted, first.status)
                val created = first.json()
                assertEquals(GoalState.GOAL_SUBMITTED.name, created.str("state"))
                assertEquals("kigawa-net/kivra", created.str("targetRepo"))

                val replay = post("/api/selfdev/goals", """{"title":"CI を速くする","body":"背景","idempotencyKey":"key-1"}""")
                assertEquals(HttpStatusCode.OK, replay.status)
                assertEquals(created.str("goalId"), replay.json().str("goalId"))

                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/goals", """{"title":"別","body":"背景","idempotencyKey":"key-1"}""").status)
            }
            assertEquals(1, db.count("selfdev_goals", "created_by = '$OPERATOR'"))
        }
    }

    @Test
    fun `the goal input follows the Task rules, and a refused input stores nothing`() {
        TestDb().use { db ->
            val bad = listOf(
                """{"title":"a\u0007b","idempotencyKey":"k"}""",
                """{"title":"   ","idempotencyKey":"k"}""",
                """{"title":"${"t".repeat(201)}","idempotencyKey":"k"}""",
                """{"title":"t","idempotencyKey":""}""",
                """{"title":"t","idempotencyKey":"k","body":"${"b".repeat(8001)}"}""",
                """{"title":"t","idempotencyKey":"k","body":"a\u0000b"}""",
                """{"title":"t","idempotencyKey":"k","repo":"other/repo"}""",
                """{"title":"t"}""",
                "not json",
            )
            testApplication {
                setUp(db)
                for (body in bad) {
                    assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals", body).status, body.take(60))
                }
            }
            assertEquals(0, db.count("selfdev_goals"))
        }
    }

    @Test
    fun `a bidi control in the goal title or body is 400 and nothing is stored`() {
        TestDb().use { db ->
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals", """{"title":"a\u202Eb","body":"背景","idempotencyKey":"k"}""").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals", """{"title":"t","body":"背景\u2067","idempotencyKey":"k"}""").status)
            }
            assertEquals(0, db.count("selfdev_goals"))
        }
    }

    @Test
    fun `a blank body is no body, and the list leaves bodies out while the detail shows them`() {
        TestDb().use { db ->
            testApplication {
                setUp(db)
                val created = post("/api/selfdev/goals", """{"title":"題名","body":"   ","idempotencyKey":"k"}""").json()
                val goalId = created.str("goalId")
                val detail = send(HttpMethod.Get, "/api/selfdev/goals/$goalId").json()
                assertEquals(JsonNull, detail.getValue("body"))
                val listed = Json.parseToJsonElement(text(send(HttpMethod.Get, "/api/selfdev/goals"))).jsonArray
                assertEquals(1, listed.size)
                assertFalse(listed.first().jsonObject.containsKey("body"))

                post("/api/selfdev/goals", """{"title":"二つ目","body":"本文の印 MARKER-BODY-7f3a","idempotencyKey":"k2"}""")
                val second = Json.parseToJsonElement(text(send(HttpMethod.Get, "/api/selfdev/goals")))
                assertFalse(second.toString().contains("MARKER-BODY-7f3a"))
                val secondId = second.jsonArray.map { it.jsonObject.str("goalId") }.first { it != goalId }
                assertTrue(text(send(HttpMethod.Get, "/api/selfdev/goals/$secondId")).contains("MARKER-BODY-7f3a"))
            }
        }
    }

    @Test
    fun `an unknown or malformed goal id is 404`() {
        TestDb().use { db ->
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/api/selfdev/goals/$ULID_NONE").status)
                assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/api/selfdev/goals/not-an-id").status)
                assertEquals(HttpStatusCode.NotFound, post("/api/selfdev/goals/$ULID_NONE/approve-plan", """{"version":0}""").status)
            }
        }
    }

    // ---- plan and task approval -----------------------------------------------------------------------------------------

    @Test
    fun `approving the plan and then each task moves the states, stores the approver, and never queues a denylisted task on its own`() {
        TestDb().use { db ->
            val (goal, plan) = proposed(db, "k1")
            testApplication {
                setUp(db)
                val approved = post("/api/selfdev/goals/${goal.goalId}/approve-plan", """{"version":${goal.version}}""")
                assertEquals(HttpStatusCode.OK, approved.status)
                assertEquals(GoalState.TASKS_QUEUED.name, approved.json().str("state"))
                assertEquals(1, db.count("selfdev_goals", "goal_id = '${goal.goalId}' AND approved_by = '$OPERATOR'"))

                val detail = send(HttpMethod.Get, "/api/selfdev/goals/${goal.goalId}").json()
                val tasks = detail.getValue("plans").jsonArray.single().jsonObject.getValue("tasks").jsonArray
                assertEquals(listOf("PENDING_APPROVAL", "PENDING_APPROVAL"), tasks.map { it.jsonObject.str("state") })

                val first = plan.tasks.first { !it.requiresApproval }
                val denylisted = plan.tasks.first { it.requiresApproval }
                val queued = post("/api/selfdev/tasks/${plan.planId}-${first.ordinal}/approve", """{"version":0}""")
                assertEquals(HttpStatusCode.OK, queued.status)
                assertEquals(TaskState.QUEUED.name, queued.json().str("state"))
                assertEquals(1, db.count("selfdev_plan_tasks", "plan_id = '${plan.planId}' AND ordinal = ${first.ordinal} AND approved_by = '$OPERATOR'"))

                val rejected = post("/api/selfdev/tasks/${plan.planId}-${denylisted.ordinal}/reject", """{"version":0}""")
                assertEquals(HttpStatusCode.OK, rejected.status)
                assertEquals(TaskState.SKIPPED.name, rejected.json().str("state"))
            }
        }
    }

    @Test
    fun `a stale version is 409, a goal that is not proposed refuses the plan approval, and a malformed body is 400`() {
        TestDb().use { db ->
            val (goal, plan) = proposed(db, "k1")
            val fresh = SelfDevGoalRepository(db.database).submit(OPERATOR, "k-fresh", "x", null, "kigawa-net/kivra")
            val freshGoal = (fresh as SubmitResult.Created).goal
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/goals/${goal.goalId}/approve-plan", """{"version":99}""").status)
                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/goals/${freshGoal.goalId}/approve-plan", """{"version":0}""").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/approve-plan", "{}").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/approve-plan", """{"version":-1}""").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/approve-plan", "nope").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/approve-plan", """{"version":0,"x":1}""").status)

                // A task of an unapproved plan cannot be approved: the goal is still PLAN_PROPOSED.
                val first = plan.tasks.first { !it.requiresApproval }
                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/tasks/${plan.planId}-${first.ordinal}/approve", """{"version":0}""").status)
                assertEquals(HttpStatusCode.NotFound, post("/api/selfdev/tasks/x-1/approve", """{"version":0}""").status)
                assertEquals(HttpStatusCode.NotFound, post("/api/selfdev/tasks/$ULID_NONE-1/approve", """{"version":0}""").status)
                assertEquals(HttpStatusCode.NotFound, post("/api/selfdev/tasks/${plan.planId}-0/approve", """{"version":0}""").status)
            }
        }
    }

    // ---- the AI principal (ADR 0019): it reads, and it changes nothing -------------------------------------------------

    @Test
    fun `the AI principal is refused a goal submission with 403 and the fixed code, and nothing is stored`() {
        TestDb().use { db ->
            testApplication {
                setUp(db, aiPrincipal = AI_PRINCIPAL)
                val refused = post("/api/selfdev/goals", """{"title":"AI 題名 MARKER-AI-TITLE","body":"AI 本文","idempotencyKey":"k"}""", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("""{"error":"forbidden"}""", text(refused))
                assertEquals(HttpStatusCode.Accepted, post("/api/selfdev/goals", """{"title":"運用者","idempotencyKey":"k2"}""").status)
            }
            assertEquals(0, db.count("selfdev_goals", "created_by = '$AI_PRINCIPAL'"))
            assertEquals(1, db.count("selfdev_goals"))
        }
    }

    @Test
    fun `the AI principal is refused a goal stop with 403, and the goal keeps its state and version`() {
        TestDb().use { db ->
            val (goal, _) = proposed(db, "k1")
            val goals = SelfDevGoalRepository(db.database)
            val before = goals.get(goal.goalId)!!
            testApplication {
                setUp(db, aiPrincipal = AI_PRINCIPAL)
                val refused = post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":${goal.version},"reason":"AI の中止"}""", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("""{"error":"forbidden"}""", text(refused))
            }
            val after = goals.get(goal.goalId)!!
            assertEquals(before.state, after.state)
            assertEquals(before.version, after.version)
            assertEquals(before.stateReason, after.stateReason)
        }
    }

    @Test
    fun `the AI principal is refused every goal, plan and task write with 403 and changes nothing`() {
        TestDb().use { db ->
            val (goal, plan) = proposed(db, "k1")
            val goals = SelfDevGoalRepository(db.database)
            val before = goals.get(goal.goalId)!!
            testApplication {
                setUp(db, aiPrincipal = AI_PRINCIPAL)
                val writes = listOf(
                    "/api/selfdev/goals/${goal.goalId}/approve-plan" to """{"version":${goal.version}}""",
                    "/api/selfdev/goals/${goal.goalId}/replan" to """{"version":${goal.version}}""",
                    "/api/selfdev/goals/${goal.goalId}/stop" to """{"version":${goal.version},"reason":"AI の中止"}""",
                    "/api/selfdev/tasks/${plan.planId}-1/approve" to """{"version":0}""",
                    "/api/selfdev/tasks/${plan.planId}-1/reject" to """{"version":0}""",
                )
                for ((path, body) in writes) {
                    val response = post(path, body, AI_PRINCIPAL)
                    assertEquals(HttpStatusCode.Forbidden, response.status, path)
                    assertEquals("""{"error":"forbidden"}""", text(response), path)
                }
            }
            val after = goals.get(goal.goalId)!!
            assertEquals(before.state, after.state)
            assertEquals(before.version, after.version)
            assertEquals(GoalState.PLAN_PROPOSED, after.state)
        }
    }

    @Test
    fun `the AI principal may read the goals and the detail, and the operator may still submit and stop`() {
        TestDb().use { db ->
            val (goal, _) = proposed(db, "k1")
            testApplication {
                setUp(db, aiPrincipal = AI_PRINCIPAL)
                assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/goals", null, principal = AI_PRINCIPAL).status)
                assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/goals/${goal.goalId}", null, principal = AI_PRINCIPAL).status)
                assertEquals(HttpStatusCode.Accepted, post("/api/selfdev/goals", """{"title":"運用者","idempotencyKey":"k2"}""").status)
                val stopped = post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":${goal.version},"reason":"止める"}""")
                assertEquals(HttpStatusCode.OK, stopped.status)
                assertEquals(GoalState.STOPPED.name, stopped.json().str("state"))
            }
        }
    }

    @Test
    fun `with no AI principal configured, a caller under that owner key is an operator as before`() {
        TestDb().use { db ->
            testApplication {
                setUp(db, aiPrincipal = null)
                assertEquals(HttpStatusCode.Accepted, post("/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}""", AI_PRINCIPAL).status)
            }
            assertEquals(1, db.count("selfdev_goals", "created_by = '$AI_PRINCIPAL'"))
        }
    }

    @Test
    fun `the AI principal is refused pause, resume and the breaker reset with 403 before the body is read, and the control does not change`() {
        TestDb().use { db ->
            val control = SelfDevAutopilotControlRepository(db.database)
            testApplication {
                setUp(db, aiPrincipal = AI_PRINCIPAL)
                // A malformed body is still a 403: the refusal comes before the body is read.
                val pause = post("/api/selfdev/autopilot/pause", """{"x":1}""", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, pause.status)
                assertEquals("""{"error":"forbidden"}""", text(pause))
                assertEquals(false, control.read().paused)

                // The operator pauses; the AI principal cannot resume it.
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/pause", "{}").status)
                val resume = post("/api/selfdev/autopilot/resume", "{}", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, resume.status)
                assertEquals("""{"error":"forbidden"}""", text(resume))
                assertEquals(true, control.read().paused)

                // The breaker stays open for the AI principal, and the reset is refused with nothing changed.
                control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = 1)
                val reset = post("/api/selfdev/autopilot/reset-breaker", "{}", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, reset.status)
                assertEquals("""{"error":"forbidden"}""", text(reset))
                assertEquals(true, control.read().breakerOpen)

                // The AI principal may still read the status, and the operator may still resume and reset as before.
                assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/autopilot", null, principal = AI_PRINCIPAL).status)
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/resume", "{}").status)
                assertEquals(false, control.read().paused)
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/reset-breaker", "{}").status)
                assertEquals(false, control.read().breakerOpen)
            }
        }
    }

    @Test
    fun `the AI principal is refused the automatic merge pause and resume with 403, and the operator switches it without a restart`() {
        TestDb().use { db ->
            val control = SelfDevAutopilotControlRepository(db.database)
            testApplication {
                setUp(db, aiPrincipal = AI_PRINCIPAL)
                assertEquals(false, control.read().autoMergePaused)

                val pause = post("/api/selfdev/autopilot/auto-merge/pause", """{"x":1}""", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, pause.status)
                assertEquals("""{"error":"forbidden"}""", text(pause))
                assertEquals(false, control.read().autoMergePaused)

                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/auto-merge/pause", "{}").status)
                assertEquals(true, control.read().autoMergePaused)
                assertEquals(true, send(HttpMethod.Get, "/api/selfdev/autopilot", null, principal = AI_PRINCIPAL).json().getValue("autoMergePaused").jsonPrimitive.boolean)

                val resume = post("/api/selfdev/autopilot/auto-merge/resume", "{}", AI_PRINCIPAL)
                assertEquals(HttpStatusCode.Forbidden, resume.status)
                assertEquals(true, control.read().autoMergePaused)

                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/auto-merge/resume", "{}").status)
                assertEquals(false, control.read().autoMergePaused)
                // The whole-autopilot pause is a separate switch.
                assertEquals(false, control.read().paused)
            }
        }
    }

    @Test
    fun `with no AI principal configured, the pause, resume and breaker reset of that owner key are the operator's as before`() {
        TestDb().use { db ->
            val control = SelfDevAutopilotControlRepository(db.database)
            testApplication {
                setUp(db, aiPrincipal = null)
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/pause", "{}", AI_PRINCIPAL).status)
                assertEquals(true, control.read().paused)
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/resume", "{}", AI_PRINCIPAL).status)
                assertEquals(false, control.read().paused)
            }
        }
    }

    // ---- stop and replan -----------------------------------------------------------------------------------------------

    @Test
    fun `stopping skips the unsubmitted tasks, a second stop is 409, and the reason is required`() {
        TestDb().use { db ->
            val (goal, plan) = proposed(db, "k1")
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":0,"reason":"  "}""").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":0,"reason":"a\u0007"}""").status)
                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":0}""").status)
                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":7,"reason":"止める"}""").status)

                val stopped = post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":${goal.version},"reason":"止める"}""")
                assertEquals(HttpStatusCode.OK, stopped.status)
                assertEquals(GoalState.STOPPED.name, stopped.json().str("state"))
                assertEquals(AutopilotReason.OPERATOR_STOPPED.name, stopped.json().str("stateReason"))
                assertEquals(2, db.count("selfdev_plan_tasks", "plan_id = '${plan.planId}' AND state = 'SKIPPED' AND state_reason = 'GOAL_STOPPED'"))

                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/goals/${goal.goalId}/stop", """{"version":${goal.version + 2},"reason":"再び"}""").status)
            }
        }
    }

    @Test
    fun `replan takes a planning-stage block back to GOAL_SUBMITTED, and is 409 for any other goal`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val blocked = goalOf(goals, "k-blocked")
            val claim = claimed(goals, blocked)
            assertTrue(goals.blockPlanning(GoalClaim(blocked.goalId, claim.version, claim.claimToken!!), AutopilotReason.PLAN_INVALID))
            val other = goalOf(goals, "k-other")
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.Conflict, post("/api/selfdev/goals/${other.goalId}/replan", """{"version":0}""").status)
                val replanned = post("/api/selfdev/goals/${blocked.goalId}/replan", """{"version":${claim.version + 1}}""")
                assertEquals(HttpStatusCode.OK, replanned.status)
                assertEquals(GoalState.GOAL_SUBMITTED.name, replanned.json().str("state"))
                assertEquals(AutopilotReason.REPLAN_REQUESTED.name, replanned.json().str("stateReason"))
            }
        }
    }

    // ---- the autopilot switches -----------------------------------------------------------------------------------------

    @Test
    fun `pause, resume and the breaker reset change the control row, and the status shows them`() {
        TestDb().use { db ->
            val control = SelfDevAutopilotControlRepository(db.database)
            testApplication {
                setUp(db)
                val before = send(HttpMethod.Get, "/api/selfdev/autopilot").json()
                assertEquals(false, before.getValue("paused").jsonPrimitive.boolean)
                assertEquals("required", before.str("approval"))

                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/pause", "{}").status)
                assertEquals(true, send(HttpMethod.Get, "/api/selfdev/autopilot").json().getValue("paused").jsonPrimitive.boolean)
                assertEquals(OPERATOR, control.read().pausedBy)

                assertEquals(HttpStatusCode.BadRequest, post("/api/selfdev/autopilot/resume", """{"x":1}""").status)
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/resume", "{}").status)
                assertEquals(false, control.read().paused)

                control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = 1)
                assertEquals(true, send(HttpMethod.Get, "/api/selfdev/autopilot").json().getValue("breakerOpen").jsonPrimitive.boolean)
                assertEquals(HttpStatusCode.OK, post("/api/selfdev/autopilot/reset-breaker", "{}").status)
                val after = send(HttpMethod.Get, "/api/selfdev/autopilot").json()
                assertEquals(false, after.getValue("breakerOpen").jsonPrimitive.boolean)
                assertEquals(0, after.getValue("consecutiveFailures").jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `the promotion readout is a GET with the four ADR conditions, and is 503 while the autopilot is disabled`() {
        TestDb().use { db ->
            testApplication {
                setUp(db, enabled = false)
                assertEquals(HttpStatusCode.ServiceUnavailable, send(HttpMethod.Get, "/api/selfdev/autopilot/promotion").status)
            }
        }
        TestDb().use { db ->
            testApplication {
                setUp(db)
                val response = send(HttpMethod.Get, "/api/selfdev/autopilot/promotion")
                assertEquals(HttpStatusCode.OK, response.status)
                val conditions = response.json().getValue("conditions").jsonArray.map { it.jsonObject }
                assertEquals(listOf("APPROVED_COMPLETED", "POLICY_REJECTED", "OUT_OF_SCOPE", "BUDGET_CONFIRMED"), conditions.map { it.str("id") })
                assertEquals(0, conditions[0].getValue("current").jsonPrimitive.int)
                assertEquals(10, conditions[0].getValue("required").jsonPrimitive.int)
                assertEquals(false, conditions[0].getValue("met").jsonPrimitive.boolean)
                assertEquals(false, conditions[3].getValue("measured").jsonPrimitive.boolean)
                assertEquals(JsonNull, conditions[3].getValue("current"))
            }
        }
    }

    // ---- logs and the operator tag ---------------------------------------------------------------------------------------

    @Test
    fun `the goal text, the reason and the principal never reach the log, only the ids and the fixed codes`() {
        TestDb().use { db ->
            val events = CopyOnWriteArrayList<ILoggingEvent>()
            val appLogger = LoggerFactory.getLogger("net.kigawa") as Logger
            val appender = object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) {
                    events.add(event)
                }
            }.also { it.context = appLogger.loggerContext; it.start(); appLogger.addAppender(it) }
            val oldLevel = appLogger.level
            appLogger.level = Level.TRACE
            try {
                testApplication {
                    setUp(db)
                    val goalId = post("/api/selfdev/goals", """{"title":"題 MARKER-TITLE-9c1","body":"MARKER-BODY-9c1","idempotencyKey":"k"}""")
                        .json().str("goalId")
                    post("/api/selfdev/goals/$goalId/stop", """{"version":0,"reason":"MARKER-REASON-9c1"}""")
                    post("/api/selfdev/autopilot/pause", "{}")
                    post("/api/selfdev/autopilot/resume", "{}")
                }
            } finally {
                appLogger.detachAppender(appender)
                appLogger.level = oldLevel
            }
            val logged = events.filter { it.loggerName == "net.kigawa.kivra.selfdev.autopilot.operator" }.map { it.formattedMessage }
            assertTrue(logged.isNotEmpty(), "the operator actions are logged")
            for (line in logged) {
                for (marker in listOf("MARKER-TITLE-9c1", "MARKER-BODY-9c1", "MARKER-REASON-9c1", OPERATOR)) {
                    assertFalse(line.contains(marker), "log line leaks $marker: $line")
                }
            }
            assertFalse(events.any { it.formattedMessage.contains("MARKER-") || it.formattedMessage.contains(OPERATOR) })
        }
    }

    @Test
    fun `the operator tag in logs is a short digest, not the principal`() {
        val tag = operatorTag(OPERATOR)
        assertEquals(12, tag.length)
        assertNotEquals(OPERATOR, tag)
        assertEquals(tag, operatorTag(OPERATOR))
    }

    @Test
    fun `the agent role is parsed, and it is required (the variable is named when it is missing)`() {
        assertEquals(RequiredRole("kivra-agent", "agent"), requiredAgentRole(mapOf("KIVRA_AGENT_REQUIRED_ROLE" to "kivra-agent:agent")))
        val missing = kotlin.test.assertFailsWith<IllegalStateException> { requiredAgentRole(emptyMap()) }
        assertEquals("KIVRA_AGENT_REQUIRED_ROLE", missing.message?.substringAfter("requires ")?.trim())
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private val cliSelfDev: SelfDevSettings by lazy { SelfDevSettings.fromEnv(selfDevEnv()) }

    private fun selfDevEnv(): Map<String, String> = mapOf(
        "KIVRA_SELFDEV_ENABLED" to "true",
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
        "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
        "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_RUNTIME" to "cli",
        "KIVRA_AGENT_API_ENABLED" to "true",
    )

    /** The autopilot switch and the CLI cost ledger's settings (ADR 0011 section 5.3). Required to enable it. */
    private fun autopilotEnv(): Map<String, String> = mapOf(
        AutopilotSettings.ENV_ENABLED to "true",
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
    )

    private fun goalOf(goals: SelfDevGoalRepository, key: String): SelfDevGoal =
        (goals.submit(OPERATOR, key, "goal $key", "body", "kigawa-net/kivra") as SubmitResult.Created).goal

    private fun claimed(goals: SelfDevGoalRepository, goal: SelfDevGoal): SelfDevGoal =
        (goals.claim(goal.goalId, TTL) as ClaimResult.Claimed).goal

    private fun proposed(db: TestDb, key: String): Pair<SelfDevGoal, SelfDevPlanCommit> {
        val goals = SelfDevGoalRepository(db.database)
        val goal = claimed(goals, goalOf(goals, key))
        val committed = (goals.commitPlan(GoalClaim(goal.goalId, goal.version, goal.claimToken!!), newPlan(), newTasks()) as CommitResult.Committed)
        return goals.get(goal.goalId)!! to SelfDevPlanCommit(committed.plan, committed.tasks)
    }

    private fun newPlan() = NewPlan(summary = "summary", plannerModel = "claude-haiku-5-5", outputSha256 = SHA, requestLedgerId = null)

    private fun newTasks() = listOf(
        NewTask("first", "body", listOf("docs/a.md"), "S", emptyList(), requiresApproval = false),
        NewTask("second", null, listOf(".github/workflows/ci.yml"), "M", listOf(1), requiresApproval = true),
    )

    private data class SelfDevPlanCommit(val plan: SelfDevPlan, val tasks: List<SelfDevPlanTask>) {
        val planId: String get() = plan.planId
    }
}
