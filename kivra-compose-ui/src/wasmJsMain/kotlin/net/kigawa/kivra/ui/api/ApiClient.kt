package net.kigawa.kivra.ui.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.ui.MemoryListFilter
import net.kigawa.kivra.ui.ProjectApi
import net.kigawa.kivra.ui.ReleaseKind
import net.kigawa.kivra.ui.SubmitFailure
import net.kigawa.kivra.ui.SubmitKind
import net.kigawa.kivra.ui.SubmitResult
import net.kigawa.kivra.ui.isValidAssigneeRef
import net.kigawa.kivra.ui.memoryListQuery
import net.kigawa.kivra.ui.percentEncode
import net.kigawa.kivra.ui.releasePath
import net.kigawa.kivra.ui.submitFailureOf
import net.kigawa.kivra.ui.submitResultOfSuccess

@Serializable private class SubmitTaskRequest(val title: String, val idempotencyKey: String)

@Serializable private class SubmitSelfDevRequest(val title: String, val body: String?, val idempotencyKey: String, val projectId: String)

/** `POST /api/selfdev/goals` の本文(server `SubmitGoalRequest`)。projectId は必須。 */
@Serializable private class SubmitGoalRequest(val title: String, val body: String?, val idempotencyKey: String, val projectId: String)

/** 投入の応答のうち使う値。Run の投入は runId、Goal の投入は taskId(どちらも状態の state を持つ)。 */
@Serializable private class SubmitReply(val runId: String? = null, val taskId: String? = null, val state: String? = null)

/** 書き込みの応答。[status] は HTTP のステータス(204 は本文なしの成功)、[text] は本文。 */
private class WriteReply(val status: Int, val text: String)

@Serializable private class CreateProjectRequest(val name: String, val description: String?)

/**
 * A failed API call: a non-2xx answer, or a body that does not match the model.
 * [status] is the HTTP status, or null when the body could not be read as the expected model or the call did not complete.
 * [detail] is the server's fixed `error` text when it sent one (for example `AUTOPILOT_DISABLED`), otherwise a fixed text of this client.
 * An expired session is always a 401 (see [ApiSession]).
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
 *
 * Session (旧 UI の apiFetch と同じ判定): a 401 ends the session, and so does a 2xx answer that is not JSON (an HTML sign-in
 * page served after a followed redirect). While the session is expired, no /api request is sent ([ApiSession.expired]).
 * A call marked `quiet` (the caller's own role, the budget) does not end the session on 401, as in the old UI.
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
    private suspend inline fun <reified T> getJson(path: String, quiet: Boolean = false): T {
        checkSession()
        val response = client.get(path)
        if (!response.status.isSuccess()) {
            throw failureOf(response, quiet)
        }
        if (!isJson(response)) {
            throw sessionLost(quiet)
        }
        return try {
            response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    /**
     * scope "mine" = 自分の Run(`/api/runs`、サーバー側で最大 50 件)。"all" = 運用者向けの全 Run(`/api/admin/runs`、最大 100 件)。
     * "all" は運用者以外では 403 の [ApiException] になる。サーバーは scope パラメータを解釈しない。
     */
    suspend fun getRuns(scope: String = "mine"): List<ApiRun> =
        if (scope == "all") getJson("/api/admin/runs?limit=100") else getJson("/api/runs")

    /**
     * プロジェクトの中の Run の一覧(scope は [getRuns] と同じ)。サーバーの `projectId` で絞る(ADR 0022。未分類の Run は含まれない)。
     * プロジェクトの中の画面はこれを読む(全体の一覧を読んで画面で絞らない)。
     */
    suspend fun getRunsIn(projectId: String, scope: String = "mine"): List<ApiRun> {
        val filter = "projectId=${percentEncode(projectId)}"
        return if (scope == "all") getJson("/api/admin/runs?limit=100&$filter") else getJson("/api/runs?$filter")
    }

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
     * Task を投入する。種別 [SubmitKind.SIMULATED] は `POST /api/tasks`(本文とプロジェクトは送らない。旧 UI と同じ)。
     * [SubmitKind.SELF_DEV] は `POST /api/projects/{id}/runs`(別名。未分類は旧ルート `POST /api/selfdev/tasks`。本文は空なら送らない。
     * プロジェクトは必須で、欠けていれば送らずに 400 扱い)。
     * 同じ内容の再送には同じ [idempotencyKey] を渡す(キーの管理は呼び出し側の [net.kigawa.kivra.ui.IdempotencyKeys])。
     */
    suspend fun submitTask(
        title: String,
        body: String?,
        kind: SubmitKind,
        projectId: String?,
        idempotencyKey: String,
    ): SubmitResult {
        if (kind == SubmitKind.SELF_DEV) {
            if (projectId == null) return SubmitResult.Failed(SubmitFailure.BAD_REQUEST)
            return submitRequest(
                ProjectApi.runSubmit(projectId),
                SubmitSelfDevRequest(title, body?.takeIf { it.isNotBlank() }, idempotencyKey, projectId),
            )
        }
        return submitRequest("/api/tasks", SubmitTaskRequest(title, idempotencyKey))
    }

    /**
     * Goal を投入する。`POST /api/projects/{projectId}/tasks`(別名。本文の projectId は path と同じ)。projectId は必須(ADR 0022)。
     * 503 は自律運転が無効。
     */
    suspend fun submitGoal(title: String, body: String?, projectId: String, idempotencyKey: String): SubmitResult =
        submitRequest(ProjectApi.goalSubmit(projectId), SubmitGoalRequest(title, body?.takeIf { it.isNotBlank() }, idempotencyKey, projectId))

    /**
     * 投入の共通処理。成功は 202(作成)か 200(再送)で、応答の id と state を読む。失敗は [ApiException] の status から
     * [SubmitFailure] にする。サーバーの本文は表示に使わない。
     */
    private suspend inline fun <reified B : Any> submitRequest(path: String, body: B): SubmitResult =
        try {
            val reply = postWriteReply(path, body)
            val parsed = parseSubmitReply(reply.text)
            submitResultOfSuccess(reply.status, parsed?.runId ?: parsed?.taskId, parsed?.state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            SubmitResult.Failed(submitFailureOf(e.status))
        } catch (e: Exception) {
            // 送信の途中で例外になった。結果が確かめられないので、通信の失敗として扱う(送信中のまま止まらないように)。
            SubmitResult.Failed(SubmitFailure.NETWORK)
        }

    private fun parseSubmitReply(text: String): SubmitReply? =
        try {
            replyJson.decodeFromString<SubmitReply>(text)
        } catch (e: Exception) {
            null
        }

    suspend fun getProjects(): List<ApiProject> =
        getJson("/api/projects")

    /**
     * プロジェクトの作成。`POST /api/projects`、本文 `{"name", "description"?}`(空の説明は送らない)。失敗は [ApiException]。
     * 403 は ORDER_MANAGE の無い利用者。
     */
    suspend fun createProject(name: String, description: String? = null) =
        postWrite("/api/projects", CreateProjectRequest(name, description?.takeIf { it.isNotBlank() }))

    /** プロジェクトの詳細(ダッシュボード)。`GET /api/projects/{id}/dashboard`。404 は無い id。scope は ALL か OWN。 */
    suspend fun getProjectDashboard(projectId: String): ApiProjectDashboard =
        getJson("/api/projects/${percentEncode(projectId)}/dashboard")

    /** 運用者: プロジェクトの repo の一覧。`GET /api/projects/{id}/repos`。 */
    suspend fun getProjectRepos(projectId: String): List<ApiProjectRepo> =
        getJson("/api/projects/${percentEncode(projectId)}/repos")

    /**
     * 運用者: repo の登録。`PUT /api/projects/{id}/repos`、本文 `{"repoKey": "owner/name"}`。409 は別のプロジェクトに登録済み、
     * 400 は形式、404 は無いプロジェクト、403 は権限(ORDER_MANAGE)。
     */
    suspend fun addProjectRepo(projectId: String, repoKey: String) =
        writeWith(HttpMethod.Put, "/api/projects/${percentEncode(projectId)}/repos", ProjectRepoBody(repoKey))

    /**
     * 運用者: repo の登録を外す。`DELETE /api/projects/{id}/repos?repoKey=owner/name`。本文は空の JSON オブジェクト `{}`
     * (サーバーは状態を変える要求に JSON だけを受ける)。404 は登録されていない repo。
     */
    suspend fun removeProjectRepo(projectId: String, repoKey: String) =
        writeWith(
            HttpMethod.Delete,
            "/api/projects/${percentEncode(projectId)}/repos?repoKey=${percentEncode(repoKey)}",
            EmptyJsonBody(),
        )

    /**
     * 運用者: 今月の予算の上限と警告の基準を設定する。`PUT /api/projects/{id}/budget`、本文 `{"monthlyLimitJpy": n, "softRatio": r}`。
     * 400 は値の形(上限は 0 以上の整数、警告の基準は 0 より大きく 1 以下)、404 は無いプロジェクト、403 は運用者でない。
     */
    suspend fun putProjectBudget(projectId: String, monthlyLimitJpy: Long, softRatio: Double) =
        writeWith(
            HttpMethod.Put,
            "/api/projects/${percentEncode(projectId)}/budget",
            ProjectBudgetBody(monthlyLimitJpy, softRatio),
        )

    /** 運用者: 今月の上限を解除する。`DELETE /api/projects/{id}/budget`、本文は空の JSON オブジェクト `{}`。 */
    suspend fun deleteProjectBudget(projectId: String) =
        writeWith(HttpMethod.Delete, "/api/projects/${percentEncode(projectId)}/budget", EmptyJsonBody())

    /**
     * 運用者: プロジェクトのメンバーの一覧。`GET /api/projects/{id}/members`(ORDER_MANAGE。403 は運用者でない、404 は無いプロジェクト)。
     */
    suspend fun getProjectMembers(projectId: String): List<ApiProjectMember> =
        getJson("/api/projects/${percentEncode(projectId)}/members")

    /**
     * 運用者: メンバーの追加、または role の変更。`PUT /api/projects/{id}/members/{assigneeId}`、本文 `{"role": "..."}`。
     * 追加は 201、同じ role の再送と変更は 200。409 は最後の ADMIN を降格させる、404 は担当かプロジェクトが無い。
     */
    suspend fun putProjectMember(projectId: String, assigneeId: String, role: String) =
        writeWith(
            HttpMethod.Put,
            "/api/projects/${percentEncode(projectId)}/members/${percentEncode(assigneeId)}",
            ProjectMemberBody(role),
        )

    /**
     * 運用者: メンバーを外す。`DELETE /api/projects/{id}/members/{assigneeId}`、本文は空の JSON オブジェクト `{}`。
     * 204 が成功。409 は最後の ADMIN、404 は メンバーでない担当。
     */
    suspend fun removeProjectMember(projectId: String, assigneeId: String) =
        writeWith(
            HttpMethod.Delete,
            "/api/projects/${percentEncode(projectId)}/members/${percentEncode(assigneeId)}",
            EmptyJsonBody(),
        )

    /**
     * 運用者: 全担当の取り込み。`POST /api/projects/{id}/members/import-all?dryRun=true|false`、本文 `{"role": "..."}`。
     * dryRun は数えるだけ(既定もそう)。有効で未登録の担当を指定の role で追加する。既存の role は変えない。
     */
    suspend fun importProjectMembers(projectId: String, role: String, dryRun: Boolean): ApiMemberImportReport {
        val text = postWriteText(
            "/api/projects/${percentEncode(projectId)}/members/import-all?dryRun=${if (dryRun) "true" else "false"}",
            ProjectMemberBody(role),
        )
        return try {
            replyJson.decodeFromString<ApiMemberImportReport>(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    /**
     * 既存データの振り分け。`POST /api/projects/backfill?dryRun=true|false`、本文 `{}`。dryRun は数えるだけ、実行は
     * プロジェクトが未設定の行だけを書く。403 は運用者でない。応答の形が違えば [ApiException]。
     */
    suspend fun backfillProjects(dryRun: Boolean): ApiBackfillReport {
        val text = postWriteText("/api/projects/backfill?dryRun=${if (dryRun) "true" else "false"}", EmptyJsonBody())
        return try {
            replyJson.decodeFromString<ApiBackfillReport>(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    /**
     * 運用者: プロジェクトを移し先へ空にする(drain)。`POST /api/projects/{id}/drain?dryRun=true|false`、本文 `{"targetProjectId": "..."}`。
     * dryRun は数えるだけ(既定もそう)。409 は進行中の Run か実行中の予約があり、今は空にできない。400・404 は移し先の指定。
     * 応答の形が違えば [ApiException]。
     */
    suspend fun drainProject(projectId: String, targetProjectId: String, dryRun: Boolean): ApiDrainReport {
        val text = postWriteText(
            "/api/projects/${percentEncode(projectId)}/drain?dryRun=${if (dryRun) "true" else "false"}",
            DrainBody(targetProjectId),
        )
        return try {
            replyJson.decodeFromString<ApiDrainReport>(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    /** 運用者: 空のプロジェクトを削除する。`DELETE /api/projects/{id}`、本文は空の JSON オブジェクト `{}`。409 は行が残っている。 */
    suspend fun deleteProject(projectId: String) =
        writeWith(HttpMethod.Delete, "/api/projects/${percentEncode(projectId)}", EmptyJsonBody())

    /**
     * Goal の再計画。`POST /api/selfdev/goals/{id}/replan`、本文 `{"version": n}`(読んだ version)。BLOCKED で計画の失敗のときだけ。
     * 409 は状態・version の競合、403 は権限(GOAL_WRITE)、503 は自律運転が無効。
     */
    suspend fun replanGoal(taskId: String, version: Long) =
        postWrite("/api/selfdev/goals/${percentEncode(taskId)}/replan", VersionRequest(version))

    /**
     * order の作成(ルートの order)。`POST /api/projects/{projectId}/orders`(別名)、本文 `{title, policy, maxCycles, projectId}`。
     * 冪等キーは送らない(サーバーの本文は厳密で、知らないフィールドは 400 になる)。ルートは複数作れる(2 つ目も受け付ける。ADR 0015 追補 2026-11-11)。
     */
    suspend fun createOrder(title: String, policy: String, maxCycles: Int, projectId: String) =
        postWrite(ProjectApi.orderCreate(projectId), CreateOrderBody(title, policy, maxCycles, projectId))

    /**
     * 呼び出し者の役割と権限。`GET /api/selfdev/me`。どの運用者にも 200 で返る。失敗は [ApiException]
     * (呼び出し側は [net.kigawa.kivra.ui.CallerPermissions.UNKNOWN] として扱う)。
     * 旧 UI の loadMe と同じく quiet。401 でもセッション切れの案内は出さない。
     */
    suspend fun getMe(): ApiMe = getJson("/api/selfdev/me", quiet = true)

    /**
     * 自己開発の有無。`GET /api/selfdev/status`。応答は `enabled` のほかに設定の要約を持つが、UI は有無だけを使う。
     * 401 はセッション切れ(旧 UI の refreshStatus と同じ)。
     */
    suspend fun getSelfDevStatus(): ApiSelfDevStatus = getJson("/api/selfdev/status")

    /**
     * `GET /health` が `{"status": "ok"}` で応えるか。/api ではないので、セッションの判定は行わない(旧 UI の checkHealth と同じ)。
     * 通信の失敗や形の違いは false。
     */
    suspend fun checkHealth(): Boolean =
        try {
            val response = client.get("/health")
            response.status.isSuccess() && response.body<HealthBody>().status == "ok"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /**
     * Goals (= selfdev tasks). [projectId] が null なら `GET /api/selfdev/goals`、あれば別名 `GET /api/projects/{id}/tasks`。
     * 503 `AUTOPILOT_DISABLED` while the autopilot is off.
     */
    suspend fun getGoals(projectId: String? = null): List<ApiGoal> =
        getJson(ProjectApi.goals(projectId))

    /** Orders. [projectId] が null なら `GET /api/selfdev/orders`、あれば別名 `GET /api/projects/{id}/orders`。503 while the autopilot is off. */
    suspend fun getOrders(projectId: String? = null): List<ApiOrder> =
        getJson(ProjectApi.orders(projectId))

    /**
     * The approvals page's data: the pending stop requests of the orders. `GET /api/selfdev/stop-requests?state=PENDING`,
     * or with [projectId] the alias `GET /api/projects/{id}/approvals?state=PENDING` (the project's orders' requests).
     * 403 when the caller is not the root approver (the old UI hides the page then).
     */
    suspend fun getApprovals(projectId: String? = null): List<ApiStopRequest> =
        getJson(ProjectApi.approvals(projectId))

    /** Memory notes (first page, up to the server's maximum of 100 items). `GET /api/selfdev/memory`. 503 while memory is off. */
    suspend fun getMemories(): List<ApiMemory> =
        getJson<ApiMemoryList>("/api/selfdev/memory?limit=100").items

    /**
     * Budget of the month. `GET /api/admin/budget/status`. quiet: 401 / 403 / 404 mean "not for this user" (the old UI hides the
     * section and keeps the session), so they do not end the session.
     */
    suspend fun getBudget(): ApiBudget =
        getJson("/api/admin/budget/status", quiet = true)

    /** 為替の期限。`GET /api/admin/budget/fx`(運用者向け)。quiet: 401 / 403 / 404 は「この利用者には見せない」で、セッションは切らない。 */
    suspend fun getBudgetFx(): ApiBudgetFxResponse =
        getJson("/api/admin/budget/fx", quiet = true)

    /** 段階 1 → 2 の昇格条件。`GET /api/selfdev/autopilot/promotion`(運用者だけ。自律運転が無効なら 503)。読み取りのみ。 */
    suspend fun getPromotion(): ApiPromotionReadout =
        getJson("/api/selfdev/autopilot/promotion")

    /**
     * 実行の経過(CLI 経路の Run)。`GET /api/admin/runs/{id}/execution`。quiet: 401 / 403 / 404 は「この Run には節を出さない」
     * (旧 UI の apiFetch と同じく、セッションは切らない)。
     */
    suspend fun getRunExecution(runId: String): ApiRunExecution =
        getJson("/api/admin/runs/${percentEncode(runId)}/execution", quiet = true)

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
     * 自律運転の状態。`GET /api/selfdev/autopilot`。503 `AUTOPILOT_DISABLED` は無効(節を出さない)、403 は READ の無い利用者。
     */
    suspend fun getAutopilot(): ApiAutopilot = getJson("/api/selfdev/autopilot")

    /** 自律運転の一時停止。`POST /api/selfdev/autopilot/pause`、本文は空の JSON オブジェクト `{}`。403 は ORDER_MANAGE の無い利用者。 */
    suspend fun pauseAutopilot() = postWrite("/api/selfdev/autopilot/pause", EmptyJsonBody())

    /** 自律運転の再開。`POST /api/selfdev/autopilot/resume`、本文 `{}`。 */
    suspend fun resumeAutopilot() = postWrite("/api/selfdev/autopilot/resume", EmptyJsonBody())

    /** サーキットブレーカーの手動再開(連続失敗の数を 0 に戻す)。`POST /api/selfdev/autopilot/reset-breaker`、本文 `{}`。 */
    suspend fun resetAutopilotBreaker() = postWrite("/api/selfdev/autopilot/reset-breaker", EmptyJsonBody())

    /**
     * order の一時停止。`POST /api/selfdev/orders/{id}/pause`、本文 `{"version": n}`(読んだ version)。
     * 409 は状態・version の競合、403 は権限(ORDER_MANAGE)。
     */
    suspend fun pauseOrder(orderId: String, version: Long) =
        postWrite("/api/selfdev/orders/${percentEncode(orderId)}/pause", VersionRequest(version))

    /**
     * order の再開。`POST /api/selfdev/orders/{id}/resume`、本文 `{"version": n}`。LIMIT_REACHED / EXPIRED の一時停止は 409。
     */
    suspend fun resumeOrder(orderId: String, version: Long) =
        postWrite("/api/selfdev/orders/${percentEncode(orderId)}/resume", VersionRequest(version))

    /**
     * order の延長(運用者のみ)。`POST /api/selfdev/orders/{id}/extend`、本文 `{"version": n, "addCycles": 1.., "extendDays": 1..14}`。
     * 読んだ version を送る。どちらかは null でよい(その項目は延ばさない)。400 は上限・期限の超過(MAX_CYCLES_INVALID など)、
     * 409 は版の競合(STALE)や延長できない状態(NOT_ROOT など)。判定の詳細は OrderExtendRules.kt。
     */
    suspend fun extendOrder(orderId: String, version: Long, addCycles: Int?, extendDays: Int?) =
        postWrite("/api/selfdev/orders/${percentEncode(orderId)}/extend", ExtendOrderBody(version, addCycles, extendDays))

    /**
     * order の停止要求。`POST /api/selfdev/orders/{id}/stop-requests`、本文 `{"version": n, "reasonCode": "..."}`。
     * reasonCode は要求できる 4 種のいずれか(一覧から選ぶ)。承認されるまで order は止まらない。403 は権限(STOP_REQUEST)。
     */
    suspend fun requestOrderStop(orderId: String, version: Long, reasonCode: String) =
        postWrite("/api/selfdev/orders/${percentEncode(orderId)}/stop-requests", OrderStopRequest(version, reasonCode))

    /**
     * 待っている停止要求の取り消し。`POST /api/selfdev/stop-requests/{id}/cancel`、本文 `{}`。
     * 403 は要求者でも停止の判断者でもない時。取り消すと order は一時停止中になる。
     */
    suspend fun cancelOrderStopRequest(requestId: String) =
        postWrite("/api/selfdev/stop-requests/${percentEncode(requestId)}/cancel", EmptyJsonBody())

    /**
     * 書き込みの共通処理。2xx 以外は [ApiException](HTTP ステータス付き)を投げる。通信の失敗は status を null にした [ApiException]。
     * 応答の本文は読まない(承認の応答は使わないため、本文の形の違いで成功を失敗にしない)。
     */
    private suspend inline fun <reified B : Any> postWrite(path: String, body: B) {
        postWriteText(path, body)
    }

    /** [postWriteReply] の本文の文字列だけを返す版。応答の本文の `status` などを読む時に使う。 */
    private suspend inline fun <reified B : Any> postWriteText(path: String, body: B): String =
        postWriteReply(path, body).text

    /** 書き込みの動詞を選べる版(PUT / DELETE。応答の本文は読まない)。 */
    private suspend inline fun <reified B : Any> writeWith(verb: HttpMethod, path: String, body: B) {
        postWriteReply(path, body, verb)
    }

    private suspend inline fun <reified B : Any> writeReply(verb: HttpMethod, path: String, body: B): WriteReply =
        postWriteReply(path, body, verb)

    /**
     * [postWrite] の本文とステータスを返す版。セッション切れの後は送らない。送信中は [ApiSession.hold] で定期更新を止める。
     * 204 は本文なしの成功。2xx で JSON でない応答はサインイン画面への追従とみなし、セッション切れにする(旧 UI と同じ)。
     */
    private suspend inline fun <reified B : Any> postWriteReply(path: String, body: B, verb: HttpMethod = HttpMethod.Post): WriteReply {
        checkSession()
        ApiSession.hold()
        try {
            val response = try {
                client.request(path) {
                    method = verb
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ApiException(null, NETWORK_FAILURE)
            }
            if (!response.status.isSuccess()) {
                throw failureOf(response, quiet = false)
            }
            if (response.status == HttpStatusCode.NoContent) {
                return WriteReply(response.status.value, "")
            }
            if (!isJson(response)) {
                throw sessionLost(quiet = false)
            }
            val text = try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            return WriteReply(response.status.value, text)
        } finally {
            ApiSession.unhold()
        }
    }

    /**
     * 非 2xx の応答を [ApiException] にする。401 はセッション切れ(quiet を除く)。サーバーの `error` と機械の `code` は固定の形のものだけ残す。
     */
    private suspend fun failureOf(response: HttpResponse, quiet: Boolean): ApiException {
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        if (response.status.value == 401 && !quiet) {
            ApiSession.markExpired()
        }
        val error = ERROR_FIELD.find(text)?.groupValues?.get(1) ?: response.status.description
        return ApiException(response.status.value, error, CODE_FIELD.find(text)?.groupValues?.get(1))
    }

    /** セッションが切れた時の例外。quiet でなければ [ApiSession] を切れた状態にする。 */
    private fun sessionLost(quiet: Boolean): ApiException {
        if (!quiet) ApiSession.markExpired()
        return ApiException(401, SESSION_DETAIL)
    }

    /** 切れた後は /api へ要求を送らない(旧 UI の apiFetch の先頭の判定)。 */
    private fun checkSession() {
        if (ApiSession.expired) throw ApiException(401, SESSION_DETAIL)
    }

    /** JSON の応答か(`application/json`、パラメータは問わない)。 */
    private fun isJson(response: HttpResponse): Boolean =
        response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
            ?.equals("application/json", ignoreCase = true) == true

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

    // ---- 担当(Assignee)の管理。サーバーは SelfDevAssigneeRoutes.kt。トークンの平文は発行の応答にだけある ----

    /** 担当の一覧。[projectId] が null なら全部、あれば別名 `GET /api/projects/{id}/assignees`(そのプロジェクトのメンバー)。 */
    suspend fun getAssigneesIn(projectId: String?): List<ApiAssignee> =
        getJson(ProjectApi.assignees(projectId))

    /** 担当の作成。`POST /api/selfdev/assignees`、本文 `{displayName, role}`。201。 */
    suspend fun createAssignee(displayName: String, role: String) =
        postWrite("/api/selfdev/assignees", CreateAssigneeBody(displayName, role))

    /** ロールの変更。`POST .../{id}/role`、本文 `{role, version}`。409 は version の競合。 */
    suspend fun changeAssigneeRole(assigneeId: String, role: String, version: Long) =
        postWrite("/api/selfdev/assignees/${percentEncode(assigneeId)}/role", ChangeRoleBody(role, version))

    /** 有効化・無効化。`POST .../{id}/enable` または `.../disable`、本文 `{version}`。無効化はトークンを全部失効する。 */
    suspend fun setAssigneeEnabled(assigneeId: String, enabled: Boolean, version: Long) {
        val action = if (enabled) "enable" else "disable"
        postWrite("/api/selfdev/assignees/${percentEncode(assigneeId)}/$action", VersionRequest(version))
    }

    /**
     * 担当の詳細(メンバーとトークン)。`GET .../{id}/members` と `GET .../{id}/tokens`。形の合わない参照は除く。
     * どちらかが失敗したら例外(呼び出し側が文言を出す)。
     */
    suspend fun getAssigneeDetail(assigneeId: String): ApiAssigneeDetail {
        val id = percentEncode(assigneeId)
        val members: List<ApiMemberRef> = getJson("/api/selfdev/assignees/$id/members")
        val tokens: List<ApiAssigneeToken> = getJson("/api/selfdev/assignees/$id/tokens")
        return ApiAssigneeDetail(
            memberRefs = members.map { it.memberRef }.filter { isValidAssigneeRef(it) },
            tokens = tokens.filter { isValidAssigneeRef(it.tokenRef) },
        )
    }

    /** 人(Keycloak の sub)の紐付け。`POST .../{id}/members`、本文 `{sub}`。201(応答の memberRef は使わない)。 */
    suspend fun bindAssigneeMember(assigneeId: String, sub: String) =
        postWrite("/api/selfdev/assignees/${percentEncode(assigneeId)}/members", BindMemberBody(sub))

    /** 紐付けの解除。`DELETE .../{id}/members/{memberRef}`、本文は `{}`(サーバーは JSON の Content-Type を要求する)。204。 */
    suspend fun unbindAssigneeMember(assigneeId: String, memberRef: String) {
        postWriteReply(
            "/api/selfdev/assignees/${percentEncode(assigneeId)}/members/${percentEncode(memberRef)}",
            EmptyJsonBody(),
            HttpMethod.Delete,
        )
    }

    /**
     * トークンの発行。`POST .../{id}/tokens`、本文 `{ttlDays}`。201 の応答に平文が入る。
     * 戻り値の平文は呼び出し側が 1 度だけ使い、保持しない。
     */
    suspend fun issueAssigneeToken(assigneeId: String, ttlDays: Int): ApiIssuedToken {
        val text = postWriteText("/api/selfdev/assignees/${percentEncode(assigneeId)}/tokens", IssueTokenBody(ttlDays))
        return try {
            replyJson.decodeFromString<ApiIssuedToken>(text)
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    /** トークンの失効。`POST .../{id}/tokens/{tokenRef}/revoke`、本文 `{}`。204。 */
    suspend fun revokeAssigneeToken(assigneeId: String, tokenRef: String) =
        postWrite("/api/selfdev/assignees/${percentEncode(assigneeId)}/tokens/${percentEncode(tokenRef)}/revoke", EmptyJsonBody())

    // ---- 記憶の操作。サーバーは SelfDevMemoryRoutes.kt。一覧は本文を返さない未検証の文章を含む(本文は null) ----

    /**
     * 記憶の 1 ページ。`GET /api/selfdev/memory?limit=20&offset=N[&kind][&tier]`。[projectId] があれば別名
     * `GET /api/projects/{id}/memory?...`(project は path で渡す)。
     */
    suspend fun getMemoryPage(filter: MemoryListFilter, offset: Int, projectId: String?): ApiMemoryPage =
        getJson(ProjectApi.withQuery(ProjectApi.memory(projectId), memoryListQuery(filter, offset, null)))

    /** 個別の削除。`POST /api/selfdev/memory/{id}/delete`、本文 `{reason}`(理由は trim 済み)。404 は既に無い。 */
    suspend fun deleteMemory(recordId: String, reason: String) =
        postWrite("/api/selfdev/memory/${percentEncode(recordId)}/delete", ReasonRequest(reason))

    /**
     * 条件での削除。`POST /api/selfdev/memory/delete-by-condition`。本文は条件(kind・tier のうち選んだもの)と dryRun。
     * 本削除(dryRun = false)には理由が要る。dryRun は件数だけを返す。409 は上限超え(matched を含む)。
     */
    suspend fun deleteMemoriesByCondition(filter: MemoryListFilter, dryRun: Boolean, reason: String?): ApiMemoryCondition {
        val body = buildJsonObject {
            filter.kind?.let { put("kind", it) }
            filter.tier?.let { put("tier", it) }
            put("dryRun", dryRun)
            if (reason != null) put("reason", reason)
        }
        val text = postWriteText("/api/selfdev/memory/delete-by-condition", body)
        return try {
            replyJson.decodeFromString<ApiMemoryCondition>(text)
        } catch (e: Exception) {
            throw ApiException(null, "response is not the expected format")
        }
    }

    private val replyJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]{0,200})\"")
    private val CODE_FIELD = Regex("\"code\"\\s*:\\s*\"([A-Z_]{1,32})\"")
    private val ACK_STATUS_ALREADY = Regex("\"status\"\\s*:\\s*\"ALREADY_ACKNOWLEDGED\"")
    private const val NETWORK_FAILURE = "network"
    private const val SESSION_DETAIL = "session expired"
}

@Serializable private class DigestRequest(val digest: String)

@Serializable private class ReasonRequest(val reason: String)

@Serializable private class StopGoalRequest(val version: Long, val reason: String)

@Serializable private class VersionRequest(val version: Long)

/** repo の登録の本文(server `ProjectRepoRequest`)。 */
@Serializable private class ProjectRepoBody(val repoKey: String)

/** メンバーの追加・role の変更・取り込みの本文(server `ProjectMemberRequest` / `ProjectMemberImportRequest`)。 */
@Serializable private class ProjectMemberBody(val role: String)

/** 今月の予算の設定の本文(server `ProjectBudgetInput`)。上限は円の整数、警告の基準は 0 より大きく 1 以下。 */
@Serializable private class ProjectBudgetBody(val monthlyLimitJpy: Long, val softRatio: Double)

@Serializable private class DrainBody(val targetProjectId: String)

/** order の作成の本文(server `CreateOrderRequest`)。冪等キーは持たない(サーバーは知らないフィールドを拒否する)。 */
@Serializable private class CreateOrderBody(val title: String, val policy: String, val maxCycles: Int, val projectId: String)

/** order の停止要求の本文(server `RequestStopRequest`)。フィールドは厳密に検査されるので、この二つだけ送る。 */
@Serializable private class OrderStopRequest(val version: Long, val reasonCode: String)

/** order の延長の本文(server `ExtendOrderRequest`)。フィールドは厳密に検査されるので、この三つだけ送る。 */
@Serializable private class ExtendOrderBody(val version: Long, val addCycles: Int? = null, val extendDays: Int? = null)

/** 本文が空の JSON オブジェクト `{}`(フィールドなし)。 */
@Serializable private class EmptyJsonBody

/** 担当の作成の本文(server `CreateAssigneeRequest`)。 */
@Serializable private class CreateAssigneeBody(val displayName: String, val role: String)

/** ロールの変更の本文(server `ChangeAssigneeRoleRequest`)。 */
@Serializable private class ChangeRoleBody(val role: String, val version: Long)

/** 人の紐付けの本文(server `BindMemberRequest`)。 */
@Serializable private class BindMemberBody(val sub: String)

/** トークンの発行の本文(server `IssueTokenRequest`)。 */
@Serializable private class IssueTokenBody(val ttlDays: Int)

/** `GET /health` の本文(`status` だけ読む)。 */
@Serializable private class HealthBody(val status: String? = null)

/** Server `MemoryListResponse`: the page of notes plus the counts. Only the items are used by the UI. */
@Serializable
private data class ApiMemoryList(val items: List<ApiMemory>)
