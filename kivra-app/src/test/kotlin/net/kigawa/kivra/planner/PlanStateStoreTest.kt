package net.kigawa.kivra.planner

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.db.DbPlanStateRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.reviewer.FindingSeverity
import net.kigawa.kivra.reviewer.ReviewFinding
import net.kigawa.kivra.reviewer.ReviewState
import net.kigawa.kivra.reviewer.ReviewerPipeline
import net.kigawa.kivra.reviewer.ReviewerPipelineConfig
import net.kigawa.kivra.reviewer.ReviewerPipelineOutcome
import net.kigawa.kivra.reviewer.ReviewerResult
import net.kigawa.kivra.reviewer.ReviewerVerdict
import org.jetbrains.exposed.sql.Database

class PlanStateStoreTest {
    private val runId = UUID.randomUUID()
    private val proposal = PlanProposal(listOf(
        PlannedChildTask("調査", "改行\nと引用\"を含む説明"),
        PlannedChildTask("実装", "調査結果を反映", listOf(0)),
    ))
    private val decomposition = DecompositionState(
        decompositionDepth = 2,
        childTaskIds = mapOf(0 to "child-a", 1 to "child-b"),
        childRunStates = mapOf("child-a" to DevelopmentRunState.COMPLETED, "child-b" to DevelopmentRunState.DEVELOPING),
        planProposal = proposal,
        planApproved = true,
    )
    private val review = ReviewState(2, setOf("finding-a", "finding-b"), ReviewerVerdict.REQUEST_CHANGES)

    private fun verifyContract(store: PlanStateStore) {
        assertNull(store.getDecompositionState("parent"))
        assertNull(store.getReviewState(runId))
        repeat(2) {
            store.saveDecompositionState("parent", decomposition)
            store.saveReviewState(runId, review)
        }
        assertEquals(decomposition, store.getDecompositionState("parent"))
        assertEquals(review, store.getReviewState(runId))
        assertNull(store.getDecompositionState("other"))
        assertNull(store.getReviewState(UUID.randomUUID()))

        store.saveDecompositionState("other", DecompositionState())
        val otherRunId = UUID.randomUUID()
        store.saveReviewState(otherRunId, ReviewState())
        val updated = decomposition.copy(childRunStates = mapOf("child-b" to DevelopmentRunState.COMPLETED))
        repeat(2) {
            store.saveDecompositionState("parent", updated)
            store.saveReviewState(runId, review.copy(lastVerdict = ReviewerVerdict.APPROVE))
        }
        assertEquals(updated, store.getDecompositionState("parent"))
        assertEquals(review.copy(lastVerdict = ReviewerVerdict.APPROVE), store.getReviewState(runId))
        assertEquals(DecompositionState(), store.getDecompositionState("other"))
        assertEquals(ReviewState(), store.getReviewState(otherRunId))
    }

    @Test
    fun `in memory store replaces snapshots idempotently`() {
        verifyContract(InMemoryPlanStateStore())
    }

    @Test
    fun `database store replaces snapshots idempotently and restores from a new connection`() = TestDb().use { db ->
        verifyContract(DbPlanStateRepository(db.database))
        val restored = DbPlanStateRepository(Database.connect(db.dataSource))
        assertEquals(decomposition.copy(childRunStates = mapOf("child-b" to DevelopmentRunState.COMPLETED)),
            restored.getDecompositionState("parent"))
        assertEquals(review.copy(lastVerdict = ReviewerVerdict.APPROVE), restored.getReviewState(runId))
        assertEquals(DecompositionState(), restored.getDecompositionState("other"))
        assertEquals(2, db.count("plan_decomposition_states"))
        assertEquals(2, db.count("plan_review_states"))
    }

    @Test
    fun `concurrent duplicate saves from separate repositories converge on one row`() = TestDb().use { db ->
        val stores = List(2) { DbPlanStateRepository(Database.connect(db.dataSource)) }
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val writes = List(4) { index ->
                executor.submit {
                    check(start.await(10, TimeUnit.SECONDS))
                    val store = stores[index % stores.size]
                    store.saveDecompositionState("parent", decomposition)
                    store.saveReviewState(runId, review)
                }
            }
            start.countDown()
            writes.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(decomposition, stores[0].getDecompositionState("parent"))
        assertEquals(review, stores[1].getReviewState(runId))
        assertEquals(1, db.count("plan_decomposition_states"))
        assertEquals(1, db.count("plan_review_states"))
    }

    @Test
    fun `pipelines continue from persisted state after recreation`() = TestDb().use { db ->
        runBlocking {
            fun planner(store: PlanStateStore) = PlannerPipeline(
                db.runs, null, null, PlannerPipelineConfig(), stateStore = store,
            )
            fun reviewer(store: PlanStateStore) = ReviewerPipeline(
                db.runs, ReviewerPipelineConfig(), stateStore = store,
            )
            val store = DbPlanStateRepository(db.database)
            val firstPlanner = planner(store)
            assertEquals(DecompositionState(), firstPlanner.getDecompositionState("parent"))
            assertTrue(firstPlanner.shouldEnterPlanning("parent"))
            assertTrue(firstPlanner.processPlanProposal("parent", proposal))
            firstPlanner.processPlanReview("parent", ReviewerResult(ReviewerVerdict.APPROVE, emptyList(), "approved"))
            firstPlanner.addChildTask("parent", 0, "child-a")
            firstPlanner.addChildTask("parent", 1, "child-b")
            firstPlanner.updateChildRunState("parent", "child-a", DevelopmentRunState.COMPLETED)
            firstPlanner.updateChildRunState("parent", "child-b", DevelopmentRunState.DEVELOPING)

            val finding = ReviewFinding("quality", FindingSeverity.MAJOR, "missing validation")
            val result = ReviewerResult(ReviewerVerdict.REQUEST_CHANGES, listOf(finding), "fix validation")
            assertEquals(ReviewerPipelineOutcome.REQUEST_CHANGES_ACCEPTED,
                reviewer(store).processReviewerResult(runId, result))

            val restoredStore = DbPlanStateRepository(Database.connect(db.dataSource))
            val restoredPlanner = planner(restoredStore)
            assertFalse(restoredPlanner.shouldEnterPlanning("parent"))
            assertEquals(proposal, restoredPlanner.getApprovedPlan("parent"))
            assertEquals(1, restoredPlanner.getDecompositionState("parent").decompositionDepth)
            assertFalse(restoredPlanner.areAllChildrenComplete("parent"))
            restoredPlanner.updateChildRunState("parent", "child-b", DevelopmentRunState.COMPLETED)
            assertTrue(planner(store).areAllChildrenComplete("parent"))

            val restoredReviewer = reviewer(restoredStore)
            assertEquals(ReviewState(1, setOf(finding.description.hashCode().toString()), ReviewerVerdict.REQUEST_CHANGES),
                restoredReviewer.getReviewState(runId))
            assertEquals(ReviewerPipelineOutcome.ESCALATED_REPEATED_FINDINGS,
                restoredReviewer.processReviewerResult(runId, result))
            restoredStore.saveReviewState(runId, review.copy(reviewRound = 3))
            assertTrue(reviewer(store).hasReachedLimit(runId))
        }
    }
}
