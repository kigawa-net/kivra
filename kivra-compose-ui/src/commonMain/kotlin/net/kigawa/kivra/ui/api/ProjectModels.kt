package net.kigawa.kivra.ui.api

import kotlinx.serialization.Serializable

/*
 * プロジェクト・リポジトリ・既存データの振り分け(backfill)の応答の型。サーバー(kivra-app の ProjectRoutes.kt)の
 * @Serializable クラスを映す。読むフィールドだけを書く(ApiClient は未知のキーを無視する)。
 */

/**
 * `GET /api/projects/{id}/dashboard`(server `ProjectDashboardResponse`)。scope は "ALL"(運用者: 全 Run)か "OWN"(自分の Run だけ)。
 * recentRuns は Run の一覧と同じ形([ApiRun])。
 */
@Serializable
data class ApiProjectDashboard(
    val project: ApiProject,
    val scope: String,
    val tasks: ApiProjectTaskCounts,
    val recentRuns: List<ApiRun>,
    val spend: ApiProjectSpend,
    val activity: List<ApiProjectActivity>,
    /** 運用者(scope ALL)だけ。他の利用者は null。 */
    val budget: ApiProjectBudget? = null,
)

/**
 * 今月の予算(server `ProjectBudgetResponse`)。金額は十進の円の文字列。上限が無い月は [monthlyLimitJpy]・[softRatio]・
 * [softLimitJpy]・[remainingJpy] が null。[softExceeded] は警告だけ(止めない)。
 */
@Serializable
data class ApiProjectBudget(
    val projectId: String,
    val period: String,
    val monthlyLimitJpy: String? = null,
    val softRatio: String? = null,
    val softLimitJpy: String? = null,
    val committedJpy: String,
    val reservedJpy: String,
    val remainingJpy: String? = null,
    val softExceeded: Boolean,
)

/** Run の件数(scope の中)。byState は出現した状態だけ。 */
@Serializable
data class ApiProjectTaskCounts(val total: Long, val byState: Map<String, Long>)

/** 費用(十進の円の文字列)。 */
@Serializable
data class ApiProjectSpend(
    val committedJpy: String,
    val reservedJpy: String,
    val lapsedJpy: String,
    val unpricedJpy: String,
    val totalJpy: String,
)

/** 活動の 1 件(固定の code だけ。担当者と自由文は無い)。 */
@Serializable
data class ApiProjectActivity(
    val runId: String,
    val occurredAt: String,
    val action: String,
    val result: String,
    val toState: String? = null,
    val reasonCode: String? = null,
)

/** `GET /api/projects/{id}/repos` の 1 件(server `ProjectRepoResponse`)。 */
@Serializable
data class ApiProjectRepo(val repoKey: String)

/** `POST /api/projects/backfill`(server `BackfillResponse`)。 */
@Serializable
data class ApiBackfillReport(val dryRun: Boolean, val tables: List<ApiBackfillTable>)

/** 表ごとの件数: matched(振り分け先がある)、updated(書いた)、unmatched(対応なし、未設定のまま)。 */
@Serializable
data class ApiBackfillTable(val table: String, val matched: Long, val updated: Long, val unmatched: Long)

/** `GET /api/projects/{id}/members`(server `ProjectMemberResponse`)の一人分。state は ACTIVE か DISABLED。 */
@Serializable
data class ApiProjectMember(val assigneeId: String, val displayName: String, val role: String, val state: String)

/** `POST /api/projects/{id}/members/import-all`(server `ImportAllResponse`)。件数だけ。 */
@Serializable
data class ApiMemberImportReport(
    val dryRun: Boolean,
    val role: String,
    val candidates: Long,
    val added: Long,
    val alreadyMember: Long,
    val disabled: Long,
)
