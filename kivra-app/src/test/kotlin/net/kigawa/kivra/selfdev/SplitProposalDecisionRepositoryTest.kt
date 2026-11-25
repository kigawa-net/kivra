package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.ChildProposalState
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.CycleStarted
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.PlanCommit
import net.kigawa.kivra.db.PlanFence
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SplitProposalMark
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

/** A clock the test holds still, so the rows' times are exact. */
private class DecisionTestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The decisions on child order proposals (V60, ADR 0015 addendum 2026-11-22 PR2) at the repository: the approval mark and the rejection are
 * conditional writes, and a proposal is expired when its order is no longer ACTIVE or its plan is superseded. H2 (TestDb).
 */
class SplitProposalDecisionRepositoryTest {

    private inner class Harness(val db: TestDb) {
        val clock = DecisionTestClock(Instant.parse("2026-11-22T01:00:00Z"))
        val orders = SelfDevOrderRepository(db.database, clock)
        val ops = SelfDevOperatorRepository(db.database, clock)
        val project: String = ProjectRepository(db.database, clock).create("Decisions", null).projectId

        fun newRoot(): SelfDevOrder =
            (orders.createRoot("operator", "Root title", "Improve the docs", "kigawa-net/kivra", 3, project) as CreateRootResult.Created).order

        fun startCycle(root: SelfDevOrder): CycleStarted = (orders.startCycle(root.orderId, root.version, "operator") as OrderResult.Moved).value

        fun claim(taskId: String): PlanFence = assertNotNull(ops.claimForPlanning(taskId, "token-$taskId", Duration.ofMinutes(10))).fence

        fun task() = NewTask(
            title = "Docs", body = "body", targetPaths = listOf("docs/a.md"), sizeEstimate = "S", dependsOn = emptyList(),
            requiresApproval = false, initialState = TaskState.QUEUED,
        )

        /** A root with one proposal (ordinal 1), and the cycle it was planned in. */
        fun proposed(): Pair<SelfDevOrder, Pair<CycleStarted, String>> {
            val root = newRoot()
            val cycle = startCycle(root)
            val committed = ops.commitPlan(claim(cycle.goalId), listOf(task()), listOf(NewChildOrder(1, "Workstream one", "Do one", 1)), root.orderId)
            assertIs<PlanCommit.Committed>(committed)
            return root to (cycle to ops.splitProposalsOf(root.orderId, 10).single().proposalId)
        }
    }

    @Test
    fun `an approval mark is written once, a repeat with the same child is Same, and another child is Refused`() = TestDb().use { db ->
        val h = Harness(db)
        val (root, cycleAndProposal) = h.proposed()
        val proposalId = cycleAndProposal.second
        val limits = ChildLimits(2, 5, 50)
        val child = assertIs<ChildCreateResult.Created>(
            h.orders.createChild(root.orderId, "Workstream one", "Do one", 1_000, "plan-split:x:1", "operator", limits, 1),
        ).order
        val other = assertIs<ChildCreateResult.Created>(
            h.orders.createChild(root.orderId, "Other", "Do other", 1_000, "plan-split:x:2", "operator", limits, 1),
        ).order

        assertIs<SplitProposalMark.Moved>(h.ops.markSplitProposalApproved(root.orderId, proposalId, child.orderId))
        assertIs<SplitProposalMark.Same>(h.ops.markSplitProposalApproved(root.orderId, proposalId, child.orderId))
        assertIs<SplitProposalMark.Refused>(h.ops.markSplitProposalApproved(root.orderId, proposalId, other.orderId))
        // An approved proposal cannot be rejected afterwards.
        assertIs<SplitProposalMark.Refused>(h.ops.rejectSplitProposal(root.orderId, proposalId))
        val row = h.ops.splitProposal(root.orderId, proposalId)
        assertNotNull(row)
        assertEquals(ChildProposalState.APPROVED, row.state)
        assertEquals(child.orderId, row.createdChildOrderId)
    }

    @Test
    fun `a reject is written once, a repeat is Same, and a rejected proposal cannot be approved`() = TestDb().use { db ->
        val h = Harness(db)
        val (root, cycleAndProposal) = h.proposed()
        val proposalId = cycleAndProposal.second

        assertIs<SplitProposalMark.Moved>(h.ops.rejectSplitProposal(root.orderId, proposalId))
        assertIs<SplitProposalMark.Same>(h.ops.rejectSplitProposal(root.orderId, proposalId))
        assertIs<SplitProposalMark.Refused>(h.ops.markSplitProposalApproved(root.orderId, proposalId, root.orderId))
        assertEquals(ChildProposalState.REJECTED, h.ops.splitProposal(root.orderId, proposalId)?.state)
        assertIs<SplitProposalMark.NotFound>(h.ops.rejectSplitProposal(root.orderId, "01AAAAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun `a proposal of an order that is no longer ACTIVE is EXPIRED on read, and cannot be decided`() = TestDb().use { db ->
        val h = Harness(db)
        val (root, cycleAndProposal) = h.proposed()
        val proposalId = cycleAndProposal.second
        assertIs<OrderResult.Moved<SelfDevOrder>>(h.orders.pauseByOperator(root.orderId, assertNotNull(h.orders.get(root.orderId)).version, "operator"))

        assertEquals(ChildProposalState.EXPIRED, h.ops.splitProposal(root.orderId, proposalId)?.state)
        assertIs<SplitProposalMark.Refused>(h.ops.rejectSplitProposal(root.orderId, proposalId))
        // An expired row is never written back to PROPOSED, even when the order resumes.
        assertIs<OrderResult.Moved<*>>(h.orders.resumeByOperator(root.orderId, assertNotNull(h.orders.get(root.orderId)).version, "operator"))
        assertEquals(ChildProposalState.EXPIRED, h.ops.splitProposal(root.orderId, proposalId)?.state)
    }

    @Test
    fun `a proposal of a superseded plan is EXPIRED, and the list can be filtered by state`() = TestDb().use { db ->
        val h = Harness(db)
        val (root, cycleAndProposal) = h.proposed()
        val (cycle, proposalId) = cycleAndProposal
        // A re-plan moves the root task's revision past the proposing plan's "r{revision}".
        transaction(db.database) {
            execUpdate("UPDATE selfdev_tasks SET revision = revision + 1 WHERE task_id = ?", cycle.goalId)
        }

        assertEquals(ChildProposalState.EXPIRED, h.ops.splitProposal(root.orderId, proposalId)?.state)
        assertEquals(0, h.ops.splitProposalsOf(root.orderId, 10, ChildProposalState.PROPOSED).size)
        assertEquals(1, h.ops.splitProposalsOf(root.orderId, 10, ChildProposalState.EXPIRED).size)
        assertNull(h.ops.splitProposal(root.orderId, "01AAAAAAAAAAAAAAAAAAAAAAAA"))
    }
}
