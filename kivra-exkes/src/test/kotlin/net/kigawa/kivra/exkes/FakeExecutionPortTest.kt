package net.kigawa.kivra.exkes

class FakeExecutionPortTest : ExecutionPortContract() {
    override fun harness(): PortHarness = object : PortHarness {
        val fake = FakeExecutionPort()
        override val port: ExecutionPort = fake
        override fun settleWorkspace(id: WorkspaceId) = fake.setWorkspaceState(id, WorkspaceState.READY)
        override fun runtimeRunning(id: RuntimeId) = fake.setRuntimeState(id, RuntimeState.RUNNING)
        override fun finishExecution(id: ExecutionId) = fake.setExecutionState(id, ExecutionState.SUCCEEDED, 0)
        override fun loseResponse(op: String, afterEffect: Boolean) = fake.injectUnknown(op, afterEffect)
        override fun effects(op: String) = fake.postCounts[op] ?: 0
    }
}
