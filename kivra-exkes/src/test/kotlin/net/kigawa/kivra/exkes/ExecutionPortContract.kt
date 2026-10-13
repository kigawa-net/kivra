package net.kigawa.kivra.exkes

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.OperationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Test-side control over the thing behind the port (the Fake itself, or an in-memory exkes behind MockEngine). */
interface PortHarness {
    val port: ExecutionPort
    fun settleWorkspace(id: WorkspaceId)
    fun runtimeRunning(id: RuntimeId)
    fun finishExecution(id: ExecutionId)

    /** The next mutating call of [op] reaches the "server" (or not) but its response is lost. */
    fun loseResponse(op: String, afterEffect: Boolean)

    /** POSTs/DELETEs that actually reached the server for [op]. */
    fun effects(op: String): Int
}

/** The contract both FakeExecutionPort and ExkesAdapter must satisfy (issue #29 completion criteria). */
abstract class ExecutionPortContract {
    abstract fun harness(): PortHarness

    private fun <T> PortResult<T>.ok(): T {
        assertIs<PortResult.Ok<T>>(this, "expected Ok but was $this")
        return value
    }

    private val runName = KivraNaming.workspaceName("01HRUN")

    @Test
    fun `createWorkspace is asynchronous and idempotent by name`() = runTest {
        val h = harness()
        val a = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        assertEquals(WorkspaceState.CREATING, a.state)
        h.settleWorkspace(a.id)
        val b = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        assertEquals(a.id, b.id)
        assertEquals(WorkspaceState.READY, b.state)
        assertEquals(1, h.effects("createWorkspace"))
        assertEquals(a.id, h.port.getWorkspace(a.id).ok().id)
    }

    @Test
    fun `unknown ids are NotFound`() = runTest {
        val h = harness()
        assertIs<PortResult.NotFound>(h.port.getWorkspace(WorkspaceId("nope")))
        assertIs<PortResult.NotFound>(h.port.deleteWorkspace(WorkspaceId("nope")))
        assertIs<PortResult.NotFound>(h.port.getRuntime(RuntimeId(WorkspaceId("nope"), "x")))
    }

    @Test
    fun `findWorkspaceByName returns null when absent`() = runTest {
        val h = harness()
        assertNull(h.port.findWorkspaceByName(runName).ok())
    }

    @Test
    fun `lookup finds the right workspace across several pages`() = runTest {
        val h = harness()
        repeat(5) { h.port.createWorkspace(WorkspaceSpec("other-$it")).ok() }
        val mine = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        repeat(3) { h.port.createWorkspace(WorkspaceSpec("later-$it")).ok() }
        assertEquals(mine.id, h.port.findWorkspaceByName(runName).ok()?.id)
    }

    @Test
    fun `lost response after effect is UNKNOWN and is reconciled by name, not recreated`() = runTest {
        val h = harness()
        h.loseResponse("createWorkspace", afterEffect = true)
        val r = h.port.createWorkspace(WorkspaceSpec(runName))
        assertEquals(OperationStatus.UNKNOWN, r.toOperationStatus())
        assertEquals(1, h.effects("createWorkspace"))

        val found = h.port.findWorkspaceByName(runName).ok()
        assertNotNull(found)
        // A (manually or evidence-gated) replay adopts the existing workspace.
        assertEquals(found.id, h.port.createWorkspace(WorkspaceSpec(runName)).ok().id)
        assertEquals(1, h.effects("createWorkspace"))
    }

    @Test
    fun `lost response before effect is UNKNOWN and reconciliation reports absence`() = runTest {
        val h = harness()
        h.loseResponse("createWorkspace", afterEffect = false)
        assertEquals(OperationStatus.UNKNOWN, h.port.createWorkspace(WorkspaceSpec(runName)).toOperationStatus())
        assertEquals(0, h.effects("createWorkspace"))
        assertNull(h.port.findWorkspaceByName(runName).ok())
    }

    @Test
    fun `runtime creation is idempotent by label`() = runTest {
        val h = harness()
        val ws = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        val spec = RuntimeSpec("tpl", KivraNaming.runLabels("01HRUN"))
        h.loseResponse("createRuntime", afterEffect = true)
        assertIs<PortResult.Unknown>(h.port.createRuntime(ws.id, spec))
        val found = h.port.findRuntimesByLabel(ws.id, spec.labels).ok()
        assertEquals(1, found.size)
        assertEquals(found.single().id, h.port.createRuntime(ws.id, spec).ok().id)
        assertEquals(1, h.effects("createRuntime"))
        assertEquals(emptyList(), h.port.findRuntimesByLabel(ws.id, mapOf("kivra.run-id" to "other")).ok())
    }

    @Test
    fun `execution needs a RUNNING runtime and is guarded against duplicates`() = runTest {
        val h = harness()
        val ws = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        val rt = h.port.createRuntime(ws.id, RuntimeSpec("tpl", KivraNaming.runLabels("01HRUN"))).ok()
        val spec = ExecutionSpec(ExecutionType.COMMAND, commandJson = """{"cmd":"echo hi"}""")
        assertIs<PortResult.Conflict>(h.port.startExecution(rt.id, spec)) // still CREATING
        h.runtimeRunning(rt.id)

        val ex = h.port.startExecution(rt.id, spec).ok()
        assertEquals(ExecutionState.QUEUED, ex.state)
        assertIs<PortResult.Conflict>(h.port.startExecution(rt.id, spec)) // guard: no 2nd POST
        assertEquals(1, h.effects("startExecution"))

        h.finishExecution(ex.id)
        assertEquals(ExecutionState.SUCCEEDED, h.port.getExecution(ex.id).ok().state)
        assertEquals(listOf(ex.id), h.port.listExecutions(rt.id).ok().map { it.id })
    }

    @Test
    fun `lost execution response is UNKNOWN and the guard blocks automatic re-POST`() = runTest {
        val h = harness()
        val ws = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        val rt = h.port.createRuntime(ws.id, RuntimeSpec("tpl", KivraNaming.runLabels("01HRUN"))).ok()
        h.runtimeRunning(rt.id)
        val spec = ExecutionSpec(ExecutionType.COMMAND, commandJson = "{}")
        h.loseResponse("startExecution", afterEffect = true)
        assertEquals(OperationStatus.UNKNOWN, h.port.startExecution(rt.id, spec).toOperationStatus())
        assertIs<PortResult.Conflict>(h.port.startExecution(rt.id, spec))
        assertEquals(1, h.effects("startExecution"))
        assertEquals(1, h.port.listExecutions(rt.id).ok().size)
    }

    @Test
    fun `cancel and logs are Unsupported`() = runTest {
        val h = harness()
        val id = ExecutionId(RuntimeId(WorkspaceId("w"), "r"), "e")
        assertIs<PortResult.Unsupported>(h.port.cancelExecution(id))
        assertIs<PortResult.Unsupported>(h.port.readLogs(id, null))
        assertEquals(false, h.port.capabilities.cancelExecution)
        assertEquals(false, h.port.capabilities.idempotencyKey)
    }

    @Test
    fun `stop and delete are accepted asynchronously`() = runTest {
        val h = harness()
        val ws = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        val rt = h.port.createRuntime(ws.id, RuntimeSpec("tpl")).ok()
        h.runtimeRunning(rt.id)
        assertEquals(RuntimeState.STOPPING, h.port.stopRuntime(rt.id).ok().state)
        assertEquals(RuntimeState.DELETING, h.port.deleteRuntime(rt.id).ok().state)
        assertEquals(WorkspaceState.DELETING, h.port.deleteWorkspace(ws.id).ok().state)
    }

    @Test
    fun `awaitWorkspace reaches READY and times out otherwise`() = runBlocking {
        val h = harness()
        val a = h.port.createWorkspace(WorkspaceSpec(runName)).ok()
        val cfg = PollConfig(interval = 5.milliseconds, timeout = 60.milliseconds)
        val timedOut = h.port.awaitWorkspace(a.id, cfg)
        assertIs<PollOutcome.TimedOut<Workspace>>(timedOut)
        assertEquals(WorkspaceState.CREATING, timedOut.last?.state)
        h.settleWorkspace(a.id)
        assertIs<PollOutcome.Reached<Workspace>>(h.port.awaitWorkspace(a.id, cfg))
        assertIs<PollOutcome.Failed>(h.port.awaitWorkspace(WorkspaceId("nope"), cfg))
    }
}
