package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeObservationPolicy
import net.kigawa.kivra.core.PullRequestBinding
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
        assertFailsWith<InvalidTransitionException> { db.runs.transition(lease, v, DevelopmentRunState.TESTING, "w") } // CREATED -> DEVELOPING is allowed (ADR 0008)
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

    // ---- M8: RunCompletionMode and PR_ONLY completion (Issue #53) ----------------------------------------------------

    private fun TestDb.toPullRequest(mode: RunCompletionMode): Pair<DevelopmentRun, Lease> {
        val run = runs.create("task-${System.nanoTime()}", "test", mode)
        val lease = leases.acquire(run.runId, "w", TTL)!!
        var v = runs.get(run.runId)!!.version // acquire bumps the row version
        for (to in listOf(DevelopmentRunState.PLANNING, DevelopmentRunState.PREPARING, DevelopmentRunState.DEVELOPING, DevelopmentRunState.TESTING, DevelopmentRunState.REVIEWING, DevelopmentRunState.PULL_REQUEST)) {
            v = runs.transition(lease, v, to, "w").version
        }
        val bound = runs.bindPullRequest(lease, v, binding(run.runId), "w")
        return bound to lease
    }

    private fun binding(
        runId: RunId,
        prNumber: Long = PR,
        headSha: String = HEAD,
        repositoryId: Long = REPO_ID,
        nodeId: String = NODE,
        baseRef: String = BASE,
        policy: String = POLICY,
        manifest: String = MANIFEST,
    ) = PullRequestBinding(runId, REPO, repositoryId, prNumber, nodeId, baseRef, headSha, policy, manifest)

    /** The production call: the repository asks its injected Authority. The test supplies no evidence. */
    private fun TestDb.complete(lease: Lease, version: Long): DevelopmentRun =
        runBlocking { runs.completePrOnlyRun(lease, version, "w") }

    private fun TestDb.completedEvents(runId: RunId) = count("outbox_events", "aggregate_id = '$runId' AND event_type = 'RunCompleted'")

    private fun TestDb.sideEffects(runId: RunId) = Pair(
        count("audit_events", "run_id = '$runId'"),
        count("outbox_events", "aggregate_id = '$runId'"),
    )

    @Test
    fun `a PR_ONLY run completes on a merge the Authority verified, with one RunCompleted event and the mode unchanged`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val done = db.complete(lease, run.version)
        assertEquals(DevelopmentRunState.COMPLETED, done.state)
        assertEquals(RunCompletionMode.PR_ONLY, done.completionMode)
        assertEquals(1, db.completedEvents(run.runId))
        assertEquals(1, db.count("development_runs", "run_id = '${run.runId}' AND completion_merge_sha = '${FakeTrustedPrMergeAuthority.MERGE_SHA}' AND completion_evidence_ref = 'github-pr:$NODE'"))
    }

    @Test
    fun `a squash merge whose merge commit differs from the PR head completes`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        assertFalse(FakeTrustedPrMergeAuthority.MERGE_SHA == HEAD)
        assertEquals(DevelopmentRunState.COMPLETED, db.complete(lease, run.version).state)
    }

    @Test
    fun `a closed PR, or a provider answer that does not match the binding, never completes the run`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val before = db.sideEffects(run.runId)
        fun refusedBy(answer: (PullRequestBinding) -> net.kigawa.kivra.core.ObservedPullRequest?) {
            db.prAuthority.answer = answer
            assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        }
        refusedBy { FakeTrustedPrMergeAuthority.merged(it).copy(merged = false, mergeCommitSha = null) }
        refusedBy { FakeTrustedPrMergeAuthority.merged(it).copy(headSha = "d4".repeat(20)) }
        refusedBy { FakeTrustedPrMergeAuthority.merged(it).copy(repository = "kigawa-net/other") }
        refusedBy { FakeTrustedPrMergeAuthority.merged(it).copy(mergeCommitSha = null) }
        refusedBy { FakeTrustedPrMergeAuthority.merged(it).copy(mergeCommitSha = "") }
        refusedBy { null }
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it) }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(run.runId)!!.state)
        assertEquals(0, db.completedEvents(run.runId))
        assertEquals(before, db.sideEffects(run.runId))
        assertFailsWith<VersionConflictException> { db.complete(lease, run.version - 1) }
    }

    @Test
    fun `the same head SHA on another PR number is refused`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val before = db.sideEffects(run.runId)
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it).copy(number = PR + 1) }
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        assertEquals(before, db.sideEffects(run.runId))
    }

    @Test
    fun `the same PR number with another node id is refused`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it).copy(nodeId = "PR_other") }
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        assertEquals(0, db.completedEvents(run.runId))
    }

    @Test
    fun `a repository with the same name but another repository id is refused`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it).copy(repositoryId = REPO_ID + 1) }
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        assertEquals(0, db.completedEvents(run.runId))
    }

    @Test
    fun `a merge into another base branch is refused`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it).copy(baseRef = "release") }
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        assertEquals(0, db.completedEvents(run.runId))
    }

    @Test
    fun `a new head pushed after binding makes a merge of the old head insufficient`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val rebound = db.runs.bindPullRequest(lease, run.version, binding(run.runId, headSha = HEAD2), "w")
        // The provider still reports the old head: refused against the new binding.
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it).copy(headSha = HEAD) }
        assertFailsWith<InvalidTransitionException> { db.complete(lease, rebound.version) }
        assertEquals(0, db.completedEvents(run.runId))
        // The merge of the new head completes it.
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it) }
        assertEquals(DevelopmentRunState.COMPLETED, db.complete(lease, rebound.version).state)
    }

    @Test
    fun `a re-bind of the head, policy or manifest during verification makes the completion refused`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        // A push lands while the Authority is answering: the verification used the old binding.
        db.prAuthority.beforeAnswer = { _ ->
            db.runs.bindPullRequest(lease, db.runs.get(run.runId)!!.version, binding(run.runId, headSha = HEAD2), "w")
        }
        assertFailsWith<VersionConflictException> { db.complete(lease, run.version) }
        db.prAuthority.beforeAnswer = { _ ->
            db.runs.bindPullRequest(lease, db.runs.get(run.runId)!!.version, binding(run.runId, policy = "policy-2"), "w")
        }
        assertFailsWith<VersionConflictException> { db.complete(lease, run.version) }
        db.prAuthority.beforeAnswer = { _ -> }
        assertEquals(0, db.completedEvents(run.runId))
        // With the current binding and version, the merge completes.
        val current = db.runs.get(run.runId)!!
        assertEquals(DevelopmentRunState.COMPLETED, db.complete(lease, current.version).state)
    }

    @Test
    fun `an Authority that refuses, is unavailable, or throws leaves the run and its audit and outbox untouched`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val before = db.sideEffects(run.runId)
        db.prAuthority.answer = { null }
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it) }
        db.prAuthority.failure = PrMergeAuthorityUnavailableException("GitHub merge observation unavailable")
        assertFailsWith<PrMergeAuthorityUnavailableException> { db.complete(lease, run.version) }
        db.prAuthority.failure = IllegalStateException("boom")
        assertFailsWith<IllegalStateException> { db.complete(lease, run.version) }
        db.prAuthority.failure = null
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(run.runId)!!.state)
        assertEquals(before, db.sideEffects(run.runId))
        assertEquals(0, db.completedEvents(run.runId))
    }

    @Test
    fun `an observation from the future or older than the allowed age is refused, a recent one is accepted`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        db.prAuthority.clock.offset = Duration.ofHours(1)
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        db.prAuthority.clock.offset = PrMergeObservationPolicy.MAX_OBSERVATION_AGE.negated().minusMinutes(1)
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        assertEquals(0, db.completedEvents(run.runId))
        db.prAuthority.clock.offset = PrMergeObservationPolicy.MAX_OBSERVATION_AGE.negated().plusMinutes(1)
        assertEquals(DevelopmentRunState.COMPLETED, db.complete(lease, run.version).state)
    }

    @Test
    fun `a run whose binding columns are missing, as in a row written before V7, is never completable and the Authority is not asked`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        transaction(db.database) { execUpdate("UPDATE development_runs SET pr_number = NULL, pr_node_id = NULL WHERE run_id = ?", run.runId.value) }
        assertEquals(null, db.runs.loadPullRequestBinding(run.runId))
        val asked = db.prAuthority.calls
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        assertEquals(asked, db.prAuthority.calls)
        assertEquals(0, db.completedEvents(run.runId))
    }

    @Test
    fun `a binding for another run cannot be recorded on this lease`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val other = db.runs.create("task-other-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY)
        assertFailsWith<IllegalArgumentException> { db.runs.bindPullRequest(lease, run.version, binding(other.runId), "w") }
    }

    @Test
    fun `WAITING_APPROVAL is also a PR_ONLY completion source`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val waiting = db.runs.transition(lease, run.version, DevelopmentRunState.WAITING_APPROVAL, "w")
        assertEquals(DevelopmentRunState.COMPLETED, db.complete(lease, waiting.version).state)
    }

    @Test
    fun `a duplicate completion fails and RunCompleted is appended exactly once`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        val done = db.complete(lease, run.version)
        // Replayed webhook / polling: the same lease and the stale version, or the same lease after the terminal state.
        // COMPLETED is terminal, so the lease is no longer valid for the run: fenced out before any write.
        assertFailsWith<LeaseLostException> { db.complete(lease, run.version) }
        assertFailsWith<LeaseLostException> { db.complete(lease, done.version) }
        assertEquals(1, db.completedEvents(run.runId))
    }

    @Test
    fun `a stale worker cannot complete a run, and a held run cannot either`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        db.runs.hold(run.runId, "admin", "admin")
        assertFailsWith<RunHaltedException> { db.complete(lease, run.version) }
        db.runs.clearHold(run.runId, "admin")
        db.expireLease(run.runId.value)
        assertFailsWith<LeaseLostException> { db.complete(lease, run.version) }
        assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(run.runId)!!.state)
        assertEquals(0, db.completedEvents(run.runId))
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
        assertFailsWith<InvalidTransitionException> { db.complete(lease, run.version) }
        var v = db.runs.transition(lease, run.version, DevelopmentRunState.RELEASING, "w").version
        v = db.runs.transition(lease, v, DevelopmentRunState.VERIFYING, "w").version
        assertEquals(DevelopmentRunState.COMPLETED, db.runs.transition(lease, v, DevelopmentRunState.COMPLETED, "w").state)
    }

    @Test
    fun `the completion mode is recorded at admission and is never changed`() = TestDb().use { db ->
        val (run, lease) = db.toPullRequest(RunCompletionMode.PR_ONLY)
        assertEquals(RunCompletionMode.PR_ONLY, db.runs.get(run.runId)!!.completionMode)
        assertEquals(RunCompletionMode.PR_ONLY, db.complete(lease, run.version).completionMode)
    }

    companion object {
        private const val REPO = "kigawa-net/kivra"
        private const val REPO_ID = 987654321L
        private const val PR = 7L
        private const val NODE = "PR_kwDOExample7"
        private const val BASE = "main"
        private const val POLICY = "policy-1"
        private const val MANIFEST = "manifest-1"
        private const val HEAD = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        private const val HEAD2 = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
    }
}
