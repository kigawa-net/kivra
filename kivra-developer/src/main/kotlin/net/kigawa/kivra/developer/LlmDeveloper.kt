package net.kigawa.kivra.developer

import kotlinx.coroutines.CancellationException
import net.kigawa.kivra.core.patch.ParseResult
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.PatchSetParser
import net.kigawa.kivra.core.patch.PatchVerdict
import net.kigawa.kivra.core.patch.RepoSnapshot
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.ErrorKind
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
import net.kigawa.kivra.modelgateway.RoutingPreference
import net.kigawa.kivra.modelgateway.Usage
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [DeveloperPort] backed by [ModelGateway]. Each proposal makes at most two provider calls (the second only when the
 * first answer is not a PatchSet). Every call has its own requestId, derived from the runId and the attempt number.
 *
 * Safety layers, in order:
 * 1. Limits on the task and on the repository context. Oversize input is refused, never truncated.
 * 2. Untrusted text is wrapped in nonce-tagged data elements (see [DeveloperPrompt]). This is a soft defence only.
 * 3. The answer is parsed strictly by [PatchSetParser]. Only an unparseable answer is retried, once.
 * 4. [PatchPolicy] validates the parsed PatchSet against the trusted constraints and the repository context. This is
 *    the hard defence. The prompt cannot make a policy violation pass, and a rejected PatchSet is never retried.
 * 5. Budget and idempotency are the gateway's job (reserve before send, fail closed on unknown price, ledger keyed by
 *    requestId). A call whose outcome is unknown is never re-sent.
 *
 * Model choice: the gateway routes by [DeveloperConfig.requirements] and [DeveloperConfig.preference]. It has no way
 * to pin one model id, so the model is not hard-coded here.
 *
 * Logging: this class logs nothing. Reason codes are the only output. Prompts, model output, task text, and API keys
 * never appear in an exception or a log line.
 */
class LlmDeveloper(
    private val gateway: ModelGateway,
    private val config: DeveloperConfig,
    private val nonceSource: () -> String = DeveloperPrompt::newNonce,
) : DeveloperPort {

    override suspend fun propose(request: DeveloperRequest): DeveloperResult {
        if (!withinLimits(request)) return DeveloperResult.Refused(DeveloperRefusal.CONTEXT_TOO_LARGE)
        val userMessage = freshUserMessage(request) ?: return DeveloperResult.Refused(DeveloperRefusal.PROMPT_UNSAFE)

        val system = DeveloperPrompt.system(request.constraints)
        val policy = PatchPolicy(
            PatchPolicyConfig(
                allowedPathPatterns = request.constraints.allowedPathPatterns,
                deniedPathPatterns = request.constraints.deniedPathPatterns,
                maxFiles = request.constraints.maxFiles,
            ),
        )
        val snapshot = ContextSnapshot(request.repoContext)

        val costs = ArrayList<Cost>()
        var usage = Usage.ZERO
        var attempt = 1
        while (true) {
            val messages = buildList {
                add(Message(Role.SYSTEM, system))
                add(Message(Role.USER, userMessage))
                if (attempt > 1) add(Message(Role.USER, DeveloperPrompt.RETRY_NOTE))
            }
            val response = try {
                gateway.invoke(modelRequest(request, attempt, messages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GatewayException) {
                return mapGatewayError(e.error)
            } catch (e: Exception) {
                // Not a classified gateway error: the call may have been sent. Do not re-send.
                return DeveloperResult.Unknown(DeveloperUnknown.OUTCOME_UNKNOWN)
            }
            usage = usage.plus(response.usage)
            costs.add(response.cost)

            when (val parsed = PatchSetParser.parse(text(response))) {
                is ParseResult.Invalid -> {
                    if (attempt >= MAX_ATTEMPTS) return DeveloperResult.Refused(DeveloperRefusal.INVALID_OUTPUT)
                    attempt++
                }

                is ParseResult.Parsed -> return when (val verdict = policy.validate(parsed.patch, snapshot)) {
                    is PatchVerdict.Accepted -> DeveloperResult.Proposed(
                        verdict.patch,
                        DeveloperUsage(attempts = attempt, usage = usage, costs = costs.toList()),
                    )

                    is PatchVerdict.Rejected -> DeveloperResult.Refused(DeveloperRefusal.POLICY_REJECTED, verdict.reasons)
                }
            }
        }
    }

    /** A user message whose nonce does not occur in the data. Draws a new nonce up to [DeveloperPrompt.MAX_NONCE_ATTEMPTS] times. */
    private fun freshUserMessage(request: DeveloperRequest): String? {
        repeat(DeveloperPrompt.MAX_NONCE_ATTEMPTS) {
            DeveloperPrompt.user(request, nonceSource())?.let { return it }
        }
        return null
    }

    private fun withinLimits(request: DeveloperRequest): Boolean {
        if (request.taskTitle.length > DeveloperPrompt.MAX_TITLE_CHARS) return false
        if (request.taskDescription.length > DeveloperPrompt.MAX_DESCRIPTION_CHARS) return false
        if (request.repoContext.size > DeveloperPrompt.MAX_CONTEXT_FILES) return false
        var bytes = utf8Size(request.taskTitle) + utf8Size(request.taskDescription)
        for (file in request.repoContext) bytes += utf8Size(file.path) + utf8Size(file.content)
        return bytes <= config.maxContextBytes
    }

    private fun modelRequest(request: DeveloperRequest, attempt: Int, messages: List<Message>) = ModelRequest(
        requestId = "developer-${request.runId}-$attempt",
        principal = config.principal,
        purpose = Purpose.CODING,
        messages = messages,
        dataClass = config.dataClass,
        budgetScopes = config.budgetScopes,
        responseFormat = ResponseFormat.Text,
        generation = GenerationParams(maxOutputTokens = config.maxOutputTokens, temperature = config.temperature),
        requirements = config.requirements,
        preference = config.preference,
        timeout = config.timeout,
    )

    private fun text(response: ModelResponse): String =
        response.message.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun mapGatewayError(error: GatewayError): DeveloperResult = when {
        error.kind == ErrorKind.OUTCOME_UNKNOWN -> DeveloperResult.Unknown(DeveloperUnknown.OUTCOME_UNKNOWN)
        error.kind == ErrorKind.CANCELLED -> DeveloperResult.Unknown(DeveloperUnknown.OUTCOME_UNKNOWN)
        error.kind == ErrorKind.TIMEOUT -> DeveloperResult.Unknown(DeveloperUnknown.TIMEOUT)
        error.kind == ErrorKind.POLICY_DENIED && error.code in BUDGET_CODES ->
            DeveloperResult.Refused(DeveloperRefusal.BUDGET)

        error.kind == ErrorKind.POLICY_DENIED && error.code == "DUPLICATE_REQUEST" ->
            DeveloperResult.Unknown(DeveloperUnknown.DUPLICATE_REQUEST)

        error.kind == ErrorKind.POLICY_DENIED && error.code == "NO_ROUTE" -> DeveloperResult.Refused(DeveloperRefusal.NO_ROUTE)
        error.kind == ErrorKind.CAPABILITY_UNSUPPORTED -> DeveloperResult.Refused(DeveloperRefusal.NO_ROUTE)
        error.kind == ErrorKind.POLICY_DENIED -> DeveloperResult.Refused(DeveloperRefusal.PROVIDER_REFUSED)
        else -> DeveloperResult.Refused(DeveloperRefusal.PROVIDER_FAILURE)
    }

    /** Usage summed over attempts. Costs are kept per call, because currencies may differ. */
    private fun Usage.plus(other: Usage) = Usage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
    )

    /** Read-only view of the repository context. A path not in the context is treated as missing. */
    private class ContextSnapshot(files: List<RepoFile>) : RepoSnapshot {
        private val byPath: Map<String, RepoFile> = files.associateBy { it.path }
        override fun exists(path: String): Boolean = path in byPath
        override fun size(path: String): Long? = byPath[path]?.let { utf8Size(it.content).toLong() }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private val BUDGET_CODES = setOf(
            BudgetDenial.BUDGET,
            BudgetDenial.BUDGET_POLICY_MISSING,
            BudgetDenial.UNKNOWN_COST,
            BudgetDenial.STORE_UNAVAILABLE,
        )

        private fun utf8Size(text: String): Long = text.toByteArray(StandardCharsets.UTF_8).size.toLong()
    }
}

/**
 * Trusted configuration, injected at wiring time. There is no default model, principal, or data class: each must be
 * given (fail closed). [dataClass] must be the authentic classification set by the trusted orchestrator.
 */
data class DeveloperConfig(
    val principal: String,
    val dataClass: DataClass,
    val budgetScopes: List<String>,
    val requirements: Requirements,
    val maxOutputTokens: Int,
    val temperature: Double = 0.0,
    val preference: RoutingPreference = RoutingPreference(),
    val timeout: Duration = 120.seconds,
    /** Upper bound on the UTF-8 size of the task and the repository context together. Input over it is refused. */
    val maxContextBytes: Long = DEFAULT_MAX_CONTEXT_BYTES,
) {
    init {
        require(principal.isNotBlank()) { "principal must not be blank" }
        require(budgetScopes.isNotEmpty()) { "budgetScopes must not be empty" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(maxContextBytes > 0) { "maxContextBytes must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_CONTEXT_BYTES: Long = 200L * 1024
    }
}
