package net.kigawa.kivra.selfdev.memory

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.selfdev.captureKivraLogs

/**
 * The recorder of ADR 0016 P1, without a database: what a fact looks like, that a failed write never reaches the caller, that a
 * refusal and a failure are logged with fixed codes only, and that the off recorder builds nothing.
 */
class SelfDevMemoryRecorderTest {

    private val goalId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
    private val planId = "01BX5ZZKBKACTAV9WEVGEMMVRY"
    private val title = "LEAK-TITLE 題名のテスト"

    private fun task(paths: List<String> = listOf("docs/a.md")) = SelfDevPlanTask(
        planId = planId, ordinal = 1, title = "LEAK-TASK-TITLE", body = "LEAK-TASK-BODY", targetPaths = paths, sizeEstimate = "S",
        dependsOn = emptyList(), requiresApproval = false, state = TaskState.QUEUED, stateReason = null, idempotencyKey = "k",
        runId = null, attempts = 0, version = 1, createdAt = LocalDateTime.of(2026, 10, 20, 0, 0), updatedAt = LocalDateTime.of(2026, 10, 20, 0, 0),
    )

    /** A recorder whose writes are collected. Every write is accepted. */
    private fun collecting(): Pair<SelfDevMemoryRecorder, MutableList<MemoryCandidate>> {
        val written = mutableListOf<MemoryCandidate>()
        return SelfDevMemoryRecorder.withStore { written += it; MemorySaveResult.Written("01ARZ3NDEKTSV4RRFFQ69G5FB0") } to written
    }

    @Test
    fun `a plan proposal is a PLAN_FACT with the fixed code and the task count, and no plan text`() {
        val (recorder, written) = collecting()
        recorder.planProposed(goalId, planId, 3)
        val c = written.single()
        assertEquals("PLAN_FACT", c.kind)
        assertEquals(MemoryChannel.SYSTEM_AUTO, c.channel)
        assertEquals("PLAN_PROPOSED", c.code)
        assertEquals(3, c.countValue)
        assertEquals(goalId, c.goalId)
        assertEquals(planId, c.planId)
        assertNull(c.body)
    }

    @Test
    fun `a planner block is a PLAN_FACT with the block reason and no plan`() {
        val (recorder, written) = collecting()
        recorder.planBlocked(goalId, AutopilotReason.PLAN_INVALID)
        val c = written.single()
        assertEquals("PLAN_INVALID", c.code)
        assertNull(c.planId)
        assertNull(c.body)
    }

    @Test
    fun `the goal's title is the only text recorded, as an operator's GOAL_TITLE`() {
        val (recorder, written) = collecting()
        recorder.goalTitle(goalId, title, "operator-1")
        val c = written.single()
        assertEquals("GOAL_TITLE", c.kind)
        assertEquals(MemoryChannel.HTTP_OPERATOR, c.channel)
        assertTrue(c.operatorAuthenticated)
        assertEquals("operator-1", c.operatorId)
        assertEquals(title, c.body)
        assertEquals(goalId, c.goalId)
    }

    @Test
    fun `a task reason in the list is a TASK_FACT with its identity, and a reason outside it writes nothing`() {
        val (recorder, written) = collecting()
        recorder.taskFact({ goalId }, task(), AutopilotReason.OPERATOR_SKIPPED)
        recorder.taskFact({ goalId }, task(), AutopilotReason.ACTIVE_RUN_LIMIT)
        recorder.taskFact({ goalId }, task(), null)
        val c = written.single()
        assertEquals("TASK_FACT", c.kind)
        assertEquals("OPERATOR_SKIPPED", c.code)
        assertEquals(1, c.taskOrdinal)
        assertEquals(SelfDevMemoryRecorder.identityOf(listOf("docs/a.md")), c.identitySha256)
        assertNull(c.body)
    }

    @Test
    fun `the identity of a set of paths does not depend on the order or the repeats, and differs for other paths`() {
        assertEquals(
            SelfDevMemoryRecorder.identityOf(listOf("docs/a.md", "docs/b.md")),
            SelfDevMemoryRecorder.identityOf(listOf("docs/b.md", "docs/a.md", "docs/a.md")),
        )
        assertFalse(SelfDevMemoryRecorder.identityOf(listOf("docs/a.md")) == SelfDevMemoryRecorder.identityOf(listOf("docs/b.md")))
        assertEquals(64, SelfDevMemoryRecorder.identityOf(listOf("docs/a.md")).length)
    }

    @Test
    fun `a run outcome is a RUN_FACT whose code is the given state reason, with the run and the task`() {
        val (recorder, written) = collecting()
        recorder.runFact(goalId, task(), "01ARZ3NDEKTSV4RRFFQ69G5FC1", "PR_MERGED")
        val c = written.single()
        assertEquals("RUN_FACT", c.kind)
        assertEquals("PR_MERGED", c.code)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FC1", c.runId)
        assertEquals(1, c.taskOrdinal)
    }

    @Test
    fun `a goal's end is a GOAL_FACT, and a goal reason outside the end is not recorded`() {
        val (recorder, written) = collecting()
        recorder.goalFact(goalId, AutopilotReason.ALL_TASKS_COMPLETED)
        recorder.goalFact(goalId, AutopilotReason.PLAN_BUDGET_HOLD)
        recorder.goalFact(goalId, null)
        assertEquals(listOf("ALL_TASKS_COMPLETED"), written.map { it.code })
        assertEquals("GOAL_FACT", written.single().kind)
    }

    @Test
    fun `a write that throws never reaches the caller, and the log names only the exception class`() {
        val recorder = SelfDevMemoryRecorder.withStore { throw IllegalStateException("LEAK-DB-MESSAGE $title") }
        val (_, logs) = captureKivraLogs { recorder.goalTitle(goalId, title, "operator-1") }
        assertTrue(logs.any { it.contains("IllegalStateException") })
        assertTrue(logs.none { it.contains("LEAK") || it.contains(title) })
    }

    @Test
    fun `a refusal is logged with its kind and fixed code only, never the text`() {
        val recorder = SelfDevMemoryRecorder.withStore { MemorySaveResult.Refused(MemoryRefusal.SECRET_DETECTED) }
        val (_, logs) = captureKivraLogs { recorder.goalTitle(goalId, title, "operator-1") }
        assertTrue(logs.any { it.contains("SECRET_DETECTED") && it.contains("GOAL_TITLE") })
        assertTrue(logs.none { it.contains(title) })
    }

    @Test
    fun `a failure while building the candidate is contained, and nothing is written`() {
        val (recorder, written) = collecting()
        recorder.taskFact({ throw IllegalStateException("lookup failed") }, task(), AutopilotReason.OPERATOR_APPROVED)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `the off recorder writes nothing and builds nothing - its lookups never run`() {
        val off = SelfDevMemoryRecorder.OFF
        assertFalse(off.enabled)
        // A lookup that would fail the test if it ran: the off recorder must not reach it.
        off.taskFact({ error("the goal lookup must not run while the memory is off") }, task(), AutopilotReason.OPERATOR_SKIPPED)
        off.goalTitle(goalId, title, "operator-1")
        off.planProposed(goalId, planId, 1)
        off.runFact(goalId, task(), "01ARZ3NDEKTSV4RRFFQ69G5FC1", "PR_MERGED")
        off.goalFact(goalId, AutopilotReason.OPERATOR_STOPPED)
    }
}
