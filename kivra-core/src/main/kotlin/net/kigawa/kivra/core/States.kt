package net.kigawa.kivra.core

/**
 * State machines of the three Kivra-owned hierarchies (design #1 section 5) and of
 * Operation Journal entries (design #11 section 4.1).
 *
 * Terminal states have no outgoing transitions: a retry is a new attempt or a new run.
 */
interface StateMachine<S : Enum<S>> {
    val next: Set<S>
    val isTerminal: Boolean get() = next.isEmpty()
}

fun <S> S.canTransitionTo(to: S): Boolean where S : Enum<S>, S : StateMachine<S> = to in next

/**
 * DevelopmentRun: the single source of truth of Run progress (design #9 section 3.3 / #10 section 3;
 * ADR docs/adr/0001-run-source-of-truth.md). There is no separate task-level run state machine; a fusha-task
 * "task" is only referenced by `task_id` and any task-level view is a projection of this state.
 *
 * Stage mapping (design #9 section 3.3): CREATED/PLANNING = DISCOVER/PLAN, PREPARING/DEVELOPING = DEVELOP,
 * TESTING/REVIEWING = TEST/REVIEW, PULL_REQUEST = PR/CI + MERGE, RELEASING = RELEASE, VERIFYING = VERIFY/LEARN.
 *
 * Rules carried over from design #9 section 3.3: RELEASING never goes to CANCELLED (rollback is requested
 * through DEC, not by cancelling), and a changed `headSha` after PULL_REQUEST sends the run back to TESTING.
 */
enum class DevelopmentRunState : StateMachine<DevelopmentRunState> {
    CREATED, PLANNING, PREPARING, DEVELOPING, TESTING, REVIEWING, PULL_REQUEST, WAITING_APPROVAL,
    RELEASING, VERIFYING, RETRY_WAITING, COMPLETED, FAILED, CANCELLED;

    override val next: Set<DevelopmentRunState>
        get() = when (this) {
            CREATED -> setOf(PLANNING, CANCELLED, FAILED)
            PLANNING -> setOf(PREPARING, RETRY_WAITING, CANCELLED, FAILED)
            PREPARING -> setOf(DEVELOPING, CANCELLED, FAILED)
            DEVELOPING -> setOf(TESTING, RETRY_WAITING, CANCELLED, FAILED)
            TESTING -> setOf(REVIEWING, DEVELOPING, RETRY_WAITING, CANCELLED, FAILED)
            REVIEWING -> setOf(DEVELOPING, PULL_REQUEST, CANCELLED, FAILED)
            PULL_REQUEST -> setOf(WAITING_APPROVAL, RELEASING, TESTING, RETRY_WAITING, CANCELLED, FAILED)
            WAITING_APPROVAL -> setOf(RELEASING, CANCELLED, FAILED)
            RELEASING -> setOf(VERIFYING, FAILED)
            VERIFYING -> setOf(COMPLETED, FAILED)
            RETRY_WAITING -> setOf(PLANNING, DEVELOPING, CANCELLED, FAILED)
            COMPLETED, FAILED, CANCELLED -> emptySet()
        }

    companion object {
        /** Names of terminal states, shared with the `active_flag` generated column in V1. */
        val terminalNames: Set<String> = entries.filter { it.isTerminal }.map { it.name }.toSet()

        /**
         * States from which a PR_ONLY run may complete, and only through `completePrOnlyRun` with verified
         * GitHub merge evidence. Not part of [next]: the generic transition() never reaches COMPLETED from these.
         */
        val prOnlyCompletionSources: Set<DevelopmentRunState> = setOf(PULL_REQUEST, WAITING_APPROVAL)
    }
}

/**
 * How a run ends on success, fixed at Run Admission and never changed afterwards (M8 PR-only vertical slice, ADR 0001).
 * - PR_ONLY: the run ends when a human merges the pull request on GitHub (verified by PrMergeEvidence). No release.
 * - RELEASE: RELEASING -> VERIFYING -> COMPLETED through DEC (design #9 section 3.3).
 */
enum class RunCompletionMode { PR_ONLY, RELEASE }

/** Agent (design #1 section 5.2). */
enum class AgentState : StateMachine<AgentState> {
    CREATED, STARTING, RUNNING, SUSPENDED, STOPPING, TERMINATED, FAILED, COMPLETED;

    override val next: Set<AgentState>
        get() = when (this) {
            CREATED -> setOf(STARTING)
            STARTING -> setOf(RUNNING, FAILED)
            RUNNING -> setOf(SUSPENDED, STOPPING, FAILED, COMPLETED)
            SUSPENDED -> setOf(RUNNING)
            STOPPING -> setOf(TERMINATED)
            TERMINATED, FAILED, COMPLETED -> emptySet()
        }
}

/** ExecutionSession (design #1 section 5.3). The exkes side state is only observed, never decided here. */
enum class ExecutionSessionState : StateMachine<ExecutionSessionState> {
    REQUESTED, PROVISIONING, READY, EXECUTING, RELEASING, RELEASED, ORPHANED, UNKNOWN;

    override val next: Set<ExecutionSessionState>
        get() = when (this) {
            REQUESTED -> setOf(PROVISIONING, UNKNOWN)
            PROVISIONING -> setOf(READY, ORPHANED)
            READY -> setOf(EXECUTING, RELEASING, ORPHANED)
            EXECUTING -> setOf(READY, RELEASING)
            RELEASING -> setOf(RELEASED)
            ORPHANED -> setOf(RELEASING)
            // UNKNOWN has no evidence-free exit. See [leaveUnknownOn].
            UNKNOWN, RELEASED -> emptySet()
        }

    override val isTerminal: Boolean get() = this == RELEASED

    /** Observed exkes-side states that settle an UNKNOWN session. Never REQUESTED: a session is not re-requested blindly. */
    val observedStates: Set<ExecutionSessionState>
        get() = when (this) {
            UNKNOWN -> setOf(PROVISIONING, READY, EXECUTING, RELEASING, RELEASED, ORPHANED)
            else -> emptySet()
        }
}

/**
 * The only way out of UNKNOWN for an ExecutionSession: the state that was actually observed on the exkes side.
 * Returns null when the observation does not settle the session (for example "not found"), so it stays UNKNOWN.
 */
fun ExecutionSessionState.leaveUnknownOn(observed: ExecutionSessionState): ExecutionSessionState? =
    if (observed in observedStates) observed else null

/**
 * Operation Journal entry (design #11 section 4.1), fail-closed for unknown external results.
 *
 * After a timeout / connection loss / crash the external side effect may already have happened, so an
 * `UNKNOWN` entry must never be re-dispatched on a guess. Its only evidence-free exit is
 * `RECONCILIATION_REQUIRED`. Every way back to a settled state (CONFIRMED / RETRYABLE / FAILED) is listed in
 * [evidenceGated] and is only possible with a [VerifiedEvidenceRef] that the [TrustedEvidenceAuthority] resolves for this operation.
 */
enum class OperationStatus : StateMachine<OperationStatus> {
    PREPARED, IN_FLIGHT, UNKNOWN, RETRYABLE, RECONCILIATION_REQUIRED, CONFIRMED, FAILED;

    /**
     * Transitions allowed without evidence.
     *
     * IN_FLIGHT means the external call may have reached the other side, so there is no evidence-free way out except
     * CONFIRMED (the success response) and UNKNOWN (timeout / connection loss / crash). Leaving IN_FLIGHT for RETRYABLE
     * needs a verified NOT_SENT ref and for FAILED a verified REJECTED ref; both are only accepted by the dedicated
     * OperationJournalRepository.markNotSent / markRejected APIs, never by the generic transition().
     */
    override val next: Set<OperationStatus>
        get() = when (this) {
            PREPARED -> setOf(IN_FLIGHT)
            IN_FLIGHT -> setOf(CONFIRMED, UNKNOWN)
            UNKNOWN -> setOf(RECONCILIATION_REQUIRED)
            RETRYABLE -> setOf(IN_FLIGHT, FAILED)
            RECONCILIATION_REQUIRED -> emptySet()
            CONFIRMED, FAILED -> emptySet()
        }

    /** Transitions that additionally require a verified reconciliation ref (design #11 section 4.1: "照合で..."). */
    val evidenceGated: Set<OperationStatus>
        get() = when (this) {
            UNKNOWN -> setOf(CONFIRMED, RETRYABLE, FAILED)
            RECONCILIATION_REQUIRED -> setOf(CONFIRMED, FAILED)
            else -> emptySet()
        }

    override val isTerminal: Boolean get() = next.isEmpty() && evidenceGated.isEmpty()
}

/** What a reconciliation observed on the external system. */
enum class ExternalObservation {
    /** The external resource / effect exists (the original call did take effect). */
    EXISTS,

    /** The external system authoritatively reports that nothing was done. */
    ABSENT,

    /** The external system (or an operator) confirmed that the operation failed for good. */
    FAILED_CONFIRMED,
}

/** What guarantees make a later re-dispatch safe (or the observation authoritative). */
enum class ProviderCapability {
    /** Lookup by the stable reference Kivra chose before the call (design #11 section 4.4). */
    LOOKUP_BY_STABLE_REF,

    /** The external API deduplicates on our idempotency key, so a replay cannot create a second effect. */
    AUTHORITATIVE_IDEMPOTENCY,

    /** A human / repair task decided. Never enough to authorise an automatic re-dispatch. */
    MANUAL_OPERATOR,

    /**
     * The lookup can authoritatively establish that this operation was NOT executed (for example a linearizable
     * read of the final result keyed by the operation's own reference). Only this, or AUTHORITATIVE_IDEMPOTENCY,
     * may justify a re-dispatch. A plain list / get that finds nothing does not qualify (eventual consistency).
     */
    AUTHORITATIVE_ABSENCE,
}

/**
 * Verified GitHub merge result for a PR_ONLY run (`completePrOnlyRun`). Must show `merged == true` for the PR head
 * that the run recorded; a close without merge, a merge of another head, or an unknown result never completes the run.
 */
data class PrMergeEvidence(
    val runId: RunId,
    val issuer: String,
    val repository: String,
    val headSha: String,
    val mergeSha: String?,
    val merged: Boolean,
    val evidenceRef: String,
    val observedAt: java.time.LocalDateTime,
) {
    init {
        requireProofFields(issuer, evidenceRef)
    }
}

private fun requireProofFields(issuer: String, evidenceRef: String) {
    require(issuer.isNotBlank()) { "issuer must not be blank" }
    require(evidenceRef.isNotBlank()) { "evidenceRef must not be blank" }
    require(evidenceRef.length <= 255) { "evidenceRef is too long" }
}

/** Transactional Outbox entry status (design #10 section 4 / #11 section 5.2). */
enum class OutboxStatus { PENDING, PUBLISHED, DEAD }
