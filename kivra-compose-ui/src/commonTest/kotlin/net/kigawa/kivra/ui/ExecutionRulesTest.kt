package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiExecutionAttempt
import net.kigawa.kivra.ui.api.ApiExecutionCost
import net.kigawa.kivra.ui.api.ApiExecutionRevision
import net.kigawa.kivra.ui.api.ApiExecutionTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionRulesTest {

    @Test
    fun `the poll interval is 5 seconds, 30 while hidden, and doubles after failures up to 60`() {
        assertEquals(5_000L, execDelayMs(hidden = false, failures = 0))
        assertEquals(30_000L, execDelayMs(hidden = true, failures = 0))
        // The failure gaps: 10, 20, 40, then capped at 60 seconds.
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..5).map { execDelayMs(hidden = false, failures = it) })
        assertEquals(60_000L, execDelayMs(hidden = true, failures = 1))
    }

    @Test
    fun `a read is due when the interval has passed, and at once when the tab is shown again`() {
        assertFalse(execDue(now = 4_999, lastAttemptAt = 0, hidden = false, wasHidden = false, failures = 0))
        assertTrue(execDue(now = 5_000, lastAttemptAt = 0, hidden = false, wasHidden = false, failures = 0))
        // Hidden: nothing at 5 seconds, a read at 30 seconds.
        assertFalse(execDue(now = 5_000, lastAttemptAt = 0, hidden = true, wasHidden = true, failures = 0))
        assertTrue(execDue(now = 30_000, lastAttemptAt = 0, hidden = true, wasHidden = true, failures = 0))
        // Shown again after hiding: read at once.
        assertTrue(execDue(now = 6_000, lastAttemptAt = 5_000, hidden = false, wasHidden = true, failures = 0))
    }

    @Test
    fun `a terminal run stops the watch, a running one does not`() {
        assertTrue(isExecutionTerminal("COMPLETED"))
        assertTrue(isExecutionTerminal("FAILED"))
        assertTrue(isExecutionTerminal("CANCELLED"))
        assertFalse(isExecutionTerminal("DEVELOPING"))
    }

    @Test
    fun `an older revision never replaces the one shown, a newer or equal one does`() {
        val shown = ApiExecutionRevision(attemptVersion = 5, eventCount = 10)
        assertFalse(shouldReplaceExecution(shown, ApiExecutionRevision(attemptVersion = 4, eventCount = 10)))
        assertFalse(shouldReplaceExecution(shown, ApiExecutionRevision(attemptVersion = 5, eventCount = 9)))
        assertTrue(shouldReplaceExecution(shown, ApiExecutionRevision(attemptVersion = 5, eventCount = 11)))
        assertTrue(shouldReplaceExecution(shown, ApiExecutionRevision(attemptVersion = 6, eventCount = 0)))
        assertTrue(shouldReplaceExecution(null, ApiExecutionRevision(attemptVersion = 0, eventCount = 0)))
    }

    @Test
    fun `server times are read as UTC with up to three fraction digits, and a bad one is unknown`() {
        assertEquals(0L, parseInstantMillis("1970-01-01T00:00:00Z"))
        assertEquals(86_400_000L, parseInstantMillis("1970-01-02T00:00:00Z"))
        assertEquals(946_684_800_000L, parseInstantMillis("2000-01-01T00:00:00Z"))
        assertEquals(946_684_800_123L, parseInstantMillis("2000-01-01T00:00:00.123456Z"))
        assertNull(parseInstantMillis("2026-13-01T00:00:00Z"))
        assertNull(parseInstantMillis("not a time"))
        assertNull(parseInstantMillis(null))
    }

    @Test
    fun `the minutes since the last receipt are whole minutes, and none without a receipt`() {
        val last = parseInstantMillis("2026-10-18T23:53:00Z")!!
        val now = parseInstantMillis("2026-10-19T00:00:00Z")!!
        assertEquals(7L, execStaleMinutes(last, now, skewMs = 0))
        assertEquals(0L, execStaleMinutes(now - 30_000, now, skewMs = 0))
        assertNull(execStaleMinutes(null, now, skewMs = 0))
    }

    @Test
    fun `money is shown with USD only when it is a plain decimal, otherwise unknown`() {
        assertEquals("USD 0.250000", usdText("0.250000"))
        assertEquals("不明", usdText("1e9"))
        assertEquals("不明", usdText(null))
    }

    @Test
    fun `a failed attempt with no report shows its estimate as a reserve`() {
        val failed = listOf(ApiExecutionAttempt("a", 1, "FAILED", 2))
        val reserve = ApiExecutionCost(confirmedUsd = "0.000000", estimatedUsd = "0.500000", estimatedBasis = "RESERVE_ESTIMATE")
        assertEquals("USD 0.500000（推定）。予約額。実費ではない", execEstimateText(reserve, failed))
        assertEquals("USD 0.500000（推定）", execEstimateText(reserve, listOf(ApiExecutionAttempt("a", 1, "RUNNING", 2))))
        val confirmed = reserve.copy(confirmedUsd = "0.250000")
        assertEquals("USD 0.500000（推定）", execEstimateText(confirmed, failed))
        assertEquals("不明（推定）", execEstimateText(ApiExecutionCost(confirmedUsd = "0", estimatedUsd = null), failed))
    }

    @Test
    fun `tool counts use the fixed names, and every other name counts as OTHER`() {
        val tools = ApiExecutionTools(total = 15, byName = mapOf("Read" to 5, "OTHER" to 1, "<img src=x>" to 4), errors = 0)
        assertEquals(5, execToolCount(tools, "Read"))
        assertEquals(0, execToolCount(tools, "Grep"))
        assertEquals(5, execToolCount(tools, "OTHER"))
    }

    @Test
    fun `a stop stage, an unknown value and an empty value are fixed words, never the server text`() {
        assertEquals("停止要求あり（エージェントの確認待ち）", execLookup(EXECUTION_STOP_STAGES, "REQUESTED"))
        assertEquals("（不明）", execLookup(EXECUTION_STOP_STAGES, ""))
        assertEquals("不明な値（<b>x</b>）", execLookup(EXECUTION_STOP_STAGES, "<b>x</b>"))
    }

    @Test
    fun `warnings are listed in the old order and only when they are not zero`() {
        assertEquals(emptyList(), execWarningLines(0, 0, false, emptyMap()))
        assertEquals(
            listOf(
                "警告: 2 件",
                "送信の取りこぼし（上限などで落とした件数）: 1 件",
                "記録の上限に達したため、以降は一部だけ記録されています",
                "API の再試行（RATE）: 3 回",
            ),
            execWarningLines(2, 1, true, mapOf("RATE" to 3)),
        )
    }

    @Test
    fun `only a self-development run with a run id of the right shape shows the section`() {
        assertTrue(showsExecutionSection("SELF_DEV", "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertFalse(showsExecutionSection("SIMULATED", "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertFalse(showsExecutionSection("SELF_DEV", "short"))
    }
}
