package net.kigawa.kivra.db

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.coding.HoldOutcome
import net.kigawa.kivra.config.SelfDevOrphanAutoReleaseSettings
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The automatic release of orphaned UNKNOWN self-development runs (ADR 0009 追補 2026-11-16) with the real repository (H2, or MariaDB with
 * `KIVRA_TEST_DB=mariadb`): only UNKNOWN / CODING_ORPHANED self-development runs without any GitHub step and past the grace are released,
 * with the same RUN_RELEASED audit and RunStateChanged outbox as the operator's release; every other UNKNOWN stays; a second pass, a second
 * replica and a concurrent pass release nothing twice; the worker and the settings.
 */
class SelfDevOrphanAutoReleaseTest {
    private val grace = Duration.ofMinutes(10)

    /** A self-development run moved DEVELOPING -> UNKNOWN by the coding runtime's own hold, as an orphaned attempt does. */
    private fun TestDb.orphanedRun(
        key: String = "k-${System.nanoTime()}",
        reason: String = CodingRuntimeService.REASON_ORPHANED,
        selfDev: Boolean = true,
        ageMinutes: Long = 60,
        beforeHold: (RunId) -> Unit = {},
    ): RunId {
        val id = runs.submitTask("alice", key, "README を直す", selfDev = selfDev, maxActiveSelfDev = null).run.runId
        forceState(id.value, DevelopmentRunState.DEVELOPING)
        beforeHold(id)
        assertEquals(HoldOutcome.MOVED_TO_UNKNOWN, DbCodingRunPort(runs, leases).holdForOperator(id, reason))
        age(id, ageMinutes)
        return id
    }

    private fun TestDb.age(id: RunId, minutes: Long) = transaction(database) {
        execUpdate(
            "UPDATE development_runs SET updated_at = ? WHERE run_id = ?",
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes), id.value,
        )
    }

    private fun TestDb.releasedAudits(id: RunId) = count("audit_events", "run_id = '$id' AND action = 'RUN_RELEASED'")
    private fun TestDb.cancelOutbox(id: RunId) =
        count("outbox_events", "aggregate_id = '$id' AND event_type = 'RunStateChanged' AND payload LIKE '%\"to\":\"CANCELLED\"%'")

    private fun TestDb.assertUntouched(id: RunId, reason: String = CodingRuntimeService.REASON_ORPHANED) {
        val run = runs.get(id)!!
        assertEquals(DevelopmentRunState.UNKNOWN, run.state)
        assertEquals(reason, run.stateReason)
        assertEquals(0, releasedAudits(id))
        assertEquals(0, cancelOutbox(id))
    }

    @Test
    fun `the repository's reason constant is the coding runtime's`() {
        assertEquals(CodingRuntimeService.REASON_ORPHANED, CODING_ORPHANED_REASON)
        assertTrue(AUTO_RELEASED_ORPHAN_REASON.length <= 64, "fits state_reason varchar(64)")
        assertTrue(AUTO_RELEASED_ORPHAN_REASON != OPERATOR_RELEASED_REASON)
        assertTrue(AUTO_RELEASED_NEEDS_HUMAN_REASON.length <= 64, "fits state_reason varchar(64)")
        // The runner writes CODING_<code> for a NEEDS_HUMAN failure (CodingAttemptPatchSource).
        assertEquals("CODING_" + net.kigawa.kivra.coding.CodingErrorCode.NEEDS_HUMAN.name, CODING_NEEDS_HUMAN_REASON)
        assertEquals(
            mapOf(CODING_ORPHANED_REASON to AUTO_RELEASED_ORPHAN_REASON, CODING_NEEDS_HUMAN_REASON to AUTO_RELEASED_NEEDS_HUMAN_REASON),
            SELF_DEV_AUTO_RELEASE_REASONS,
        )
        assertEquals(4, (SELF_DEV_AUTO_RELEASE_REASONS.values + OPERATOR_RELEASED_REASON + OPERATOR_RELEASED_STUCK_REASON).toSet().size, "every released reason is distinct")
    }

    @Test
    fun `a NEEDS_HUMAN run past the grace is released with its own reason, the audit and the outbox, once`() = TestDb().use { db ->
        // The attempt the agent failed with NEEDS_HUMAN is terminal (FAILED), so it does not block the release.
        val id = db.orphanedRun(reason = CODING_NEEDS_HUMAN_REASON, beforeHold = { runId ->
            val queued = CodingAttemptRepository(db.database).enqueue(runId.value, "a".repeat(40), Duration.ofMinutes(5))
            val attemptId = assertIs<EnqueueResult.Enqueued>(queued).attempt.attemptId
            transaction(db.database) { execUpdate("UPDATE coding_attempts SET state = 'FAILED', error_code = 'NEEDS_HUMAN' WHERE attempt_id = ?", attemptId) }
        })
        assertEquals(AutoReleaseReport(1, 0, 0, mapOf(AUTO_RELEASED_NEEDS_HUMAN_REASON to 1)), db.runs.autoReleaseUnknownRuns(grace, 50))
        val run = db.runs.get(id)!!
        assertEquals(DevelopmentRunState.CANCELLED, run.state)
        assertEquals(AUTO_RELEASED_NEEDS_HUMAN_REASON, run.stateReason)
        assertEquals(
            1,
            db.count(
                "audit_events",
                "run_id = '$id' AND action = 'RUN_RELEASED' AND actor = '$SELF_DEV_AUTO_RELEASE_ACTOR' " +
                    "AND payload LIKE '%\"from\":\"UNKNOWN\"%' AND payload LIKE '%\"reasonCode\":\"$AUTO_RELEASED_NEEDS_HUMAN_REASON\"%' " +
                    "AND payload LIKE '%\"reason\":\"$AUTO_RELEASE_GRACE_ELAPSED\"%'",
            ),
        )
        assertEquals(
            1,
            db.count(
                "outbox_events",
                "aggregate_id = '$id' AND event_type = 'RunStateChanged' AND aggregate_version = ${run.version} " +
                    "AND payload LIKE '%\"reasonCode\":\"$AUTO_RELEASED_NEEDS_HUMAN_REASON\"%'",
            ),
        )
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50), "a second pass releases nothing")
        assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(id, grace))
        assertEquals(1, db.releasedAudits(id))
        assertEquals(1, db.cancelOutbox(id))
    }

    @Test
    fun `a NEEDS_HUMAN run within the grace, bound to a pull request, past PATCH_READY or not self-development is not released`() = TestDb().use { db ->
        val recent = db.orphanedRun(reason = CODING_NEEDS_HUMAN_REASON, ageMinutes = 9)
        val bound = db.orphanedRun(reason = CODING_NEEDS_HUMAN_REASON)
        transaction(db.database) { execUpdate("UPDATE development_runs SET pr_number = 7, pr_repository = 'kigawa-net/kivra' WHERE run_id = ?", bound.value) }
        val notSelfDev = db.orphanedRun(reason = CODING_NEEDS_HUMAN_REASON, selfDev = false)
        // A resume from PATCH_READY maps any failure to UNKNOWN CODING_<code>; GitHub may have been written, so it stays.
        val resumed = db.runs.submitTask("alice", "needs-human-resume", "README を直す", selfDev = true, maxActiveSelfDev = null).run.runId
        db.forceState(resumed.value, DevelopmentRunState.DEVELOPING)
        val lease = db.leases.acquire(resumed, "worker", Duration.ofMinutes(1), Duration.ZERO)!!
        val ready = db.runs.transition(lease, db.runs.get(resumed)!!.version, DevelopmentRunState.PATCH_READY, "worker")
        db.runs.transition(lease, ready.version, DevelopmentRunState.UNKNOWN, "worker", reason = CODING_NEEDS_HUMAN_REASON)
        db.leases.release(lease)
        db.age(resumed, 60)
        assertEquals(AutoReleaseReport(0, 1, 0), db.runs.autoReleaseUnknownRuns(grace, 50), "only the resumed run is a candidate by its row")
        for (id in listOf(recent, bound, notSelfDev, resumed)) {
            assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(id, grace))
            db.assertUntouched(id, CODING_NEEDS_HUMAN_REASON)
        }
    }

    @Test
    fun `one pass takes both reasons, each with its own released reason`() = TestDb().use { db ->
        val orphan = db.orphanedRun(ageMinutes = 61)
        val needsHuman = db.orphanedRun(reason = CODING_NEEDS_HUMAN_REASON, ageMinutes = 60)
        val other = db.orphanedRun(reason = "PR_NOT_FOUND")
        assertEquals(
            AutoReleaseReport(2, 0, 0, mapOf(AUTO_RELEASED_ORPHAN_REASON to 1, AUTO_RELEASED_NEEDS_HUMAN_REASON to 1)),
            db.runs.autoReleaseUnknownRuns(grace, 50),
        )
        assertEquals(AUTO_RELEASED_ORPHAN_REASON, db.runs.get(orphan)!!.stateReason)
        assertEquals(AUTO_RELEASED_NEEDS_HUMAN_REASON, db.runs.get(needsHuman)!!.stateReason)
        db.assertUntouched(other, "PR_NOT_FOUND")
    }

    @Test
    fun `an orphaned run past the grace is released with the audit and the outbox in one go`() = TestDb().use { db ->
        val id = db.orphanedRun()
        assertEquals(AutoReleaseReport(1, 0, 0, mapOf(AUTO_RELEASED_ORPHAN_REASON to 1)), db.runs.autoReleaseUnknownRuns(grace, 50))
        val run = db.runs.get(id)!!
        assertEquals(DevelopmentRunState.CANCELLED, run.state)
        assertEquals(AUTO_RELEASED_ORPHAN_REASON, run.stateReason)
        assertEquals(
            1,
            db.count(
                "audit_events",
                "run_id = '$id' AND action = 'RUN_RELEASED' AND actor = '$SELF_DEV_AUTO_RELEASE_ACTOR' AND result = 'OK' " +
                    "AND payload LIKE '%\"from\":\"UNKNOWN\"%' AND payload LIKE '%\"to\":\"CANCELLED\"%' " +
                    "AND payload LIKE '%\"reasonCode\":\"$AUTO_RELEASED_ORPHAN_REASON\"%' AND payload LIKE '%\"reason\":\"$AUTO_RELEASE_GRACE_ELAPSED\"%'",
            ),
        )
        assertEquals(
            1,
            db.count(
                "outbox_events",
                "aggregate_id = '$id' AND event_type = 'RunStateChanged' AND aggregate_version = ${run.version} " +
                    "AND payload LIKE '%\"from\":\"UNKNOWN\"%' AND payload LIKE '%\"reasonCode\":\"$AUTO_RELEASED_ORPHAN_REASON\"%'",
            ),
        )
        // A released run no longer counts against the active-run cap.
        db.runs.submitTask("bob", "after-release", "次の依頼", selfDev = true, maxActiveSelfDev = 1)
    }

    @Test
    fun `a second pass releases nothing more`() = TestDb().use { db ->
        val id = db.orphanedRun()
        assertEquals(AutoReleaseReport(1, 0, 0, mapOf(AUTO_RELEASED_ORPHAN_REASON to 1)), db.runs.autoReleaseUnknownRuns(grace, 50))
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(id, grace))
        assertEquals(1, db.releasedAudits(id))
        assertEquals(1, db.cancelOutbox(id))
    }

    @Test
    fun `within the grace nothing is released`() = TestDb().use { db ->
        val id = db.orphanedRun(ageMinutes = 9)
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(id, grace), "the lock-side check refuses it too")
        db.assertUntouched(id)
    }

    @Test
    fun `other UNKNOWN reasons stay for an operator`() = TestDb().use { db ->
        val others = listOf("PR_NOT_FOUND", "PR_AMBIGUOUS", "GITHUB_UNKNOWN_OUTCOME", "DEVELOPER_OUTCOME_UNKNOWN", CodingRuntimeService.REASON_QUEUE_EXPIRED, "CODING_AUTH_REQUIRED", "CODING_RESULT_MISSING", "COMMIT_NOT_APPLIED_TWICE")
        val ids = others.map { it to db.orphanedRun(reason = it) }
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        for ((reason, id) in ids) {
            assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(id, grace), reason)
            db.assertUntouched(id, reason)
        }
    }

    @Test
    fun `a run bound to a pull request, even partly, is not released`() = TestDb().use { db ->
        val bound = db.orphanedRun()
        transaction(db.database) { execUpdate("UPDATE development_runs SET pr_number = 7, pr_repository = 'kigawa-net/kivra' WHERE run_id = ?", bound.value) }
        val headOnly = db.orphanedRun()
        transaction(db.database) { execUpdate("UPDATE development_runs SET pr_head_sha = ? WHERE run_id = ?", "b".repeat(40), headOnly.value) }
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        // The lock-side check holds on its own as well (the candidate query is only a hint).
        assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(bound, grace))
        assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(headOnly, grace))
        db.assertUntouched(bound)
        db.assertUntouched(headOnly)
    }

    @Test
    fun `a run that reached PATCH_READY (the first GitHub write) is not released`() = TestDb().use { db ->
        val id = db.runs.submitTask("alice", "patch-ready", "README を直す", selfDev = true, maxActiveSelfDev = null).run.runId
        db.forceState(id.value, DevelopmentRunState.DEVELOPING)
        val lease = db.leases.acquire(id, "worker", Duration.ofMinutes(1), Duration.ZERO)!!
        val ready = db.runs.transition(lease, db.runs.get(id)!!.version, DevelopmentRunState.PATCH_READY, "worker")
        db.runs.transition(lease, ready.version, DevelopmentRunState.UNKNOWN, "worker", reason = CodingRuntimeService.REASON_ORPHANED)
        db.leases.release(lease)
        db.age(id, 60)
        // It is a candidate by its row, but the audit shows the GitHub step, so the lock-side check refuses it.
        assertEquals(AutoReleaseReport(0, 1, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        db.assertUntouched(id)
    }

    @Test
    fun `a run that is not self-development is not released`() = TestDb().use { db ->
        val id = db.orphanedRun(selfDev = false)
        assertFalse(db.runs.get(id)!!.taskId.startsWith(SELF_DEV_TASK_PREFIX))
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        assertIs<UnknownAutoReleaseResult.NotEligible>(db.runs.autoReleaseUnknownSelfDev(id, grace))
        db.assertUntouched(id)
    }

    @Test
    fun `a run an operator already released is left alone`() = TestDb().use { db ->
        val id = db.orphanedRun()
        assertIs<ReleaseResult.Released>(db.runs.releaseUnknownSelfDev(id, "op-1", "確認した"))
        assertEquals(AutoReleaseReport(0, 0, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        val run = db.runs.get(id)!!
        assertEquals(OPERATOR_RELEASED_REASON, run.stateReason)
        assertEquals(1, db.releasedAudits(id))
        assertEquals(0, db.count("audit_events", "run_id = '$id' AND actor = '$SELF_DEV_AUTO_RELEASE_ACTOR'"))
    }

    @Test
    fun `a run with an active coding attempt or a held lease is not released`() = TestDb().use { db ->
        val withAttempt = db.orphanedRun(beforeHold = { id ->
            assertIs<EnqueueResult.Enqueued>(CodingAttemptRepository(db.database).enqueue(id.value, "a".repeat(40), Duration.ofMinutes(5)))
        })
        val leased = db.orphanedRun()
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET lease_owner = 'worker-1', lease_expires_at = TIMESTAMPADD(SECOND, 300, NOW(6)) WHERE run_id = ?", leased.value)
        }
        db.age(leased, 60)
        assertEquals(AutoReleaseReport(0, 2, 0), db.runs.autoReleaseUnknownRuns(grace, 50))
        db.assertUntouched(withAttempt)
        db.assertUntouched(leased)
    }

    @Test
    fun `one pass releases at most the batch, oldest first, and the next pass the rest`() = TestDb().use { db ->
        val ids = (1..3).map { db.orphanedRun(ageMinutes = 60L - it) }
        assertEquals(AutoReleaseReport(2, 0, 0, mapOf(AUTO_RELEASED_ORPHAN_REASON to 2)), db.runs.autoReleaseUnknownRuns(grace, 2))
        assertEquals(
            listOf(DevelopmentRunState.CANCELLED, DevelopmentRunState.CANCELLED, DevelopmentRunState.UNKNOWN),
            ids.map { db.runs.get(it)!!.state },
        )
        assertEquals(AutoReleaseReport(1, 0, 0, mapOf(AUTO_RELEASED_ORPHAN_REASON to 1)), db.runs.autoReleaseUnknownRuns(grace, 2))
    }

    @Test
    fun `a replica that listed the run before another released it does not release it twice`() = TestDb().use { db ->
        val id = db.orphanedRun()
        val replica = DevelopmentRunRepository(
            org.jetbrains.exposed.sql.Database.connect(db.replicaDataSource()), db.outbox, db.audit, db.prAuthority,
        )
        assertEquals(listOf(id), replica.dueAutoReleasableSelfDev(grace, 50))
        assertEquals(AutoReleaseReport(1, 0, 0, mapOf(AUTO_RELEASED_ORPHAN_REASON to 1)), db.runs.autoReleaseUnknownRuns(grace, 50))
        assertIs<UnknownAutoReleaseResult.NotEligible>(replica.autoReleaseUnknownSelfDev(id, grace))
        assertEquals(1, db.releasedAudits(id))
        assertEquals(1, db.cancelOutbox(id))
    }

    @Test
    fun `concurrent passes release each run once`() = TestDb().use { db ->
        val ids = (1..4).map { db.orphanedRun() }
        val replica = DevelopmentRunRepository(
            org.jetbrains.exposed.sql.Database.connect(db.replicaDataSource()), db.outbox, db.audit, db.prAuthority,
        )
        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        try {
            val futures = listOf(db.runs, replica).map { repo -> pool.submit<AutoReleaseReport> { go.await(); repo.autoReleaseUnknownRuns(grace, 50) } }
            go.countDown()
            val reports = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(4, reports.sumOf { it.released })
        } finally {
            pool.shutdownNow()
        }
        for (id in ids) {
            assertEquals(DevelopmentRunState.CANCELLED, db.runs.get(id)!!.state)
            assertEquals(1, db.releasedAudits(id))
            assertEquals(1, db.cancelOutbox(id))
        }
    }

    @Test
    fun `the worker survives a failure and runs the repository's release`() {
        var calls = 0
        val failing = SelfDevOrphanAutoReleaseWorker({ _, _ -> calls++; error("db down") }, grace)
        assertNull(failing.tick())
        assertNull(failing.tick(), "a failure does not stop the next pass")
        assertEquals(2, calls)
        var seen: Pair<Duration, Int>? = null
        val ok = SelfDevOrphanAutoReleaseWorker({ g, b -> seen = g to b; AutoReleaseReport(1, 0, 1) }, grace)
        assertEquals(AutoReleaseReport(1, 0, 1), ok.tick())
        assertEquals(grace to SelfDevOrphanAutoReleaseWorker.DEFAULT_BATCH, seen)
        assertFailsWith<IllegalArgumentException> { SelfDevOrphanAutoReleaseWorker({ _, _ -> AutoReleaseReport(0, 0, 0) }, Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { SelfDevOrphanAutoReleaseWorker({ _, _ -> AutoReleaseReport(0, 0, 0) }, grace, batch = 0) }
    }

    @Test
    fun `the settings are off by default, take a grace of at least a minute, and refuse invalid values`() {
        val default = SelfDevOrphanAutoReleaseSettings.fromEnv(emptyMap())
        assertFalse(default.enabled)
        assertEquals(Duration.ofSeconds(600), default.grace)
        assertFalse(SelfDevOrphanAutoReleaseSettings.fromEnv(mapOf(SelfDevOrphanAutoReleaseSettings.ENV_ENABLED to "false")).enabled)
        assertFalse(SelfDevOrphanAutoReleaseSettings.fromEnv(mapOf(SelfDevOrphanAutoReleaseSettings.ENV_ENABLED to "")).enabled)
        val on = SelfDevOrphanAutoReleaseSettings.fromEnv(
            mapOf(SelfDevOrphanAutoReleaseSettings.ENV_ENABLED to "true", SelfDevOrphanAutoReleaseSettings.ENV_GRACE_SECONDS to "60"),
        )
        assertTrue(on.enabled)
        assertEquals(Duration.ofSeconds(60), on.grace)
        for (bad in listOf("59", "0", "-1", "ten", "1.5")) {
            val e = assertFailsWith<IllegalStateException> {
                SelfDevOrphanAutoReleaseSettings.fromEnv(mapOf(SelfDevOrphanAutoReleaseSettings.ENV_GRACE_SECONDS to bad))
            }
            // A fixed message: the value is not echoed.
            assertTrue(
                e.message == "${SelfDevOrphanAutoReleaseSettings.ENV_GRACE_SECONDS} must be a whole number of seconds" ||
                    e.message == "${SelfDevOrphanAutoReleaseSettings.ENV_GRACE_SECONDS} must be at least 60",
                e.message,
            )
        }
        val e = assertFailsWith<IllegalStateException> {
            SelfDevOrphanAutoReleaseSettings.fromEnv(mapOf(SelfDevOrphanAutoReleaseSettings.ENV_ENABLED to "yes"))
        }
        assertFalse(e.message!!.contains("yes"))
    }
}
