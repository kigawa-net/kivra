package net.kigawa.kivra.db

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import net.kigawa.kivra.selfdev.autofix.INCIDENT_FIX_PRINCIPAL
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryRefusal
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * ADR 0022 PR-F: the write paths never write a NULL project into the two columns that V52 makes NOT NULL. Each refusal writes nothing.
 * On H2 with the real repositories (the default migration chain, so V52 is applied).
 */
class ProjectContractWriteTest {
    private val clock: Clock = Clock.systemUTC()

    private fun TestDb.countTasks(createdBy: String, key: String): Long = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM selfdev_tasks WHERE created_by = ? AND idempotency_key = ?", createdBy, key,
        ) { it.getLong(1) }.single()
    }

    @Test
    fun `an operator's task without a project is refused and nothing is written`() {
        TestDb().use { db ->
            val ops = SelfDevOperatorRepository(db.database)
            assertFailsWith<ProjectRequiredException> {
                ops.submitTask("operator", "no-project", "Title", null, "kigawa-net/kivra", null)
            }
            assertEquals(0L, db.countTasks("operator", "no-project"))
        }
    }

    @Test
    fun `a system task takes its repository's project, and is refused when the repository has none`() {
        TestDb().use { db ->
            val projects = ProjectRepository(db.database, clock)
            val alpha = projects.create("Alpha", null).projectId
            projects.addRepo(alpha, "kigawa-net/mapped")
            val ops = SelfDevOperatorRepository(db.database)

            val mapped = assertIs<SelfDevSubmitResult.Created>(
                ops.submitTask(INCIDENT_FIX_PRINCIPAL, "incident:mapped", "Fix", null, "kigawa-net/mapped"),
            ).task
            assertEquals(alpha, mapped.projectId)

            assertFailsWith<ProjectRequiredException> {
                ops.submitTask(INCIDENT_FIX_PRINCIPAL, "incident:unmapped", "Fix", null, "kigawa-net/unmapped")
            }
            assertEquals(0L, db.countTasks(INCIDENT_FIX_PRINCIPAL, "incident:unmapped"))
        }
    }

    @Test
    fun `a cycle cannot start for an order without a project, and no task is written`() {
        TestDb().use { db ->
            val orders = SelfDevOrderRepository(db.database)
            val order = (orders.createRoot("operator", "Order", "Improve the docs", "kigawa-net/kivra", 3, null) as CreateRootResult.Created).order
            assertEquals(null, order.projectId)

            val refused = assertIs<OrderResult.Refused>(orders.startCycle(order.orderId, order.version, "operator"))
            assertEquals(OrderConflict.NO_PROJECT, refused.reason)
            assertEquals(0, db.count("selfdev_tasks", "order_id = '${order.orderId}'"))
        }
    }

    @Test
    fun `a memory record without any project in its context is refused and nothing is written`() {
        TestDb().use { db ->
            val memory = SelfDevMemoryRepository(db.database, enabled = true)
            val result = memory.save(
                MemoryCandidate(
                    kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = "1:i:oper",
                    body = "CI を速くする",
                ),
            )
            assertEquals(MemorySaveResult.Refused(MemoryRefusal.NO_PROJECT), result)
            assertEquals(0, db.count("selfdev_memory_records"))
        }
    }
}
