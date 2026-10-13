package net.kigawa.kivra.exkes

/** The same contract as FakeExecutionPortTest, against ExkesAdapter over the exkes simulator (small pages on purpose). */
class ExkesAdapterContractTest : ExecutionPortContract() {
    override fun harness(): PortHarness {
        val sim = ExkesSimulator()
        val cfg = ExkesConfig("http://exkes.test", pageSize = 2)
        val adapter = ExkesAdapter(ExkesAdapter.newClient(sim.engine, cfg), cfg)
        return object : PortHarness {
            override val port: ExecutionPort = adapter
            override fun settleWorkspace(id: WorkspaceId) {
                sim.workspaces[id.value] = sim.workspaces.getValue(id.value).copy(status = "READY")
            }
            override fun runtimeRunning(id: RuntimeId) {
                sim.runtimes[id.value] = sim.runtimes.getValue(id.value).copy(status = "RUNNING")
            }
            override fun finishExecution(id: ExecutionId) {
                sim.executions[id.value] = sim.executions.getValue(id.value).copy(status = "SUCCEEDED", exitCode = 0)
            }
            override fun loseResponse(op: String, afterEffect: Boolean) { sim.lost.addLast(op to afterEffect) }
            override fun effects(op: String) = sim.effects[op] ?: 0
        }
    }
}
