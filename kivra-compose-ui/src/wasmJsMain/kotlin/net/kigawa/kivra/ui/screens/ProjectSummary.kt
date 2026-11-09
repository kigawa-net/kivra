package net.kigawa.kivra.ui.screens

import kotlinx.coroutines.CancellationException
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiProjectBudget
import net.kigawa.kivra.ui.attentionRunCountOf
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
)

/** 1 件のプロジェクトの要約。読めない項目だけ null にして、他は出す(一部の失敗で全体を落とさない)。 */
internal suspend fun loadProjectSummary(projectId: String): ProjectSummary {
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

/** 読めない・見せない(503 自律運転の無効、403 の権限なし)は 0 件として扱う。それ以外は投げる。 */
internal suspend fun <T> unavailableAsEmpty(load: suspend () -> List<T>): List<T> =
    try {
        load()
    } catch (e: ApiException) {
        if (e.status == 503 || e.status == 403) emptyList() else throw e
    }
