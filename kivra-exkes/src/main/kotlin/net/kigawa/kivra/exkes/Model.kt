package net.kigawa.kivra.exkes

/*
 * Concrete value types of the ExecutionPort. Only facts verified against exkes origin/main
 * (exkes-api routes + exkes-common domain, checked 2026-10-12) are modelled; anything else is
 * marked "要確認" and listed in docs/exkes-requests.md.
 *
 * Identifiers carry their parent id because exkes addresses children through the parent:
 * GET /v1/workspaces/{ws}/runtimes/{id}, GET /v1/runtimes/{rt}/executions/{id}.
 */

@JvmInline
value class WorkspaceId(val value: String)

data class RuntimeId(val workspaceId: WorkspaceId, val value: String)

data class ExecutionId(val runtimeId: RuntimeId, val value: String)

/** exkes WorkspaceStatus. [UNRECOGNIZED] = a state name this client does not know (newer exkes). */
enum class WorkspaceState {
    CREATING, READY, ARCHIVED, DELETING, DELETED, ERROR, UNRECOGNIZED;

    companion object {
        fun parse(raw: String): WorkspaceState = entries.firstOrNull { it.name == raw } ?: UNRECOGNIZED
    }
}

/** exkes RuntimeStatus. */
enum class RuntimeState {
    CREATING, STARTING, RUNNING, STOPPING, STOPPED, FAILED, DELETING, DELETED, EXPIRED, UNRECOGNIZED;

    companion object {
        fun parse(raw: String): RuntimeState = entries.firstOrNull { it.name == raw } ?: UNRECOGNIZED
    }
}

/** exkes ExecutionStatus (QUEUED -> STARTING -> RUNNING -> final). */
enum class ExecutionState(val isFinal: Boolean) {
    QUEUED(false), STARTING(false), RUNNING(false),
    SUCCEEDED(true), FAILED(true), CANCELLED(true), TIMED_OUT(true),
    UNRECOGNIZED(false);

    companion object {
        fun parse(raw: String): ExecutionState = entries.firstOrNull { it.name == raw } ?: UNRECOGNIZED
    }
}

/** exkes ExecutionType. */
enum class ExecutionType { COMMAND, AGENT, SYSTEM }

data class WorkspaceSpec(
    val name: String,
    val labels: Map<String, String> = emptyMap(),
    val storageSizeBytes: Long? = null,
)

/** [templateId] is mandatory in exkes (CreateRuntimeRequest). Which template Kivra uses is 要確認. */
data class RuntimeSpec(
    val templateId: String,
    val labels: Map<String, String> = emptyMap(),
    val resourcesJson: String? = null,
    val envJson: String? = null,
    val ttlSeconds: Long? = null,
)

/**
 * [singleExecutionGuard]: exkes cannot tag or deduplicate an Execution (no label, no idempotency key),
 * so the only pre-POST check available is "this Runtime already has Executions". With the guard on
 * (default; Kivra's model is one Execution per run-scoped Runtime, 要確認) startExecution refuses to POST
 * when the Runtime already has any Execution and returns [PortResult.Conflict] instead.
 */
data class ExecutionSpec(
    val type: ExecutionType,
    val commandJson: String? = null,
    val agentConfigJson: String? = null,
    val singleExecutionGuard: Boolean = true,
)

data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val state: WorkspaceState,
    val labels: Map<String, String>,
    val errorMessage: String?,
)

data class Runtime(
    val id: RuntimeId,
    val templateId: String,
    val state: RuntimeState,
    val labels: Map<String, String>,
    val errorMessage: String?,
)

data class Execution(
    val id: ExecutionId,
    val type: ExecutionType?,
    val state: ExecutionState,
    val exitCode: Int?,
    val errorMessage: String?,
    /** Object-store key only; exkes has no log read API (see [ExecutionCapabilities.readLogs]). */
    val logObjectKey: String?,
)

@JvmInline
value class LogCursor(val value: String)

data class LogPage(val lines: List<String>, val next: LogCursor?)

/**
 * What the connected exkes can do. Everything that is false is answered with [PortResult.Unsupported]
 * and must never be emulated silently. Values for the HTTP adapter are the verified state of exkes main.
 */
data class ExecutionCapabilities(
    /** Idempotency-Key header / operationId on POST. exkes: absent (so no AUTHORITATIVE_IDEMPOTENCY). */
    val idempotencyKey: Boolean = false,
    /** Server-side filter by workspace name / label. exkes: absent, filtering is client-side. */
    val serverSideFilter: Boolean = false,
    /** Execution cancel API. exkes: absent (only Runtime stop/delete). */
    val cancelExecution: Boolean = false,
    /** Log read API. exkes: absent (Execution only exposes logObjectKey). */
    val readLogs: Boolean = false,
    /** Whether the server enforces authentication. exkes: not implemented (network-restricted only). */
    val authentication: Boolean = false,
)
