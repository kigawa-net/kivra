package net.kigawa.kivra.ui

/*
 * The project dashboard's layout as pure logic. Wide and tall enough, the chat stays pinned beside the side column, and only the side column scrolls.
 * Narrower or shorter, the page stacks and scrolls as a whole, and the chat has a fixed height ([CHAT_COMPACT_LIST_HEIGHT_DP]).
 */

/** The side column's width (dp) in the pinned layout. */
const val DASHBOARD_SIDE_WIDTH_DP = 320

/** The gap (dp) between the chat and the side column. */
const val DASHBOARD_GAP_DP = 12

/** The narrowest width (dp) that still fits the chat beside the side column. Below it the page stacks. */
const val DASHBOARD_PINNED_MIN_WIDTH_DP = 720

/** The shortest height (dp) that leaves the chat room to be pinned. Below it the page stacks and scrolls. */
const val DASHBOARD_PINNED_MIN_HEIGHT_DP = 480

/** Whether the width is too narrow for two columns. */
fun isCompactWidth(widthDp: Float): Boolean = widthDp < DASHBOARD_PINNED_MIN_WIDTH_DP

/** Whether the chat is pinned beside the side column: wide enough and tall enough. */
fun isPinnedChatLayout(widthDp: Float, heightDp: Float): Boolean =
    !isCompactWidth(widthDp) && heightDp >= DASHBOARD_PINNED_MIN_HEIGHT_DP

/** ヘッダーに「設定」への導線を出すか。未分類など、設定のページを持たないプロジェクトでは出さない。 */
fun showsSettingsLink(projectId: String): Boolean =
    NavDestination.ProjectSettings in pagesOf(projectId)
