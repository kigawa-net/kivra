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

/** TaskRun (design #1 section 5.1). */
enum class TaskRunState : StateMachine<TaskRunState> {
    ACCEPTED, PLANNING, RUNNING, WAITING_APPROVAL, RETRY_WAITING, RECONCILING, SUCCEEDED, FAILED, CANCELLED;

    override val next: Set<TaskRunState>
        get() = when (this) {
            ACCEPTED -> setOf(PLANNING, CANCELLED, FAILED)
            PLANNING -> setOf(RUNNING, WAITING_APPROVAL, CANCELLED, FAILED)
            RUNNING -> setOf(WAITING_APPROVAL, RETRY_WAITING, RECONCILING, SUCCEEDED, FAILED, CANCELLED)
            WAITING_APPROVAL -> setOf(RUNNING, CANCELLED)
            RETRY_WAITING -> setOf(RUNNING, FAILED, CANCELLED)
            RECONCILING -> setOf(RUNNING, FAILED)
            SUCCEEDED, FAILED, CANCELLED -> emptySet()
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

/** Operation Journal entry (design #11 section 4.1). */
enum class OperationStatus : StateMachine<OperationStatus> {
    PREPARED, IN_FLIGHT, UNKNOWN, RETRYABLE, RECONCILIATION_REQUIRED, CONFIRMED, FAILED;

    override val next: Set<OperationStatus>
        get() = when (this) {
            PREPARED -> setOf(IN_FLIGHT)
            IN_FLIGHT -> setOf(CONFIRMED, FAILED, RETRYABLE, UNKNOWN)
            UNKNOWN -> setOf(CONFIRMED, RETRYABLE, FAILED, RECONCILIATION_REQUIRED)
            RETRYABLE -> setOf(IN_FLIGHT, FAILED)
            RECONCILIATION_REQUIRED -> setOf(CONFIRMED, FAILED)
            CONFIRMED, FAILED -> emptySet()
        }
}

/** Transactional Outbox entry status (design #10 section 4 / #11 section 5.2). */
enum class OutboxStatus { PENDING, PUBLISHED, DEAD }
