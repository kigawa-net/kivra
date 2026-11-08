package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiMe

/*
 * 画面の枠(ヘッダー・サイドナビ・ページタイトル)の表示規則。旧 UI(kivra-app/src/main/resources/ui/app.js の renderNav、
 * renderMe、PAGES、index.html の me-role / me-note / header-approvals)と同じ条件と文言。
 * 権限による項目の出し分けは PermissionRules.kt の CallerPermissions.navVisible が行い、ここは API の結果による出し分けだけ。
 */

/**
 * ヘッダーの役割の表示。[roleText] は「役割: ROLE」、[note] は判定の状態の補足(enforce のときは無い)。
 * 読めていない・形が合わないときは null(何も出さない。旧 UI の me-role が hidden の状態)。
 */
data class RoleHeader(val roleText: String, val note: String?)

/** `GET /api/selfdev/me` の結果からヘッダーの役割を作る。権限の検査は [CallerPermissions.of] と同じ(形が合わなければ null)。 */
fun roleHeaderOf(me: ApiMe?): RoleHeader? {
    if (me == null) return null
    if (CallerPermissions.of(me) === CallerPermissions.UNKNOWN) return null
    val note = when (me.mode) {
        "shadow" -> "判定は従来どおり（shadow）。役割の権限はまだ強制されていません"
        "off" -> "役割による判定は無効です（off）"
        else -> null
    }
    return RoleHeader("役割: ${me.role}", note)
}

/**
 * 保留中の承認の件数の表示(ヘッダーのリンクとサイドナビのバッジ)。件数が読めていない([count] が null)ときは出さない。
 * ヘッダーは 1 件以上のときだけ出す(旧 UI の header-approvals)。バッジは 0 でも出す(旧 UI の nav-approvals-badge)。
 */
fun headerApprovalsText(count: Int?): String? = if (count == null || count < 1) null else "承認待ち $count 件"

/** サイドナビの承認のバッジの文言。件数が読めていないときは null(バッジを出さない)。 */
fun approvalsBadgeText(count: Int?): String? = count?.toString()

/**
 * API の結果でサイドナビの項目を出すか(権限は別: [CallerPermissions.navVisible])。旧 UI の renderNav と同じ条件:
 * - 開発目標・order: 自律運転が有効なときだけ(旧 UI は自律運転が無効なら一覧ごと隠す)。
 * - 承認: 自律運転が有効で、承認の一覧が読めたとき(読めない・403・503 は件数が null)。
 * - 予算: 予算の API が読めたとき(運用者だけ。読めない利用者には出さない)。
 * その他の項目は隠さない。
 */
fun navVisibleForSections(
    destination: NavDestination,
    autopilot: AutopilotAvailability,
    budgetVisible: Boolean,
    approvalsPending: Int?,
): Boolean = when (destination) {
    NavDestination.Goals, NavDestination.Orders -> autopilot == AutopilotAvailability.ENABLED
    NavDestination.Approvals -> autopilot == AutopilotAvailability.ENABLED && approvalsPending != null
    NavDestination.Budget -> budgetVisible
    else -> true
}

/**
 * ブラウザのページタイトル。旧 UI は「Kivra — <画面名>」(Run の詳細は「Run の詳細」)。
 * 開発目標・order の詳細と周の一覧は、その一覧の名前を使う。解釈できないリンクは「Kivra」だけ。
 */
fun documentTitleOf(route: Route): String {
    val title = when (route) {
        is Route.RunDetail -> "Run の詳細"
        is Route.Invalid -> null
        else -> route.nav?.label
    }
    return if (title == null) "Kivra" else "Kivra — $title"
}
