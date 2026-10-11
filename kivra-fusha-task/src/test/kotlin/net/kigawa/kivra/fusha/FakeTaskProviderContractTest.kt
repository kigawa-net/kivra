package net.kigawa.kivra.fusha

import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskSnapshot
import java.time.Instant

class FakeTaskProviderContractTest : TaskProviderContract() {
    private fun snap(n: Long, title: String, assignee: String, updated: Instant, fields: Map<String, String> = emptyMap(), next: List<Long> = emptyList()) =
        TaskSnapshot(ContractFixture.id(n), title, null, assignee, null, fields, next.map { ContractFixture.id(it) }, emptyList(), updated, Instant.EPOCH)

    override fun provider(): TaskProvider = FakeTaskProvider(
        listOf(
            snap(1, "Task one", "alice", ContractFixture.t1, mapOf("status" to "\"ready\""), listOf(2)),
            snap(2, "Task two", "bob", ContractFixture.t2),
            snap(3, "Task three", "alice", ContractFixture.t2),
        ),
        viewMembers = mapOf(10L to setOf(ContractFixture.id(1), ContractFixture.id(2))),
    )
}
