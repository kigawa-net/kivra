package net.kigawa.kivra.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ClaimResult
import net.kigawa.kivra.db.CommitResult
import net.kigawa.kivra.db.GoalClaim
import net.kigawa.kivra.db.NewPlan
import net.kigawa.kivra.db.NewTask
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.db.Ulid
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * The Goal tools of ADR 0019 S1 on `/mcp` (`kivra_goal_list`, `kivra_goal_get`, `kivra_goal_stop`), through the production wiring
 * ([buildMcpRouteDeps]) on H2 with the real repositories and the real JWT verifier. No network, no GitHub, no model.
 *
 * The AI principal is the owner key of `sub-alice`; `sub-bob` is the human operator.
 */
class McpGoalToolsTest {
    private val repo = RepoRef("kigawa-net", "kivra")
    private val aiOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-alice")!!
    private val humanOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-bob")!!

    private val readScope = McpTestTokens.READ_SCOPE
    private val readAndStop = "$readScope $SCOPE_SELFDEV_STOP"
    private val aiToken get() = McpTestTokens.token(subject = "sub-alice", scope = readAndStop)
    private val humanToken get() = McpTestTokens.token(subject = "sub-bob", scope = readAndStop)
    private val readOnlyToken get() = McpTestTokens.token(subject = "sub-bob", scope = readScope)
    private val noRoleToken get() = McpTestTokens.token(subject = "sub-bob", roles = emptyList(), scope = readAndStop)

    private val stopTool = "kivra_goal_stop"
    private val readTools = listOf("kivra_goal_list", "kivra_goal_get")
    private val noSuchGoal = "0".repeat(25) + "1"

    private val titleMarker = "TITLE-MARKER-GOAL"
    private val bodyMarker = "BODY-MARKER-GOAL"
    private val taskMarker = "TASK-MARKER-GOAL"

    private val submitScope = "$readScope $SCOPE_SELFDEV_START"
    private val humanSubmitToken get() = McpTestTokens.token(subject = "sub-bob", scope = submitScope)
    private val aiSubmitToken get() = McpTestTokens.token(subject = "sub-alice", scope = submitScope)
    private val noRoleSubmitToken get() = McpTestTokens.token(subject = "sub-bob", roles = emptyList(), scope = submitScope)
    private val submitTool = "kivra_goal_submit"

    private fun env(readOn: Boolean, stopOn: Boolean, writeOn: Boolean, submitOn: Boolean = false): Map<String, String> = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
        McpSettings.ENV_ENABLED to "true",
        McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
        McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        McpSettings.ENV_ALLOWED_ORIGINS to McpTestTokens.ORIGIN,
        McpSettings.ENV_WRITE_ENABLED to writeOn.toString(),
        McpSettings.ENV_GOAL_READ_ENABLED to readOn.toString(),
        McpSettings.ENV_GOAL_STOP_ENABLED to stopOn.toString(),
        McpSettings.ENV_GOAL_SUBMIT_ENABLED to submitOn.toString(),
    )

    /** The production wiring as Application builds it. With [autopilot] off, no Goal dependency is passed (no Goal tool). */
    private fun deps(
        db: TestDb,
        autopilot: Boolean = true,
        readOn: Boolean = true,
        stopOn: Boolean = true,
        writeOn: Boolean = true,
        submitOn: Boolean = false,
        dailyLimit: Int = McpSettings.DEFAULT_GOAL_SUBMIT_DAILY_LIMIT,
        clock: Clock = Clock.systemUTC(),
    ): McpRouteDeps {
        val e = env(readOn, stopOn, writeOn, submitOn)
        val goals = if (autopilot) {
            McpGoalDeps(
                SelfDevOperatorRepository(db.database), SelfDevPlanRepository(db.database), db.runs, aiOwner, SelfDevMemoryRecorder.OFF,
                submit = McpGoalSubmitDeps(SelfDevGoalRepository(db.database, clock), "kigawa-net/kivra", dailyLimit, clock),
            )
        } else {
            null
        }
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, repo,
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 1, goals = goals,
        )!!
    }

    private fun ApplicationTestBuilder.setUp(deps: McpRouteDeps) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureMcpRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.rpc(body: String, token: String): HttpResponse =
        client.post("/mcp") {
            header(HttpHeaders.Host, McpTestTokens.HOST)
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun call(name: String, args: String, id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""

    private suspend fun ApplicationTestBuilder.callTool(name: String, args: String, token: String): JsonObject =
        rpc(call(name, args), token).json()

    private suspend fun ApplicationTestBuilder.listed(token: String): List<String> =
        rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token).json()["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun JsonObject.result(): JsonObject = this["result"]!!.jsonObject
    private fun JsonObject.structured(): JsonObject = result()["structuredContent"]!!.jsonObject
    private fun JsonObject.isError(): Boolean = result()["isError"]!!.jsonPrimitive.content.toBoolean()
    private fun JsonObject.errorCode(): String = structured()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun JsonObject.rpcCode(): Int = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    private fun stopArgs(goalId: String, version: Long, reason: String) =
        """{"goal_id":"$goalId","version":$version,"reason":"$reason"}"""

    private fun withTestDb(block: (TestDb) -> Unit) = TestDb().use(block)

    private fun submitArgs(title: String, key: String, body: String? = null): String = buildJsonObject {
        put("title", title)
        put("idempotency_key", key)
        body?.let { put("body", it) }
    }.toString()

    private fun TestDb.goalCount(): Int = SelfDevOperatorRepository(database).listGoals(100).size

    /** The formatted messages of every log line written while [block] runs. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    /**
     * A goal with a title and body, a plan of two tasks (the second needs approval), the plan approved, and the first task submitted by a
     * Run that is still in CREATED. Returns the goal id. The markers are in the title, the body and the task titles.
     */
    private fun TestDb.submittedGoal(key: String): String {
        val goals = SelfDevGoalRepository(database)
        val ops = SelfDevOperatorRepository(database)
        val plans = SelfDevPlanRepository(database)
        val created = assertIs<SubmitResult.Created>(goals.submit(humanOwner, key, titleMarker, bodyMarker, "kigawa-net/kivra")).goal
        val claimed = assertIs<ClaimResult.Claimed>(goals.claim(created.goalId, TTL)).goal
        val committed = assertIs<CommitResult.Committed>(
            goals.commitPlan(
                GoalClaim(claimed.goalId, claimed.version, claimed.claimToken!!),
                NewPlan(summary = "summary", plannerModel = "claude-haiku-5-5", outputSha256 = SHA, requestLedgerId = null),
                listOf(
                    NewTask("$taskMarker-FIRST", "body", listOf("docs/a.md"), "S", emptyList(), requiresApproval = false),
                    NewTask("$taskMarker-SECOND", null, listOf(".github/workflows/ci.yml"), "M", listOf(1), requiresApproval = true),
                ),
            ),
        )
        val planId = committed.plan.planId
        movedGoal(ops.approvePlan(created.goalId, goals.get(created.goalId)!!.version, humanOwner))
        val first = committed.tasks.first { !it.requiresApproval }
        val queued = movedTask(ops.approveTask(planId, first.ordinal, first.version, humanOwner))
        val submitting = movedTask(plans.transitionTask(planId, first.ordinal, queued.version, TaskState.SUBMITTING, null))
        val runId = insertRun("selfdev:$key")
        movedTask(plans.transitionTask(planId, first.ordinal, submitting.version, TaskState.SUBMITTED, null, runId))
        return created.goalId
    }

    private fun movedTask(result: Transition<SelfDevPlanTask>): SelfDevPlanTask =
        assertIs<Transition.Moved<SelfDevPlanTask>>(result).row

    private fun movedGoal(result: Transition<SelfDevGoal>): SelfDevGoal =
        assertIs<Transition.Moved<SelfDevGoal>>(result).row

    private fun TestDb.goalOf(goalId: String): SelfDevGoal = SelfDevOperatorRepository(database).goal(goalId)!!

    private fun TestDb.tasksOf(goalId: String): List<SelfDevPlanTask> {
        val plans = SelfDevPlanRepository(database)
        return plans.plansOf(goalId).single().let { plans.tasksOf(it.planId) }
    }

    private fun TestDb.setRunState(runId: String, state: String) = transaction(database) {
        execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", state, runId)
    }

    private fun TestDb.insertRun(task: String): String {
        val runId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO development_runs
                    (run_id, task_id, state, completion_mode, attempt, plan_revision, lease_generation, cancel_requested, version, created_at, updated_at)
                VALUES (?, ?, 'CREATED', ?, 0, 0, 0, FALSE, 0, NOW(6), NOW(6))
                """.trimIndent(),
                runId, task, RunCompletionMode.RELEASE.name,
            )
        }
        return runId
    }

    // ---- registration and settings ---------------------------------------------------------------------------

    @Test
    fun `with every Goal switch off, or the autopilot off, no Goal tool is listed or answered, even to a token with every scope`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, readOn = false, stopOn = false))
            assertFalse(listed(aiToken).any { it.startsWith("kivra_goal_") })
            assertEquals(-32602, callTool("kivra_goal_list", "{}", aiToken).rpcCode())
        }
        testApplication {
            setUp(deps(db, autopilot = false, readOn = true, stopOn = true))
            assertFalse(listed(humanToken).any { it.startsWith("kivra_goal_") })
            assertEquals(-32602, callTool(stopTool, stopArgs(noSuchGoal, 0, "OPERATOR_REQUEST"), humanToken).rpcCode())
        }
    }

    @Test
    fun `the read switch lists the two reads only, and the stop is not listed without its switch`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, readOn = true, stopOn = false))
            assertEquals(readTools, listed(aiToken).filter { it.startsWith("kivra_goal_") })
            assertEquals(-32602, callTool(stopTool, stopArgs(noSuchGoal, 0, "OPERATOR_REQUEST"), humanToken).rpcCode())
        }
    }

    @Test
    fun `the stop is listed only to a token with its scope, and the reads only to a token with the runs read scope`() = withTestDb { db ->
        testApplication {
            setUp(deps(db))
            assertEquals(readTools + stopTool, listed(humanToken).filter { it.startsWith("kivra_goal_") })
            assertEquals(readTools, listed(readOnlyToken).filter { it.startsWith("kivra_goal_") })
        }
    }

    @Test
    fun `the Goal switches default to off, and the stop switch is refused without the write switch`() {
        val base = mapOf(
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
            "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
            "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
            McpSettings.ENV_ENABLED to "true",
            McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
            McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        )
        val defaults = McpSettings.fromEnv(base)
        assertFalse(defaults.goalReadEnabled)
        assertFalse(defaults.goalStopEnabled)
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(env(readOn = false, stopOn = true, writeOn = false)) }
        // A Goal switch with the endpoint off is refused, as the write switch is.
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(base - McpSettings.ENV_ENABLED + (McpSettings.ENV_GOAL_READ_ENABLED to "true")) }
    }

    // ---- the reads -------------------------------------------------------------------------------------------

    @Test
    fun `the reads return ids, states, versions and task counts, and never the title, the body or a task's text`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-read")
        testApplication {
            setUp(deps(db))
            val list = callTool("kivra_goal_list", "{}", readOnlyToken)
            val get = callTool("kivra_goal_get", """{"goal_id":"$goalId"}""", readOnlyToken)
            assertFalse(list.isError())
            assertFalse(get.isError())

            val row = list.structured()["goals"]!!.jsonArray.single().jsonObject
            assertEquals(goalId, row["goal_id"]!!.jsonPrimitive.content)
            assertEquals(db.goalOf(goalId).state.name, row["state"]!!.jsonPrimitive.content)
            assertEquals(db.goalOf(goalId).version, row["version"]!!.jsonPrimitive.content.toLong())
            val counts = row["task_counts"]!!.jsonObject
            assertEquals(TaskState.entries.size, counts.size)
            assertEquals(2, counts.values.sumOf { it.jsonPrimitive.content.toInt() })
            assertEquals(1, counts["SUBMITTED"]!!.jsonPrimitive.content.toInt())

            val detail = get.structured()
            assertEquals(goalId, detail["goal_id"]!!.jsonPrimitive.content)
            val tasks = detail["tasks"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(1, 2), tasks.map { it["ordinal"]!!.jsonPrimitive.content.toInt() })
            assertEquals("SUBMITTED", tasks.first()["state"]!!.jsonPrimitive.content)
            assertTrue(tasks.first()["run_id"]!!.jsonPrimitive.content.isNotEmpty())

            val everything = listOf(list.toString(), get.toString()).joinToString("\n")
            for (marker in listOf(titleMarker, bodyMarker, taskMarker)) {
                assertFalse(everything.contains(marker), "a read leaked the goal text")
            }
        }
    }

    @Test
    fun `a missing goal is a fixed not-found, and the reads take no unknown argument`() = withTestDb { db ->
        testApplication {
            setUp(deps(db))
            val missing = callTool("kivra_goal_get", """{"goal_id":"$noSuchGoal"}""", readOnlyToken)
            assertTrue(missing.isError())
            assertEquals("GOAL_NOT_FOUND", missing.errorCode())
            assertEquals(-32602, callTool("kivra_goal_get", """{"goal_id":"$noSuchGoal","title":"x"}""", readOnlyToken).rpcCode())
            assertEquals(-32602, callTool("kivra_goal_list", """{"limit":5}""", readOnlyToken).rpcCode())
        }
    }

    // ---- the stop --------------------------------------------------------------------------------------------

    @Test
    fun `the operator stops a goal as the REST route does, skips the unsubmitted task, and reports the Run still active`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-stop")
        val before = db.goalOf(goalId)
        testApplication {
            setUp(deps(db))
            val res = callTool(stopTool, stopArgs(goalId, before.version, "OPERATOR_REQUEST"), humanToken)
            assertFalse(res.isError())
            val s = res.structured()
            assertEquals(GoalState.STOPPED.name, s["state"]!!.jsonPrimitive.content)
            assertEquals("OPERATOR_STOPPED", s["reason_code"]!!.jsonPrimitive.content)
            assertEquals(before.version + 1, s["version"]!!.jsonPrimitive.content.toLong())
            assertTrue(s["run_still_active"]!!.jsonPrimitive.content.toBoolean())
        }
        assertEquals(GoalState.STOPPED, db.goalOf(goalId).state)
        assertEquals(setOf(TaskState.SUBMITTED, TaskState.SKIPPED), db.tasksOf(goalId).map { it.state }.toSet())
    }

    @Test
    fun `after the submitted Run has ended, the stop reports no Run still active`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-ended")
        val runId = db.tasksOf(goalId).first { it.state == TaskState.SUBMITTED }.runId!!
        db.setRunState(runId, "COMPLETED")
        val before = db.goalOf(goalId)
        testApplication {
            setUp(deps(db))
            val s = callTool(stopTool, stopArgs(goalId, before.version, "OPERATOR_REQUEST"), humanToken).structured()
            assertEquals(false, s["run_still_active"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `a stale version and an unknown goal are fixed refusals, and nothing changes`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-stale")
        val before = db.goalOf(goalId)
        testApplication {
            setUp(deps(db))
            val stale = callTool(stopTool, stopArgs(goalId, before.version + 5, "OPERATOR_REQUEST"), humanToken)
            assertTrue(stale.isError())
            assertEquals("STALE_VERSION", stale.errorCode())
            val unknown = callTool(stopTool, stopArgs(noSuchGoal, 0, "OPERATOR_REQUEST"), humanToken)
            assertEquals("GOAL_NOT_FOUND", unknown.errorCode())
        }
        assertEquals(before.state, db.goalOf(goalId).state)
        assertEquals(before.version, db.goalOf(goalId).version)
    }

    @Test
    fun `the AI principal is refused the stop, the goal does not change, and it can still read`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-ai")
        val before = db.goalOf(goalId)
        testApplication {
            setUp(deps(db))
            val refused = callTool(stopTool, stopArgs(goalId, before.version, "OPERATOR_REQUEST"), aiToken)
            assertTrue(refused.isError())
            assertEquals("forbidden", refused.errorCode())
            assertFalse(callTool("kivra_goal_get", """{"goal_id":"$goalId"}""", aiToken).isError())
        }
        assertEquals(before.state, db.goalOf(goalId).state)
        assertEquals(before.version, db.goalOf(goalId).version)
    }

    @Test
    fun `a token without the operator role is refused, and the goal does not change`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-role")
        val before = db.goalOf(goalId)
        testApplication {
            setUp(deps(db))
            val res = rpc(call(stopTool, stopArgs(goalId, before.version, "OPERATOR_REQUEST")), noRoleToken)
            assertFalse(res.status == HttpStatusCode.OK)
            assertFalse(res.bodyAsText().contains("\"result\""))
        }
        assertEquals(before.state, db.goalOf(goalId).state)
    }

    @Test
    fun `the stop reason is a fixed code, and free text or an unknown field is refused with nothing changed`() = withTestDb { db ->
        val goalId = db.submittedGoal("k-args")
        val before = db.goalOf(goalId)
        testApplication {
            setUp(deps(db))
            val freeText = callTool(stopTool, stopArgs(goalId, before.version, "please stop, the budget is gone"), humanToken)
            assertEquals(-32602, freeText.rpcCode())
            val extra = callTool(stopTool, """{"goal_id":"$goalId","version":${before.version},"reason":"OPERATOR_REQUEST","body":"x"}""", humanToken)
            assertEquals(-32602, extra.rpcCode())
        }
        assertEquals(before.state, db.goalOf(goalId).state)
    }

    @Test
    fun `the logs carry the fixed reason code and a short goal id, and never the title, the body or a principal`() {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        var goalId = ""
        try {
            TestDb().use { db ->
                goalId = db.submittedGoal("k-log")
                val version = db.goalOf(goalId).version
                testApplication {
                    setUp(deps(db))
                    callTool(stopTool, stopArgs(goalId, version, "OPERATOR_REQUEST"), aiToken)
                    callTool(stopTool, stopArgs(goalId, version, "OPERATOR_REQUEST"), humanToken)
                }
            }
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { it.contains("action=STOP") && it.contains("via=mcp") && it.contains("goalId=${goalId.take(12)} ") })
        assertTrue(lines.any { it.contains("reason=OPERATOR_REQUEST") && it.contains("result=STOPPED") })
        assertTrue(lines.any { it.contains("action=STOP") && it.contains("actor=AI") && it.contains("result=forbidden") })
        val everything = lines.joinToString("\n")
        for (marker in listOf(titleMarker, bodyMarker, taskMarker, "sub-alice", "sub-bob", aiOwner, humanOwner, goalId)) {
            assertFalse(everything.contains(marker), "a log line leaked a value")
        }
    }

    // ---- the submission (ADR 0019 S2) ------------------------------------------------------------------------

    @Test
    fun `the submit tool is listed only with its switch and the write switch, and only to a token with its scope`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, submitOn = false))
            assertFalse(listed(humanSubmitToken).contains(submitTool))
            assertEquals(-32602, callTool(submitTool, submitArgs("t", "k"), humanSubmitToken).rpcCode())
        }
        testApplication {
            setUp(deps(db, readOn = true, stopOn = true, submitOn = false))
            assertFalse(listed(humanSubmitToken).contains(submitTool))
        }
        testApplication {
            setUp(deps(db, submitOn = true))
            assertTrue(listed(humanSubmitToken).contains(submitTool))
            assertFalse(listed(readOnlyToken).contains(submitTool))
        }
    }

    @Test
    fun `the submit switch defaults to off, needs the endpoint and the write switch, and its daily cap is 1 to 100`() {
        val base = mapOf(
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
            "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
            "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
            McpSettings.ENV_ENABLED to "true",
            McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
            McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        )
        val defaults = McpSettings.fromEnv(base)
        assertFalse(defaults.goalSubmitEnabled)
        assertEquals(10, defaults.goalSubmitDailyLimit)
        // Without the write switch, or with the endpoint off, the submit switch is refused at startup.
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(env(readOn = false, stopOn = false, writeOn = false, submitOn = true)) }
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(base - McpSettings.ENV_ENABLED + (McpSettings.ENV_GOAL_SUBMIT_ENABLED to "true")) }
        val on = env(readOn = false, stopOn = false, writeOn = true, submitOn = true)
        assertTrue(McpSettings.fromEnv(on).goalSubmitEnabled)
        assertEquals(100, McpSettings.fromEnv(on + (McpSettings.ENV_GOAL_SUBMIT_DAILY_LIMIT to "100")).goalSubmitDailyLimit)
        assertFailsWith<IllegalArgumentException> { McpSettings.fromEnv(on + (McpSettings.ENV_GOAL_SUBMIT_DAILY_LIMIT to "0")) }
        assertFailsWith<IllegalArgumentException> { McpSettings.fromEnv(on + (McpSettings.ENV_GOAL_SUBMIT_DAILY_LIMIT to "101")) }
    }

    @Test
    fun `the operator submits a goal, and neither the reply nor the logs carry the title or the body`() {
        var goalTitle = ""
        var reply = ""
        val logs = captureLogs {
            withTestDb { db ->
                testApplication {
                    setUp(deps(db, submitOn = true))
                    val res = callTool(submitTool, submitArgs(titleMarker, "k-submit-ok", bodyMarker), humanSubmitToken)
                    assertFalse(res.isError())
                    val s = res.structured()
                    assertEquals(GoalState.GOAL_SUBMITTED.name, s["state"]!!.jsonPrimitive.content)
                    assertEquals("false", s["replayed"]!!.jsonPrimitive.content)
                    reply = res.toString()
                    goalTitle = db.goalOf(s["goal_id"]!!.jsonPrimitive.content).title
                }
                assertEquals(1, db.goalCount())
            }
        }
        assertEquals(titleMarker, goalTitle)
        assertTrue(logs.any { it.contains("action=SUBMIT") && it.contains("via=mcp") && it.contains("result=GOAL_SUBMITTED") })
        val everything = (logs + reply).joinToString("\n")
        for (marker in listOf(titleMarker, bodyMarker, "k-submit-ok", "sub-bob", humanOwner)) {
            assertFalse(everything.contains(marker), "the submission leaked a value")
        }
    }

    @Test
    fun `a repeat with the same key and content replays the goal, and another content is a conflict that reveals no id`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, submitOn = true))
            val first = callTool(submitTool, submitArgs(titleMarker, "k-replay", bodyMarker), humanSubmitToken).structured()
            val goalId = first["goal_id"]!!.jsonPrimitive.content
            val again = callTool(submitTool, submitArgs(titleMarker, "k-replay", bodyMarker), humanSubmitToken)
            assertFalse(again.isError())
            assertEquals("true", again.structured()["replayed"]!!.jsonPrimitive.content)
            assertEquals(goalId, again.structured()["goal_id"]!!.jsonPrimitive.content)
            val other = callTool(submitTool, submitArgs(titleMarker, "k-replay", "another body"), humanSubmitToken)
            assertTrue(other.isError())
            assertEquals("IDEMPOTENCY_CONFLICT", other.errorCode())
            assertFalse(other.toString().contains(goalId))
            assertEquals(1, db.goalCount())
        }
    }

    @Test
    fun `an invalid title, key or body is a fixed refusal, and no goal is created`() = withTestDb { db ->
        // Two applications: each has its own write rate limiter (5 per minute), and the cases below make six calls in all.
        for (cases in listOf(
            listOf(
                submitArgs("x".repeat(201), "k-long"),
                submitArgs("bad\u0001title", "k-ctl"),
                submitArgs("   ", "k-blank"),
            ),
            listOf(
                submitArgs(titleMarker, "k".repeat(129)),
                submitArgs(titleMarker, "k-body-ctl", "body\u0007"),
                submitArgs(titleMarker, "k-body-long", "b".repeat(8_001)),
            ),
        )) {
            testApplication {
                setUp(deps(db, submitOn = true))
                for (args in cases) {
                    val res = callTool(submitTool, args, humanSubmitToken)
                    assertTrue(res.isError(), "a case was accepted")
                    assertEquals("INVALID_INPUT", res.errorCode())
                    assertFalse(res.toString().contains(titleMarker))
                }
            }
        }
        assertEquals(0, db.goalCount())
    }

    @Test
    fun `the AI principal is refused before its input is read, and a token without the operator role is refused`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, submitOn = true))
            val refused = callTool(submitTool, submitArgs(titleMarker, "k-ai"), aiSubmitToken)
            assertTrue(refused.isError())
            assertEquals("forbidden", refused.errorCode())
            // Not read: an input that would be refused as malformed still gets the refusal for the principal.
            assertEquals("forbidden", callTool(submitTool, """{"bogus":1}""", aiSubmitToken).errorCode())
            val noRole = rpc(call(submitTool, submitArgs(titleMarker, "k-role")), noRoleSubmitToken)
            assertFalse(noRole.status == HttpStatusCode.OK)
            assertFalse(noRole.bodyAsText().contains("\"result\""))
        }
        assertEquals(0, db.goalCount())
    }

    @Test
    fun `the daily cap refuses the goal past the limit, a replay is never counted, and the next UTC day resets it`() {
        val clock = MutableClock(Instant.parse("2026-10-21T10:00:00Z"))
        withTestDb { db ->
            testApplication {
                setUp(deps(db, submitOn = true, dailyLimit = 2, clock = clock))
                assertFalse(callTool(submitTool, submitArgs(titleMarker, "k-day-1"), humanSubmitToken).isError())
                assertFalse(callTool(submitTool, submitArgs(titleMarker, "k-day-2"), humanSubmitToken).isError())
                val over = callTool(submitTool, submitArgs(titleMarker, "k-day-3"), humanSubmitToken)
                assertTrue(over.isError())
                assertEquals("DAILY_LIMIT", over.errorCode())
                assertEquals(2, db.goalCount())
                // A replay of an earlier key is answered, even at the cap, and creates nothing.
                val replay = callTool(submitTool, submitArgs(titleMarker, "k-day-1"), humanSubmitToken)
                assertFalse(replay.isError())
                assertEquals("true", replay.structured()["replayed"]!!.jsonPrimitive.content)
                assertEquals(2, db.goalCount())
                // The next UTC day starts with an empty count.
                clock.now = Instant.parse("2026-10-22T00:00:01Z")
                assertFalse(callTool(submitTool, submitArgs(titleMarker, "k-day-3"), humanSubmitToken).isError())
                assertEquals(3, db.goalCount())
            }
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private companion object {
        val TTL: java.time.Duration = java.time.Duration.ofSeconds(60)
        val SHA = "b".repeat(64)
    }
}
