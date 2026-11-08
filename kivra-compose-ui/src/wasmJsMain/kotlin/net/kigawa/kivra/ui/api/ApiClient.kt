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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.ui.ReleaseKind
import net.kigawa.kivra.ui.percentEncode
import net.kigawa.kivra.ui.releasePath

@Serializable private class SubmitTaskRequest(val title: String, val idempotencyKey: String)

@Serializable private class SubmitSelfDevRequest(val title: String, val body: String?, val idempotencyKey: String)

@Serializable private class CreateProjectRequest(val name: String, val description: String?)

/**
 * A failed API call: a non-2xx answer, or a body that does not match the model.
 * [status] is the HTTP status, or null when the body could not be read as the expected model.
 * [detail] is the server's fixed `error` text when it sent one (for example `AUTOPILOT_DISABLED`), otherwise the status text.
 */
class ApiException(val status: Int?, val detail: String, val code: String? = null) : Exception(
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

    /**
     * 停止要求の承認。`POST /api/selfdev/stop-requests/{id}/approve`、本文 `{"digest": "..."}`(一覧で読んだ digest をそのまま返す)。
     * 応答は order の要約(使わない)。409 は状態の競合、403 は権限(root 承認者でない / 自分の要求)。
     */
    suspend fun approveStopRequest(requestId: String, digest: String) =
        postWrite("/api/selfdev/stop-requests/${percentEncode(requestId)}/approve", DigestRequest(digest))

    /**
     * 停止要求の却下。`POST /api/selfdev/stop-requests/{id}/reject`、本文は空の JSON オブジェクト `{}`(JSON の Content-Type が必須)。
     * 理由は取らない(app.js と同じ)。
     */
    suspend fun rejectStopRequest(requestId: String) =
        postWrite("/api/selfdev/stop-requests/${percentEncode(requestId)}/reject", EmptyJsonBody())

    /**
     * 開発目標(Task)の承認。`POST /api/selfdev/tasks/{id}/approve`、本文 `{"version": n}`(読んだ version)。
     * 409 は version の競合または状態の不整合、403 は権限(GOAL_WRITE)。計画の親タスク(revision > 0)は 409。
     */
    suspend fun approveGoal(taskId: String, version: Long) =
        postWrite("/api/selfdev/tasks/${percentEncode(taskId)}/approve", VersionRequest(version))

    /**
     * 開発目標(Task)の見送り(却下)。`POST /api/selfdev/tasks/{id}/reject`、本文 `{"version": n}`。
     * 状態は SKIPPED になる。理由は取らない(app.js の「見送る」と同じ)。
     */
    suspend fun rejectGoal(taskId: String, version: Long) =
        postWrite("/api/selfdev/tasks/${percentEncode(taskId)}/reject", VersionRequest(version))

    /**
     * 書き込みの共通処理。2xx 以外は [ApiException](HTTP ステータス付き)を投げる。通信の失敗は status を null にした [ApiException]。
     * 応答の本文は読まない(承認の応答は使わないため、本文の形の違いで成功を失敗にしない)。
     */
    private suspend inline fun <reified B : Any> postWrite(path: String, body: B) {
        postWriteText(path, body)
    }

    /** [postWrite] の本文の文字列を返す版。応答の本文の `status` などを読む時に使う。 */
    private suspend inline fun <reified B : Any> postWriteText(path: String, body: B): String {
        val response = try {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(null, NETWORK_FAILURE)
        }
        if (!response.status.isSuccess()) {
            throw failureOf(response)
        }
        return try {
            response.bodyAsText()
        } catch (e: Exception) {
            ""
        }
    }

    /** A non-2xx write answer: the status, the server's fixed `error` text, and the machine `code` when it sent one. */
    private suspend fun failureOf(response: HttpResponse): ApiException {
        val text = try {
            response.bodyAsText()
        } catch (e: Exception) {
            ""
        }
        val error = ERROR_FIELD.find(text)?.groupValues?.get(1) ?: response.status.description
        return ApiException(response.status.value, error, CODE_FIELD.find(text)?.groupValues?.get(1))
    }

    /**
     * Run の解放(運用者)。UNKNOWN は `POST .../release`、束縛なしの PR は `POST .../release-unbound-pr`(パスは [releasePath])。
     * 本文は `{"reason": "..."}` だけ(サーバーは余計なフィールドを拒否する)。冪等キーは取らない。
     * 409 は状態が変わった、404 は無い Run、400 は理由の形式、403 は権限(BUDGET_OVERRIDE)。
     */
    suspend fun releaseRun(runId: String, kind: ReleaseKind, reason: String) =
        postWrite(releasePath(runId, kind), ReasonRequest(reason))

    /**
     * Goal(Task)の中止。`POST /api/selfdev/goals/{id}/stop`、本文 `{"version": n, "reason": "..."}`(読んだ version)。
     * 理由はサーバーが保存もログもしない。409 は状態・version の競合、503 は自律運転が無効、403 は権限(GOAL_STOP)。
     */
    suspend fun stopGoal(taskId: String, version: Long, reason: String) =
        postWrite("/api/selfdev/goals/${percentEncode(taskId)}/stop", StopGoalRequest(version, reason))

    /**
     * 未確定の予約の解除(運用者)。`POST /api/admin/budget/acknowledge`。本文は scopeId・period・reservationKey(一覧の行の値)、
     * evidence・reason(trim 済み)、actualAmountJpy(任意。空欄なら送らない = 実額不明)。冪等キーは取らない(同じ解除は
     * サーバーが ALREADY_ACKNOWLEDGED で返す)。400 は code に TOO_LARGE / NOT_COMPARABLE が入ることがある。
     * 戻り値は「既に解除済みだった」なら true(応答の status が ALREADY_ACKNOWLEDGED)。
     */
    suspend fun acknowledgeBudget(
        scopeId: String,
        period: String,
        reservationKey: String,
        actualAmountJpy: String?,
        evidence: String,
        reason: String,
    ): Boolean {
        val body = buildJsonObject {
            put("scopeId", scopeId)
            put("period", period)
            put("reservationKey", reservationKey)
            if (actualAmountJpy != null) put("actualAmountJpy", actualAmountJpy)
            put("evidence", evidence)
            put("reason", reason)
        }
        val text = postWriteText("/api/admin/budget/acknowledge", body)
        return ACK_STATUS_ALREADY.containsMatchIn(text)
    }

    private val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]{0,200})\"")
    private val CODE_FIELD = Regex("\"code\"\\s*:\\s*\"([A-Z_]{1,32})\"")
    private val ACK_STATUS_ALREADY = Regex("\"status\"\\s*:\\s*\"ALREADY_ACKNOWLEDGED\"")
    private const val NETWORK_FAILURE = "network"
}

@Serializable private class DigestRequest(val digest: String)

@Serializable private class ReasonRequest(val reason: String)

@Serializable private class StopGoalRequest(val version: Long, val reason: String)

@Serializable private class VersionRequest(val version: Long)

/** 本文が空の JSON オブジェクト `{}`(フィールドなし)。 */
@Serializable private class EmptyJsonBody

/** Server `MemoryListResponse`: the page of notes plus the counts. Only the items are used by the UI. */
@Serializable
private data class ApiMemoryList(val items: List<ApiMemory>)
