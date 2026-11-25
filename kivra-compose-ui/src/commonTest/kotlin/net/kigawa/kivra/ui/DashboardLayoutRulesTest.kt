package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardLayoutRulesTest {
    @Test
    fun aWidthBelowTheMinimumIsCompact() {
        assertTrue(isCompactWidth(719.9f))
        assertFalse(isCompactWidth(720f))
    }

    @Test
    fun aWideAndTallViewportPinsTheChat() {
        assertTrue(isPinnedChatLayout(widthDp = 1200f, heightDp = 800f))
    }

    @Test
    fun theMinimumsThemselvesPin() {
        assertTrue(isPinnedChatLayout(widthDp = 720f, heightDp = 480f))
    }

    @Test
    fun aNarrowViewportStacksEvenWhenTall() {
        assertFalse(isPinnedChatLayout(widthDp = 719f, heightDp = 1000f))
    }

    @Test
    fun aShortViewportStacksEvenWhenWide() {
        assertFalse(isPinnedChatLayout(widthDp = 1400f, heightDp = 479f))
    }
}
