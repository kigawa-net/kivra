package net.kigawa.kivra.agent

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.AgentState
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.ExecutionSessionState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidence
import net.kigawa.kivra.core.VerifiedEvidenceRef
import net.kigawa.kivra.db.AgentRepository
import net.kigawa.kivra.db.FakeTrustedEvidenceAuthority
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.exkes.Execution
import net.kigawa.kivra.exkes.ExecutionCapabilities
import net.kigawa.kivra.exkes.ExecutionId
import net.kigawa.kivra.exkes.ExecutionPort
import net.kigawa.kivra.exkes.ExecutionSpec
import net.kigawa.kivra.exkes.ExecutionState
import net.kigawa.kivra.exkes.FakeExecutionPort
import net.kigawa.kivra.exkes.KivraNaming
import net.kigawa.kivra.exkes.PortResult
import net.kigawa.kivra.exkes.RuntimeSpec
import net.kigawa.kivra.exkes.Runtime
import net.kigawa.kivra.exkes.RuntimeId
import net.kigawa.kivra.exkes.RuntimeState
import net.kigawa.kivra.exkes.Workspace
import net.kigawa.kivra.exkes.WorkspaceId
import net.kigawa.kivra.exkes.WorkspaceSpec
import net.kigawa.kivra.exkes.WorkspaceState

class SimulatedCrash : Error("worker process died")

/**
 * Fake exkes that also plays the control plane: a resource turns READY / RUNNING the first time it is read, and an
 * Execution ends with [executionResult] (null = keeps RUNNING). Hooks inject the faults the tests need.
 */
class TestPort(val fake: FakeExecutionPort = FakeExecutionPort()) : ExecutionPort by fake {
    var executionResult: ExecutionState? = ExecutionState.SUCCEEDED
    var onGetExecution: suspend (Int) -> Unit = {}
    var transientCreateWorkspace = 0
    var unsupportedStartExecution = false
    var crashAfterStartExecution = false
    /** The Runtime never leaves STOPPING (the stop is accepted but not observed to complete). */
    var stuckStopping = false
    var unsupportedStopRuntime = false
    private var getExecutionCalls = 0
    val mutatingCalls = mutableListOf<String>()
    private val failures = HashMap<String, Pair<Boolean, PortResult<Nothing>>>()

    /** The next call of [op] answers HTTP 409. [afterEffect] = the POST was applied before the 409 (our own earlier POST). */
    fun injectConflict(op: String, afterEffect: Boolean) = injectFailure(op, afterEffect, PortResult.Conflict("injected: 409 for $op"))

    /** The next call of [op] answers [result]; [afterEffect] = the effect was applied before the answer. */
    fun injectFailure(op: String, afterEffect: Boolean, result: PortResult<Nothing>) {
        failures[op] = afterEffect to result
    }

    private suspend fun <T> conflicting(op: String, call: suspend () -> PortResult<T>): PortResult<T> {
        val (afterEffect, result) = failures.remove(op) ?: return call()
        if (afterEffect) call()
        return result
    }

    override suspend fun createWorkspace(spec: WorkspaceSpec): PortResult<Workspace> {
        mutatingCalls += "createWorkspace"
        if (transientCreateWorkspace > 0) {
            transientCreateWorkspace--
            return PortResult.Transient("connection refused")
        }
        return conflicting("createWorkspace") { fake.createWorkspace(spec) }
    }

    override suspend fun createRuntime(workspace: WorkspaceId, spec: RuntimeSpec): PortResult<Runtime> =
        conflicting("createRuntime") { fake.createRuntime(workspace, spec) }

    override suspend fun getWorkspace(id: WorkspaceId): PortResult<Workspace> {
        val r = fake.getWorkspace(id)
        if (r is PortResult.Ok && r.value.state == WorkspaceState.CREATING) fake.setWorkspaceState(id, WorkspaceState.READY)
        if (r is PortResult.Ok && r.value.state == WorkspaceState.DELETING) fake.setWorkspaceState(id, WorkspaceState.DELETED)
        return fake.getWorkspace(id)
    }

    override suspend fun getRuntime(id: RuntimeId): PortResult<Runtime> {
        val r = fake.getRuntime(id)
        if (r is PortResult.Ok && r.value.state == RuntimeState.CREATING) fake.setRuntimeState(id, RuntimeState.RUNNING)
        if (r is PortResult.Ok && r.value.state == RuntimeState.STOPPING && !stuckStopping) fake.setRuntimeState(id, RuntimeState.STOPPED)
        return fake.getRuntime(id)
    }

    override suspend fun startExecution(runtime: RuntimeId, spec: ExecutionSpec): PortResult<Execution> {
        mutatingCalls += "startExecution"
        if (unsupportedStartExecution) return PortResult.Unsupported("agent execution")
        val r = conflicting("startExecution") { fake.startExecution(runtime, spec) }
        if (crashAfterStartExecution) {
            crashAfterStartExecution = false
            throw SimulatedCrash()
        }
        return r
    }

    override suspend fun getExecution(id: ExecutionId): PortResult<Execution> {
        onGetExecution(++getExecutionCalls)
        val r = fake.getExecution(id)
        val final = executionResult
        if (final != null && r is PortResult.Ok && !r.value.state.isFinal) fake.setExecutionState(id, final, if (final == ExecutionState.SUCCEEDED) 0 else 1)
        return fake.getExecution(id)
    }

    override suspend fun stopRuntime(id: RuntimeId): PortResult<Runtime> {
        mutatingCalls += "stopRuntime"
        if (unsupportedStopRuntime) return PortResult.Unsupported("stopRuntime")
        return fake.stopRuntime(id)
    }

    override suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace> {
        mutatingCalls += "deleteWorkspace"
        return fake.deleteWorkspace(id)
    }
}

/**
 * The adapter pipeline of these tests: when the executor asks for evidence, the test port has observed exactly that
 * (a Transient call was never sent; a lookup found the resource). The observation is registered on the shared Fake and
 * verified by it, so subject, contract, purpose and expiry checks still apply. The executor decides WHEN it asks: only a
 * Transient result asks for NOT_SENT, only an observed EXISTS asks for RECONCILE_EXISTS.
 */
private class PipelineObservedAuthority(private val fake: FakeTrustedEvidenceAuthority) : TrustedEvidenceAuthority {
    override suspend fun verify(
        subject: EvidenceSubject,
        observation: EvidenceObservationRef,
        requiredPurpose: EvidencePurpose,
    ): EvidenceVerification {
        val observed = when (requiredPurpose) {
            EvidencePurpose.NOT_SENT -> null
            EvidencePurpose.RECONCILE_EXISTS -> ExternalObservation.EXISTS
            else -> error("the test pipeline never observes $requiredPurpose")
        }
        val now = LocalDateTime.now(ZoneOffset.UTC)
        fake.register(
            observation,
            FakeTrustedEvidenceAuthority.Observation(
                subject = subject,
                principal = "exkes-adapter",
                purpose = requiredPurpose,
                observation = observed,
                capability = ProviderCapability.LOOKUP_BY_STABLE_REF,
                observedAt = now,
                expiresAt = now.plusHours(1),
            ),
        )
        return fake.verify(subject, observation, requiredPurpose)
    }

    override suspend fun resolve(ref: VerifiedEvidenceRef, expected: EvidenceSubject, requiredPurpose: EvidencePurpose): VerifiedEvidence =
        fake.resolve(ref, expected, requiredPurpose)
}

private val PROFILE = AgentProfile(name = "developer", version = "1", templateId = "tpl-dev")

private val TEST_CONFIG = AgentManagerConfig(
    tenantId = "tenant-1",
    providerContractVersion = "2026-01",
    leaseTtl = Duration.ofSeconds(30),
    leaseGrace = Duration.ZERO,
    pollInterval = Duration.ofMillis(1),
    transientBackoff = Duration.ZERO,
    workspaceReadyTimeout = Duration.ofSeconds(2),
    runtimeReadyTimeout = Duration.ofSeconds(2),
    executionTimeout = Duration.ofSeconds(2),
)

class AgentManagerTest {
    private fun TestDb.preparingRun(): RunId {
        val run = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        forceState(run.runId.value, DevelopmentRunState.PREPARING)
        return run.runId
    }

    private fun TestDb.manager(port: ExecutionPort, worker: String = "worker-A", config: AgentManagerConfig = TEST_CONFIG) =
        AgentManager(worker, port, leases, runs, AgentRepository(database), journal, config, pipelineAuthority())

    private fun TestDb.pipelineAuthority(): TrustedEvidenceAuthority {
        evidence.setProviderContract("EXKES", "2026-01", ProviderCapability.LOOKUP_BY_STABLE_REF)
        return PipelineObservedAuthority(evidence)
    }

    @Test
    fun `untrusted provider and lookup exceptions never enter the durable operation journal`() {
        TestDb().use { db ->
        val runId = db.preparingRun()
        val lease = db.leases.acquire(runId, "error-redaction-test", Duration.ofSeconds(30))!!
        val secret = "Authorization: Bearer DO-NOT-STORE-THIS"
        val call = Op<Unit>(
            opType = "EXKES_CREATE_WORKSPACE",
            key = "redaction-guard",
            stableRef = "workspace-redaction",
            requestHash = "a".repeat(64),
            call = { PortResult.Unknown("provider error $secret") },
            refOf = { null },
            observe = { throw IllegalStateException("lookup failure $secret") },
        )
        val executor = OperationExecutor(db.journal, db.pipelineAuthority(), "tenant-1", "2026-01", 3, Duration.ZERO)
        val result = runBlocking {
            executor.perform(lease, call, allowCancel = false, allowDispatch = true, guard = {})
        }
        assertIs<OpOutcome.ReconciliationRequired>(result)
        val journalRow = db.journal.listByRun(runId).single()
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, journalRow.status)
        assertEquals("PROVIDER_LOOKUP_UNDETERMINED", journalRow.lastError)
        assertFalse(journalRow.lastError?.contains("DO-NOT-STORE-THIS") == true)
        }
    }

    @Test
    fun `provider rejection text and exception text never reach journal, audit, outbox or run state`() = TestDb().use { db ->
        val secret = "Authorization: Bearer DO-NOT-STORE-REJECT"
        val port = TestPort().also {
            it.injectFailure("createWorkspace", afterEffect = false, PortResult.Rejected(400, "VALIDATION_ERROR", secret))
        }
        val runId = db.preparingRun()
        runBlocking { db.manager(port).run(runId, PROFILE) }
        assertFalse(db.allPersistedText().contains("DO-NOT-STORE-REJECT"), "a provider rejection leaked into persisted state")
    }

    @Test
    fun `provider unknown outcome with a secret-bearing cause never reaches persisted state`() = TestDb().use { db ->
        val secret = "Authorization: Bearer DO-NOT-STORE-CAUSE"
        val port = TestPort().also {
            it.injectFailure("createWorkspace", afterEffect = false, PortResult.Unknown("transport failed", IllegalStateException(secret)))
        }
        val runId = db.preparingRun()
        runBlocking { db.manager(port).run(runId, PROFILE) }
        assertFalse(db.allPersistedText().contains("DO-NOT-STORE-CAUSE"), "a Throwable message leaked into persisted state")
        assertFalse(PortResult.Unknown("transport failed", IllegalStateException(secret)).toString().contains("DO-NOT-STORE-CAUSE"))
    }

    private fun TestDb.agentsOf(runId: RunId) = AgentRepository(database).agentsOf(runId)
    private fun TestDb.ops(runId: RunId) = journal.listByRun(runId).associateBy { it.opType }
    private fun TestDb.runState(runId: RunId) = runs.get(runId)!!.state

    @Test
    fun `one run goes through workspace runtime execution to TESTING with every effect journaled`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.preparingRun()
        val out = runBlocking { db.manager(port).run(runId, PROFILE) }
        assertEquals(AgentRunOutcome.Completed(DevelopmentRunState.TESTING), out)
        assertEquals(DevelopmentRunState.TESTING, db.runState(runId))
        val agent = db.agentsOf(runId).single()
        assertEquals(AgentState.COMPLETED, agent.state)
        val session = AgentRepository(db.database).sessionOf(agent.agentId)!!
        assertEquals(ExecutionSessionState.RELEASED, session.state)
        assertNotNull(session.workspaceId); assertNotNull(session.runtimeId); assertNotNull(session.executionId)
        val ops = db.ops(runId)
        assertEquals(setOf("EXKES_CREATE_WORKSPACE", "EXKES_CREATE_RUNTIME", "EXKES_START_EXECUTION", "EXKES_STOP_RUNTIME"), ops.keys)
        assertTrue(ops.values.all { it.status == OperationStatus.CONFIRMED })
        assertEquals("kivra-run-$runId", ops.getValue("EXKES_CREATE_WORKSPACE").stableRef)
        assertEquals("run:$runId:workspace", ops.getValue("EXKES_CREATE_WORKSPACE").idempotencyKey)
        assertEquals(1, port.fake.postCounts["createWorkspace"]); assertEquals(1, port.fake.postCounts["startExecution"])
        // the Workspace is kept for the next stage
        assertEquals(null, port.fake.postCounts["deleteWorkspace"])
        assertEquals(null, db.runs.get(runId)!!.leaseOwner)
    }

    @Test
    fun `lost createWorkspace response with the effect applied adopts the workspace and never posts twice`() = TestDb().use { db ->
        val port = TestPort().also { it.fake.injectUnknown("createWorkspace", afterEffect = true) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.fake.postCounts["createWorkspace"])
        val ws = db.ops(runId).getValue("EXKES_CREATE_WORKSPACE")
        assertEquals(OperationStatus.CONFIRMED, ws.status)
        assertEquals("EXISTS", ws.evidenceObservation)
        assertEquals("LOOKUP_BY_STABLE_REF", ws.providerCapability)
    }

    @Test
    fun `lost createWorkspace response is never replayed on an ABSENT lookup - not atomic, so reconciliation is required`() = TestDb().use { db ->
        // The POST was lost before it took effect: nothing exists, yet an ABSENT lookup proves nothing (#44 / #60).
        val port = TestPort().also { it.fake.injectUnknown("createWorkspace", afterEffect = false) }
        val runId = db.preparingRun()
        val rr = assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port, "worker-A").run(runId, PROFILE) })
        assertEquals("EXKES_CREATE_WORKSPACE", rr.opType)
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.journal.get(rr.opId)!!.status)
        assertEquals(1, port.mutatingCalls.count { it == "createWorkspace" })
        // a late-arriving or a second worker must not resend either
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) })
        assertEquals(1, port.mutatingCalls.count { it == "createWorkspace" })
        assertEquals(DevelopmentRunState.PREPARING, db.runState(runId))
    }

    @Test
    fun `createWorkspace 409 caused by our own earlier POST is adopted - never FAILED`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("createWorkspace", afterEffect = true) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.fake.postCounts["createWorkspace"])
        val ws = db.ops(runId).getValue("EXKES_CREATE_WORKSPACE")
        assertEquals(OperationStatus.CONFIRMED, ws.status)
        assertEquals("EXISTS", ws.evidenceObservation)
    }

    @Test
    fun `createWorkspace 409 with a same-name Workspace of another owner is RECONCILIATION_REQUIRED, never adopted or FAILED`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("createWorkspace", afterEffect = false) }
        val runId = db.preparingRun()
        // someone else's Workspace under our name: the labels do not carry this run
        runBlocking { port.fake.createWorkspace(WorkspaceSpec(KivraNaming.workspaceName(runId.value), mapOf("owner" to "someone-else"))) }
        val rr = assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals("EXKES_CREATE_WORKSPACE", rr.opType)
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.journal.get(rr.opId)!!.status)
        assertEquals(1, port.mutatingCalls.count { it == "createWorkspace" })
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) })
        assertEquals(1, port.mutatingCalls.count { it == "createWorkspace" })
        assertEquals(DevelopmentRunState.PREPARING, db.runState(runId))
    }

    @Test
    fun `createWorkspace 409 with nothing found is RECONCILIATION_REQUIRED, not FAILED`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("createWorkspace", afterEffect = false) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.ops(runId).getValue("EXKES_CREATE_WORKSPACE").status)
        assertEquals(1, port.mutatingCalls.count { it == "createWorkspace" })
    }

    @Test
    fun `createWorkspace 400 rejection is not trusted as no-effect - reconciled, never FAILED`() = TestDb().use { db ->
        val port = TestPort().also {
            it.injectFailure("createWorkspace", afterEffect = false, PortResult.Rejected(400, "VALIDATION", "bad name"))
        }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.ops(runId).getValue("EXKES_CREATE_WORKSPACE").status)
    }

    @Test
    fun `createWorkspace 404 on a create is reconciled by lookup, never FAILED`() = TestDb().use { db ->
        val port = TestPort().also { it.injectFailure("createWorkspace", afterEffect = false, PortResult.NotFound) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.ops(runId).getValue("EXKES_CREATE_WORKSPACE").status)
    }

    @Test
    fun `createRuntime 409 caused by our own earlier POST is adopted from the label listing`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("createRuntime", afterEffect = true) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.fake.postCounts["createRuntime"])
        assertEquals("EXISTS", db.ops(runId).getValue("EXKES_CREATE_RUNTIME").evidenceObservation)
    }

    @Test
    fun `createRuntime 409 with nothing found is RECONCILIATION_REQUIRED and never resent`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("createRuntime", afterEffect = false) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) })
        assertEquals(0, port.fake.postCounts["createRuntime"] ?: 0)
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.ops(runId).getValue("EXKES_CREATE_RUNTIME").status)
    }

    @Test
    fun `startExecution 409 caused by our own earlier POST is adopted from the execution listing`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("startExecution", afterEffect = true) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.fake.postCounts["startExecution"])
        assertEquals("EXISTS", db.ops(runId).getValue("EXKES_START_EXECUTION").evidenceObservation)
    }

    @Test
    fun `startExecution 409 with nothing found is RECONCILIATION_REQUIRED and never resent`() = TestDb().use { db ->
        val port = TestPort().also { it.injectConflict("startExecution", afterEffect = false) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.mutatingCalls.count { it == "startExecution" })
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.ops(runId).getValue("EXKES_START_EXECUTION").status)
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
    }

    @Test
    fun `lost createRuntime response is never replayed on an ABSENT label lookup`() = TestDb().use { db ->
        val port = TestPort().also { it.fake.injectUnknown("createRuntime", afterEffect = false) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) })
        // the lost POST never reached the fake (0 effects); a resend would have made it 1
        assertEquals(0, port.fake.postCounts["createRuntime"] ?: 0)
        assertEquals(DevelopmentRunState.PREPARING, db.runState(runId))
    }

    @Test
    fun `a Runtime stop accepted but not observed STOPPED keeps the Run in DEVELOPING - no TESTING on SUCCEEDED alone`() = TestDb().use { db ->
        val port = TestPort().also { it.stuckStopping = true }
        val runId = db.preparingRun()
        val config = TEST_CONFIG.copy(teardownTimeout = Duration.ofMillis(50))
        assertIs<AgentRunOutcome.CleanupPending>(runBlocking { db.manager(port, config = config).run(runId, PROFILE) })
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
        assertEquals(AgentState.COMPLETED, db.agentsOf(runId).single().state)
        // a later pass only re-verifies: no second stop, and TESTING once the Runtime is observed STOPPED
        port.stuckStopping = false
        assertEquals(AgentRunOutcome.Completed(DevelopmentRunState.TESTING), runBlocking { db.manager(port, config = config).run(runId, PROFILE) })
        assertEquals(1, port.mutatingCalls.count { it == "stopRuntime" })
        assertEquals(1, port.fake.postCounts["createRuntime"])
    }

    @Test
    fun `a failed attempt whose Runtime is not confirmed stopped is not retried - no new Agent or Runtime`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = ExecutionState.FAILED; it.stuckStopping = true }
        val runId = db.preparingRun()
        val config = TEST_CONFIG.copy(teardownTimeout = Duration.ofMillis(50))
        assertIs<AgentRunOutcome.CleanupPending>(runBlocking { db.manager(port, config = config).run(runId, PROFILE) })
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId)) // not RETRY_WAITING
        assertEquals(listOf(AgentState.FAILED), db.agentsOf(runId).map { it.state })
        assertEquals(1, port.fake.postCounts["createRuntime"])
    }

    @Test
    fun `Cancel is not CANCELLED while the Runtime stop is unsupported, and the Workspace is not deleted`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = null; it.unsupportedStopRuntime = true }
        val runId = db.preparingRun()
        port.onGetExecution = { n -> if (n == 2) db.runs.requestCancel(runId, "admin") }
        val pending = assertIs<AgentRunOutcome.CancelPending>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(false, pending.teardownConfirmed)
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
        assertEquals(null, port.fake.postCounts["deleteWorkspace"])
    }

    @Test
    fun `a resumed run with a different profile version is refused before any request is sent`() = TestDb().use { db ->
        val port = TestPort().also { it.crashAfterStartExecution = true }
        val runId = db.preparingRun()
        assertFailsWith<SimulatedCrash> { runBlocking { db.manager(port, "worker-A").run(runId, PROFILE) } }
        val changed = PROFILE.copy(version = "2")
        assertIs<AgentRunOutcome.ProfileMismatch>(runBlocking { db.manager(port, "worker-B").run(runId, changed) })
        assertEquals(1, port.fake.postCounts["createRuntime"])
        assertEquals(1, port.mutatingCalls.count { it == "startExecution" })
    }

    @Test
    fun `UNKNOWN startExecution is never resent without reconciliation, across restarts too`() = TestDb().use { db ->
        val port = TestPort().also { it.fake.injectUnknown("startExecution", afterEffect = false) }
        val runId = db.preparingRun()
        val first = runBlocking { db.manager(port, "worker-A").run(runId, PROFILE) }
        val rr = assertIs<AgentRunOutcome.ReconciliationRequired>(first)
        assertEquals("EXKES_START_EXECUTION", rr.opType)
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.journal.get(rr.opId)!!.status)
        assertEquals(1, port.mutatingCalls.count { it == "startExecution" })
        // a new worker picks the run up: it must look, not send
        val second = runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) }
        assertIs<AgentRunOutcome.ReconciliationRequired>(second)
        assertEquals(1, port.mutatingCalls.count { it == "startExecution" })
        assertEquals(null, port.fake.postCounts["startExecution"] ?.takeIf { it > 0 })
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
        assertEquals(AgentState.STARTING, db.agentsOf(runId).single().state) // no terminal state is forced
    }

    @Test
    fun `lost startExecution response with the effect applied is adopted from the runtime listing`() = TestDb().use { db ->
        val port = TestPort().also { it.fake.injectUnknown("startExecution", afterEffect = true) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.fake.postCounts["startExecution"])
        assertEquals("EXISTS", db.ops(runId).getValue("EXKES_START_EXECUTION").evidenceObservation)
    }

    @Test
    fun `worker handoff - a crash after the POST leaves IN_FLIGHT and the next worker reconciles instead of resending`() = TestDb().use { db ->
        val port = TestPort().also { it.crashAfterStartExecution = true }
        val runId = db.preparingRun()
        assertFailsWith<SimulatedCrash> { runBlocking { db.manager(port, "worker-A").run(runId, PROFILE) } }
        assertEquals(OperationStatus.IN_FLIGHT, db.ops(runId).getValue("EXKES_START_EXECUTION").status)

        val out = runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) }
        assertIs<AgentRunOutcome.Completed>(out)
        assertEquals(1, port.fake.postCounts["startExecution"])
        assertEquals(1, port.mutatingCalls.count { it == "startExecution" })
        assertEquals(1, db.agentsOf(runId).size) // same Agent, no second Runtime
        assertEquals(1, port.fake.postCounts["createRuntime"])
        assertEquals(2L, db.runs.get(runId)!!.leaseGeneration)
    }

    @Test
    fun `a worker whose lease expired and was taken over stops at once and writes nothing more`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = null }
        val runId = db.preparingRun()
        var callsAtTakeover = 0
        port.onGetExecution = { n ->
            if (n == 2) {
                db.expireLease(runId.value)
                assertNotNull(db.leases.acquire(runId, "worker-B", Duration.ofSeconds(30)))
                callsAtTakeover = port.mutatingCalls.size
            }
            if (n > 3) error("old worker kept polling after losing its lease")
        }
        val out = runBlocking { db.manager(port, "worker-A").run(runId, PROFILE) }
        assertIs<AgentRunOutcome.LeaseLost>(out)
        assertEquals(callsAtTakeover, port.mutatingCalls.size) // no stop / delete / start after the takeover
        assertEquals(AgentState.RUNNING, db.agentsOf(runId).single().state)
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
        val run = db.runs.get(runId)!!
        assertEquals("worker-B", run.leaseOwner); assertEquals(2L, run.leaseGeneration)
    }

    @Test
    fun `an administrator Hold stops the worker without any further call`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = null }
        val runId = db.preparingRun()
        var callsAtHold = 0
        port.onGetExecution = { n ->
            if (n == 2) {
                db.runs.hold(runId, "ops", "admin")
                callsAtHold = port.mutatingCalls.size
            }
        }
        val out = runBlocking { db.manager(port).run(runId, PROFILE) }
        assertIs<AgentRunOutcome.Halted>(out)
        assertEquals(callsAtHold, port.mutatingCalls.size)
        assertEquals(AgentState.RUNNING, db.agentsOf(runId).single().state)
    }

    @Test
    fun `Cancel winds down - stops the runtime, deletes the workspace, then CANCELLED`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = null }
        val runId = db.preparingRun()
        port.onGetExecution = { n -> if (n == 2) db.runs.requestCancel(runId, "admin") }
        val out = runBlocking { db.manager(port).run(runId, PROFILE) }
        assertEquals(AgentRunOutcome.Cancelled, out)
        assertEquals(DevelopmentRunState.CANCELLED, db.runState(runId))
        val agent = db.agentsOf(runId).single()
        assertEquals(AgentState.TERMINATED, agent.state)
        assertEquals(ExecutionSessionState.RELEASED, AgentRepository(db.database).sessionOf(agent.agentId)!!.state)
        assertEquals(1, port.mutatingCalls.count { it == "stopRuntime" })
        assertEquals(1, port.mutatingCalls.count { it == "deleteWorkspace" })
    }

    @Test
    fun `Cancel does not force CANCELLED while a side effect is unresolved`() = TestDb().use { db ->
        val port = TestPort().also { it.fake.injectUnknown("startExecution", afterEffect = false) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        db.runs.requestCancel(runId, "admin")
        val out = runBlocking { db.manager(port, "worker-B").run(runId, PROFILE) }
        val pending = assertIs<AgentRunOutcome.CancelPending>(out)
        assertEquals(1, pending.unresolvedOpIds.size)
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
        assertEquals(1, port.mutatingCalls.count { it == "startExecution" }) // wind-down never dispatches
        assertEquals(1, port.mutatingCalls.count { it == "stopRuntime" })
    }

    @Test
    fun `execution timeout fails the attempt, the run retries with a new Agent and Runtime and then completes`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = null }
        val runId = db.preparingRun()
        val config = TEST_CONFIG.copy(executionTimeout = Duration.ofMillis(100))
        val first = runBlocking { db.manager(port, config = config).run(runId, PROFILE) }
        assertEquals(AgentRunOutcome.Failed("EXECUTION_TIMEOUT", willRetry = true), first)
        assertEquals(DevelopmentRunState.RETRY_WAITING, db.runState(runId))
        assertEquals(1, port.mutatingCalls.count { it == "stopRuntime" })
        assertEquals(null, port.fake.postCounts["deleteWorkspace"]) // kept for the retry

        port.executionResult = ExecutionState.SUCCEEDED
        val second = runBlocking { db.manager(port, config = config).run(runId, PROFILE) }
        assertIs<AgentRunOutcome.Completed>(second)
        assertEquals(listOf(AgentState.FAILED, AgentState.COMPLETED), db.agentsOf(runId).map { it.state })
        assertEquals(2, port.fake.postCounts["createRuntime"])
        assertEquals(1, port.fake.postCounts["createWorkspace"]) // the Workspace is reused
    }

    @Test
    fun `retries are bounded - the last failed attempt fails the run`() = TestDb().use { db ->
        val port = TestPort().also { it.executionResult = ExecutionState.FAILED }
        val runId = db.preparingRun()
        val config = TEST_CONFIG.copy(maxAgentAttempts = 2)
        val m = db.manager(port, config = config)
        assertEquals(true, (runBlocking { m.run(runId, PROFILE) } as AgentRunOutcome.Failed).willRetry)
        assertEquals(false, (runBlocking { m.run(runId, PROFILE) } as AgentRunOutcome.Failed).willRetry)
        assertEquals(DevelopmentRunState.FAILED, db.runState(runId))
        assertIs<AgentRunOutcome.NotRunnable>(runBlocking { m.run(runId, PROFILE) })
    }

    @Test
    fun `transient failures are retried up to the limit and then fail`() = TestDb().use { db ->
        val ok = TestPort().also { it.transientCreateWorkspace = 2 }
        val run1 = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(ok).run(run1, PROFILE) })
        assertEquals(3, ok.mutatingCalls.count { it == "createWorkspace" })

        val bad = TestPort().also { it.transientCreateWorkspace = 10 }
        val run2 = db.preparingRun()
        val out = runBlocking { db.manager(bad).run(run2, PROFILE) }
        assertIs<AgentRunOutcome.Failed>(out)
        assertEquals(3, bad.mutatingCalls.count { it == "createWorkspace" })
        assertEquals(DevelopmentRunState.FAILED, db.runState(run2)) // PREPARING has no retry path
    }

    @Test
    fun `transient failure with the Authority unavailable is UNKNOWN - no NOT_SENT evidence, never resent`() = TestDb().use { db ->
        val port = TestPort().also { it.transientCreateWorkspace = 1 }
        db.evidence.available = false
        val runId = db.preparingRun()
        val rr = assertIs<AgentRunOutcome.ReconciliationRequired>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, port.mutatingCalls.count { it == "createWorkspace" })
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.journal.get(rr.opId)!!.status)
        assertEquals(DevelopmentRunState.PREPARING, db.runState(runId))
    }

    @Test
    fun `missing exkes capability parks the run as WaitingDependency instead of failing it`() = TestDb().use { db ->
        val port = TestPort().also { it.unsupportedStartExecution = true }
        val runId = db.preparingRun()
        val out = runBlocking { db.manager(port).run(runId, PROFILE) }
        assertIs<AgentRunOutcome.WaitingDependency>(out)
        assertEquals(DevelopmentRunState.DEVELOPING, db.runState(runId))
        assertEquals(OperationStatus.RETRYABLE, db.ops(runId).getValue("EXKES_START_EXECUTION").status)
        // once exkes can do it, the same run continues with the same Agent
        port.unsupportedStartExecution = false
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(1, db.agentsOf(runId).size)
    }

    @Test
    fun `concurrency limit - a second run is refused while the slot is taken`() = TestDb().use { db ->
        val port = TestPort()
        val a = db.preparingRun()
        val b = db.preparingRun()
        val m = db.manager(port)
        var second: AgentRunOutcome? = null
        port.onGetExecution = { n -> if (n == 1) second = m.run(b, PROFILE) }
        assertIs<AgentRunOutcome.Completed>(runBlocking { m.run(a, PROFILE) })
        assertEquals(AgentRunOutcome.Busy, second)
        assertEquals(DevelopmentRunState.PREPARING, db.runState(b))
    }

    @Test
    fun `a run that is not in a runnable state is refused`() = TestDb().use { db ->
        val run = db.runs.create("task-x", "test", RunCompletionMode.RELEASE)
        assertEquals(AgentRunOutcome.NotRunnable(DevelopmentRunState.CREATED), runBlocking { db.manager(TestPort()).run(run.runId, PROFILE) })
    }

    @Test
    fun `startup reconciliation resumes runs nobody owns`() = TestDb().use { db ->
        val port = TestPort().also { it.crashAfterStartExecution = true }
        val orphan = db.preparingRun()
        assertFailsWith<SimulatedCrash> { runBlocking { db.manager(port, "worker-A").run(orphan, PROFILE) } }
        val untouched = db.preparingRun()
        val held = db.preparingRun().also { db.runs.hold(it, "ops", "admin") }
        val outcomes = runBlocking { db.manager(port, "worker-B").resumeAll(PROFILE) }
        assertEquals(setOf(orphan, untouched), outcomes.keys)
        assertTrue(outcomes.values.all { it is AgentRunOutcome.Completed })
        // the orphan's Execution was adopted (1 POST by the dead worker), the untouched run posted once: 2 in total, never 3
        assertEquals(2, port.fake.postCounts["startExecution"])
        assertEquals(DevelopmentRunState.PREPARING, db.runState(held))
    }

    @Test
    fun `agent and session writes are fenced and follow the state machines`() = TestDb().use { db ->
        val repo = AgentRepository(db.database)
        val runId = db.preparingRun()
        val lease = db.leases.acquire(runId, "w", Duration.ofSeconds(30))!!
        val agent = repo.createAgent(lease, "developer")
        assertFailsWith<InvalidTransitionException> { repo.transitionAgent(lease, agent.agentId, agent.version, AgentState.RUNNING) }
        val starting = repo.transitionAgent(lease, agent.agentId, agent.version, AgentState.STARTING)
        assertEquals(AgentState.STARTING, starting.state)
        db.expireLease(runId.value)
        assertFailsWith<net.kigawa.kivra.core.LeaseLostException> { repo.transitionAgent(lease, agent.agentId, starting.version, AgentState.RUNNING) }
        assertEquals(AgentState.STARTING, repo.agentsOf(runId).single().state)
    }

    @Test
    fun `agent profile needs a pinned version`() {
        val json = """{"name":"developer","version":"3","templateId":"tpl","executionType":"AGENT","timeoutSeconds":600}"""
        val p = AgentProfile.parse(json, pinnedVersion = "3")
        assertEquals("developer@3", p.ref)
        assertEquals(Duration.ofSeconds(600), p.timeout)
        assertFailsWith<IllegalArgumentException> { AgentProfile.parse(json, pinnedVersion = "4") }
        assertFailsWith<IllegalArgumentException> { AgentProfile.parse("""{"name":"a","version":" ","templateId":"t"}""") }
    }
}
