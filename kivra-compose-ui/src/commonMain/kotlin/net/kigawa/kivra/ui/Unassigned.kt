package net.kigawa.kivra.ui

/*
 * 未分類(`project_id` が NULL のデータ: ADR 0022 で PR-F までに残る既存の Run・目標・order・記憶)を、
 * 疑似プロジェクト(固定 id [UNASSIGNED_PROJECT_ID])として扱う規則。サーバーの `unassigned`(ProjectFilter.Unassigned)と同じ値。
 *
 * - 未分類は一覧のデータだけを出す。ダッシュボード・担当・予算・リポジトリの対応は、プロジェクトの実体が無いので出さない。
 * - 作成(開発目標・order・タスク)は出さない。作成はプロジェクトに固定し、未分類にデータを増やさない。
 * - API は一覧の旧ルートに `?projectId=unassigned` を付ける(別名ルート `/api/projects/{id}/...` は ULID 専用で、unassigned は使えない)。
 */

/** 未分類の疑似プロジェクトの id。サーバーの `UNASSIGNED_PROJECT_ID` と同じ。 */
const val UNASSIGNED_PROJECT_ID = "unassigned"

/** 未分類の表示名。 */
const val UNASSIGNED_LABEL = "未分類"

/** 本物のプロジェクトの id の形(ULID。サーバーの PROJECT_ID_SHAPE と同じ)。 */
private val PROJECT_ID_SHAPE = Regex("[0-9A-Z]{26}")

/** [projectId] が未分類か。 */
fun isUnassigned(projectId: String?): Boolean = projectId == UNASSIGNED_PROJECT_ID

/**
 * 端末に保存していた「最後に選んだプロジェクト」を検証する。ULID か未分類の形のときだけ返し、それ以外(壊れた値・空)は null。
 * 保存先(localStorage)は他のものが書き換えうるので、読んだ値をそのまま URL にしない。
 */
fun validLastProjectId(raw: String?): String? =
    raw?.takeIf { isUnassigned(it) || PROJECT_ID_SHAPE.matches(it) }

/** プロジェクトの中の項目。未分類はデータの一覧だけ([NavDestination.unassignedPages])。 */
fun pagesOf(projectId: String?): List<NavDestination> =
    if (isUnassigned(projectId)) NavDestination.unassignedPages else NavDestination.projectPages

/** プロジェクトを開いたときの最初のページ。未分類にはダッシュボードが無いので、タスク(Run の一覧)にする。 */
fun landingPageOf(projectId: String): NavDestination =
    if (isUnassigned(projectId)) NavDestination.Tasks else NavDestination.Dashboard

/** 画面に出す名前。未分類は「未分類」、それ以外は名前(読めていなければ id)。 */
fun projectLabel(projectId: String, name: String?): String =
    if (isUnassigned(projectId)) UNASSIGNED_LABEL else name ?: projectId
