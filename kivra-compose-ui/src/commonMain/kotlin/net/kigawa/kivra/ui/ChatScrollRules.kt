package net.kigawa.kivra.ui

/*
 * The scroll rules of the project chat's message list, as pure logic. In the stacked dashboard layout the list has a fixed height and scrolls inside it.
 * It goes to the newest message on the first load and after the user's own send; otherwise only when it is already at or near the bottom,
 * so a background refresh never pulls a reader away from the history they are reading.
 */

/** The chat message list's height (dp) in the stacked dashboard layout. The input box sits below it, always visible. */
const val CHAT_COMPACT_LIST_HEIGHT_DP = 360

/** How close to the bottom (dp) still counts as "at the bottom". */
const val CHAT_NEAR_BOTTOM_DP = 48

/**
 * Whether the list shows its bottom, or is within [thresholdPx] of it.
 * [lastVisibleIndex] is the index of the last visible item (-1 when none is visible), [lastItemBottomPx] the bottom edge of that item and
 * [viewportEndPx] the bottom edge of the viewport, in the same px coordinates. An empty list counts as at the bottom.
 */
fun isNearBottom(
    lastVisibleIndex: Int,
    itemCount: Int,
    lastItemBottomPx: Int,
    viewportEndPx: Int,
    thresholdPx: Int,
): Boolean {
    if (itemCount <= 0) return true
    if (lastVisibleIndex < itemCount - 1) return false
    return lastItemBottomPx - viewportEndPx <= thresholdPx
}

/**
 * Whether to scroll to the newest item now. [contentChanged] is whether the list's content changed (a new message, a reply's new text).
 * [forceScroll] is set on the first load and after the user's own send; it scrolls even when the reader is up in the history.
 */
fun shouldAutoScroll(contentChanged: Boolean, nearBottom: Boolean, forceScroll: Boolean): Boolean =
    contentChanged && (forceScroll || nearBottom)
