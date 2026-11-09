package net.kigawa.kivra.ui.screens

import kotlinx.coroutines.CancellationException
import net.kigawa.kivra.ui.UNASSIGNED_PROJECT_ID
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiProjectBudget
import net.kigawa.kivra.ui.api.ApiRun
import net.kigawa.kivra.ui.attentionRunCountOf
import net.kigawa.kivra.ui.isUnassigned
import net.kigawa.kivra.ui.runningCountOf

/**
 * プロジェクトの要約(ホームとプロジェクト一覧に出す件数)。null は読めなかったこと(0 件とは区別して出す)。
 * 承認待ちは `/api/projects/{id}/approvals`、Run の件数と予算はダッシュボード(`/api/projects/{id}/dashboard`)。
 */
internal data class ProjectSummary(
    val approvals: Int?,
    val running: Long?,
    val attention: Long?,
    val budget: ApiProjectBudget?,
    /** 未分類だけ: 読めた行の数(Run・目標・order・承認の合計)。0 のときは一覧で薄く出す。読めなければ null。 */
    val unassignedRows: Int? = null,
)

/**
 * 1 件のプロジェクトの要約。読めない項目だけ null にして、他は出す(一部の失敗で全体を落とさない)。
 * 未分類は、ダッシュボードが無いので一覧([ApiClient.getRunsIn] など)から数える。件数は読んだ範囲(最大 100 件)。
 */
internal suspend fun loadProjectSummary(projectId: String): ProjectSummary {
    if (isUnassigned(projectId)) return loadUnassignedSummary()
    val approvals = try {
        unavailableAsEmpty { ApiClient.getApprovals(projectId) }.size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val dashboard = try {
        loadProjectDashboard(projectId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return ProjectSummary(
        approvals = approvals,
        running = dashboard?.let { runningCountOf(it.tasks.byState) },
        attention = dashboard?.let { attentionRunCountOf(it.tasks.byState) },
        budget = dashboard?.budget,
    )
}

/** 未分類の要約。Run は運用者なら全員分、そうでなければ自分の分(403 のとき)。目標と order は自律運転が無効なら 0 件。 */
private suspend fun loadUnassignedSummary(): ProjectSummary {
    val runs: List<ApiRun>? = try {
        try {
            ApiClient.getRunsIn(UNASSIGNED_PROJECT_ID, "all")
        } catch (e: ApiException) {
            if (e.status == 403) ApiClient.getRunsIn(UNASSIGNED_PROJECT_ID, "mine") else throw e
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val approvals = try {
        unavailableAsEmpty { ApiClient.getApprovals(UNASSIGNED_PROJECT_ID) }.size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val goals = try {
        unavailableAsEmpty { ApiClient.getGoals(UNASSIGNED_PROJECT_ID) }.size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val orders = try {
        unavailableAsEmpty { ApiClient.getOrders(UNASSIGNED_PROJECT_ID) }.size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val byState = runs?.groupingBy { it.state }?.eachCount()?.mapValues { it.value.toLong() }
    val counts = listOf(runs?.size, approvals, goals, orders)
    return ProjectSummary(
        approvals = approvals,
        running = byState?.let { runningCountOf(it) },
        attention = byState?.let { attentionRunCountOf(it) },
        budget = null,
        unassignedRows = if (counts.all { it == null }) null else counts.sumOf { it ?: 0 },
    )
}

/** 読めない・見せない(503 自律運転の無効、403 の権限なし)は 0 件として扱う。それ以外は投げる。 */
internal suspend fun <T> unavailableAsEmpty(load: suspend () -> List<T>): List<T> =
    try {
        load()
    } catch (e: ApiException) {
        if (e.status == 503 || e.status == 403) emptyList() else throw e
    }
