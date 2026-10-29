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
import net.kigawa.kivra.db.TaskClaimRefusal
import net.kigawa.kivra.db.TaskClaimResult
import net.kigawa.kivra.db.TaskCommitResult
import net.kigawa.kivra.db.TaskClaim

import net.kigawa.kivra.db.NewTaskPlan
import net.kigawa.kivra.db.SelfDevTaskRepository
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
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import net.kigawa.kivra.selfdev.memory.SuppressShadow
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

/**
 * The requestId of one call of a claim. The budget key starts with `planner-<taskId>-` (read by SelfDevCycleSpendReader, pinned by
 * SelfDevCycleSpendContractTest).
 */
internal fun plannerRequestId(taskId: String, version: Long, attempt: Int): String = "planner-$taskId-$version-$attempt"

/** What happened to one goal in one claim. Fixed codes; no text. */
sealed interface PlanAttempt {
    /** The claim was not granted. [reason] is the repository's fixed code. */
    data class NotClaimed(val reason: TaskClaimRefusal) : PlanAttempt
    data class Proposed(val taskCount: Int, val queuedCount: Int) : PlanAttempt
    data class Blocked(val reason: AutopilotReason) : PlanAttempt
    /** The fence no longer matched (the claim expired, or the goal moved). Nothing was written. */
    data object Discarded : PlanAttempt
}

/**
 * Plans one GOAL_SUBMITTED goal (ADR 0011 section 3). Claims it, asks the model once, checks the answer, and retries the whole call
 * once when the answer is invalid. A plan is written only with the claim's fence. Budget refusals and unknown costs block the goal as
 * PLAN_BUDGET_HOLD; every other failure blocks it as PLAN_CALL_FAILED or PLAN_INVALID. An operator returns a blocked goal to planning
 * (U-16, [SelfDevTaskRepository.recoverBlockedPlan]).
 *
 * Logging (ADR 0011 section 7): IDs, fixed codes, counts, costs and a digest prefix only. Never the goal text, the plan, the paths, the
 * model's output, or an exception message.
 */
class AutopilotPlanner(
    private val goals: SelfDevTaskRepository,
    private val gateway: ModelGateway,
    private val config: PlannerConfig,
    private val nonceSource: () -> String = PlannerPrompt::newNonce,
    /** The repository file list (Issue #293). Null when it is disabled: the prompts are then the ones used before. */
    private val repoFiles: PlannerRepoFiles? = null,
    /** The shadow record of the self-development memory (ADR 0016 P1). Written after the plan or the block is stored. Never read. */
    private val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
    /** The suppression shadow (ADR 0016 P3). Counts after a plan is stored; never changes the plan. */
    private val suppress: SuppressShadow = SuppressShadow.OFF,
) {

    suspend fun planGoal(taskId: String): PlanAttempt {
        val claimed = when (val result = goals.claim(taskId, config.claimTtl)) {
            is TaskClaimResult.Refused -> {
                logger.info("planner skipped: taskId={} reason={}", taskId, result.reason.name)
                return PlanAttempt.NotClaimed(result.reason)
            }
            is TaskClaimResult.Claimed -> result.task
        }
        val fence = TaskClaim(taskId, claimed.version, checkNotNull(claimed.claimToken) { "a claimed task has a claim token" })
        logger.info("planner claimed: taskId={}", taskId)

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
                logCallFailure(taskId, attempt, e.error)
                return block(fence, if (isBudgetRefusal(e.error)) AutopilotReason.PLAN_BUDGET_HOLD else AutopilotReason.PLAN_CALL_FAILED)
            } catch (e: Exception) {
                // Not a classified gateway error: the call may have been sent. Never re-sent here.
                logger.warn("planner call failed: taskId={} attempt={} exception={}", taskId, attempt, e.javaClass.simpleName)
                return block(fence, AutopilotReason.PLAN_CALL_FAILED)
            }

            val text = response.message.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            val digest = sha256Hex(text)
            when (val check = PlanValidator.validate(text, config.autopilot)) {
                is PlanCheck.Valid -> return commit(taskId, fence, response, digest, check)
                is PlanCheck.Invalid -> {
                    logger.warn(
                        "planner output invalid: taskId={} attempt={} reason={} outputSha256={} finishReason={}",
                        taskId, attempt, check.reason.name, digest.take(DIGEST_PREFIX), response.finishReason.name,
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

    private fun commit(taskId: String, fence: TaskClaim, response: ModelResponse, digest: String, check: PlanCheck.Valid): PlanAttempt {
        val result = goals.commitPlan(
            fence,
            NewTaskPlan(
                summary = check.summary,
                plannerModel = response.selected.modelId,
                outputSha256 = digest,
                requestLedgerId = response.requestId,
            ),
        )
        return when (result) {
            is TaskCommitResult.StaleClaim -> {
                logger.warn("planner result discarded: taskId={} reason=PLAN_DISCARDED_STALE", taskId)
                PlanAttempt.Discarded
            }
            is TaskCommitResult.Committed -> {
                val taskCount = check.tasks.size
                val queued = check.tasks.count { !it.requiresApproval }
                logger.info(
                    "planner proposed: taskId={} revision={} tasks={} queued={} outputSha256={} cost={} currency={}",
                    taskId, result.task.revision, taskCount, queued,
                    digest.take(DIGEST_PREFIX), response.cost.amount.toPlainString(), response.cost.currency,
                )
                memory.planProposed(taskId, taskCount)
                PlanAttempt.Proposed(taskCount, queued)
            }
        }
    }

    /** Blocks the goal with a planning-stage reason, fenced by the claim. */
    private fun block(fence: TaskClaim, reason: AutopilotReason): PlanAttempt {
        return if (goals.blockPlanning(fence, reason)) {
            logger.warn("planner blocked: taskId={} reason={}", fence.taskId, reason.name)
            memory.planBlocked(fence.taskId, reason)
            PlanAttempt.Blocked(reason)
        } else {
            logger.warn("planner result discarded: taskId={} reason=PLAN_DISCARDED_STALE", fence.taskId)
            PlanAttempt.Discarded
        }
    }

    private fun request(
        fence: TaskClaim,
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
            requestId = plannerRequestId(fence.taskId, fence.version, attempt),
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

    private fun logCallFailure(taskId: String, attempt: Int, error: GatewayError) {
        logger.warn(
            "planner call failed: taskId={} attempt={} errorKind={} errorCode={} sendState={}",
            taskId, attempt, error.kind.name, safeCode(error.code), error.sendState.name,
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
    private val goals: SelfDevTaskRepository,
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
        val expired = listOrEmpty("expired claims") { goals.expiredClaimTaskIds(batch) }
        for (taskId in expired) {
            try {
                if (goals.expireClaim(taskId)) logger.info("planner claim expired: taskId={} reason=CLAIM_EXPIRED", taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("planner claim reset failed: taskId={} exception={}", taskId, e.javaClass.simpleName)
            }
        }
        val waiting = listOrEmpty("waiting goals") { goals.submittedTaskIds(batch) }
        for (taskId in waiting) {
            try {
                planner.planGoal(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("planner step failed: taskId={} exception={}", taskId, e.javaClass.simpleName)
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
