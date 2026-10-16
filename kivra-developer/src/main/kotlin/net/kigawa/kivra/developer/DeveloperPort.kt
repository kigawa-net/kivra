package net.kigawa.kivra.developer

import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.RejectReason
import net.kigawa.kivra.core.patch.PatchPolicyDefaults
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.Usage

/*
 * Self-development loop, part 3 (Issue #56, ADR 0008). The Developer turns a task and a slice of the repository into a
 * [PatchSet]. The port is the only thing the runner (part 4) sees. It has no GitHub, DB or Run state dependency.
 *
 * Results carry fixed reason codes only. Task text, repository content and model output never go into a reason,
 * a log line or an exception message.
 */

/** A repository file the Developer may read. [content] is UTF-8 text. [toString] hides the content. */
data class RepoFile(val path: String, val content: String) {
    override fun toString(): String = "RepoFile(path=$path, contentChars=${content.length})"
}

/**
 * Trusted, per-request limits. They are rendered into the system prompt and used to build the [PatchPolicy] that
 * checks the answer. The policy is the enforcement point. The prompt only tells the model what is expected.
 */
data class DeveloperConstraints(
    val maxFiles: Int = 10,
    val allowedPathPatterns: List<String> = PatchPolicyDefaults.ALLOWED_PATH_PATTERNS,
    val deniedPathPatterns: List<String> = PatchPolicyDefaults.DENIED_PATH_PATTERNS,
) {
    init {
        require(maxFiles > 0) { "maxFiles must be positive" }
    }
}

/**
 * Input for one proposal. [taskTitle] and [taskDescription] are untrusted text written by the task author. They are
 * sent to the model as data, never as instructions (see [LlmDeveloper]). [toString] hides the task text.
 */
data class DeveloperRequest(
    val runId: String,
    val taskTitle: String,
    val taskDescription: String,
    val repoContext: List<RepoFile>,
    val constraints: DeveloperConstraints = DeveloperConstraints(),
) {
    override fun toString(): String =
        "DeveloperRequest(runId=$runId, titleChars=${taskTitle.length}, descriptionChars=${taskDescription.length}, " +
            "files=${repoContext.size}, maxFiles=${constraints.maxFiles})"
}

/** Model usage for a proposal, summed over every attempt. [costs] keep the per-call evidence from the gateway. */
data class DeveloperUsage(val attempts: Int, val usage: Usage, val costs: List<Cost>)

sealed interface DeveloperResult {
    /** The patch passed [net.kigawa.kivra.core.patch.PatchPolicy]. Nothing has been written anywhere yet. */
    data class Proposed(val patch: PatchSet, val usage: DeveloperUsage) : DeveloperResult

    /**
     * No patch was produced. The provider may have been called, but nothing was written anywhere. Re-running the
     * same runId is not allowed to pay twice: the gateway ledger refuses it (see [LlmDeveloper]).
     */
    data class Refused(val reason: DeveloperRefusal, val policyReasons: List<RejectReason> = emptyList()) : DeveloperResult

    /**
     * The provider call may have happened and its outcome is not known (lost response, 5xx, timeout). The caller must
     * reconcile it (ADR 0005). The Developer never re-sends it automatically.
     */
    data class Unknown(val reason: DeveloperUnknown) : DeveloperResult
}

/** Fixed refusal codes. None of them carry text. */
enum class DeveloperRefusal {
    /** The model did not return a PatchSet JSON, even after one retry. */
    INVALID_OUTPUT,

    /** The answer was cut at the output limit (finishReason LENGTH) and is not a PatchSet. Not retried: the same request is cut the same way. */
    OUTPUT_TRUNCATED,

    /** [net.kigawa.kivra.core.patch.PatchPolicy] rejected the PatchSet. See `policyReasons`. */
    POLICY_REJECTED,

    /** Budget reservation was denied, or the price is unknown. The provider was not called. */
    BUDGET,

    /** The task or the repository context is over the size limit. The provider was not called. */
    CONTEXT_TOO_LARGE,

    /** The data delimiter could not be made unique against the content. The provider was not called. */
    PROMPT_UNSAFE,

    /** No model satisfies the requirements. The provider was not called. */
    NO_ROUTE,

    /** The gateway refused the request for another reason (for example, a reused requestId). The provider was not called. */
    PROVIDER_REFUSED,

    /** A classified provider failure that is not a send-state unknown (auth, invalid request, content filter, ...). */
    PROVIDER_FAILURE,
}

enum class DeveloperUnknown {
    /** The provider call may have been made and its response was lost or failed (5xx, transport loss, reconciliation). */
    OUTCOME_UNKNOWN,

    /** The request deadline passed after the call may have been sent. */
    TIMEOUT,

    /** A call with the same runId is still in flight, or its outcome is already unknown in the ledger. */
    DUPLICATE_REQUEST,
}

interface DeveloperPort {
    suspend fun propose(request: DeveloperRequest): DeveloperResult
}
