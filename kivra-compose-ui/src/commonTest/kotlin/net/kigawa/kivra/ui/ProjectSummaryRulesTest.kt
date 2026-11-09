package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectSummaryRulesTest {
    @Test
    fun runningIsEveryStateThatIsNotTerminal() {
        val byState = mapOf("RUNNING" to 2L, "PENDING" to 1L, "FAILED" to 3L, "COMPLETED" to 5L, "UNKNOWN" to 1L)
        assertEquals(4L, runningCountOf(byState))
    }

    @Test
    fun attentionIsFailedAndUnknownRuns() {
        val byState = mapOf("RUNNING" to 2L, "FAILED" to 3L, "UNKNOWN" to 1L, "COMPLETED" to 5L)
        assertEquals(4L, attentionRunCountOf(byState))
    }

    @Test
    fun noRunsCountsAsZero() {
        assertEquals(0L, runningCountOf(emptyMap()))
        assertEquals(0L, attentionRunCountOf(emptyMap()))
    }
}
