package net.kigawa.kivra.ui.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.ui.percentEncode

@Serializable private class SubmitTaskRequest(val title: String, val idempotencyKey: String)

@Serializable private class SubmitSelfDevRequest(val title: String, val body: String?, val idempotencyKey: String)

@Serializable private class CreateProjectRequest(val name: String, val description: String?)

/**
 * A failed API call: a non-2xx answer, or a body that does not match the model.
 * [status] is the HTTP status, or null when the body could not be read as the expected model.
 * [detail] is the server's fixed `error` text when it sent one (for example `AUTOPILOT_DISABLED`), otherwise the status text.
 */
class ApiException(val status: Int?, val detail: String) : Exception(
    if (status != null) "HTTP $status: $detail" else detail,
)

/**
 * Ktor HTTP client for the Kivra API.
 *
 * Authentication: the page is served behind the reverse proxy (oauth2-proxy), which adds the token. The client sends no credential
 * header and relies on the same-origin cookie, the same as the old UI (`kivra-app/src/main/resources/ui/app.js`, apiFetch).
 *
 * Read calls throw [ApiException] on failure, so a failed load is never shown as an empty list. Callers must catch it.
 */
object ApiClient {

    val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    /** GET [path] and decode the body as [T]. Throws [ApiException] for a non-2xx answer or a body that does not decode. */
    private suspend inline fun <reified T> getJson(path: String): T {
        val response = client.get(path)
        if (!response.status.isSuccess()) {
            throw ApiException(response.status.value, serverErrorOf(response))
        }
        return try {
            response.body<T>()
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    /** The server's `{"error": "..."}` text, or the status description when the body has none. */
    private suspend fun serverErrorOf(response: HttpResponse): String {
        val text = try {
            response.bodyAsText()
        } catch (e: Exception) {
            return response.status.description
        }
        return ERROR_FIELD.find(text)?.groupValues?.get(1) ?: response.status.description
    }

    /**
     * scope "mine" = 自分の Run(`/api/runs`、サーバー側で最大 50 件)。"all" = 運用者向けの全 Run(`/api/admin/runs`、最大 100 件)。
     * "all" は運用者以外では 403 の [ApiException] になる。サーバーは scope パラメータを解釈しない。
     */
    suspend fun getRuns(scope: String = "mine"): List<ApiRun> =
        if (scope == "all") getJson("/api/admin/runs?limit=100") else getJson("/api/runs")

    /** Run の詳細(イベントを含む)。`GET /api/runs/{id}`。404 は存在しない/他人の Run。 */
    suspend fun getRun(runId: String): ApiRun =
        getJson("/api/runs/${percentEncode(runId)}")

    /** 開発目標の詳細。`GET /api/selfdev/goals/{id}`。404 は存在しない id、503 は自律運転が off。 */
    suspend fun getGoal(taskId: String): ApiGoalDetail =
        getJson("/api/selfdev/goals/${percentEncode(taskId)}")

    /** order の詳細。`GET /api/selfdev/orders/{id}`。 */
    suspend fun getOrder(orderId: String): ApiOrderDetail =
        getJson("/api/selfdev/orders/${percentEncode(orderId)}")

    /** order の周の一覧(周番号順)。`GET /api/selfdev/orders/{id}/cycles`。 */
    suspend fun getOrderCycles(orderId: String): List<ApiOrderCycle> =
        getJson("/api/selfdev/orders/${percentEncode(orderId)}/cycles")

    /**
     * Task を投入する。サーバーは idempotencyKey を必須とし、JSON の Content-Type を要求する(`SubmitTaskRequest` / `SubmitGoalRequest`)。
     * kind が "SELF_DEV" なら `/api/selfdev/tasks`(本文あり)、それ以外は `/api/tasks`(本文は送らない。旧 UI と同じ)。
     * 2xx なら true。同じ呼び出しの再送は新しい key になるため、二重投入の防止は呼び出し側で行う。
     */
    suspend fun submitTask(title: String, body: String? = null, kind: String = "SIMULATED"): Boolean {
        return try {
            val key = newIdempotencyKey()
            val response = if (kind == "SELF_DEV") {
                client.post("/api/selfdev/tasks") {
                    contentType(ContentType.Application.Json)
                    setBody(SubmitSelfDevRequest(title, body?.takeIf { it.isNotBlank() }, key))
                }
            } else {
                client.post("/api/tasks") {
                    contentType(ContentType.Application.Json)
                    setBody(SubmitTaskRequest(title, key))
                }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    private fun newIdempotencyKey(): String =
        "compose-" + (1..24).map { "0123456789abcdef".random() }.joinToString("")

    suspend fun getProjects(): List<ApiProject> =
        getJson("/api/projects")

    suspend fun createProject(name: String, description: String? = null): Boolean {
        return try {
            val response = client.post("/api/projects") {
                contentType(ContentType.Application.Json)
                setBody(CreateProjectRequest(name, description?.takeIf { it.isNotBlank() }))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    /** Goals (= selfdev tasks). `GET /api/selfdev/goals`. 503 `AUTOPILOT_DISABLED` while the autopilot is off. */
    suspend fun getGoals(): List<ApiGoal> =
        getJson("/api/selfdev/goals")

    /** Orders. `GET /api/selfdev/orders`. 503 while the autopilot is off. */
    suspend fun getOrders(): List<ApiOrder> =
        getJson("/api/selfdev/orders")

    /**
     * The approvals page's data: the pending stop requests of the orders. `GET /api/selfdev/stop-requests?state=PENDING`.
     * 403 when the caller is not the root approver (the old UI hides the page then).
     */
    suspend fun getApprovals(): List<ApiStopRequest> =
        getJson("/api/selfdev/stop-requests?state=PENDING")

    /** Memory notes (first page, up to the server's maximum of 100 items). `GET /api/selfdev/memory`. 503 while memory is off. */
    suspend fun getMemories(): List<ApiMemory> =
        getJson<ApiMemoryList>("/api/selfdev/memory?limit=100").items

    /** Budget of the month. `GET /api/admin/budget/status`. */
    suspend fun getBudget(): ApiBudget =
        getJson("/api/admin/budget/status")

    /** Assignees. `GET /api/selfdev/assignees`. 403 unless the caller may manage assignees; 503 while roles are off. */
    suspend fun getAssignees(): List<ApiAssignee> =
        getJson("/api/selfdev/assignees")

    private val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]{0,200})\"")
}

/** Server `MemoryListResponse`: the page of notes plus the counts. Only the items are used by the UI. */
@Serializable
private data class ApiMemoryList(val items: List<ApiMemory>)
