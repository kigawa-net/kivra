package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.coding.ChangeKind
import net.kigawa.kivra.coding.CodingChange
import net.kigawa.kivra.coding.CodingClaimOutcome
import net.kigawa.kivra.coding.CodingErrorCode
import net.kigawa.kivra.coding.CodingInput
import net.kigawa.kivra.coding.CodingResult
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.CodingSubmitOutcome
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.coding.UnmeteredCodingCost
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import net.kigawa.kivra.selfdev.memory.SuppressShadow
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RunBranch
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.InMemoryRequestLedger
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelEntry
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Price
import net.kigawa.kivra.modelgateway.ProviderEntry
import net.kigawa.kivra.modelgateway.ProviderHealth
import net.kigawa.kivra.modelgateway.ProviderHealthState
import net.kigawa.kivra.modelgateway.ProviderModelInfo
import net.kigawa.kivra.modelgateway.QualityTier
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage
import net.kigawa.kivra.routes.SelfDevGoalRouteDeps
import net.kigawa.kivra.routes.configureSelfDevGoalRoutes
import org.slf4j.LoggerFactory

/*
 * Shared fixtures of the autopilot end-to-end tests (ADR 0011 section 9, PR E). H2 and the real repositories, the real HTTP routes
 * (operator API), the real Planner, Dispatcher and SelfDevRunner (CLI runtime). Only the model, GitHub and the coding agent are fakes.
 * No network, no real key. The agent is played by the test, as the agent would call the coding API.
 */

internal const val E2E_REPO = "kigawa-net/kivra"
internal const val E2E_OPERATOR = "op-e2e"
internal const val E2E_AGENT = "agent-e2e"
private const val TENANT = "tenant-e2e"
private const val NONCE = "n0nce-e2e-test"
private const val AUTH_HEADER = "X-E2E-Principal"
private const val BUDGET_LIMIT_JPY = "10000"

/** The files of the repository the fake GitHub serves at its base commit. Every path a planned task touches must be here. */
internal val E2E_FILES: Map<String, String> = mapOf(
    "README.md" to "# kivra\n",
    "docs/a.md" to "old a\n",
    "docs/b.md" to "old b\n",
    "kivra-core/src/test/kotlin/FooTest.kt" to "class FooTest\n",
    "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Thing.kt" to "object Thing\n",
    "kivra-app/src/main/kotlin/net/kigawa/kivra/db/migration/Thing.kt" to "object Thing\n",
)

internal fun e2eConfig(
    approval: AutopilotApproval = AutopilotApproval.REQUIRED,
    maxPendingPrs: Int = 3,
    maxFailures: Int = 3,
    maxRunsPerDay: Int = 10,
) = AutopilotConfig(approval, maxPendingPrs, maxFailures, maxRunsPerDay, budgetSharePercent = 50)

/** The self-development settings the autopilot needs (the cli runtime, enabled). */
internal val E2E_SELF_DEV: SelfDevSettings by lazy {
    SelfDevSettings.fromEnv(
        mapOf(
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
        ),
    )
}

/** The autopilot settings the operator API sees, built from the same values as [e2eConfig]. */
internal fun e2eSettings(config: AutopilotConfig?): AutopilotSettings = if (config == null) {
    AutopilotSettings.disabled()
} else {
    AutopilotSettings.fromEnv(
        mapOf(
            AutopilotSettings.ENV_ENABLED to "true",
            AutopilotSettings.ENV_APPROVAL to config.approval.name.lowercase(),
            AutopilotSettings.ENV_MAX_PENDING_PRS to config.maxPendingPrs.toString(),
            AutopilotSettings.ENV_MAX_CONSECUTIVE_FAILURES to config.maxConsecutiveFailures.toString(),
            AutopilotSettings.ENV_MAX_RUNS_PER_DAY to config.maxRunsPerDay.toString(),
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        ),
        E2E_SELF_DEV,
    )
}

/** A scripted model. [text] receives the request and returns the answer text. Every call is recorded. */
internal class ScriptedModel(var text: (ModelRequest) -> String) : ModelProvider {
    override val providerId: String = "anthropic"
    val calls = CopyOnWriteArrayList<ModelRequest>()

    override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
    override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()

    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
        calls.add(req)
        val answer = text(req)
        return ModelResponse(
            requestId = req.requestId,
            attemptId = ctx.attemptId,
            selected = ModelRef("anthropic", PLANNER_MODEL_ID, PLANNER_MODEL_ID),
            message = Message(Role.ASSISTANT, answer),
            structured = null,
            finishReason = FinishReason.STOP,
            usage = Usage(1_000, 500),
            cost = Cost.zero(),
            latencyMs = 5,
            routingDecisionId = ctx.routingDecisionId,
            usageComplete = true,
        )
    }

    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = emptyFlow()
}

/** One planned task, as the model writes it. */
internal data class PlanTask(
    val title: String,
    val body: String,
    val paths: List<String>,
    val size: String = "S",
    val dependsOn: List<Int> = emptyList(),
)

/** The model's answer for [tasks]: the output schema of ADR 0011 section 3, written as JSON. */
internal fun planJson(vararg tasks: PlanTask, summary: String = "e2e plan"): String = buildJsonObject {
    put("summary", summary)
    put("tasks", buildTaskArray(tasks.toList()))
}.toString()

private fun buildTaskArray(tasks: List<PlanTask>) = kotlinx.serialization.json.buildJsonArray {
    for (t in tasks) {
        add(
            buildJsonObject {
                put("title", t.title)
                put("body", t.body)
                put("targetPaths", kotlinx.serialization.json.buildJsonArray { t.paths.forEach { add(JsonPrimitive(it)) } })
                put("sizeEstimate", t.size)
                put("dependsOn", kotlinx.serialization.json.buildJsonArray { t.dependsOn.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }
}

/** A gateway over [provider] with the Planner's model active and priced, and a budget that is never the limit here. */
internal fun plannerGateway(provider: ModelProvider): ModelGateway = ModelGateway(
    registry = ModelRegistry(
        "e2e-registry-v1",
        listOf(
            ProviderEntry(
                "anthropic", "anthropic", TrustZone.EXTERNAL,
                DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms"),
                true, "https://api.example.test", "ANTHROPIC_API_KEY",
            ),
        ),
        listOf(
            ModelEntry(
                PLANNER_MODEL_ID, "anthropic", PLANNER_MODEL_ID, Lifecycle.ACTIVE, null,
                setOf(Capability.TEXT, Capability.SYSTEM_PROMPT), 100_000, 8_192,
                listOf(Price(BigDecimal("3"), BigDecimal("15"))), QualityTier.A,
            ),
        ),
    ),
    providers = mapOf("anthropic" to provider),
    budget = BudgetController(mapOf(TENANT to BudgetLimit(BigDecimal("100")))),
    ledger = InMemoryRequestLedger(),
    sleep = { },
)

/** Every log line of the net.kigawa.kivra loggers at TRACE while [block] runs, as formatted text. */
internal inline fun <T> captureKivraLogs(block: () -> T): Pair<T, List<String>> {
    val logger = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val previous = logger.level
    logger.level = Level.TRACE
    logger.addAppender(appender)
    try {
        val result = block()
        return result to appender.list.map { it.formattedMessage }
    } finally {
        logger.detachAppender(appender)
        logger.level = previous
    }
}

/**
 * One operator's world: the database, the planner and the dispatcher built as production builds them, the fake model, GitHub, and the
 * coding agent. The operator's actions go through [OperatorApi] (HTTP); the reads and the scheduler ticks are direct calls.
 */
internal class E2eWorld(
    val db: TestDb,
    val config: AutopilotConfig,
    val model: ScriptedModel,
    private val files: Map<String, String> = E2E_FILES,
    /** The self-development concurrency limit (KIVRA_SELFDEV_MAX_ACTIVE_RUNS). The default is 1. */
    private val maxActive: Int = 1,
    /** The self-development memory's recorder (ADR 0016 P1). Off unless a test passes one. */
    val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
    /** The suppression shadow (ADR 0016 P3). Off unless a test passes one. */
    val suppress: SuppressShadow = SuppressShadow.OFF,
) {
    val goals = SelfDevGoalRepository(db.database)
    val plans = SelfDevPlanRepository(db.database)
    val control = SelfDevAutopilotControlRepository(db.database)
    val github = FakeGitHub()
    val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "from the model\n"))

    private val service = CodingRuntimeService(
        attempts = CodingAttemptRepository(db.database),
        runs = DbCodingRunPort(db.runs, db.leases),
        results = DbCodingResultStore(db.database),
        tasks = CodingTaskSource { it.taskTitle },
        cost = UnmeteredCodingCost,
    )
    private val runner = SelfDevRunner(
        config = SelfDevConfig(enabled = true, runtime = SelfDevRuntime.CLI),
        runs = DbDevelopmentRunStore(db.runs),
        leases = db.leases,
        developer = developer,
        github = github,
        repoReader = InMemoryRepoReader(files, github.baseSha),
        taskTexts = DbTaskTextSource,
        clock = Clock.systemUTC(),
        coding = CodingAttemptPatchSource(service),
    )

    /** The Planner, as production builds it (the model is pinned to [PLANNER_MODEL_ID] by the gateway's requirements). */
    val planner = AutopilotPlanner(
        goals = goals,
        gateway = plannerGateway(model),
        config = PlannerConfig(budgetScopes = listOf(TENANT), autopilot = config),
        nonceSource = { NONCE },
        memory = memory,
        suppress = suppress,
    )
    private val plannerWorker = PlannerWorker(goals, planner, Duration.ofSeconds(30))

    /** A dispatcher as Application.kt builds it (one active self-development run). A new one each time, as after a restart. */
    fun dispatcher(cfg: AutopilotConfig = config) = AutopilotDispatcher(
        database = db.database,
        runs = db.runs,
        config = cfg,
        maxActiveSelfDev = maxActive,
        budgetAllows = { share -> autopilotBudgetAllows(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal(BUDGET_LIMIT_JPY), share) },
        memory = memory,
    )

    fun tick() = dispatcher().tick()

    /** One Planner pass over the goals waiting in GOAL_SUBMITTED. */
    suspend fun plan() = plannerWorker.tick()

    fun goal(goalId: String): SelfDevGoal = goals.get(goalId)!!

    fun planId(goalId: String): String = plans.plansOf(goalId).single().planId

    fun tasks(goalId: String): List<SelfDevPlanTask> = plans.tasksOf(planId(goalId))

    fun task(goalId: String, ordinal: Int): SelfDevPlanTask = plans.task(planId(goalId), ordinal)!!

    fun runId(goalId: String, ordinal: Int): RunId = RunId(task(goalId, ordinal).runId!!)

    fun runCount(): Int = db.count("development_runs")

    fun runState(runId: RunId): DevelopmentRunState = db.runs.get(runId)!!.state

    /**
     * The agent claims the queued attempt of [runId] and submits a change to [path]. The Run then reaches its pull request. The
     * runner's PatchPolicy is the gate, as in production.
     */
    suspend fun openPullRequest(runId: RunId, path: String) {
        val input = startAttempt(runId)
        val change = CodingChange(path, ChangeKind.WRITE, "changed by ${runId.value}\n")
        val outcome = service.submitResult(input.attemptId, E2E_AGENT, input.leaseGeneration, CodingResult("e2e change", listOf(change), 0, "session-e2e"))
        assertIs<CodingSubmitOutcome.Accepted>(outcome, "the agent's change to $path was $outcome")
        assertEquals(
            AdvanceResult.Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST),
            runner.advance(runId),
        )
    }

    /** The agent reports a fixed failure code for [runId]. The Run moves to the state that code names (ADR 0010). */
    suspend fun failRun(runId: RunId, code: CodingErrorCode): DevelopmentRunState {
        val input = startAttempt(runId)
        assertIs<CodingAttemptResult.Ok>(service.fail(input.attemptId, E2E_AGENT, input.leaseGeneration, code))
        return (runner.advance(runId) as AdvanceResult.Advanced).to
    }

    /** A human merges the pull request of [runId]; the runner verifies the merge and completes the Run. */
    suspend fun mergeAndComplete(runId: RunId) {
        github.humanMerges(openPrNumber(runId))
        assertEquals(
            AdvanceResult.Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"),
            runner.advance(runId),
        )
    }

    fun openPrNumber(runId: RunId): Int = github.pullRequests()
        .single { it.headRef == RunBranch.name(runId.value) && it.state == PullRequestState.OPEN }.number

    /** The Run's first two calls (CREATED to DEVELOPING, then the wait for the agent), and the agent's claim. */
    private suspend fun startAttempt(runId: RunId): CodingInput {
        assertEquals(
            AdvanceResult.Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING),
            runner.advance(runId),
        )
        assertEquals(AdvanceResult.Waiting(WaitReason.AWAITING_CODING_AGENT), runner.advance(runId))
        return assertIs<CodingClaimOutcome.Claimed>(service.claim(E2E_AGENT)).input
    }
}

/** The operator's HTTP calls (ADR 0011 section 1.2). Every call carries the operator's principal; no CSRF header is needed for JSON. */
internal class OperatorApi(private val client: HttpClient) {
    suspend fun submit(title: String, body: String?, key: String): HttpResponse = post(
        "/api/selfdev/goals",
        buildJsonObject {
            put("title", title)
            if (body != null) put("body", body)
            put("idempotencyKey", key)
        }.toString(),
    )

    suspend fun approvePlan(goalId: String, version: Long): HttpResponse =
        post("/api/selfdev/goals/$goalId/approve-plan", """{"version":$version}""")

    suspend fun approveTask(planId: String, ordinal: Int, version: Long): HttpResponse =
        post("/api/selfdev/tasks/$planId-$ordinal/approve", """{"version":$version}""")

    suspend fun rejectTask(planId: String, ordinal: Int, version: Long): HttpResponse =
        post("/api/selfdev/tasks/$planId-$ordinal/reject", """{"version":$version}""")

    suspend fun stop(goalId: String, version: Long, reason: String): HttpResponse = post(
        "/api/selfdev/goals/$goalId/stop",
        buildJsonObject { put("version", version); put("reason", reason) }.toString(),
    )

    suspend fun pause(): HttpResponse = post("/api/selfdev/autopilot/pause", "{}")
    suspend fun resume(): HttpResponse = post("/api/selfdev/autopilot/resume", "{}")
    suspend fun resetBreaker(): HttpResponse = post("/api/selfdev/autopilot/reset-breaker", "{}")

    suspend fun status(): HttpResponse = client.get("/api/selfdev/autopilot") { header(AUTH_HEADER, E2E_OPERATOR) }

    private suspend fun post(path: String, json: String): HttpResponse = client.post(path) {
        header(AUTH_HEADER, E2E_OPERATOR)
        contentType(ContentType.Application.Json)
        setBody(json)
    }
}

/** An operator is the caller that presents a principal header. No header is unauthenticated. */
internal object E2eOperatorAuth : OperatorAuthenticator {
    override suspend fun authenticate(headers: Headers): OperatorAuth = when (val p = headers[AUTH_HEADER]) {
        null, "" -> OperatorAuth.Unauthenticated
        else -> OperatorAuth.Authenticated(p)
    }
}

/** Installs the operator routes over [world] with the autopilot settings [settings] (disabled answers 503, as in production). */
internal fun ApplicationTestBuilder.operatorApp(world: E2eWorld, settings: AutopilotSettings) {
    val deps = SelfDevGoalRouteDeps(
        operators = E2eOperatorAuth,
        autopilot = settings,
        targetRepo = E2E_REPO,
        goals = world.goals,
        plans = world.plans,
        operations = SelfDevOperatorRepository(world.db.database),
        control = world.control,
        memory = world.memory,
    )
    application {
        install(ContentNegotiation) { json() }
        routing { configureSelfDevGoalRoutes(deps) }
    }
}

/** The goal id from a 202 or 200 response of POST /api/selfdev/goals. Fails the test on any other status. */
internal suspend fun HttpResponse.goalIdOrFail(): String {
    assertEquals(HttpStatusCode.Accepted, status, bodyAsText())
    return Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("goalId").jsonPrimitive.content
}

/** Fails the test unless the response is 200 OK. */
internal fun HttpResponse.assertOk(): HttpResponse {
    assertEquals(HttpStatusCode.OK, status)
    return this
}

internal fun List<SelfDevPlanTask>.states(): List<TaskState> = map { it.state }

/** Submits a goal straight to the repository (no HTTP) and returns its id. For the tests whose subject is not the operator API. */
internal fun E2eWorld.submitDirect(key: String, title: String = "Goal $key", body: String? = null): String =
    (goals.submit(E2E_OPERATOR, key, title, body, E2E_REPO) as SubmitResult.Created).goal.goalId

/** Approves the plan of [goalId] and every task of it, straight through the operator repository (the same writes as the HTTP path). */
internal fun E2eWorld.approveDirect(goalId: String) {
    val operations = SelfDevOperatorRepository(db.database)
    assertIs<Transition.Moved<SelfDevGoal>>(operations.approvePlan(goalId, goal(goalId).version, E2E_OPERATOR))
    val planId = planId(goalId)
    for (task in tasks(goalId)) {
        assertIs<Transition.Moved<SelfDevPlanTask>>(
            operations.approveTask(planId, task.ordinal, task(goalId, task.ordinal).version, E2E_OPERATOR),
        )
    }
}
