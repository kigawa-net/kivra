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
    }
}

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
            UNKNOWN -> setOf(PROVISIONING, REQUESTED)
            RELEASED -> emptySet()
        }
}

/**
 * Operation Journal entry (design #11 section 4.1), fail-closed for unknown external results.
 *
 * After a timeout / connection loss / crash the external side effect may already have happened, so an
 * `UNKNOWN` entry must never be re-dispatched on a guess. Its only evidence-free exit is
 * `RECONCILIATION_REQUIRED`. Every way back to a settled state (CONFIRMED / RETRYABLE / FAILED) is listed in
 * [evidenceGated] and is only possible with a persisted [ReconciliationEvidence] (observed external state).
 */
enum class OperationStatus : StateMachine<OperationStatus> {
    PREPARED, IN_FLIGHT, UNKNOWN, RETRYABLE, RECONCILIATION_REQUIRED, CONFIRMED, FAILED;

    /** Transitions allowed without evidence. */
    override val next: Set<OperationStatus>
        get() = when (this) {
            PREPARED -> setOf(IN_FLIGHT)
            IN_FLIGHT -> setOf(CONFIRMED, FAILED, RETRYABLE, UNKNOWN)
            UNKNOWN -> setOf(RECONCILIATION_REQUIRED)
            RETRYABLE -> setOf(IN_FLIGHT, FAILED)
            RECONCILIATION_REQUIRED -> emptySet()
            CONFIRMED, FAILED -> emptySet()
        }

    /** Transitions that additionally require [ReconciliationEvidence] (design #11 section 4.1: "照合で..."). */
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
}

/**
 * Observation of the real external state, persisted as the reason of a transition out of UNKNOWN /
 * RECONCILIATION_REQUIRED. [evidenceRef] points at the artifact (response id, log line, ticket);
 * [observedAt] must be at or after the moment the operation became UNKNOWN.
 */
data class ReconciliationEvidence(
    val observation: ExternalObservation,
    val evidenceRef: String,
    val observedAt: java.time.LocalDateTime,
    val providerCapability: ProviderCapability,
) {
    init {
        require(evidenceRef.isNotBlank()) { "evidenceRef must not be blank" }
        require(evidenceRef.length <= 255) { "evidenceRef is too long" }
    }

    /** Whether this evidence justifies moving an UNKNOWN / RECONCILIATION_REQUIRED entry to [to]. */
    fun justifies(to: OperationStatus): Boolean = when (to) {
        OperationStatus.CONFIRMED -> observation == ExternalObservation.EXISTS
        OperationStatus.FAILED -> observation == ExternalObservation.FAILED_CONFIRMED
        // Re-dispatch only if the system says "absent" AND a replay is safe or verifiable.
        OperationStatus.RETRYABLE -> observation == ExternalObservation.ABSENT &&
            providerCapability != ProviderCapability.MANUAL_OPERATOR
        else -> false
    }
}

/** Transactional Outbox entry status (design #10 section 4 / #11 section 5.2). */
enum class OutboxStatus { PENDING, PUBLISHED, DEAD }
