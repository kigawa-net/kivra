package net.kigawa.kivra.fusha

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.TaskCapability
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskPatch
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskProviderException
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.core.TaskWriteResult
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.random.Random

/**
 * Read-only [TaskProvider] backed by fusha-task REST (`GET /tasks`, `GET /tasks/:id`).
 *
 * Confirmed from the fusha-task source (design #2 section 2): base `/api/v1`, Bearer auth, list supports only
 * `assigneeId` / `viewId`, no paging, 404 for both missing and invisible tasks, no optimistic locking.
 *
 * Write operations (update / comment / create / delete) are deliberately UNSUPPORTED in M1: without server-side
 * CAS a read-modify-write can still overwrite concurrent customFields edits, and the machine authentication path
 * is unverified (issue #27 boundary note). 要確認: enable in a later phase once both are confirmed.
 *
 * Retry policy: GET only; network errors, timeouts, 429 and 5xx are retried with exponential backoff + jitter;
 * 401 triggers one token refresh and one retry; 400/403 are never retried.
 */
class FushaTaskAdapter(
    private val config: FushaTaskConfig,
    private val client: HttpClient,
    private val tokens: TokenProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() },
) : TaskProvider {

    override val capabilities: Set<TaskCapability> = setOf(TaskCapability.GET, TaskCapability.LIST)

    private val base = config.baseUrl.trimEnd('/')
    private val idPrefix = "fusha:${config.environment}:"

    override suspend fun get(id: TaskId): TaskSnapshot? {
        val numeric = parseId(id)
        val response = request("$base/tasks/$numeric")
        return when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.value in 200..299 -> decode<FushaTaskDto>(response).toSnapshot()
            else -> throw classify(response.status.value)
        }
    }

    override suspend fun list(filter: TaskFilter): List<TaskSnapshot> {
        val response = request("$base/tasks") {
            filter.assigneeId?.let { parameter("assigneeId", it) }
            filter.viewId?.let { parameter("viewId", it) }
        }
        return when {
            response.status.value in 200..299 -> decode<List<FushaTaskDto>>(response).map { it.toSnapshot() }
            // list 404 means "view not found" (not "task not found"): surface it instead of returning empty.
            else -> throw classify(response.status.value)
        }
    }

    override suspend fun update(id: TaskId, patch: TaskPatch, expectedUpdatedAt: Instant?): TaskWriteResult =
        unsupported(TaskCapability.UPDATE)

    override suspend fun comment(id: TaskId, body: String, key: IdempotencyKey): TaskWriteResult =
        unsupported(TaskCapability.COMMENT)

    private fun unsupported(cap: TaskCapability) = TaskWriteResult.Unsupported(
        cap,
        "fusha-task writes are disabled in M1: no server-side CAS/ETag and machine auth is unverified (要確認)",
    )

    private fun parseId(id: TaskId): Long {
        val v = id.value
        if (!v.startsWith(idPrefix)) {
            throw TaskProviderException(TaskErrorKind.REJECTED, "TaskId '$v' does not belong to environment '${config.environment}'")
        }
        return v.removePrefix(idPrefix).toLongOrNull()
            ?: throw TaskProviderException(TaskErrorKind.REJECTED, "TaskId '$v' has a non-numeric id")
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T = try {
        response.body<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        throw TaskProviderException(TaskErrorKind.PROTOCOL, "unexpected fusha-task response body", response.status.value, e)
    } catch (e: io.ktor.serialization.ContentConvertException) {
        throw TaskProviderException(TaskErrorKind.PROTOCOL, "unexpected fusha-task response body", response.status.value, e)
    } catch (e: io.ktor.client.call.NoTransformationFoundException) {
        throw TaskProviderException(TaskErrorKind.PROTOCOL, "unexpected fusha-task response body", response.status.value, e)
    } catch (e: IOException) {
        throw TaskProviderException(TaskErrorKind.TRANSIENT, "failed reading fusha-task response", response.status.value, e)
    }

    private fun FushaTaskDto.toSnapshot(): TaskSnapshot {
        val updated = try {
            Instant.parse(updatedAt)
        } catch (e: DateTimeParseException) {
            throw TaskProviderException(TaskErrorKind.PROTOCOL, "task $id has unparseable updatedAt", cause = e)
        }
        return TaskSnapshot(
            id = TaskId("$idPrefix$id"),
            title = title,
            description = description,
            assigneeId = assigneeId,
            dueDate = dueDate,
            fields = customFields.mapValues { it.value.toString() },
            nextTaskIds = nextTaskIds.map { TaskId("$idPrefix$it") },
            relatedTaskIds = relatedTaskIds.map { TaskId("$idPrefix$it") },
            updatedAt = updated,
            fetchedAt = clock.instant(),
        )
    }

    /** Statuses other than success/404 are mapped to exceptions (400 REJECTED, 401, 403, others TRANSIENT/PROTOCOL). */
    private fun classify(status: Int): TaskProviderException = when (status) {
        401 -> TaskProviderException(TaskErrorKind.UNAUTHORIZED, "fusha-task rejected the bearer token", status)
        403 -> TaskProviderException(TaskErrorKind.FORBIDDEN, "fusha-task denied access", status)
        404 -> TaskProviderException(TaskErrorKind.REJECTED, "fusha-task returned 404", status)
        429 -> TaskProviderException(TaskErrorKind.TRANSIENT, "fusha-task rate limited", status)
        in 400..499 -> TaskProviderException(TaskErrorKind.REJECTED, "fusha-task rejected the request", status)
        in 500..599 -> TaskProviderException(TaskErrorKind.TRANSIENT, "fusha-task server error", status)
        else -> TaskProviderException(TaskErrorKind.PROTOCOL, "unexpected fusha-task status", status)
    }

    /** Issues a GET with auth, 401 refresh-once and transient retry. Returns any non-retryable response. */
    private suspend fun request(
        url: String,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        var refreshed = false
        var attempt = 0
        var forceRefresh = false
        while (true) {
            val outcome: Result<HttpResponse> = try {
                val token = tokens.token(forceRefresh)
                forceRefresh = false
                Result.success(
                    client.get(url) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.Accept, "application/json")
                        configure()
                    },
                )
            } catch (e: CancellationException) {
                // Timeouts (HttpRequestTimeoutException etc.) are IOExceptions in Ktor 3; real cancellation propagates.
                throw e
            } catch (e: IOException) {
                Result.failure(e)
            }

            val response = outcome.getOrNull()
            if (response != null) {
                val code = response.status.value
                if (code == 401 && !refreshed) {
                    refreshed = true
                    forceRefresh = true
                    continue
                }
                val retryable = code == 429 || code in 500..599
                if (!retryable) return response
                if (attempt >= config.maxRetries) throw classify(code)
            } else if (attempt >= config.maxRetries) {
                throw TaskProviderException(TaskErrorKind.TRANSIENT, "fusha-task unreachable: ${outcome.exceptionOrNull()?.javaClass?.simpleName}", cause = outcome.exceptionOrNull())
            }
            sleep(backoffMillis(attempt))
            attempt++
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val exp = config.initialBackoff.toMillis() * (1L shl attempt.coerceAtMost(20))
        val capped = exp.coerceAtMost(config.maxBackoff.toMillis())
        // Full-ish jitter: 50%..100% of the capped value.
        return (capped * (0.5 + 0.5 * jitter())).toLong()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Builds a client with timeouts and JSON. Pass a MockEngine in tests; defaults to CIO. */
        fun createClient(config: FushaTaskConfig, engine: HttpClientEngine? = null): HttpClient {
            val setup: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                expectSuccess = false
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) {
                    requestTimeoutMillis = config.requestTimeout.toMillis()
                    connectTimeoutMillis = config.connectTimeout.toMillis()
                    socketTimeoutMillis = config.requestTimeout.toMillis()
                }
            }
            return if (engine != null) HttpClient(engine, setup) else HttpClient(CIO, setup)
        }
    }
}
