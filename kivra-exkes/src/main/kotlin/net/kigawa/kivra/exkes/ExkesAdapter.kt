package net.kigawa.kivra.exkes

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ExkesConfig(
    /** e.g. http://exkes-api.exkes.svc:8080 (no trailing slash needed). */
    val baseUrl: String,
    val requestTimeout: Duration = 30.seconds,
    val connectTimeout: Duration = 5.seconds,
    val pageSize: Int = 100,
)

/**
 * HTTP adapter for the exkes REST API (exkes-api, `/v1/...`).
 *
 * Facts relied on (verified in exkes origin/main, 2026-10-12; see docs/exkes-requests.md):
 *  - Workspace/Runtime/Execution POST -> 202 + resource in CREATING/QUEUED; state is followed with GET.
 *  - workspaces.name is UNIQUE -> a duplicate POST returns 409 CONFLICT.
 *  - Runtime accepts `labels` on create; Execution does not (no label, no idempotency key anywhere).
 *  - list endpoints are paginated (limit/offset, total) with only a `status` filter.
 *  - errors are `{"code","message"}` (NOT_FOUND / CONFLICT / VALIDATION_ERROR / INTERNAL).
 *
 * Retry policy (M0 Operation Journal contract): this class NEVER resends a request. A mutating call
 * that times out / loses the connection / gets 5xx returns [PortResult.Unknown]. The [HttpClient]
 * must not install HttpRequestRetry either (the constructor rejects it); use [newClient].
 */
class ExkesAdapter(
    private val client: HttpClient,
    config: ExkesConfig,
    private val tokenProvider: TokenProvider = TokenProvider.None,
) : ExecutionPort {
    private val baseUrl = config.baseUrl.trimEnd('/')
    private val pageSize = config.pageSize.also { require(it in 1..500) { "pageSize must be 1..500 (exkes MAX_LIMIT is 500)" } }

    init {
        require(client.pluginOrNull(HttpRequestRetry) == null) {
            "HttpRequestRetry must not be installed: POSTs may not be resent automatically"
        }
    }

    override val capabilities = ExecutionCapabilities()

    // ---------------------------------------------------------------- Workspace

    override suspend fun createWorkspace(spec: WorkspaceSpec): PortResult<Workspace> {
        // Look up first (stable ref = unique name), so a replay adopts instead of duplicating.
        when (val found = findWorkspaceByName(spec.name)) {
            is PortResult.Ok -> found.value?.let { return PortResult.Ok(it) }
            else -> return found.asFailure()
        }
        val body = CreateWorkspaceBody(spec.name, spec.storageSizeBytes, spec.labels.ifEmpty { null })
        val r = post("/v1/workspaces", json.encodeToString(CreateWorkspaceBody.serializer(), body), WorkspaceDto.serializer())
        return when (r) {
            is PortResult.Ok -> checkIdentity(spec.name, r.value.name) { r.value.toDomain() }
            is PortResult.Conflict -> {
                // 409 = the name already exists (our own earlier POST that we did not see).
                when (val again = findWorkspaceByName(spec.name)) {
                    is PortResult.Ok -> again.value?.let { PortResult.Ok(it) } ?: r
                    else -> again.asFailure()
                }
            }
            else -> r.asFailure()
        }
    }

    override suspend fun getWorkspace(id: WorkspaceId): PortResult<Workspace> =
        when (val r = get("/v1/workspaces/${id.value}", WorkspaceDto.serializer())) {
            is PortResult.Ok -> checkIdentity(id.value, r.value.id) { r.value.toDomain() }
            else -> r.asFailure()
        }

    override suspend fun findWorkspaceByName(name: String): PortResult<Workspace?> =
        when (val r = listAll("/v1/workspaces", WorkspaceDto.serializer())) {
            is PortResult.Ok -> PortResult.Ok(r.value.firstOrNull { it.name == name }?.toDomain())
            else -> r.asFailure()
        }

    override suspend fun listWorkspaces(): PortResult<List<Workspace>> =
        when (val r = listAll("/v1/workspaces", WorkspaceDto.serializer())) {
            is PortResult.Ok -> PortResult.Ok(r.value.filter { it.status != "DELETED" }.map { it.toDomain() })
            else -> r.asFailure()
        }

    override suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace> =
        when (val r = delete("/v1/workspaces/${id.value}", WorkspaceDto.serializer())) {
            is PortResult.Ok -> checkIdentity(id.value, r.value.id) { r.value.toDomain() }
            else -> r.asFailure()
        }

    // ---------------------------------------------------------------- Runtime

    override suspend fun createRuntime(workspace: WorkspaceId, spec: RuntimeSpec): PortResult<Runtime> {
        // Without labels a created Runtime cannot be found again: skip the lookup (and document it).
        if (spec.labels.isNotEmpty()) {
            when (val found = findRuntimesByLabel(workspace, spec.labels)) {
                is PortResult.Ok -> when (found.value.size) {
                    0 -> Unit
                    1 -> return PortResult.Ok(found.value.single())
                    else -> return PortResult.Conflict("ambiguous: ${found.value.size} runtimes already carry labels ${spec.labels}")
                }
                else -> return found.asFailure()
            }
        }
        val body = CreateRuntimeBody(spec.templateId, spec.resourcesJson, spec.envJson, spec.ttlSeconds, spec.labels.ifEmpty { null })
        val r = post(
            "/v1/workspaces/${workspace.value}/runtimes",
            json.encodeToString(CreateRuntimeBody.serializer(), body),
            RuntimeDto.serializer(),
        )
        return when (r) {
            is PortResult.Ok -> {
                val d = r.value
                when {
                    d.workspaceId != workspace.value -> PortResult.IdentityMismatch(workspace.value, d.workspaceId)
                    d.templateId != spec.templateId -> PortResult.IdentityMismatch(spec.templateId, d.templateId)
                    else -> PortResult.Ok(d.toDomain())
                }
            }
            else -> r.asFailure()
        }
    }

    override suspend fun getRuntime(id: RuntimeId): PortResult<Runtime> =
        when (val r = get(runtimePath(id), RuntimeDto.serializer())) {
            is PortResult.Ok -> checkRuntime(id, r.value)
            else -> r.asFailure()
        }

    override suspend fun findRuntimesByLabel(workspace: WorkspaceId, labels: Map<String, String>): PortResult<List<Runtime>> =
        when (val r = listRuntimes(workspace)) {
            is PortResult.Ok -> PortResult.Ok(r.value.filter { rt -> labels.all { (k, v) -> rt.labels[k] == v } })
            else -> r.asFailure()
        }

    override suspend fun listRuntimes(workspace: WorkspaceId): PortResult<List<Runtime>> {
        val r = listAll("/v1/workspaces/${workspace.value}/runtimes", RuntimeDto.serializer())
        if (r !is PortResult.Ok) return r.asFailure()
        r.value.firstOrNull { it.workspaceId != workspace.value }?.let {
            return PortResult.IdentityMismatch(workspace.value, it.workspaceId)
        }
        return PortResult.Ok(r.value.filter { it.status != "DELETED" }.map { it.toDomain() })
    }

    override suspend fun stopRuntime(id: RuntimeId): PortResult<Runtime> =
        when (val r = post("${runtimePath(id)}/stop", null, RuntimeDto.serializer())) {
            is PortResult.Ok -> checkRuntime(id, r.value)
            else -> r.asFailure()
        }

    override suspend fun deleteRuntime(id: RuntimeId): PortResult<Runtime> =
        when (val r = delete(runtimePath(id), RuntimeDto.serializer())) {
            is PortResult.Ok -> checkRuntime(id, r.value)
            else -> r.asFailure()
        }

    // ---------------------------------------------------------------- Execution

    override suspend fun startExecution(runtime: RuntimeId, spec: ExecutionSpec): PortResult<Execution> {
        if (spec.singleExecutionGuard) {
            when (val existing = listExecutions(runtime)) {
                is PortResult.Ok -> if (existing.value.isNotEmpty()) {
                    return PortResult.Conflict(
                        "runtime ${runtime.value} already has executions ${existing.value.map { it.id.value }}; " +
                            "exkes has no idempotency key or label for executions, reconcile instead of POSTing again",
                    )
                }
                else -> return existing.asFailure()
            }
        }
        val body = CreateExecutionBody(spec.type.name, spec.commandJson, spec.agentConfigJson)
        val r = post(
            "/v1/runtimes/${runtime.value}/executions",
            json.encodeToString(CreateExecutionBody.serializer(), body),
            ExecutionDto.serializer(),
        )
        return when (r) {
            is PortResult.Ok -> {
                val d = r.value
                when {
                    d.runtimeId != runtime.value -> PortResult.IdentityMismatch(runtime.value, d.runtimeId)
                    d.type != spec.type.name -> PortResult.IdentityMismatch(spec.type.name, d.type)
                    else -> PortResult.Ok(d.toDomain(runtime))
                }
            }
            else -> r.asFailure()
        }
    }

    override suspend fun getExecution(id: ExecutionId): PortResult<Execution> =
        when (val r = get("/v1/runtimes/${id.runtimeId.value}/executions/${id.value}", ExecutionDto.serializer())) {
            is PortResult.Ok -> when {
                r.value.id != id.value -> PortResult.IdentityMismatch(id.value, r.value.id)
                r.value.runtimeId != id.runtimeId.value -> PortResult.IdentityMismatch(id.runtimeId.value, r.value.runtimeId)
                else -> PortResult.Ok(r.value.toDomain(id.runtimeId))
            }
            else -> r.asFailure()
        }

    override suspend fun listExecutions(runtime: RuntimeId): PortResult<List<Execution>> {
        val r = listAll("/v1/runtimes/${runtime.value}/executions", ExecutionDto.serializer())
        if (r !is PortResult.Ok) return r.asFailure()
        r.value.firstOrNull { it.runtimeId != runtime.value }?.let {
            return PortResult.IdentityMismatch(runtime.value, it.runtimeId)
        }
        return PortResult.Ok(r.value.map { it.toDomain(runtime) })
    }

    override suspend fun cancelExecution(id: ExecutionId): PortResult<Execution> = PortResult.Unsupported(
        "cancelExecution",
        "exkes has no Execution cancel API (only Runtime stop/delete); use stopRuntime/deleteRuntime, which ends every execution of the runtime",
    )

    override suspend fun readLogs(id: ExecutionId, cursor: LogCursor?): PortResult<LogPage> = PortResult.Unsupported(
        "readLogs",
        "exkes exposes only Execution.logObjectKey (object store key); no log read endpoint",
    )

    // ---------------------------------------------------------------- HTTP plumbing

    private fun runtimePath(id: RuntimeId) = "/v1/workspaces/${id.workspaceId.value}/runtimes/${id.value}"

    private fun checkRuntime(id: RuntimeId, d: RuntimeDto): PortResult<Runtime> = when {
        d.id != id.value -> PortResult.IdentityMismatch(id.value, d.id)
        d.workspaceId != id.workspaceId.value -> PortResult.IdentityMismatch(id.workspaceId.value, d.workspaceId)
        else -> PortResult.Ok(d.toDomain())
    }

    private inline fun <T> checkIdentity(expected: String, actual: String, ok: () -> T): PortResult<T> =
        if (expected == actual) PortResult.Ok(ok()) else PortResult.IdentityMismatch(expected, actual)

    @Suppress("UNCHECKED_CAST")
    private fun PortResult<*>.asFailure(): PortResult<Nothing> = this as PortResult<Nothing>

    private suspend fun <T> get(path: String, ser: KSerializer<T>, query: Map<String, String> = emptyMap()) =
        execute(HttpMethod.Get, path, null, ser, mutating = false, query = query)

    private suspend fun <T> post(path: String, body: String?, ser: KSerializer<T>) =
        execute(HttpMethod.Post, path, body, ser, mutating = true)

    private suspend fun <T> delete(path: String, ser: KSerializer<T>) =
        execute(HttpMethod.Delete, path, null, ser, mutating = true)

    private suspend fun <T> listAll(path: String, item: KSerializer<T>): PortResult<List<T>> {
        val out = ArrayList<T>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = when (
                val r = get(
                    path,
                    ListDto.serializer(item),
                    mapOf("limit" to pageSize.toString(), "offset" to offset.toString()),
                )
            ) {
                is PortResult.Ok -> r.value
                else -> return r.asFailure()
            }
            out += page.items
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) return PortResult.Ok(out)
        }
        return PortResult.Transient("list $path exceeded $MAX_PAGES pages")
    }

    /** Single attempt. Failure classification is the heart of the UNKNOWN contract. */
    private suspend fun <T> execute(
        method: HttpMethod,
        path: String,
        body: String?,
        ser: KSerializer<T>,
        mutating: Boolean,
        query: Map<String, String> = emptyMap(),
    ): PortResult<T> {
        val ambiguous: (String, Throwable?) -> PortResult<Nothing> =
            { msg, t -> if (mutating) PortResult.Unknown(msg, t) else PortResult.Transient(msg) }

        val token = try {
            tokenProvider.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PortResult.Transient("token provider failed: ${e.message}") // nothing was sent
        }

        val status: Int
        val text: String
        try {
            val response = client.request("$baseUrl$path") {
                this.method = method
                applyAuth(token)
                query.forEach { (k, v) -> parameter(k, v) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            status = response.status.value
            text = response.bodyAsText()
        } catch (e: ConnectTimeoutException) {
            return PortResult.Transient("connect timeout: ${e.message}") // connect never completed, nothing was written
        } catch (e: ConnectException) {
            return PortResult.Transient("connection refused: ${e.message}") // nothing was sent
        } catch (e: UnresolvedAddressException) {
            return PortResult.Transient("DNS resolution failed: ${e.message}") // no socket was opened
        } catch (e: UnknownHostException) {
            return PortResult.Transient("DNS resolution failed: ${e.message}") // no socket was opened
        } catch (e: CancellationException) {
            if (e.javaClass.simpleName == "HttpRequestTimeoutException") return ambiguous("request timeout", e)
            throw e
        } catch (e: Exception) {
            // includes HttpRequestTimeoutException (an IOException), socket timeouts, resets, EOF
            return ambiguous("${e.javaClass.simpleName}: ${e.message}", e)
        }

        return when {
            status in 200..299 -> try {
                PortResult.Ok(json.decodeFromString(ser, text))
            } catch (e: Exception) {
                ambiguous("undecodable $status response from $method $path: ${e.message}", e)
            }
            status == 404 -> PortResult.NotFound
            status == 409 -> PortResult.Conflict(errorOf(text)?.message ?: "conflict")
            // 429 is an HTTP response: exkes has seen the request. A mutating call is therefore UNKNOWN, not
            // "not sent"; a GET is idempotent and may be retried by the caller (Transient).
            status == 429 -> ambiguous("429 too many requests", null)
            status in 400..499 -> {
                val err = errorOf(text)
                PortResult.Rejected(status, err?.code, err?.message ?: "HTTP $status")
            }
            else -> ambiguous("HTTP $status from $method $path", null)
        }
    }

    private fun HttpRequestBuilder.applyAuth(token: String?) {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun errorOf(text: String): ErrorDto? =
        runCatching { json.decodeFromString(ErrorDto.serializer(), text) }.getOrNull()

    private fun WorkspaceDto.toDomain() = Workspace(WorkspaceId(id), name, WorkspaceState.parse(status), labels, errorMessage)

    private fun RuntimeDto.toDomain() =
        Runtime(RuntimeId(WorkspaceId(workspaceId), id), templateId, RuntimeState.parse(status), labels, errorMessage)

    private fun ExecutionDto.toDomain(runtime: RuntimeId) = Execution(
        ExecutionId(runtime, id),
        ExecutionType.entries.firstOrNull { it.name == type },
        ExecutionState.parse(status),
        exitCode,
        errorMessage,
        logObjectKey,
    )

    companion object {
        private const val MAX_PAGES = 200
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        /**
         * Builds an HttpClient with request/connect timeouts and NO retry plugin. [engine] is injected
         * (CIO in production, MockEngine in tests).
         */
        fun newClient(engine: HttpClientEngine, config: ExkesConfig, extra: HttpClientConfig<*>.() -> Unit = {}) =
            HttpClient(engine) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
                    connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
                    socketTimeoutMillis = config.requestTimeout.inWholeMilliseconds
                }
                extra()
            }
    }
}
