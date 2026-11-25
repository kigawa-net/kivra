package net.kigawa.kivra.ui

/*
 * The scroll rules of the project chat's message list, as pure logic. The list is reversed: its item 0 is the newest message, drawn at the
 * bottom of the viewport. So the first layout already shows the newest message, and "at the newest" means item 0 is the first visible item
 * and is not scrolled up. In the stacked dashboard layout the list has a fixed height and scrolls inside it.
 * It goes to the newest message on the first load and after the user's own send; otherwise only when the reader is already at the newest,
 * so a background refresh never pulls a reader away from the history they are reading.
 */

/** The chat message list's height (dp) in the stacked dashboard layout. The input box sits below it, always visible. */
const val CHAT_COMPACT_LIST_HEIGHT_DP = 360

/** How far (dp) the reader may have scrolled up from the newest message and still count as "at the newest". */
const val CHAT_NEAR_BOTTOM_DP = 48

/**
 * Whether a reversed list shows its newest message: item 0 is the first visible item, scrolled up by at most [thresholdPx].
 * [firstVisibleIndex] and [firstVisibleOffsetPx] are the list's first visible item and its scroll offset. An empty list counts as at the newest.
 */
fun isAtNewest(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, thresholdPx: Int): Boolean =
    firstVisibleIndex <= 0 && firstVisibleOffsetPx <= thresholdPx

/**
 * Whether to scroll to the newest item now. [contentChanged] is whether the list's content changed (a new message, a reply's new text).
 * [forceScroll] is set on the first load and after the user's own send; it scrolls even when the reader is up in the history.
 */
fun shouldAutoScroll(contentChanged: Boolean, nearBottom: Boolean, forceScroll: Boolean): Boolean =
    contentChanged && (forceScroll || nearBottom)

/**
 * Whether a stored history is newer than the one on screen: more messages, or the same count with a later last message.
 * Used to choose between the history read after a turn and the latest background refresh, so neither one hides the other's newer messages.
 */
fun isNewerChatHistory(candidateCount: Long, candidateLastId: Long, currentCount: Long, currentLastId: Long): Boolean =
    candidateCount > currentCount || (candidateCount == currentCount && candidateLastId > currentLastId)

/**
 * Whether the turn's user text is already the stored history's last message (a refresh read it while the turn streams),
 * so the turn must not draw the same message a second time.
 */
fun isTurnEchoedInHistory(lastStoredRole: String?, lastStoredContent: String?, turnUserText: String): Boolean =
    lastStoredRole == "user" && lastStoredContent == turnUserText
