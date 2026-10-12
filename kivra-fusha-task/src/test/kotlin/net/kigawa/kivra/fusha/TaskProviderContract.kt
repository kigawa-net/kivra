package net.kigawa.kivra.fusha

import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.TaskCapability
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskPatch
import net.kigawa.kivra.core.TaskWriteResult
import net.kigawa.kivra.core.TaskProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture shared by Fake and Adapter contract tests. Env label `test`; ids 1..3. */
object ContractFixture {
    const val ENV = "test"
    fun id(n: Long) = TaskId("fusha:$ENV:$n")
    val t1 = Instant.parse("2026-10-01T00:00:00.000Z")
    val t2 = Instant.parse("2026-10-02T12:34:56.789Z")
}

/** Both implementations must pass these. Subclasses build a provider seeded with the fixture. */
abstract class TaskProviderContract {
    /** Seeds: #1 assignee=alice (customFields status=ready, next=[2]); #2 assignee=bob; #3 assignee=alice. View 10 = {#1,#2}. */
    abstract fun provider(): TaskProvider

    @Test fun `capabilities are read-only`() {
        assertEquals(setOf(TaskCapability.GET, TaskCapability.LIST), provider().capabilities)
    }

    @Test fun `get returns snapshot`() = runTest {
        val s = provider().get(ContractFixture.id(1))!!
        assertEquals(ContractFixture.id(1), s.id)
        assertEquals("Task one", s.title)
        assertEquals("alice", s.assigneeId)
        assertEquals(ContractFixture.t1, s.updatedAt)
        assertEquals("\"ready\"", s.fields["status"])
        assertEquals(listOf(ContractFixture.id(2)), s.nextTaskIds)
    }

    @Test fun `get missing returns null`() = runTest {
        assertNull(provider().get(ContractFixture.id(999)))
    }

    @Test fun `list returns all visible tasks`() = runTest {
        assertEquals(listOf(1L, 2L, 3L).map { ContractFixture.id(it) }, provider().list().map { it.id }.sortedBy { it.value })
    }

    @Test fun `list filters by assignee`() = runTest {
        val ids = provider().list(TaskFilter(assigneeId = "alice")).map { it.id }.toSet()
        assertEquals(setOf(ContractFixture.id(1), ContractFixture.id(3)), ids)
    }

    @Test fun `list filters by view`() = runTest {
        val ids = provider().list(TaskFilter(viewId = 10)).map { it.id }.toSet()
        assertEquals(setOf(ContractFixture.id(1), ContractFixture.id(2)), ids)
    }

    @Test fun `writes are unsupported`() = runTest {
        val p = provider()
        val u = p.update(ContractFixture.id(1), TaskPatch(mapOf("status" to "\"done\"")), null)
        assertIs<TaskWriteResult.Unsupported>(u)
        assertEquals(TaskCapability.UPDATE, u.capability)
        val c = p.comment(ContractFixture.id(1), "hi", IdempotencyKey("k"))
        assertIs<TaskWriteResult.Unsupported>(c)
        assertTrue(c.reason.isNotBlank())
    }
}
