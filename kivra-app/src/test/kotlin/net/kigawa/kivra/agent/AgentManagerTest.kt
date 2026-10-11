package net.kigawa.kivra.agent

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.AgentState
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.ExecutionSessionState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AgentRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.exkes.Execution
import net.kigawa.kivra.exkes.ExecutionCapabilities
import net.kigawa.kivra.exkes.ExecutionId
import net.kigawa.kivra.exkes.ExecutionPort
import net.kigawa.kivra.exkes.ExecutionSpec
import net.kigawa.kivra.exkes.ExecutionState
import net.kigawa.kivra.exkes.FakeExecutionPort
import net.kigawa.kivra.exkes.PortResult
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
    private var getExecutionCalls = 0
    val mutatingCalls = mutableListOf<String>()

    override suspend fun createWorkspace(spec: WorkspaceSpec): PortResult<Workspace> {
        mutatingCalls += "createWorkspace"
        if (transientCreateWorkspace > 0) {
            transientCreateWorkspace--
            return PortResult.Transient("connection refused")
        }
        return fake.createWorkspace(spec)
    }

    override suspend fun getWorkspace(id: WorkspaceId): PortResult<Workspace> {
        val r = fake.getWorkspace(id)
        if (r is PortResult.Ok && r.value.state == WorkspaceState.CREATING) fake.setWorkspaceState(id, WorkspaceState.READY)
        return fake.getWorkspace(id)
    }

    override suspend fun getRuntime(id: RuntimeId): PortResult<Runtime> {
        val r = fake.getRuntime(id)
        if (r is PortResult.Ok && r.value.state == RuntimeState.CREATING) fake.setRuntimeState(id, RuntimeState.RUNNING)
        return fake.getRuntime(id)
    }

    override suspend fun startExecution(runtime: RuntimeId, spec: ExecutionSpec): PortResult<Execution> {
        mutatingCalls += "startExecution"
        if (unsupportedStartExecution) return PortResult.Unsupported("agent execution")
        val r = fake.startExecution(runtime, spec)
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
        return fake.stopRuntime(id)
    }

    override suspend fun deleteWorkspace(id: WorkspaceId): PortResult<Workspace> {
        mutatingCalls += "deleteWorkspace"
        return fake.deleteWorkspace(id)
    }
}

private val PROFILE = AgentProfile(name = "developer", version = "1", templateId = "tpl-dev")

private val TEST_CONFIG = AgentManagerConfig(
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
        val run = runs.create("task-${System.nanoTime()}", "test")
        forceState(run.runId.value, DevelopmentRunState.PREPARING)
        return run.runId
    }

    private fun TestDb.manager(port: ExecutionPort, worker: String = "worker-A", config: AgentManagerConfig = TEST_CONFIG) =
        AgentManager(worker, port, leases, runs, AgentRepository(database), journal, config)

    private fun TestDb.agentsOf(runId: RunId) = AgentRepository(database).agentsOf(runId)
    private fun TestDb.ops(runId: RunId) = journal.listByRun(runId).associateBy { it.opType }
    private fun TestDb.runState(runId: RunId) = runs.get(runId)!!.state

    @Test
    fun `one run goes through workspace runtime execution to TESTING with every effect journaled`() = TestDb().use { db ->
        val port = TestPort()
        val runId = db.preparingRun()
        val out = runBlocking { db.manager(port).run(runId, PROFILE) }
        assertEquals(AgentRunOutcome.Completed(DevelopmentRunState.TESTING, cleanupPending = false), out)
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
    fun `lost createWorkspace response without effect is replayed only on persisted ABSENT evidence`() = TestDb().use { db ->
        val port = TestPort().also { it.fake.injectUnknown("createWorkspace", afterEffect = false) }
        val runId = db.preparingRun()
        assertIs<AgentRunOutcome.Completed>(runBlocking { db.manager(port).run(runId, PROFILE) })
        assertEquals(2, port.mutatingCalls.count { it == "createWorkspace" })
        assertEquals(1, port.fake.postCounts["createWorkspace"]) // the lost one never reached the server
        val ws = db.ops(runId).getValue("EXKES_CREATE_WORKSPACE")
        assertEquals("ABSENT", ws.evidenceObservation)
        assertEquals(2, ws.attempt)
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
        val run = db.runs.create("task-x", "test")
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
