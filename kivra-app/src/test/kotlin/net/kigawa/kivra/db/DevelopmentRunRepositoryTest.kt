package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.PrMergeEvidence
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)

class DevelopmentRunRepositoryTest {
    @Test
    fun `create writes the run, an audit event and an outbox event`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE, traceId = "trace-1")
        assertEquals(DevelopmentRunState.CREATED, run.state)
        assertEquals(0, run.version)
        assertEquals(1, db.count("audit_events"))
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${run.runId}' AND aggregate_version = 0"))
    }

    @Test
    fun `only one active run per task but a new run is allowed after a terminal one`() = TestDb().use { db ->
        val first = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        assertFailsWith<DuplicateActiveRunException> { db.runs.create("task-1", "test", RunCompletionMode.RELEASE) }
        db.runs.create("task-2", "test", RunCompletionMode.RELEASE) // other tasks are unaffected

        val lease = db.leases.acquire(first.runId, "w", TTL)!!
        db.runs.transition(lease, db.runs.get(first.runId)!!.version, DevelopmentRunState.CANCELLED, "w")
        val second = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        assertFalse(second.runId == first.runId)
    }

    @Test
    fun `transition bumps version and writes audit and outbox in the same transaction`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val before = db.runs.get(run.runId)!!
        val after = db.runs.transition(lease, before.version, DevelopmentRunState.PLANNING, "w", reason = "picked")
        assertEquals(DevelopmentRunState.PLANNING, after.state)
        assertEquals(before.version + 1, after.version)
        assertEquals(1, db.count("outbox_events", "aggregate_version = ${after.version} AND event_type = 'RunStateChanged'"))
        assertEquals(2, db.count("audit_events"))
        assertTrue(transaction(db.database) { db.audit.verifyChain(this, run.runId) })
    }

    @Test
    fun `invalid transitions and stale versions are rejected without side effects`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val v = db.runs.get(run.runId)!!.version
        assertFailsWith<InvalidTransitionException> { db.runs.transition(lease, v, DevelopmentRunState.DEVELOPING, "w") }
        assertFailsWith<VersionConflictException> { db.runs.transition(lease, v - 1, DevelopmentRunState.PLANNING, "w") }
        assertEquals(DevelopmentRunState.CREATED, db.runs.get(run.runId)!!.state)
        assertEquals(1, db.count("outbox_events"))
        assertEquals(1, db.count("audit_events"))
    }

    @Test
    fun `terminal states have no way out`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.runs.transition(lease, db.runs.get(run.runId)!!.version, DevelopmentRunState.CANCELLED, "w")
        // The lease is useless on a terminal run (requireLease rejects non-terminal-only writes).
        assertFailsWith<LeaseLostException> {
            db.runs.transition(lease, db.runs.get(run.runId)!!.version, DevelopmentRunState.PLANNING, "w")
        }
    }

    @Test
    fun `a stale worker rolls back everything including audit and outbox`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "a", TTL)!!
        db.expireLease(run.runId.value)
        db.leases.acquire(run.runId, "b", TTL)!!
        val v = db.runs.get(run.runId)!!.version
        assertFailsWith<LeaseLostException> { db.runs.transition(a, v, DevelopmentRunState.PLANNING, "a") }
        assertEquals(1, db.count("outbox_events"))
        assertEquals(1, db.count("audit_events"))
    }

    @Test
    fun `audit chain detects tampering`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.runs.transition(lease, db.runs.get(run.runId)!!.version, DevelopmentRunState.PLANNING, "w")
        assertTrue(transaction(db.database) { db.audit.verifyChain(this, run.runId) })
        transaction(db.database) { execUpdate("UPDATE audit_events SET actor = 'mallory' WHERE seq = 1") }
        assertFalse(transaction(db.database) { db.audit.verifyChain(this, run.runId) })
    }

    // ---- M8: RunCompletionMode and PR_ONLY completion -----------------------------------------------------------------

    private fun TestDb.toPullRequest(mode: RunCompletionMode): Pair<DevelopmentRun, Lease> {
        val run = runs.create("task-${System.nanoTime()}", "test", mode)
        val lease = leases.acquire(run.runId, "w", TTL)!!
        var v = runs.get(run.runId)!!.version // acquire bumps the row version
        for (to in listOf(DevelopmentRunState.PLANNING, DevelopmentRunState.PREPARING, DevelopmentRunState.DEVELOPING, DevelopmentRunState.TESTING, DevelopmentRunState.REVIEWING, DevelopmentRunState.PULL_REQUEST)) {
            v = runs.transition(lease, v, to, "w").version
        }
        val bound = runs.bindPullRequest(lease, v, REPO, HEAD, "w")
        return bound to lease
    }

    private fun mergeEvidence(
        runId: RunId,
        merged: Boolean = true,
        head: String = HEAD,
        repo: String = REPO,
        mergeSha: String? = "merge-1",
        issuer: String = "github-adapter",
        observedAt: LocalDateTime = utcNow(Clock.systemUTC()),
    ) = PrMergeEvidence(runId, issuer, repo, head, mergeSha, merged, "github:pr/1/merged", observedAt)

    @Test
    fun `a PR_ONLY run completes on verified merge, with one RunCompleted event and the mode unchanged`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val done = db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w")
        assertEquals(DevelopmentRunState.COMPLETED, done.state)
        assertEquals(RunCompletionMode.PR_ONLY, done.completionMode)
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${run.runId}' AND event_type = 'RunCompleted'"))
        assertEquals(1, db.count("development_runs", "run_id = '${run.runId}' AND completion_merge_sha = 'merge-1' AND completion_evidence_ref = 'github:pr/1/merged'"))
    }

    @Test
    fun `closing the PR without merging, a merge of another head, or another repository never completes the run`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId, merged = false, mergeSha = null), "w") }
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId, head = "other-head"), "w") }
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId, repo = "kigawa-net/other"), "w") }
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId, mergeSha = null), "w") }
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId, issuer = "random-agent"), "w") }
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(RunId(Ulid.generate())), "w") }
        assertFailsWith<InvalidTransitionException> {
            db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId, observedAt = utcNow(Clock.systemUTC()).plusHours(1)), "w")
        }
        assertFailsWith<VersionConflictException> { db.runs.completePrOnlyRun(lease, run.version - 1, mergeEvidence(run.runId), "w") }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(run.runId)!!.state)
        assertEquals(0, db.count("outbox_events", "aggregate_id = '${run.runId}' AND event_type = 'RunCompleted'"))
    }

    @Test
    fun `a new head pushed after binding makes the merge of the old head insufficient`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val rebound = db.runs.bindPullRequest(lease, run.version, REPO, "head-2", "w")
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, rebound.version, mergeEvidence(run.runId, head = HEAD), "w") }
        assertEquals(DevelopmentRunState.COMPLETED, db.runs.completePrOnlyRun(lease, rebound.version, mergeEvidence(run.runId, head = "head-2"), "w").state)
    }

    @Test
    fun `WAITING_APPROVAL is also a PR_ONLY completion source`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val waiting = db.runs.transition(lease, run.version, DevelopmentRunState.WAITING_APPROVAL, "w")
        assertEquals(DevelopmentRunState.COMPLETED, db.runs.completePrOnlyRun(lease, waiting.version, mergeEvidence(run.runId), "w").state)
    }

    @Test
    fun `a duplicate completion fails and RunCompleted is appended exactly once`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val done = db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w")
        // Replayed webhook / polling: the same lease and the stale version, or the same lease after the terminal state.
        // COMPLETED is terminal, so the lease is no longer valid for the run: fenced out before any write.
        assertFailsWith<LeaseLostException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w") }
        assertFailsWith<LeaseLostException> { db.runs.completePrOnlyRun(lease, done.version, mergeEvidence(run.runId), "w") }
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${run.runId}' AND event_type = 'RunCompleted'"))
    }

    @Test
    fun `a stale worker cannot complete a run, and a held run cannot either`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        db.runs.hold(run.runId, "admin", "admin")
        assertFailsWith<RunHaltedException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w") }
        db.runs.clearHold(run.runId, "admin")
        db.expireLease(run.runId.value)
        assertFailsWith<LeaseLostException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w") }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(run.runId)!!.state)
        assertEquals(0, db.count("outbox_events", "aggregate_id = '${run.runId}' AND event_type = 'RunCompleted'"))
    }

    @Test
    fun `generic transitions cannot reach COMPLETED, and PR_ONLY runs never release`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        assertFailsWith<InvalidTransitionException> { db.runs.transition(lease, run.version, DevelopmentRunState.COMPLETED, "w") }
        assertFailsWith<InvalidTransitionException> { db.runs.transition(lease, run.version, DevelopmentRunState.RELEASING, "w") }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(run.runId)!!.state)
    }

    @Test
    fun `completePrOnlyRun is refused for RELEASE runs, which keep the RELEASING VERIFYING COMPLETED path`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.RELEASE)
        assertFailsWith<InvalidTransitionException> { db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w") }
        var v = db.runs.transition(lease, run.version, DevelopmentRunState.RELEASING, "w").version
        v = db.runs.transition(lease, v, DevelopmentRunState.VERIFYING, "w").version
        assertEquals(DevelopmentRunState.COMPLETED, db.runs.transition(lease, v, DevelopmentRunState.COMPLETED, "w").state)
    }

    @Test
    fun `the completion mode is recorded at admission and is never changed`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        assertEquals(RunCompletionMode.PR_ONLY, db.runs.get(run.runId)!!.completionMode)
        assertEquals(RunCompletionMode.PR_ONLY, db.runs.completePrOnlyRun(lease, run.version, mergeEvidence(run.runId), "w").completionMode)
    }

    companion object {
        private const val REPO = "kigawa-net/kivra"
        private const val HEAD = "head-1"
    }
}
