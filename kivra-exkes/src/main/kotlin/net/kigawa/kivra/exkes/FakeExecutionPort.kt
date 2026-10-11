package net.kigawa.kivra.exkes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory ExecutionPort for tests of callers (Agent Manager, Reaper) and for the shared contract
 * test. Resources stay in their initial state (CREATING / QUEUED) until the test moves them with
 * [setWorkspaceState] / [setRuntimeState] / [setExecutionState], mimicking exkes' asynchronous 202 model.
 *
 * Fault injection ([injectUnknown]) reproduces "the POST timed out": the call returns
 * [PortResult.Unknown], with the effect applied ([afterEffect] = true, the dangerous case) or not.
 */
class FakeExecutionPort(
    override val capabilities: ExecutionCapabilities = ExecutionCapabilities(),
) : ExecutionPort {
    private val mutex = Mutex()
    private var seq = 0
    private val workspaces = LinkedHashMap<String, Workspace>()
    private val runtimes = LinkedHashMap<String, Runtime>()
    private val executions = LinkedHashMap<String, Execution>()
    private val faults = ArrayDeque<Pair<String, Boolean>>()

    /** Number of POSTs that actually reached the "server" per operation name (not counting lookups). */
    val postCounts = HashMap<String, Int>()

    /** The next mutating call of [operation] (e.g. "createWorkspace") loses its response. */
    fun injectUnknown(operation: String, afterEffect: Boolean) {
        faults.addLast(operation to afterEffect)
    }

    fun setWorkspaceState(id: WorkspaceId, state: WorkspaceState) {
        val w = workspaces.getValue(id.value)
        if (state == WorkspaceState.DELETED) workspaces.remove(id.value) else workspaces[id.value] = w.copy(state = state)
    }

    fun setRuntimeState(id: RuntimeId, state: RuntimeState) {
        runtimes[id.value] = runtimes.getValue(id.value).copy(state = state)
    }

    fun setExecutionState(id: ExecutionId, state: ExecutionState, exitCode: Int? = null) {
        executions[id.value] = executions.getValue(id.value).copy(state = state, exitCode = exitCode)
    }

    private fun nextId(prefix: String) = "$prefix-${++seq}"

    /** Returns Unknown if a fault is armed for [op]; [apply] runs the effect when it should happen. */
    private inline fun <T> mutate(op: String, apply: () -> PortResult<T>): PortResult<T> {
        val fault = faults.firstOrNull { it.first == op }
        if (fault != null) {
            faults.remove(fault)
            if (fault.second) apply()
            postCounts.merge(op, if (fault.second) 1 else 0, Int::plus)
            return PortResult.Unknown("injected: response lost for $op")
        }
        postCounts.merge(op, 1, Int::plus)
        return apply()
    }

    // ---- Workspace
    override suspend fun createWorkspace(spec: WorkspaceSpec): PortResult<Workspace> = mutex.withLock {
        workspaces.values.firstOrNull { it.name == spec.name }?.let { return PortResult.Ok(it) }
        mutate("createWorkspace") {
            val w = Workspace(WorkspaceId(nextId("ws")), spec.name, WorkspaceState.CREATING, spec.labels, null)
            workspaces[w.id.value] = w
            PortResult.Ok(w)
        }
    }

    override suspend fun getWorkspace(id: WorkspaceId): PortResult<Workspace> = mutex.withLock {
        workspaces[id.value]?.let { PortResult.Ok(it) } ?: PortResult.NotFound
    }

    override suspend fun findWorkspaceByName(name: String): PortResult<Workspace?> = mutex.withLock {
        PortResult.Ok(workspaces.values.firstOrNull { it.name == name })
    }

    override suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace> = mutex.withLock {
        val w = workspaces[id.value] ?: return PortResult.NotFound
        mutate("deleteWorkspace") {
            val d = w.copy(state = WorkspaceState.DELETING)
            workspaces[id.value] = d
            PortResult.Ok(d)
        }
    }

    // ---- Runtime
    override suspend fun createRuntime(workspace: WorkspaceId, spec: RuntimeSpec): PortResult<Runtime> = mutex.withLock {
        if (workspace.value !in workspaces) return PortResult.NotFound
        if (spec.labels.isNotEmpty()) {
            val existing = matching(workspace, spec.labels)
            when (existing.size) {
                0 -> Unit
                1 -> return PortResult.Ok(existing.single())
                else -> return PortResult.Conflict("ambiguous: ${existing.size} runtimes already carry labels ${spec.labels}")
            }
        }
        mutate("createRuntime") {
            val r = Runtime(RuntimeId(workspace, nextId("rt")), spec.templateId, RuntimeState.CREATING, spec.labels, null)
            runtimes[r.id.value] = r
            PortResult.Ok(r)
        }
    }

    private fun matching(ws: WorkspaceId, labels: Map<String, String>) = runtimes.values.filter {
        it.id.workspaceId == ws && it.state != RuntimeState.DELETED && labels.all { (k, v) -> it.labels[k] == v }
    }

    private fun runtime(id: RuntimeId) = runtimes[id.value]?.takeIf { it.id.workspaceId == id.workspaceId }

    override suspend fun getRuntime(id: RuntimeId): PortResult<Runtime> = mutex.withLock {
        runtime(id)?.let { PortResult.Ok(it) } ?: PortResult.NotFound
    }

    override suspend fun findRuntimesByLabel(workspace: WorkspaceId, labels: Map<String, String>): PortResult<List<Runtime>> =
        mutex.withLock {
            if (workspace.value !in workspaces) PortResult.NotFound else PortResult.Ok(matching(workspace, labels))
        }

    override suspend fun stopRuntime(id: RuntimeId): PortResult<Runtime> = mutex.withLock {
        val r = runtime(id) ?: return PortResult.NotFound
        mutate("stopRuntime") {
            val s = r.copy(state = RuntimeState.STOPPING)
            runtimes[id.value] = s
            PortResult.Ok(s)
        }
    }

    override suspend fun deleteRuntime(id: RuntimeId): PortResult<Runtime> = mutex.withLock {
        val r = runtime(id) ?: return PortResult.NotFound
        mutate("deleteRuntime") {
            val s = r.copy(state = RuntimeState.DELETING)
            runtimes[id.value] = s
            PortResult.Ok(s)
        }
    }

    // ---- Execution
    override suspend fun startExecution(runtime: RuntimeId, spec: ExecutionSpec): PortResult<Execution> = mutex.withLock {
        val rt = runtime(runtime) ?: return PortResult.NotFound
        val existing = executions.values.filter { it.id.runtimeId == runtime }
        if (spec.singleExecutionGuard && existing.isNotEmpty()) {
            return PortResult.Conflict("runtime ${runtime.value} already has executions ${existing.map { it.id.value }}")
        }
        if (rt.state != RuntimeState.RUNNING) return PortResult.Conflict("runtime is ${rt.state}, executions need RUNNING")
        mutate("startExecution") {
            val e = Execution(ExecutionId(runtime, nextId("ex")), spec.type, ExecutionState.QUEUED, null, null, null)
            executions[e.id.value] = e
            PortResult.Ok(e)
        }
    }

    override suspend fun getExecution(id: ExecutionId): PortResult<Execution> = mutex.withLock {
        executions[id.value]?.takeIf { it.id.runtimeId == id.runtimeId }?.let { PortResult.Ok(it) } ?: PortResult.NotFound
    }

    override suspend fun listExecutions(runtime: RuntimeId): PortResult<List<Execution>> = mutex.withLock {
        if (runtime(runtime) == null) PortResult.NotFound
        else PortResult.Ok(executions.values.filter { it.id.runtimeId == runtime })
    }

    override suspend fun cancelExecution(id: ExecutionId): PortResult<Execution> =
        PortResult.Unsupported("cancelExecution", "use stopRuntime")

    override suspend fun readLogs(id: ExecutionId, cursor: LogCursor?): PortResult<LogPage> =
        PortResult.Unsupported("readLogs")
}
