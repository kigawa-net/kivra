package net.kigawa.kivra.exkes

/**
 * Supplies the Bearer token for exkes. exkes has no authentication yet (Keycloak OIDC planned,
 * client-credentials flow 要確認): a provider returning null sends no Authorization header.
 */
fun interface TokenProvider {
    suspend fun token(): String?

    companion object {
        val None = TokenProvider { null }
        fun static(token: String) = TokenProvider { token }
    }
}

/**
 * Port to exkes (Workspace / Runtime / Execution). The single ExecutionPort of Kivra: the earlier placeholder
 * in kivra-core (no UNKNOWN / Unsupported channel, IdempotencyKeys exkes cannot honour) was removed in #30.
 *
 * Contract shared by [FakeExecutionPort] and [ExkesAdapter] (see ExecutionPortContractTest):
 *  - create* are "look up, then create": they never create a second resource for the same stable
 *    reference (workspace name `kivra-run-<runId>`, runtime label `kivra.run-id`) and adopt the existing one.
 *  - create* are asynchronous on exkes (202, state CREATING); poll with get* / [awaitWorkspace] etc.
 *  - a mutating call with an unknown outcome returns [PortResult.Unknown] and is never retried here.
 *  - responses whose identity does not match the request yield [PortResult.IdentityMismatch].
 */
interface ExecutionPort {
    val capabilities: ExecutionCapabilities

    // ---- Workspace ----
    suspend fun createWorkspace(spec: WorkspaceSpec): PortResult<Workspace>
    suspend fun getWorkspace(id: WorkspaceId): PortResult<Workspace>

    /** Reconciliation lookup. Ok(null) = no live workspace with this name (DELETED tombstones are not listed by exkes). */
    suspend fun findWorkspaceByName(name: String): PortResult<Workspace?>
    suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace>

    // ---- Runtime ----
    suspend fun createRuntime(workspace: WorkspaceId, spec: RuntimeSpec): PortResult<Runtime>
    suspend fun getRuntime(id: RuntimeId): PortResult<Runtime>

    /** Reconciliation lookup; every given label must match. exkes has no filter, so this is client-side. */
    suspend fun findRuntimesByLabel(workspace: WorkspaceId, labels: Map<String, String>): PortResult<List<Runtime>>
    suspend fun stopRuntime(id: RuntimeId): PortResult<Runtime>
    suspend fun deleteRuntime(id: RuntimeId): PortResult<Runtime>

    // ---- Execution ----
    suspend fun startExecution(runtime: RuntimeId, spec: ExecutionSpec): PortResult<Execution>
    suspend fun getExecution(id: ExecutionId): PortResult<Execution>
    suspend fun listExecutions(runtime: RuntimeId): PortResult<List<Execution>>

    /** Unsupported while [ExecutionCapabilities.cancelExecution] is false. Workaround: [stopRuntime]. */
    suspend fun cancelExecution(id: ExecutionId): PortResult<Execution>

    /** Unsupported while [ExecutionCapabilities.readLogs] is false. */
    suspend fun readLogs(id: ExecutionId, cursor: LogCursor?): PortResult<LogPage>
}
