package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatScrollRulesTest {
    @Test
    fun anEmptyListIsAtTheBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = -1, itemCount = 0, lastItemBottomPx = 0, viewportEndPx = 0, thresholdPx = 0))
    }

    @Test
    fun theListShowingItsLastItemAtTheEndIsAtTheBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = 9, itemCount = 10, lastItemBottomPx = 420, viewportEndPx = 420, thresholdPx = 48))
    }

    @Test
    fun aShortListThatDoesNotFillTheViewportIsAtTheBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = 2, itemCount = 3, lastItemBottomPx = 200, viewportEndPx = 420, thresholdPx = 48))
    }

    @Test
    fun withinTheThresholdIsNearTheBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = 9, itemCount = 10, lastItemBottomPx = 460, viewportEndPx = 420, thresholdPx = 48))
    }

    @Test
    fun beyondTheThresholdIsNotNearTheBottom() {
        assertFalse(isNearBottom(lastVisibleIndex = 9, itemCount = 10, lastItemBottomPx = 500, viewportEndPx = 420, thresholdPx = 48))
    }

    @Test
    fun aLastItemThatIsNotVisibleIsNotNearTheBottom() {
        assertFalse(isNearBottom(lastVisibleIndex = 5, itemCount = 10, lastItemBottomPx = 420, viewportEndPx = 420, thresholdPx = 48))
    }

    @Test
    fun noVisibleItemInANonEmptyListIsNotNearTheBottom() {
        assertFalse(isNearBottom(lastVisibleIndex = -1, itemCount = 4, lastItemBottomPx = 0, viewportEndPx = 420, thresholdPx = 48))
    }

    @Test
    fun aContentChangeScrollsWhenTheReaderIsAtTheBottom() {
        assertTrue(shouldAutoScroll(contentChanged = true, nearBottom = true, forceScroll = false))
    }

    @Test
    fun aBackgroundRefreshDoesNotPullAReaderUpInTheHistory() {
        assertFalse(shouldAutoScroll(contentChanged = true, nearBottom = false, forceScroll = false))
    }

    @Test
    fun aForcedScrollGoesToTheBottomEvenWhenTheReaderIsUpInTheHistory() {
        assertTrue(shouldAutoScroll(contentChanged = true, nearBottom = false, forceScroll = true))
    }

    @Test
    fun noContentChangeNeverScrolls() {
        assertFalse(shouldAutoScroll(contentChanged = false, nearBottom = true, forceScroll = true))
    }
}
