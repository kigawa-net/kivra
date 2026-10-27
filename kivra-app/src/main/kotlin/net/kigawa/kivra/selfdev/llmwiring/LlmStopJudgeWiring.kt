package net.kigawa.kivra.selfdev.llmwiring

import io.ktor.client.HttpClient
import java.time.Clock
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.modelgateway.AnthropicAuth
import net.kigawa.kivra.selfdev.AnthropicHandle
import net.kigawa.kivra.selfdev.llm.AnthropicStopJudgeClient
import net.kigawa.kivra.selfdev.llm.JudgeCircuitBreaker
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalMode
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalSettings
import net.kigawa.kivra.selfdev.llm.StopJudgeClient
import net.kigawa.kivra.selfdev.llm.StopJudgeClientConfig
import net.kigawa.kivra.selfdev.llm.StopJudgeLimiter
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/** The fixed codes of the start-up warnings. */
object LlmStopWiringCode {
    const val MODE_ACTIVE_NOT_AVAILABLE = "MODE_ACTIVE_NOT_AVAILABLE"
    const val ROLES_MODE_NOT_ENFORCE = "ROLES_MODE_NOT_ENFORCE"
    const val PREREQUISITE_MISSING = "PREREQUISITE_MISSING"
}

/** Reads the orders through [SelfDevOrderRepository]. Only the two reads: there is no approval here. */
private class DbStopJudgeOrders(private val repo: SelfDevOrderRepository) : StopJudgeOrders {
    override fun stopRequest(requestId: String): SelfDevOrderStopRequest? = repo.stopRequest(requestId)
    override fun order(orderId: String): SelfDevOrder? = repo.get(orderId)
}

private val wiringLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.llmwiring.LlmStopJudgeWiring")

/**
 * Builds the shadow judgement of the LLM stop approval (ADR 0021 L4). The settings are read here and nowhere else.
 *
 * Disabled (the flag is not exactly true): returns null at once. No other variable is read, and no client, store or job is created.
 * Enabled: builds the client (with the existing Anthropic credential), the DB limits store and the job, and installs the listener that
 * nudges the job after a stop request is committed. Returns null, with a warning code, when the orders or the self-development are off
 * (there is nothing to judge, or no credential). Mode `active` runs as shadow in L4: the warning code is written once, here.
 * A roles mode other than enforce is warned once, and the job records SKIPPED for every request.
 *
 * [clientOverride] is for tests, to use a fake client instead of the Anthropic one.
 */
internal fun buildLlmStopJudge(
    env: Map<String, String>,
    database: Database,
    orders: SelfDevOrderRepository?,
    anthropic: AnthropicHandle?,
    rolesMode: PermissionMode,
    clock: Clock = Clock.systemUTC(),
    clientOverride: StopJudgeClient? = null,
): LlmStopJudgeJob? {
    val settings = LlmStopApprovalSettings.fromEnv(env)
    if (!settings.enabled) return null
    if (orders == null || (anthropic == null && clientOverride == null)) {
        wiringLog.warn("llm stop judge: not started code={}", LlmStopWiringCode.PREREQUISITE_MISSING)
        return null
    }
    if (settings.mode == LlmStopApprovalMode.ACTIVE) {
        wiringLog.warn("llm stop judge: running as shadow code={}", LlmStopWiringCode.MODE_ACTIVE_NOT_AVAILABLE)
    }
    if (rolesMode != PermissionMode.ENFORCE) {
        wiringLog.warn("llm stop judge: every request is skipped code={}", LlmStopWiringCode.ROLES_MODE_NOT_ENFORCE)
    }
    val client = clientOverride ?: judgeClient(settings, checkNotNull(anthropic).http, checkNotNull(anthropic).auth, clock)
    val assignees = SelfDevAssigneeRepository(database)
    val job = LlmStopJudgeJob(
        settings = settings,
        rolesMode = rolesMode,
        orders = DbStopJudgeOrders(orders),
        decisions = DbLlmStopDecisionStore(database, clock),
        limiter = StopJudgeLimiter(settings.limits, DbLimitsStore(database), clock),
        client = client,
        hasActiveApprover = { assignees.hasActiveAiApprover() },
        clock = clock,
    )
    orders.setStopRequestListener { requestId -> job.nudge(requestId) }
    return job
}

private fun judgeClient(settings: LlmStopApprovalSettings, http: HttpClient, auth: AnthropicAuth, clock: Clock): StopJudgeClient =
    AnthropicStopJudgeClient(
        client = http,
        auth = auth,
        config = StopJudgeClientConfig(model = checkNotNull(settings.model), maxOutputTokens = settings.limits.maxOutputTokens),
        breaker = JudgeCircuitBreaker(clock),
    )
