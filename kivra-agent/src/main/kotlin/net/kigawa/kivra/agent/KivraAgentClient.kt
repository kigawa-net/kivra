package net.kigawa.kivra.agent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.agent.claude.ReportedUsage
import net.kigawa.kivra.agent.workdir.AgentChangeKind
import net.kigawa.kivra.agent.workdir.AgentResult

/**
 * The HTTP settings every kivra-agent client shares: no redirects (a Bearer token is never sent to another origin), a request
 * timeout and a connect timeout. Used by the real client in `main` and by the MockEngine tests.
 */
fun HttpClientConfig<*>.agentClientConfig() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
}

/**
 * The fixed categories of a failed call. The agent logs and branches on these names only: never a response body, a header, a
 * token, a URL or an exception message.
 */
enum class AgentApiErrorKind {
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    REJECTED,
    CLIENT_ERROR,
    SERVER,
    REDIRECT,
    NETWORK,
    TIMEOUT,
    MALFORMED_RESPONSE,
    TOKEN_REJECTED,
    TOKEN_UNAVAILABLE,
    SECRET_UNREADABLE,
}

/** A failed call. It carries only [kind]; the cause is deliberately not chained, since it may name a URL or a header. */
class AgentApiException(val kind: AgentApiErrorKind) : Exception(kind.name)

/** The outcome of `claim`: a 200 with an attempt, or a 204 with nothing to do. */
sealed interface ClaimResult {
    data class Assigned(val attempt: ClaimedAttempt) : ClaimResult
    data object NoAssignment : ClaimResult
}

/** The outcome of `result` when kivra did not refuse the request outright: accepted (with the new state) or rejected by policy (422). */
sealed interface ResultReply {
    data class Accepted(val state: String) : ResultReply
    data object Rejected : ResultReply
}

/** The state of an attempt and whether kivra has asked to cancel it. */
data class AttemptStatus(val state: String, val cancelRequested: Boolean)

/**
 * The kivra agent API client (`/api/agent/v1`, runbook-selfdev section 6). Every request carries `Authorization: Bearer` only.
 * A response is parsed strictly: a missing or wrongly typed required field is [AgentApiErrorKind.MALFORMED_RESPONSE]. Unknown
 * fields are ignored.
 */
class KivraAgentClient(
    private val http: HttpClient,
    baseUrl: String,
    private val tokens: TokenSource,
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun claim(): ClaimResult {
        val reply = send(HttpMethod.Post, "/api/agent/v1/claim", "{}")
        if (reply.status == 204) return ClaimResult.NoAssignment
        requireSuccess(reply.status)
        return ClaimResult.Assigned(parseClaim(reply.body))
    }

    /** Returns the state the server reports. [progress] is optional; null means no progress change. */
    suspend fun heartbeat(attemptId: String, generation: Long, progress: ProgressPhase?): String {
        val body = buildJsonObject {
            put("generation", generation)
            if (progress != null) put("progress", progress.name)
        }.toString()
        val reply = send(HttpMethod.Post, attemptPath(attemptId, "heartbeat"), body)
        requireSuccess(reply.status)
        return parseState(reply.body)
    }

    /** Reports a failure. [usage] is sent only when the CLI ran and gave a result; null leaves the body as it was before usage existed. */
    suspend fun fail(attemptId: String, generation: Long, code: AgentErrorCode, usage: ReportedUsage? = null): String {
        val body = buildJsonObject {
            put("generation", generation)
            put("errorCode", code.name)
            if (usage != null) put("usage", usage.toJson())
        }.toString()
        val reply = send(HttpMethod.Post, attemptPath(attemptId, "fail"), body)
        requireSuccess(reply.status)
        return parseState(reply.body)
    }

    /**
     * Sends the result of an attempt. A 422 (kivra refused the change set) is [ResultReply.Rejected]; the reasons it carries are not read.
     * A 409 or 404 is thrown as [AgentApiException], and the caller must then discard the result.
     */
    suspend fun submitResult(attemptId: String, generation: Long, result: AgentResult, usage: ReportedUsage? = null): ResultReply {
        val body = buildJsonObject {
            put("generation", generation)
            putJsonObject("result") {
                put("summary", result.summary)
                put("exitStatus", result.exitStatus)
                if (result.sessionId != null) put("sessionId", result.sessionId) else put("sessionId", JsonNull)
                putJsonArray("changes") {
                    for (change in result.changes) {
                        add(
                            buildJsonObject {
                                put("path", change.path)
                                put("kind", change.kind.name)
                                put("fileKind", "REGULAR")
                                if (change.kind == AgentChangeKind.WRITE) put("content", change.content.orEmpty())
                            },
                        )
                    }
                }
            }
            if (usage != null) put("usage", usage.toJson())
        }.toString()
        val reply = send(HttpMethod.Post, attemptPath(attemptId, "result"), body)
        if (reply.status == 422) return ResultReply.Rejected
        requireSuccess(reply.status)
        val parsed = decode<StateBody>(reply.body)
        validId(parsed.attemptId)
        validState(parsed.state)
        return ResultReply.Accepted(parsed.state)
    }

    suspend fun status(attemptId: String): AttemptStatus {
        val reply = send(HttpMethod.Get, attemptPath(attemptId, "status"), null)
        requireSuccess(reply.status)
        val parsed = decode<StatusBody>(reply.body)
        validState(parsed.state)
        return AttemptStatus(parsed.state, parsed.cancelRequested)
    }

    private class Reply(val status: Int, val body: String)

    private suspend fun send(method: HttpMethod, path: String, json: String?): Reply {
        val token = tokens.token()
        val reply = try {
            http.request("$base$path") {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer ${token.reveal()}")
                if (json != null) setBody(TextContent(json, ContentType.Application.Json))
            }.let { Reply(it.status.value, it.bodyAsText()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AgentApiException(if (e.isTimeout()) AgentApiErrorKind.TIMEOUT else AgentApiErrorKind.NETWORK)
        }
        if (reply.status == 401) tokens.invalidate()
        return reply
    }

    private fun attemptPath(attemptId: String, action: String): String {
        require(ID.matches(attemptId)) { "attempt id is not an identifier" }
        return "/api/agent/v1/attempts/$attemptId/$action"
    }

    private fun parseClaim(text: String): ClaimedAttempt {
        val body = decode<ClaimBody>(text)
        validId(body.runId)
        validId(body.attemptId)
        if (body.generation < 0) malformed()
        val input = body.input
        if (!SHA.matches(input.baseSha)) malformed()
        if (input.task.trust != TRUST_UNTRUSTED) malformed()
        val l = input.limits
        val positive = l.maxRuntimeSeconds > 0 && l.maxTurns > 0 && l.maxOutputBytes > 0 && l.maxChangedFiles > 0 &&
            l.maxFileBytes > 0 && l.maxTotalBytes > 0 && l.maxDeletes >= 0
        if (!positive) malformed()
        return ClaimedAttempt(
            runId = body.runId,
            attemptId = body.attemptId,
            generation = body.generation,
            baseSha = input.baseSha,
            task = AgentTask(input.task.origin, input.task.value, input.task.trust, input.task.truncated),
            limits = AgentLimits(
                maxRuntimeSeconds = l.maxRuntimeSeconds,
                maxTurns = l.maxTurns,
                maxOutputBytes = l.maxOutputBytes,
                maxChangedFiles = l.maxChangedFiles,
                maxFileBytes = l.maxFileBytes,
                maxTotalBytes = l.maxTotalBytes,
                maxDeletes = l.maxDeletes,
            ),
            allowedPathPatterns = input.allowedPathPatterns,
            deniedPathPatterns = input.deniedPathPatterns,
        )
    }

    private fun parseState(text: String): String {
        val parsed = decode<StateBody>(text)
        validId(parsed.attemptId)
        validState(parsed.state)
        return parsed.state
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            JSON.decodeFromString<T>(text)
        } catch (e: Exception) {
            malformed()
        }

    private fun validId(value: String) {
        if (!ID.matches(value)) malformed()
    }

    private fun validState(value: String) {
        if (!STATE.matches(value)) malformed()
    }

    private fun malformed(): Nothing = throw AgentApiException(AgentApiErrorKind.MALFORMED_RESPONSE)

    /** The `usage` object of the result and fail bodies. Numbers and the model name only (runbook-selfdev section 6.10c). */
    private fun ReportedUsage.toJson(): JsonObject = buildJsonObject {
        put("totalCostUsd", totalCostUsd)
        put("model", model)
        put("numTurns", numTurns)
        put("inputTokens", inputTokens)
        put("outputTokens", outputTokens)
        put("cacheCreationTokens", cacheCreationTokens)
        put("cacheReadTokens", cacheReadTokens)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        val SHA = Regex("^[0-9a-f]{40}$")
        val STATE = Regex("^[A-Z_]{1,32}$")
    }
}

/** Maps a status code to the fixed category, or null for a 2xx. */
internal fun statusKind(status: Int): AgentApiErrorKind? = when (status) {
    in 200..299 -> null
    401 -> AgentApiErrorKind.UNAUTHORIZED
    403 -> AgentApiErrorKind.FORBIDDEN
    404 -> AgentApiErrorKind.NOT_FOUND
    409 -> AgentApiErrorKind.CONFLICT
    400, 413, 415, 422 -> AgentApiErrorKind.REJECTED
    in 300..399 -> AgentApiErrorKind.REDIRECT
    in 500..599 -> AgentApiErrorKind.SERVER
    else -> AgentApiErrorKind.CLIENT_ERROR
}

private fun requireSuccess(status: Int) {
    statusKind(status)?.let { throw AgentApiException(it) }
}

internal fun Throwable.isTimeout(): Boolean =
    this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException

@Serializable
private class ClaimBody(val attemptId: String, val runId: String, val generation: Long, val input: InputBody)

@Serializable
private class InputBody(
    val baseSha: String,
    val task: TaskBody,
    val limits: LimitsBody,
    val allowedPathPatterns: List<String>,
    val deniedPathPatterns: List<String>,
)

@Serializable
private class TaskBody(val origin: String, val value: String, val trust: String, val truncated: Boolean)

@Serializable
private class LimitsBody(
    val maxRuntimeSeconds: Long,
    val maxTurns: Int,
    val maxOutputBytes: Long,
    val maxChangedFiles: Int,
    val maxFileBytes: Long,
    val maxTotalBytes: Long,
    val maxDeletes: Int,
)

@Serializable
private class StateBody(val attemptId: String, val state: String)

@Serializable
private class StatusBody(val attemptId: String, val state: String, val cancelRequested: Boolean)
