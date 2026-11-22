package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ChildProposalState
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.CycleStarted
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.PlanCommit
import net.kigawa.kivra.db.PlanFence
import net.kigawa.kivra.db.PlanningOrder
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.db.TestDb

/** A clock the test holds still (or moves by hand), so the rows' times are exact. */
private class SplitTestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The child order proposals of a committed plan (V60, ADR 0015 addendum 2026-11-22): written with the plan in one transaction, listed by
 * their order, and never creating an order. The database is the real one (TestDb).
 */
class SplitProposalRepositoryTest {

    private inner class Harness(val db: TestDb) {
        val clock = SplitTestClock(Instant.parse("2026-11-22T01:00:00Z"))
        val orders = SelfDevOrderRepository(db.database, clock)
        val ops = SelfDevOperatorRepository(db.database, clock)
        val project: String = ProjectRepository(db.database, clock).create("Splits", null).projectId

        fun newRoot(): SelfDevOrder =
            (orders.createRoot("operator", "Root title", "Improve the docs", "kigawa-net/kivra", 3, project) as CreateRootResult.Created).order

        /** Starts the root's first cycle, so the cycle's root task exists and is TASK_SUBMITTED. */
        fun startCycle(root: SelfDevOrder): CycleStarted = (orders.startCycle(root.orderId, root.version, "operator") as OrderResult.Moved).value

        fun claim(taskId: String): PlanFence = assertNotNull(ops.claimForPlanning(taskId, "token-$taskId", Duration.ofMinutes(10))).fence

        fun task() = NewTask(
            title = "Docs", body = "body", targetPaths = listOf("docs/a.md"), sizeEstimate = "S", dependsOn = emptyList(),
            requiresApproval = false, initialState = TaskState.QUEUED,
        )

        fun child(ordinal: Int, title: String = "Workstream $ordinal") =
            NewChildOrder(ordinal = ordinal, title = title, instruction = "Do $title", maxCycles = ordinal)
    }

    @Test
    fun `a committed plan stores its child proposals under the order, with the plan id, in ordinal order`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newRoot()
        val cycle = h.startCycle(root)
        val fence = h.claim(cycle.goalId)

        val committed = h.ops.commitPlan(fence, listOf(h.task()), listOf(h.child(1), h.child(2)), root.orderId)
        assertIs<PlanCommit.Committed>(committed)

        val proposals = h.ops.splitProposalsOf(root.orderId, 10)
        assertEquals(listOf(1, 2), proposals.map { it.ordinal })
        val first = proposals.first()
        assertEquals("${cycle.goalId}:r1", first.planId)
        assertEquals(cycle.goalId, first.planTaskId)
        assertEquals(root.orderId, first.orderId)
        assertEquals("Workstream 1", first.title)
        assertEquals("Do Workstream 1", first.instruction)
        assertEquals(1, first.maxCycles)
        assertEquals(ChildProposalState.PROPOSED, first.state)
        assertNull(first.createdChildOrderId)
        assertEquals(2, proposals[1].maxCycles)
        // A proposal creates no order: the root is the only order of the project.
        assertEquals(1, h.orders.list(100, net.kigawa.kivra.db.ProjectFilter.Only(h.project)).size)
    }

    @Test
    fun `a plan with no child orders writes no proposal rows, and the plan is stored as before`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newRoot()
        val cycle = h.startCycle(root)
        val fence = h.claim(cycle.goalId)

        assertIs<PlanCommit.Committed>(h.ops.commitPlan(fence, listOf(h.task())))
        assertEquals(emptyList(), h.ops.splitProposalsOf(root.orderId, 10))
        val task = assertNotNull(h.ops.task(cycle.goalId))
        assertEquals(TaskState.PENDING_APPROVAL, task.state)
        assertEquals(1, task.revision)
    }

    @Test
    fun `a blocked planning attempt writes no proposals, and the plan committed after a re-plan is the one stored`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newRoot()
        val cycle = h.startCycle(root)
        val first = h.claim(cycle.goalId)
        assertTrue(h.ops.blockPlanning(first, AutopilotReason.PLAN_INVALID))
        assertEquals(emptyList(), h.ops.splitProposalsOf(root.orderId, 10))

        val blocked = assertNotNull(h.ops.task(cycle.goalId))
        assertIs<TaskTransition.Moved>(h.ops.replanBlocked(cycle.goalId, blocked.version))
        val second = h.claim(cycle.goalId)
        assertIs<PlanCommit.Committed>(h.ops.commitPlan(second, listOf(h.task()), listOf(h.child(1)), root.orderId))

        val proposals = h.ops.splitProposalsOf(root.orderId, 10)
        assertEquals(1, proposals.size)
        assertEquals("${cycle.goalId}:r1", proposals.single().planId)
    }

    @Test
    fun `a stale fence writes neither the tasks nor the proposals`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newRoot()
        val cycle = h.startCycle(root)
        val fence = h.claim(cycle.goalId)

        val stale = PlanFence(fence.taskId, fence.version, "another-token")
        assertEquals(PlanCommit.Stale, h.ops.commitPlan(stale, listOf(h.task()), listOf(h.child(1)), root.orderId))
        assertEquals(emptyList(), h.ops.splitProposalsOf(root.orderId, 10))
        assertEquals(TaskState.PLANNING, assertNotNull(h.ops.task(cycle.goalId)).state)
    }

    @Test
    fun `child proposals need the order that proposes them`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newRoot()
        val cycle = h.startCycle(root)
        val fence = h.claim(cycle.goalId)

        assertFailsWith<IllegalArgumentException> { h.ops.commitPlan(fence, listOf(h.task()), listOf(h.child(1)), null) }
        assertEquals(emptyList(), h.ops.splitProposalsOf(root.orderId, 10))
    }

    @Test
    fun `planningOrderOf tells the root order of a cycle, and nothing for an operator's own task`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newRoot()
        val cycle = h.startCycle(root)
        assertEquals(PlanningOrder(root.orderId, null, 0), h.ops.planningOrderOf(cycle.goalId))

        val manual = assertIs<SelfDevSubmitResult.Created>(
            h.ops.submitTask("operator", "manual-1", "Manual", "body", "kigawa-net/kivra", h.project),
        ).task
        assertNull(h.ops.planningOrderOf(manual.taskId))
        assertNull(h.ops.planningOrderOf("01M0000000000000000000000Z"))
    }
}
