package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiBackfillReport
import net.kigawa.kivra.ui.api.ApiBackfillTable
import net.kigawa.kivra.ui.api.ApiMe
import net.kigawa.kivra.ui.api.ApiProjectActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectsOrdersRulesTest {

    private val operator = CallerPermissions.of(ApiMe(mode = "enforce", role = "OPERATOR", permissions = listOf("READ", "ORDER_MANAGE", "GOAL_WRITE")))
    private val viewer = CallerPermissions.of(ApiMe(mode = "enforce", role = "VIEWER", permissions = listOf("READ")))

    // ---- プロジェクト ----

    @Test
    fun projectNameIsTrimmedAndLimitedToOneHundredUnits() {
        assertEquals("abc", validatedProjectName("  abc  "))
        assertNull(validatedProjectName("   "))
        assertEquals("a".repeat(100), validatedProjectName("a".repeat(100)))
        assertNull(validatedProjectName("a".repeat(101)))
    }

    @Test
    fun projectDescriptionMayBeEmptyAndIsLimitedToFiveHundred() {
        assertEquals("", validatedProjectDescription("   "))
        assertNull(validatedProjectDescription("a".repeat(501)))
    }

    @Test
    fun createFailureIsFixedTextExceptSessionLoss() {
        assertEquals("プロジェクトの作成に失敗しました。", projectCreateFailureText(500))
        assertEquals("プロジェクトの作成に失敗しました。", projectCreateFailureText(null))
        assertEquals(SESSION_EXPIRED_MESSAGE, projectCreateFailureText(401))
    }

    @Test
    fun repoKeyMustBeOwnerSlashName() {
        assertTrue(isRepoKeyShape("kigawa-net/beta"))
        assertTrue(isRepoKeyShape("kigawa-net/kivra.v2_x"))
        assertFalse(isRepoKeyShape("not a repo"))
        assertFalse(isRepoKeyShape("a/b/c"))
        assertFalse(isRepoKeyShape("owner/"))
        assertFalse(isRepoKeyShape("a".repeat(141)))
    }

    @Test
    fun repoRemoveExplainNamesTheRepoAndKeepsRoutedRows() {
        val text = projectRepoRemoveExplain("kigawa-net/beta")
        assertTrue(text.contains("kigawa-net/beta"))
        assertTrue(text.contains("変わりません"))
    }

    @Test
    fun repoFailureTextsAreFixedByStatus() {
        assertTrue(projectRepoFailureText(409).contains("既に登録"))
        assertEquals(REPO_INVALID_MESSAGE, projectRepoFailureText(400))
        assertEquals(SESSION_EXPIRED_MESSAGE, projectRepoFailureText(401))
        assertEquals("操作に失敗しました。時間をおいてもう一度お試しください。", projectRepoFailureText(null))
    }

    @Test
    fun operatorBlockFollowsTheDashboardScope() {
        assertTrue(showsProjectOperator("ALL"))
        assertFalse(showsProjectOperator("OWN"))
    }

    @Test
    fun scopeAndTotalTexts() {
        assertTrue(projectScopeText("ALL").contains("すべての Run"))
        assertTrue(projectScopeText("OWN").contains("あなたが投入した Run だけ"))
        assertEquals("Run はまだありません。", projectTotalText(0))
        assertEquals("全部で 3 件です。", projectTotalText(3))
    }

    // ---- ダッシュボードの表示 ----

    @Test
    fun stateRowsFollowTheStateOrderThenUnknownCodes() {
        val rows = projectStateRows(mapOf("COMPLETED" to 1L, "ZZZ" to 3L, "DEVELOPING" to 2L))
        assertEquals(listOf("DEVELOPING" to 2L, "COMPLETED" to 1L, "ZZZ" to 3L), rows)
    }

    @Test
    fun stateTextShowsCodeAndDescription() {
        assertEquals("DEVELOPING（実装中）", projectRunStateText("DEVELOPING"))
        assertEquals("ZZZ（不明な状態です（この画面に説明はありません））", projectRunStateText("ZZZ"))
    }

    @Test
    fun reasonTextUsesTheLabelOrTheFallbacks() {
        assertEquals("PR がマージされ、マージを検証した（PR_MERGED）", reasonText("PR_MERGED"))
        assertEquals("開発エージェント（モデル）の結果を使えなかった（DEVELOPER_X）", reasonText("DEVELOPER_X"))
        assertEquals("不明な理由（FOO）", reasonText("FOO"))
        assertEquals("", reasonText(null))
    }

    @Test
    fun activityLineHasActionStateReasonAndResult() {
        val event = ApiProjectActivity(
            runId = "R1",
            occurredAt = "2026-10-18T10:05:00Z",
            action = "RUN_TRANSITION",
            result = "OK",
            toState = "DEVELOPING",
            reasonCode = null,
        )
        assertEquals("Run R1  2026-10-18T10:05:00Z  状態の変更 → DEVELOPING  [OK]", projectActivityText(event))
        val unknown = event.copy(action = "EVIL", toState = null, reasonCode = "CODING_TIMEOUT")
        assertEquals(
            "Run R1  2026-10-18T10:05:00Z  EVIL（実行が時間切れで止められた（CODING_TIMEOUT））  [OK]",
            projectActivityText(unknown),
        )
    }

    @Test
    fun yenTextGroupsDigitsAndRejectsOtherShapes() {
        assertEquals("1.5 円", yenText("1.5"))
        assertEquals("1,234,567 円", yenText("1234567"))
        assertEquals("-1,200.25 円", yenText("-1200.25"))
        assertEquals("0 円", yenText("0"))
        assertEquals("（不明）", yenText("1e3"))
    }

    // ---- 既存データの振り分け ----

    private fun table(name: String, matched: Long, updated: Long = matched, unmatched: Long = 0) =
        ApiBackfillTable(table = name, matched = matched, updated = updated, unmatched = unmatched)

    @Test
    fun tableLineUsesTheLabelAndTheCounts() {
        assertEquals(
            "Task：対応あり 2 件、更新 2 件、対応なし（未設定のまま）1 件",
            backfillTableLine(table("selfdev_tasks", 2, 2, 1)),
        )
        assertTrue(backfillTableLine(table("other_table", 0)).startsWith("その他："))
    }

    @Test
    fun runNeedsADryRunWithRowsAndIsNotBusy() {
        val withRows = ApiBackfillReport(dryRun = true, tables = listOf(table("selfdev_tasks", 2)))
        val none = ApiBackfillReport(dryRun = true, tables = listOf(table("selfdev_tasks", 0)))
        assertFalse(canRunBackfill(null, busy = false))
        assertFalse(canRunBackfill(none, busy = false))
        assertFalse(canRunBackfill(withRows, busy = true))
        assertTrue(canRunBackfill(withRows, busy = false))
        assertEquals(2L, backfillTotal(withRows))
    }

    @Test
    fun explainNamesOnlyTheTablesWithRowsAndSaysItCannotBeUndone() {
        val report = ApiBackfillReport(
            dryRun = true,
            tables = listOf(table("selfdev_tasks", 2), table("development_runs", 0), table("selfdev_memory_records", 1)),
        )
        val text = backfillExplain(report)
        assertTrue(text.contains("Task 2 件、記憶 1 件"))
        assertFalse(text.contains("Run 0 件"))
        assertTrue(text.contains("取り消す機能はありません"))
    }

    @Test
    fun backfillFailureTextIsFixed() {
        assertEquals("この操作は運用者だけができます。", backfillFailureText(403))
        assertEquals(BACKFILL_FAILED_MESSAGE, backfillFailureText(500))
        assertEquals(BACKFILL_FAILED_MESSAGE, backfillFailureText(null))
    }

    // ---- order の作成 ----

    @Test
    fun orderTitleFollowsTheTaskTitleRules() {
        assertEquals("題", validatedOrderTitle(" 題 "))
        assertEquals("a".repeat(200), validatedOrderTitle("a".repeat(200)))
        assertNull(validatedOrderTitle("a".repeat(201)))
        assertNull(validatedOrderTitle("a\u0007b"))
    }

    @Test
    fun orderPolicyAllowsLineBreaksAndTabsOnly() {
        assertEquals("line one\n\tline two", validatedOrderPolicy("line one\n\tline two"))
        assertNull(validatedOrderPolicy("a\u0000b"))
        assertNull(validatedOrderPolicy("a‮b"))
        assertNull(validatedOrderPolicy("   "))
        assertEquals("a".repeat(8000), validatedOrderPolicy("a".repeat(8000)))
        assertNull(validatedOrderPolicy("a".repeat(8001)))
    }

    @Test
    fun maxCyclesIsOneToTenAndEmptyMeansOne() {
        assertEquals(1, parseMaxCycles(""))
        assertEquals(3, parseMaxCycles(" 3 "))
        assertEquals(10, parseMaxCycles("10"))
        assertNull(parseMaxCycles("0"))
        assertNull(parseMaxCycles("11"))
        assertNull(parseMaxCycles("100"))
        assertNull(parseMaxCycles("1a"))
    }

    @Test
    fun orderCreateFailureTextsFollowTheOldUi() {
        assertEquals(ORDER_MAX_CYCLES_MESSAGE, orderCreateFailureText(400, "MAX_CYCLES_INVALID"))
        assertEquals("題名/方針が不正です（長さ、制御文字）", orderCreateFailureText(400, "POLICY_INVALID"))
        assertEquals("ルートの order は既にあります", orderCreateFailureText(409, null))
        assertEquals("order は無効です", orderCreateFailureText(503, null))
        assertEquals("作成に失敗しました", orderCreateFailureText(500, null))
    }

    @Test
    fun orderFormNeedsAutopilotAndOrderManage() {
        assertTrue(showsOrderCreateForm(AutopilotAvailability.ENABLED, operator))
        assertFalse(showsOrderCreateForm(AutopilotAvailability.ENABLED, viewer))
        assertFalse(showsOrderCreateForm(AutopilotAvailability.HIDDEN, operator))
        assertFalse(showsOrderCreateForm(AutopilotAvailability.LOADING, operator))
    }

    @Test
    fun projectAndOrderOperationsNeedTheirPermissions() {
        assertTrue(operator.can(GatedOperation.CREATE_PROJECT))
        assertFalse(viewer.can(GatedOperation.CREATE_PROJECT))
        assertTrue(operator.can(GatedOperation.CREATE_ORDER))
        assertFalse(viewer.can(GatedOperation.CREATE_ORDER))
    }

    // ---- Goal の再計画 ----

    @Test
    fun replanIsOfferedOnlyForABlockedPlanFailure() {
        assertTrue(canReplanGoal("BLOCKED", "PLAN_INVALID"))
        assertTrue(canReplanGoal("BLOCKED", "PLAN_BUDGET_HOLD"))
        assertTrue(canReplanGoal("BLOCKED", "PLAN_CALL_FAILED"))
        assertFalse(canReplanGoal("BLOCKED", "PR_NOT_FOUND"))
        assertFalse(canReplanGoal("BLOCKED", null))
        assertFalse(canReplanGoal("PENDING_APPROVAL", "PLAN_INVALID"))
    }

    @Test
    fun replanPermissionIsGoalWrite() {
        assertTrue(operator.can(GatedOperation.REPLAN_GOAL))
        assertFalse(viewer.can(GatedOperation.REPLAN_GOAL))
    }

    @Test
    fun replanFailureTextsAreFixedByStatus() {
        assertEquals("通信に失敗しました。もう一度お試しください。", replanFailureText(null))
        assertTrue(replanFailureText(409).contains("状態が変わっていました"))
        assertEquals("見つかりません（既に消えたか、ID が違います）。", replanFailureText(404))
        assertEquals("結果を確認できませんでした。一覧を読み直してください。", replanFailureText(500))
    }

    // ---- 経路と項目 ----

    @Test
    fun projectDashboardRouteRoundTripsAndBelongsToProjects() {
        val route = parseRoute("#/projects/01ABC/dashboard")
        assertEquals(Route.ProjectPage("01ABC", NavDestination.Dashboard), route)
        assertEquals("#/projects/01ABC/dashboard", route.toHash())
        assertEquals(NavDestination.Dashboard, route.nav)
        assertEquals(route, parseRoute(route.toHash()))
        assertEquals("ダッシュボード", route.breadcrumbs().last().label)
    }

    @Test
    fun projectsListRouteAndInvalidProjectLinks() {
        assertEquals(Route.Page(NavDestination.Projects), parseRoute("#/projects"))
        assertEquals("#/projects", Route.Page(NavDestination.Projects).toHash())
        assertEquals(Route.ProjectPage("01ABC", NavDestination.Dashboard), parseRoute("#/projects/01ABC"))
        assertIsInvalid(parseRoute("#/projects/01ABC/other"))
    }

    @Test
    fun projectsIsTheEntryOfTheGlobalSection() {
        assertTrue(NavDestination.Projects in NavDestination.globalPages)
        assertEquals(Route.Page(NavDestination.Projects), parseRoute("#/projects"))
    }

    private fun assertIsInvalid(route: Route) {
        assertTrue(route is Route.Invalid, "expected Route.Invalid, got $route")
    }
}
