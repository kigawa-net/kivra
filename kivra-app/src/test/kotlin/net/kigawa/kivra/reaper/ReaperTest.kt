package net.kigawa.kivra.reaper

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kigawa.kivra.agent.AgentManager
import net.kigawa.kivra.agent.AgentManagerConfig
import net.kigawa.kivra.agent.AgentRunOutcome
import net.kigawa.kivra.agent.TestPort
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AgentRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.agent.AgentProfile
import net.kigawa.kivra.exkes.ExecutionPort
import net.kigawa.kivra.exkes.KivraNaming
import net.kigawa.kivra.exkes.PortResult
import net.kigawa.kivra.exkes.RuntimeSpec
import net.kigawa.kivra.exkes.RuntimeId
import net.kigawa.kivra.exkes.Runtime
import net.kigawa.kivra.exkes.RuntimeState
import net.kigawa.kivra.exkes.Workspace
import net.kigawa.kivra.exkes.WorkspaceId
import net.kigawa.kivra.exkes.WorkspaceSpec
import net.kigawa.kivra.exkes.WorkspaceState
import org.jetbrains.exposed.sql.transactions.transaction

private val PROFILE = AgentProfile(name = "developer", version = "1", templateId = "tpl-dev")

private val AGENT_CONFIG = AgentManagerConfig(
    leaseTtl = Duration.ofSeconds(30),
    leaseGrace = Duration.ZERO,
    pollInterval = Duration.ofMillis(1),
    transientBackoff = Duration.ZERO,
    workspaceReadyTimeout = Duration.ofSeconds(2),
    runtimeReadyTimeout = Duration.ofSeconds(2),
    executionTimeout = Duration.ofSeconds(2),
)

/** Retention grace of zero: a terminal run is an orphan as soon as it is terminal. */
private val NO_GRACE = ReaperConfig(dryRun = false, autoDeleteEnabled = true, retentionGrace = Duration.ZERO)

/** Lists nothing: exkes unreachable for the listing. Every other call is delegated. */
private class ListingDownPort(private val inner: TestPort) : ExecutionPort by inner {
    override suspend fun listWorkspaces(): PortResult<List<Workspace>> = PortResult.Transient("list down")
}

/** Refuses every deletion with a transient failure (no proof is possible from the Reaper). */
private class DeleteTransientPort(private val inner: TestPort) : ExecutionPort by inner {
    override suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace> {
        inner.mutatingCalls += "deleteWorkspace"
        return PortResult.Transient("connection refused")
    }
}

/** Answers every deletion with [answer] and never reaches the fake (the effect, if any, is not modelled). */
private class DeleteAnswerPort(private val inner: TestPort, private val answer: PortResult<Workspace>) : ExecutionPort by inner {
    override suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace> {
        inner.mutatingCalls += "deleteWorkspace"
        return answer
    }
}

/** Runs [beforeRecheck] on the second runtime listing of a deletion (the re-check after the claim), then delegates. */
private class RecheckHookPort(private val inner: TestPort, private val beforeRecheck: suspend () -> Unit) : ExecutionPort by inner {
    private var listCalls = 0
    override suspend fun listRuntimes(workspace: WorkspaceId): PortResult<List<Runtime>> {
        if (++listCalls == 2) beforeRecheck()
        return inner.listRuntimes(workspace)
    }
}

class ReaperTest {
    /** A run that AgentManager drove through its workspace / runtime / execution, then ended (COMPLETED). */
    private fun TestDb.completedRun(port: TestPort): RunId {
        val run = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        forceState(run.runId.value, DevelopmentRunState.PREPARING)
        val out = runBlocking {
            AgentManager("worker-A", port, leases, runs, AgentRepository(database), journal, AGENT_CONFIG).run(run.runId, PROFILE)
        }
        check(out is AgentRunOutcome.Completed) { "setup run did not complete: $out" }
        forceState(run.runId.value, DevelopmentRunState.COMPLETED)
        return run.runId
    }

    private fun TestDb.setRunColumns(runId: RunId, sql: String, vararg params: Any?) = transaction(database) {
        execUpdate(sql, *params, runId.value)
    }

    private fun TestDb.workspaceName(runId: RunId) = KivraNaming.workspaceName(runId.value)

    private fun TestDb.reapRows(runId: RunId) = count(
        "external_operations",
        "run_id = '${runId.value}' AND op_type = 'EXKES_REAP_DELETE_WORKSPACE'",
    )

    private fun findingFor(report: ReapReport, runId: RunId) =
        report.findings.single { it.runId == runId.value }

    @Test
    fun `dry-run is the default - an orphan is a candidate and nothing is deleted or journaled`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val report = runBlocking { Reaper(port, db.database, ReaperConfig(retentionGrace = Duration.ZERO)).runOnce() }
        assertEquals(ReapVerdict.CANDIDATE, findingFor(report, runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
        assertEquals(0, db.reapRows(runId))
        assertNotNull(workspaceOf(port, db, runId), "dry-run must leave the workspace in place")
    }

    @Test
    fun `auto-delete requests the deletion once, verifies it gone on a later pass, and never resends`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val reaper = Reaper(port, db.database, NO_GRACE)
        val wsId = workspaceIdOf(port, db, runId)
        val key = "reap:${wsId.value}"

        val first = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.DELETE_REQUESTED, findingFor(first, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
        assertEquals(1, db.reapRows(runId))
        assertEquals(OperationStatus.UNKNOWN, db.journal.findByKey(runId, "EXKES_REAP_DELETE_WORKSPACE", key)!!.status)

        // still listed (DELETING): reported only, not resent, not DELETED
        val second = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.STILL_PRESENT, findingFor(second, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })

        // exkes finishes: absent from the listing and not found on GET
        port.fake.setWorkspaceState(wsId, WorkspaceState.DELETED)
        val third = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.DELETED, findingFor(third, runId).verdict)
        assertEquals(OperationStatus.CONFIRMED, db.journal.findByKey(runId, "EXKES_REAP_DELETE_WORKSPACE", key)!!.status)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
        assertTrue(runBlocking { reaper.runOnce() }.findings.none { it.runId == runId.value })
    }

    @Test
    fun `a transient deletion failure is recorded UNKNOWN (no lease, so no not-sent proof) and never resent`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val reaper = Reaper(DeleteTransientPort(port), db.database, NO_GRACE)

        val first = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.DELETE_UNKNOWN, findingFor(first, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
        val entry = db.journal.findByKey(runId, "EXKES_REAP_DELETE_WORKSPACE", "reap:${workspaceIdOf(port, db, runId).value}")
        assertEquals(OperationStatus.UNKNOWN, entry!!.status)

        val second = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.JOURNALED, findingFor(second, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
    }

    @Test
    fun `human approval deletes only the approved workspace even with auto-delete off`() = TestDb().use { db ->
        val port = TestPort()
        val approved = db.completedRun(port)
        val other = db.completedRun(port)
        val approvedId = workspaceIdOf(port, db, approved).value
        val config = ReaperConfig(dryRun = false, approvals = ReapApprovalPort { it.workspaceId == approvedId }, retentionGrace = Duration.ZERO)

        val report = runBlocking { Reaper(port, db.database, config).runOnce() }
        assertEquals(ReapVerdict.DELETE_REQUESTED, findingFor(report, approved).verdict)
        assertEquals(ReapVerdict.CANDIDATE, findingFor(report, other).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
    }

    @Test
    fun `a failing approval store approves nothing`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val config = ReaperConfig(dryRun = false, approvals = ReapApprovalPort { error("approval store down") }, retentionGrace = Duration.ZERO)
        assertEquals(ReapVerdict.CANDIDATE, findingFor(runBlocking { Reaper(port, db.database, config).runOnce() }, runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `a runtime that appears after the claim blocks the deletion before anything is sent`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val wsId = workspaceIdOf(port, db, runId)
        var appeared: RuntimeId? = null
        val hooked = RecheckHookPort(port) {
            appeared = (port.fake.createRuntime(wsId, RuntimeSpec("tpl-dev")) as PortResult.Ok).value.id
        }
        val reaper = Reaper(hooked, db.database, NO_GRACE)

        assertEquals(ReapVerdict.BLOCKED, findingFor(runBlocking { reaper.runOnce() }, runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" }, "nothing may be sent")
        assertEquals(OperationStatus.PREPARED, db.journal.findByKey(runId, "EXKES_REAP_DELETE_WORKSPACE", "reap:${wsId.value}")!!.status)

        // the runtime stops: a later pass re-claims the PREPARED record and deletes exactly once
        port.fake.setRuntimeState(appeared!!, RuntimeState.STOPPED)
        assertEquals(ReapVerdict.DELETE_REQUESTED, findingFor(runBlocking { reaper.runOnce() }, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
    }

    @Test
    fun `a hold placed after the claim stops the deletion at the Run re-check, before anything is sent`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val hooked = RecheckHookPort(port) {
            db.setRunColumns(runId, "UPDATE development_runs SET hold_reason = 'ops-investigation' WHERE run_id = ?")
        }
        val reaper = Reaper(hooked, db.database, NO_GRACE)

        assertEquals(ReapVerdict.BLOCKED, findingFor(runBlocking { reaper.runOnce() }, runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
        db.setRunColumns(runId, "UPDATE development_runs SET hold_reason = NULL WHERE run_id = ?")
        assertEquals(ReapVerdict.DELETE_REQUESTED, findingFor(runBlocking { reaper.runOnce() }, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
    }

    @Test
    fun `a 404 on delete is not proof of deletion - the workspace is verified, not assumed gone`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val reaper = Reaper(DeleteAnswerPort(port, PortResult.NotFound), db.database, NO_GRACE)

        assertEquals(ReapVerdict.DELETE_REQUESTED, findingFor(runBlocking { reaper.runOnce() }, runId).verdict)
        // the fake still holds the workspace: a later pass reports it, does not declare it deleted, does not resend
        assertEquals(ReapVerdict.STILL_PRESENT, findingFor(runBlocking { reaper.runOnce() }, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
    }

    @Test
    fun `provider message text is kept out of findings and the Journal`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val reaper = Reaper(DeleteAnswerPort(port, PortResult.Transient("Authorization: Bearer SECRET-TOKEN")), db.database, NO_GRACE)
        val finding = findingFor(runBlocking { reaper.runOnce() }, runId)
        val entry = db.journal.findByKey(runId, "EXKES_REAP_DELETE_WORKSPACE", "reap:${workspaceIdOf(port, db, runId).value}")!!
        assertTrue("SECRET-TOKEN" !in finding.reason && "SECRET-TOKEN" !in (entry.lastError ?: ""), "leaked: ${finding.reason} / ${entry.lastError}")
    }

    @Test
    fun `an active run is in use and is never touched`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        db.forceState(runId.value, DevelopmentRunState.TESTING)
        val report = runBlocking { Reaper(port, db.database, NO_GRACE).runOnce() }
        assertEquals(ReapVerdict.IN_USE, findingFor(report, runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `a terminal run inside the retention grace is kept`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val report = runBlocking { Reaper(port, db.database, ReaperConfig(dryRun = false, autoDeleteEnabled = true)).runOnce() }
        assertEquals(ReapVerdict.RETENTION, findingFor(report, runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `a hold, a live lease or a live runtime blocks the deletion`() = TestDb().use { db ->
        val port = TestPort()
        val held = db.completedRun(port)
        db.setRunColumns(held, "UPDATE development_runs SET hold_reason = 'ops-investigation' WHERE run_id = ?")
        val leased = db.completedRun(port)
        db.setRunColumns(leased, "UPDATE development_runs SET lease_owner = 'other', lease_expires_at = TIMESTAMPADD(SECOND, 60, NOW(6)) WHERE run_id = ?")
        val running = db.completedRun(port)
        val rt = runBlocking { port.listRuntimes(workspaceIdOf(port, db, running)) }.let { (it as PortResult.Ok).value.single() }
        port.fake.setRuntimeState(rt.id, RuntimeState.RUNNING)

        val report = runBlocking { Reaper(port, db.database, NO_GRACE).runOnce() }
        assertEquals(ReapVerdict.BLOCKED, findingFor(report, held).verdict)
        assertEquals(ReapVerdict.BLOCKED, findingFor(report, leased).verdict)
        assertEquals(ReapVerdict.BLOCKED, findingFor(report, running).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `stopped runtimes do not block the deletion of an orphan`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port) // AgentManager leaves its runtime STOPPED
        val report = runBlocking { Reaper(port, db.database, NO_GRACE).runOnce() }
        assertEquals(ReapVerdict.DELETE_REQUESTED, findingFor(report, runId).verdict)
    }

    @Test
    fun `a Kivra-named workspace without journal proof is quarantined, not deleted`() = TestDb().use { db ->
        val port = TestPort()
        val run = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        db.forceState(run.runId.value, DevelopmentRunState.COMPLETED)
        // Created outside AgentManager: the Journal has no EXKES_CREATE_WORKSPACE record for it.
        runBlocking {
            port.createWorkspace(WorkspaceSpec(db.workspaceName(run.runId), KivraNaming.runLabels(run.runId.value)))
        }
        val report = runBlocking { Reaper(port, db.database, NO_GRACE).runOnce() }
        assertEquals(ReapVerdict.QUARANTINE, findingFor(report, run.runId).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `a Kivra name whose run record does not exist is quarantined`() = TestDb().use { db ->
        val port = TestPort()
        val ghost = RunId("01HZZZZZZZZZZZZZZZZZZZZZZZ")
        runBlocking { port.createWorkspace(WorkspaceSpec(KivraNaming.workspaceName(ghost.value), KivraNaming.runLabels(ghost.value))) }
        val report = runBlocking { Reaper(port, db.database, NO_GRACE).runOnce() }
        assertEquals(ReapVerdict.QUARANTINE, findingFor(report, ghost).verdict)
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `workspaces of other parties are never touched, even with a Kivra label`() = TestDb().use { db ->
        val port = TestPort()
        runBlocking {
            port.createWorkspace(WorkspaceSpec("someone-elses-sandbox", emptyMap()))
            port.createWorkspace(WorkspaceSpec("kivra-run-01HZZZZZZZZZZZZZZZZZZZZZZZ-manual", KivraNaming.runLabels("01HZZZZZZZZZZZZZZZZZZZZZZZ"))) // not a valid run name
        }
        val report = runBlocking { Reaper(port, db.database, NO_GRACE).runOnce() }
        assertTrue(report.findings.isEmpty(), "only Kivra-named workspaces are reviewed: ${report.findings}")
        assertTrue(port.mutatingCalls.none { it == "deleteWorkspace" })
    }

    @Test
    fun `a lost deletion response is recorded as UNKNOWN and never resent`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        port.fake.injectUnknown("deleteWorkspace", afterEffect = true)
        val reaper = Reaper(port, db.database, NO_GRACE)

        val first = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.DELETE_UNKNOWN, findingFor(first, runId).verdict)
        assertEquals(OperationStatus.UNKNOWN, db.journal.listByRun(runId).single { it.opType == "EXKES_REAP_DELETE_WORKSPACE" }.status)

        val second = runBlocking { reaper.runOnce() }
        assertEquals(ReapVerdict.JOURNALED, findingFor(second, runId).verdict)
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" }, "UNKNOWN must not be resent")
    }

    @Test
    fun `concurrent reapers delete an orphan at most once`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.completedRun(port)
        val reports = runBlocking {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    listOf(Reaper(port, db.database, NO_GRACE), Reaper(port, db.database, NO_GRACE))
                        .map { async { it.runOnce() } }.awaitAll()
                }
            }
        }
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
        val verdicts = reports.map { findingFor(it, runId).verdict }.sorted()
        assertEquals(1, verdicts.count { it == ReapVerdict.DELETE_REQUESTED }, "verdicts: $verdicts")
        assertTrue(verdicts.all { it == ReapVerdict.DELETE_REQUESTED || it == ReapVerdict.JOURNALED || it == ReapVerdict.STILL_PRESENT }, "verdicts: $verdicts")
    }

    @Test
    fun `a failed listing touches nothing`() = TestDb().use { db ->
        val inner = TestPort()
        val report = runBlocking { Reaper(ListingDownPort(inner), db.database, NO_GRACE).runOnce() }
        assertNotNull(report.listingFailed)
        assertTrue(inner.mutatingCalls.none { it == "deleteWorkspace" })
    }

    private fun workspaceOf(port: ExecutionPort, db: TestDb, runId: RunId): Workspace? =
        (runBlocking { port.findWorkspaceByName(db.workspaceName(runId)) } as PortResult.Ok).value

    private fun workspaceIdOf(port: ExecutionPort, db: TestDb, runId: RunId): WorkspaceId =
        workspaceOf(port, db, runId)!!.id
}
