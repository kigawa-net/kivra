package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiMe

/*
 * 操作の出し分けの純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js)の can(...) と
 * 各操作の表示条件、サーバー(kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Permissions.kt の Permission と
 * ROLE_PERMISSIONS、routes/ の permit / permitOr403 の引数)の規則に合わせる。
 *
 * ここで隠すのは UX のため。サーバーが最終的な判定をし、権限が無ければ 403 を返す。画面の 403 の文言は残す(多層防御)。
 */

/** サーバーの Permission の名前。`/api/selfdev/me` の permissions は この名前の文字列で返る。 */
enum class SelfDevPermission {
    READ,
    READ_TEXT,
    GOAL_STOP,
    ORDER_MANAGE,
    STOP_REQUEST,
    STOP_DECIDE,
    STOP_DECIDE_AUTO,
    GOAL_WRITE,
    SPEND_START,
    BUDGET_OVERRIDE,
    ASSIGNEE_MANAGE,
}

/**
 * 画面の操作と、その操作に必要な権限の対応。app.js の can(...) の引数と、サーバーのルートが要求する権限に合わせる。
 */
enum class GatedOperation(val permission: SelfDevPermission) {
    /** 停止要求の承認・却下(app.js: can('STOP_DECIDE') / サーバー: order.stop.approve・reject)。 */
    DECIDE_STOP_REQUEST(SelfDevPermission.STOP_DECIDE),

    /** 開発目標(Task)の承認・見送り(app.js: can('GOAL_WRITE') / サーバー: task_approve・task_reject)。 */
    APPROVE_GOAL(SelfDevPermission.GOAL_WRITE),

    /** 開発目標の中止(app.js: can('GOAL_STOP') / サーバー: goal_stop)。 */
    STOP_GOAL(SelfDevPermission.GOAL_STOP),

    /** Run の解放(app.js: can('BUDGET_OVERRIDE') / サーバー: selfdev_release・selfdev_release_unbound_pr)。 */
    RELEASE_RUN(SelfDevPermission.BUDGET_OVERRIDE),

    /** 未確定の予約の解除(app.js: can('BUDGET_OVERRIDE') / サーバー: budget_acknowledge)。 */
    ACKNOWLEDGE_BUDGET(SelfDevPermission.BUDGET_OVERRIDE),

    /** Task の投入(サーバー: /api/tasks と /api/selfdev/tasks の submit は SPEND_START。app.js は出し分けていない)。 */
    SUBMIT_TASK(SelfDevPermission.SPEND_START),

    /** Goal の投入(app.js: can('GOAL_WRITE') で goal-form を出す / サーバー: POST /api/selfdev/goals は GOAL_WRITE)。 */
    SUBMIT_GOAL(SelfDevPermission.GOAL_WRITE),

    /** プロジェクトの作成(サーバー: project_create は ORDER_MANAGE。app.js は出し分けていない。Compose 版の画面はまだ無い)。 */
    CREATE_PROJECT(SelfDevPermission.ORDER_MANAGE),

    /** 記憶の一覧とナビ(app.js: memory-section の can('READ_TEXT')。サーバー: memory.list)。 */
    READ_MEMORY(SelfDevPermission.READ_TEXT),

    /** 自律運転の一時停止・再開、サーキットブレーカーの手動再開(app.js: can('ORDER_MANAGE') / サーバー: autopilot pause・resume・reset-breaker)。 */
    MANAGE_AUTOPILOT(SelfDevPermission.ORDER_MANAGE),

    /** order の一時停止・再開(app.js: can('ORDER_MANAGE') / サーバー: order.pause・order.resume)。 */
    MANAGE_ORDER(SelfDevPermission.ORDER_MANAGE),

    /** order の停止要求と、要求の取り消し(app.js: can('STOP_REQUEST') / サーバー: order.stop.request・order.stop.cancel)。 */
    REQUEST_ORDER_STOP(SelfDevPermission.STOP_REQUEST),

    /** 記憶の個別の削除と条件での削除(app.js: can('GOAL_WRITE') / サーバー: memory.delete・memory.delete_by_condition)。 */
    DELETE_MEMORY(SelfDevPermission.GOAL_WRITE),

    /** 担当の管理(作成・ロール・有効化/無効化・メンバー・トークン)。担当の画面を見せる条件と同じ(サーバー: ASSIGNEE_MANAGE)。 */
    MANAGE_ASSIGNEE(SelfDevPermission.ASSIGNEE_MANAGE),
}

private val ROLE_NAME = Regex("[A-Z_]{1,32}")
private val MODES = setOf("off", "shadow", "enforce")

/**
 * 呼び出し者の権限(`GET /api/selfdev/me` の結果)。[UNKNOWN] は取得できなかった・形が合わなかったときの状態。
 *
 * [can] は app.js の can() と同じ: 未取得、または mode が enforce でないときは常に true(何も隠さない。サーバーが判定する)。
 * enforce のときだけ、権限の名前が含まれるかで決める。
 */
class CallerPermissions private constructor(
    private val loaded: Boolean,
    private val mode: String?,
    private val granted: Set<SelfDevPermission>,
) {
    /** 操作を出してよいか。[UNKNOWN]、off、shadow では常に true(app.js と同じ)。 */
    fun can(operation: GatedOperation): Boolean = !loaded || mode != "enforce" || operation.permission in granted

    /**
     * サイドナビの項目を出すか。旧 UI の規則に合わせる。
     * - 記憶: [can] の READ_MEMORY と同じ。
     * - 担当: [assigneesVisible]。取得前・失敗時は出さない(app.js の assigneesVisible)。
     * - 承認: [showsApprovals]。
     * - 予算・order・開発目標・タスク: 隠さない。旧 UI は API の可否(自律運転の状態など)で隠すが、この画面は問い合わせていない。
     */
    fun navVisible(destination: NavDestination): Boolean = when (destination) {
        NavDestination.Memory -> can(GatedOperation.READ_MEMORY)
        NavDestination.Assignees -> assigneesVisible()
        NavDestination.Approvals -> showsApprovals()
        else -> true
    }

    /** 担当の画面を見せてよいか(app.js の assigneesVisible): 取得済み、mode が off でなく、ASSIGNEE_MANAGE を持つ。 */
    fun assigneesVisible(): Boolean = loaded && mode != "off" && SelfDevPermission.ASSIGNEE_MANAGE in granted

    /**
     * 承認の画面を見せてよいか。旧 UI は停止要求の一覧の問い合わせが通ったときだけ出すので、その近似として STOP_DECIDE を見る。
     * 取得前・失敗時は隠さない(問い合わせの結果はこの画面で決まる)。
     */
    fun showsApprovals(): Boolean = !loaded || SelfDevPermission.STOP_DECIDE in granted

    companion object {
        /** 未取得・失敗・形が合わない応答。何も隠さない。 */
        val UNKNOWN = CallerPermissions(loaded = false, mode = null, granted = emptySet())

        /**
         * 応答から作る。app.js の loadMe と同じ検査: mode は off / shadow / enforce のいずれか、role と各権限名は大文字と `_` の名前。
         * どれかが外れたら [UNKNOWN]。知らない権限名は読み飛ばす(サーバーが後から増やした名前)。
         */
        fun of(me: ApiMe): CallerPermissions {
            if (me.mode !in MODES || !ROLE_NAME.matches(me.role)) return UNKNOWN
            if (!me.permissions.all { ROLE_NAME.matches(it) }) return UNKNOWN
            val granted = me.permissions.mapNotNullTo(mutableSetOf()) { name ->
                SelfDevPermission.entries.firstOrNull { it.name == name }
            }
            return CallerPermissions(loaded = true, mode = me.mode, granted = granted)
        }
    }
}
