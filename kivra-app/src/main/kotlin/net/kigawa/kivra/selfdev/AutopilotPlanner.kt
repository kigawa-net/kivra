package net.kigawa.kivra.selfdev

import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ClaimRefusal
import net.kigawa.kivra.db.ClaimResult
import net.kigawa.kivra.db.CommitResult
import net.kigawa.kivra.db.GoalClaim
import net.kigawa.kivra.db.NewPlan
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.ErrorKind
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.GatewayError
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.GenerationParams
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.Purpose
import net.kigawa.kivra.modelgateway.Requirements
import net.kigawa.kivra.modelgateway.ResponseFormat
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.ThinkingMode
import kotlin.time.toKotlinDuration
import org.slf4j.LoggerFactory

/** The model the Planner is pinned to (ADR 0011 section 3). It must be in the registry, active, at startup. */
const val PLANNER_MODEL_ID = "claude-haiku-5-5"

/** The principal the Planner's model calls are made under. Not a user identity. */
const val SELF_DEV_PLANNER_PRINCIPAL = "selfdev-planner"

/**
 * Trusted settings of the Planner, injected at wiring time. Every value is fixed in code or set by the operator's environment. The
 * claim lasts longer than the calls it covers ([callTimeout] per attempt, at most [MAX_ATTEMPTS] attempts), so a healthy Planner is not
 * fenced out by its own expiry.
 */
data class PlannerConfig(
    val budgetScopes: List<String>,
    val autopilot: AutopilotConfig,
    val principal: String = SELF_DEV_PLANNER_PRINCIPAL,
    val dataClass: DataClass = DataClass.INTERNAL,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val callTimeout: Duration = Duration.ofSeconds(120),
    val claimTtl: Duration = Duration.ofSeconds(600),
) {
    init {
        require(budgetScopes.isNotEmpty()) { "budgetScopes must not be empty" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(!callTimeout.isNegative && !callTimeout.isZero) { "callTimeout must be positive" }
        require(claimTtl >= callTimeout.multipliedBy(3)) { "claimTtl must cover the calls it fences" }
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        const val MAX_ATTEMPTS = 2
    }
}

/** What happened to one goal in one claim. Fixed codes; no text. */
sealed interface PlanAttempt {
    /** The claim was not granted. [reason] is the repository's fixed code. */
    data class NotClaimed(val reason: ClaimRefusal) : PlanAttempt
    data class Proposed(val taskCount: Int, val queuedCount: Int) : PlanAttempt
    data class Blocked(val reason: AutopilotReason) : PlanAttempt
    /** The fence no longer matched (the claim expired, or the goal moved). Nothing was written. */
    data object Discarded : PlanAttempt
}

/**
 * Plans one GOAL_SUBMITTED goal (ADR 0011 section 3). Claims it, asks the model once, checks the answer, and retries the whole call
 * once when the answer is invalid. A plan is written only with the claim's fence. Budget refusals and unknown costs block the goal as
 * PLAN_BUDGET_HOLD; every other failure blocks it as PLAN_CALL_FAILED or PLAN_INVALID. An operator returns a blocked goal to planning
 * (U-16, [SelfDevGoalRepository.recoverBlockedPlan]).
 *
 * Logging (ADR 0011 section 7): IDs, fixed codes, counts, costs and a digest prefix only. Never the goal text, the plan, the paths, the
 * model's output, or an exception message.
 */
class AutopilotPlanner(
    private val goals: SelfDevGoalRepository,
    private val gateway: ModelGateway,
    private val config: PlannerConfig,
    private val nonceSource: () -> String = PlannerPrompt::newNonce,
    /** The repository file list (Issue #293). Null when it is disabled: the prompts are then the ones used before. */
    private val repoFiles: PlannerRepoFiles? = null,
) {

    suspend fun planGoal(goalId: String): PlanAttempt {
        val claimed = when (val result = goals.claim(goalId, config.claimTtl)) {
            is ClaimResult.Refused -> {
                logger.info("planner skipped: goalId={} reason={}", goalId, result.reason.name)
                return PlanAttempt.NotClaimed(result.reason)
            }
            is ClaimResult.Claimed -> result.goal
        }
        val fence = GoalClaim(goalId, claimed.version, checkNotNull(claimed.claimToken) { "a claimed goal has a claim token" })
        logger.info("planner claimed: goalId={}", goalId)

        // Read once per goal. A failed read gives null, and the Planner works without the list (never a stop).
        val repoList = repoFiles?.list()
        var rejectedPath: String? = null
        var attempt = 1
        while (true) {
            val response = try {
                gateway.invoke(request(fence, attempt, claimed.title, claimed.body, repoList, rejectedPath))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GatewayException) {
                logCallFailure(goalId, attempt, e.error)
                return block(fence, if (isBudgetRefusal(e.error)) AutopilotReason.PLAN_BUDGET_HOLD else AutopilotReason.PLAN_CALL_FAILED)
            } catch (e: Exception) {
                // Not a classified gateway error: the call may have been sent. Never re-sent here.
                logger.warn("planner call failed: goalId={} attempt={} exception={}", goalId, attempt, e.javaClass.simpleName)
                return block(fence, AutopilotReason.PLAN_CALL_FAILED)
            }

            val text = response.message.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            val digest = sha256Hex(text)
            when (val check = PlanValidator.validate(text, config.autopilot)) {
                is PlanCheck.Valid -> return commit(goalId, fence, response, digest, check)
                is PlanCheck.Invalid -> {
                    logger.warn(
                        "planner output invalid: goalId={} attempt={} reason={} outputSha256={} finishReason={}",
                        goalId, attempt, check.reason.name, digest.take(DIGEST_PREFIX), response.finishReason.name,
                    )
                    // A cut answer would be cut the same way again, so it is not re-sent (as in LlmDeveloper).
                    val retry = attempt < PlannerConfig.MAX_ATTEMPTS && response.finishReason != FinishReason.LENGTH
                    if (!retry) return block(fence, AutopilotReason.PLAN_INVALID)
                    // Only the path refusal is fed back, and only with the file list enabled (Issue #293).
                    rejectedPath = if (repoFiles != null && check.reason == PlanRejectReason.PATH_NOT_ALLOWED) check.path else null
                    attempt++
                }
            }
        }
    }

    private fun commit(goalId: String, fence: GoalClaim, response: ModelResponse, digest: String, check: PlanCheck.Valid): PlanAttempt {
        val result = goals.commitPlan(
            fence,
            NewPlan(
                summary = check.summary,
                plannerModel = response.selected.modelId,
                outputSha256 = digest,
                requestLedgerId = response.requestId,
            ),
            check.tasks,
        )
        return when (result) {
            is CommitResult.StaleClaim -> {
                logger.warn("planner result discarded: goalId={} reason=PLAN_DISCARDED_STALE", goalId)
                PlanAttempt.Discarded
            }
            is CommitResult.Committed -> {
                val queued = result.tasks.count { it.state == TaskState.QUEUED }
                logger.info(
                    "planner proposed: goalId={} planId={} revision={} tasks={} queued={} outputSha256={} cost={} currency={}",
                    goalId, result.plan.planId, result.plan.revision, result.tasks.size, queued,
                    digest.take(DIGEST_PREFIX), response.cost.amount.toPlainString(), response.cost.currency,
                )
                PlanAttempt.Proposed(result.tasks.size, queued)
            }
        }
    }

    /** Blocks the goal with a planning-stage reason, fenced by the claim. */
    private fun block(fence: GoalClaim, reason: AutopilotReason): PlanAttempt {
        return if (goals.blockPlanning(fence, reason)) {
            logger.warn("planner blocked: goalId={} reason={}", fence.goalId, reason.name)
            PlanAttempt.Blocked(reason)
        } else {
            logger.warn("planner result discarded: goalId={} reason=PLAN_DISCARDED_STALE", fence.goalId)
            PlanAttempt.Discarded
        }
    }

    private fun request(
        fence: GoalClaim,
        attempt: Int,
        title: String,
        body: String?,
        repoList: String?,
        rejectedPath: String?,
    ): ModelRequest {
        val nonce = (1..PlannerPrompt.MAX_NONCE_ATTEMPTS)
            .asSequence()
            .map { nonceSource() }
            .firstOrNull { PlannerPrompt.user(title, body, it, repoList) != null }
            ?: throw IllegalStateException("no usable nonce for the planner prompt")
        val user = checkNotNull(PlannerPrompt.user(title, body, nonce, repoList))
        val retryNote = if (rejectedPath != null) {
            PlannerPrompt.RETRY_NOTE + "\n" + PlannerPrompt.pathRetryNote(rejectedPath)
        } else {
            PlannerPrompt.RETRY_NOTE
        }
        return ModelRequest(
            // Unique per claim and attempt: the version changes with every claim, so a retried claim never reuses a request id.
            requestId = "planner-${fence.goalId}-${fence.version}-$attempt",
            principal = config.principal,
            purpose = Purpose.PLANNING,
            messages = buildList {
                add(Message(Role.SYSTEM, PlannerPrompt.system(repoFiles = repoList != null)))
                add(Message(Role.USER, user))
                if (attempt > 1) add(Message(Role.USER, retryNote))
            },
            dataClass = config.dataClass,
            budgetScopes = config.budgetScopes,
            responseFormat = ResponseFormat.Text,
            generation = GenerationParams(
                maxOutputTokens = config.maxOutputTokens,
                // Haiku 5.5 may spend the whole output limit on thinking (see DeveloperConfig.thinking). Thinking stays off.
                thinking = ThinkingMode.DISABLED,
            ),
            requirements = Requirements(
                requiredCapabilities = setOf(Capability.TEXT),
                allowedModelIds = setOf(PLANNER_MODEL_ID),
            ),
            timeout = config.callTimeout.toKotlinDuration(),
        )
    }

    private fun logCallFailure(goalId: String, attempt: Int, error: GatewayError) {
        logger.warn(
            "planner call failed: goalId={} attempt={} errorKind={} errorCode={} sendState={}",
            goalId, attempt, error.kind.name, safeCode(error.code), error.sendState.name,
        )
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val logger = LoggerFactory.getLogger(AutopilotPlanner::class.java)
        const val DIGEST_PREFIX = 12
        val CODE_SHAPE = Regex("[A-Z0-9_]{1,64}")

        /** The budget refusals of the gateway: the call was never sent, so a plan can wait for the budget. */
        val BUDGET_CODES = setOf(
            BudgetDenial.BUDGET,
            BudgetDenial.BUDGET_POLICY_MISSING,
            BudgetDenial.UNKNOWN_COST,
            BudgetDenial.STORE_UNAVAILABLE,
        )

        fun isBudgetRefusal(error: GatewayError): Boolean =
            error.kind == ErrorKind.POLICY_DENIED && error.code in BUDGET_CODES

        fun safeCode(code: String?): String = when {
            code == null -> "none"
            CODE_SHAPE.matches(code) -> code
            else -> "other"
        }
    }
}

/**
 * The periodic loop of the Planner (ADR 0011 section 6, PR B). One [tick] expires stale planning claims, then plans up to [batch] goals
 * waiting in GOAL_SUBMITTED, oldest first. Two workers cannot plan the same goal twice: the claim is the exclusion. A failure is
 * contained to its goal and logged with the exception's class name only. Cancellation is never swallowed.
 */
class PlannerWorker(
    private val goals: SelfDevGoalRepository,
    private val planner: AutopilotPlanner,
    private val interval: Duration,
    private val batch: Int = DEFAULT_BATCH,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(batch > 0) { "batch must be positive" }
    }

    /** One pass. Returns the number of goals that were given a claim attempt. */
    suspend fun tick(): Int {
        val expired = listOrEmpty("expired claims") { goals.expiredClaimGoalIds(batch) }
        for (goalId in expired) {
            try {
                if (goals.expireClaim(goalId)) logger.info("planner claim expired: goalId={} reason=CLAIM_EXPIRED", goalId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("planner claim reset failed: goalId={} exception={}", goalId, e.javaClass.simpleName)
            }
        }
        val waiting = listOrEmpty("waiting goals") { goals.submittedGoalIds(batch) }
        for (goalId in waiting) {
            try {
                planner.planGoal(goalId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("planner step failed: goalId={} exception={}", goalId, e.javaClass.simpleName)
            }
        }
        return waiting.size
    }

    /** Starts the loop in [scope]. It stops when the scope is cancelled (application stop). */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            tick()
            delay(interval.toMillis())
        }
    }

    private fun listOrEmpty(what: String, read: () -> List<String>): List<String> = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("planner listing failed: what={} exception={}", what, e.javaClass.simpleName)
        emptyList()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PlannerWorker::class.java)
        const val DEFAULT_BATCH = 5
    }
}
