package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatScrollRulesTest {
    @Test
    fun theNewestItemShownAtTheStartOfAReversedListIsAtTheNewest() {
        // Item 0 is the newest message; an empty list reports the same position.
        assertTrue(isAtNewest(firstVisibleIndex = 0, firstVisibleOffsetPx = 0, thresholdPx = 48))
    }

    @Test
    fun aNewestItemScrolledUpWithinTheThresholdIsAtTheNewest() {
        assertTrue(isAtNewest(firstVisibleIndex = 0, firstVisibleOffsetPx = 48, thresholdPx = 48))
    }

    @Test
    fun aNewestItemScrolledUpBeyondTheThresholdIsNotAtTheNewest() {
        assertFalse(isAtNewest(firstVisibleIndex = 0, firstVisibleOffsetPx = 49, thresholdPx = 48))
    }

    @Test
    fun aReaderUpInTheHistoryIsNotAtTheNewest() {
        assertFalse(isAtNewest(firstVisibleIndex = 5, firstVisibleOffsetPx = 0, thresholdPx = 48))
    }

    @Test
    fun aContentChangeScrollsWhenTheReaderIsAtTheNewest() {
        assertTrue(shouldAutoScroll(contentChanged = true, nearBottom = true, forceScroll = false))
    }

    @Test
    fun aBackgroundRefreshDoesNotPullAReaderUpInTheHistory() {
        assertFalse(shouldAutoScroll(contentChanged = true, nearBottom = false, forceScroll = false))
    }

    @Test
    fun aForcedScrollGoesToTheNewestEvenWhenTheReaderIsUpInTheHistory() {
        assertTrue(shouldAutoScroll(contentChanged = true, nearBottom = false, forceScroll = true))
    }

    @Test
    fun noContentChangeNeverScrolls() {
        assertFalse(shouldAutoScroll(contentChanged = false, nearBottom = true, forceScroll = true))
    }

    @Test
    fun aHistoryWithMoreMessagesIsNewer() {
        assertTrue(isNewerChatHistory(candidateCount = 7, candidateLastId = 70, currentCount = 6, currentLastId = 60))
    }

    @Test
    fun aHistoryWithFewerMessagesIsNotNewer() {
        assertFalse(isNewerChatHistory(candidateCount = 5, candidateLastId = 50, currentCount = 6, currentLastId = 60))
    }

    @Test
    fun aHistoryWithTheSameCountAndLaterLastMessageIsNewer() {
        assertTrue(isNewerChatHistory(candidateCount = 6, candidateLastId = 61, currentCount = 6, currentLastId = 60))
    }

    @Test
    fun aHistoryWithTheSameMessagesIsNotNewer() {
        assertFalse(isNewerChatHistory(candidateCount = 6, candidateLastId = 60, currentCount = 6, currentLastId = 60))
    }

    @Test
    fun aTurnsUserTextAlreadyStoredAsTheLastMessageIsEchoed() {
        assertTrue(isTurnEchoedInHistory(lastStoredRole = "user", lastStoredContent = "hello", turnUserText = "hello"))
    }

    @Test
    fun aTurnWhoseUserTextIsNotStoredYetIsNotEchoed() {
        assertFalse(isTurnEchoedInHistory(lastStoredRole = "assistant", lastStoredContent = "hello", turnUserText = "hello"))
        assertFalse(isTurnEchoedInHistory(lastStoredRole = "user", lastStoredContent = "earlier", turnUserText = "hello"))
        assertFalse(isTurnEchoedInHistory(lastStoredRole = null, lastStoredContent = null, turnUserText = "hello"))
    }
}
