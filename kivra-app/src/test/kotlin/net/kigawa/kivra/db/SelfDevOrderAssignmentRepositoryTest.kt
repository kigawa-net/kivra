package net.kigawa.kivra.db

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.jetbrains.exposed.sql.transactions.transaction

/** Real schema and repository, shared with API, MCP and chat integration tests. */
fun assignmentMember(db: TestDb, projectId: String, role: String = "OPERATOR", state: String = "ACTIVE", memberRole: String? = "OPERATOR"): String {
    val id = Ulid.generate()
    val now = utcNow(Clock.systemUTC())
    transaction(db.database) {
        execUpdate("INSERT INTO selfdev_assignees (assignee_id, display_name, role, state, created_by, created_at, updated_at) VALUES (?, 'Assignment member', ?, ?, 'bootstrap', ?, ?)", id, role, state, now, now)
        if (memberRole != null) execUpdate("INSERT INTO project_members (project_id, assignee_id, role, created_at) VALUES (?, ?, ?, ?)", projectId, id, memberRole, now)
    }
    return id
}

class SelfDevOrderAssignmentRepositoryTest {
    private val actor = "012345abcdef"
    private fun project(db: TestDb) = ProjectRepository(db.database, Clock.systemUTC()).create("Assignment test", null).projectId
    private fun root(db: TestDb, project: String?) = (SelfDevOrderRepository(db.database).createRoot("operator", "Title", "Policy", "owner/repo", projectId = project) as CreateRootResult.Created).order

    @Test
    fun `assign reassign and unassign retain immutable history and same assignee is a no-op`() = TestDb().use { db ->
        val project = project(db)
        val order = root(db, project)
        val first = assignmentMember(db, project)
        val second = assignmentMember(db, project)
        val clock = Clock.fixed(Instant.parse("2026-11-17T01:02:03Z"), ZoneOffset.UTC)
        val repo = SelfDevOrderAssignmentRepository(db.database, clock)
        val a = repo.assign(order.orderId, first, actor)
        assertEquals(a, repo.getActive(order.orderId))
        assertEquals(a, repo.reassign(order.orderId, first, "abcdef012345"))
        assertEquals(1, repo.listForOrder(order.orderId).size)
        val b = repo.reassign(order.orderId, second, actor)
        val history = repo.listForOrder(order.orderId)
        assertEquals(a.copy(unassignedAt = b.assignedAt), history.first { it.assignmentId == a.assignmentId })
        assertEquals(actor, history.first().assignedBy)
        assertEquals(listOf(b), repo.listForAssignee(second))
        assertTrue(repo.listForAssignee(first).isEmpty())
        repo.unassign(order.orderId, actor)
        repo.unassign(order.orderId, actor)
        assertNull(repo.getActive(order.orderId))
        assertEquals(2, repo.listForOrder(order.orderId).size)
        assertTrue(repo.listForOrder(order.orderId).all { it.unassignedAt != null })
    }

    @Test
    fun `assignment rejects disabled non-members AI global or project role and projectless orders without changing history`() = TestDb().use { db ->
        val project = project(db)
        val order = root(db, project)
        val repo = SelfDevOrderAssignmentRepository(db.database)
        val valid = assignmentMember(db, project)
        val original = repo.assign(order.orderId, valid, actor)
        val invalid = listOf(
            assignmentMember(db, project, state = "DISABLED") to "ASSIGNEE_DISABLED",
            assignmentMember(db, project, memberRole = null) to "ASSIGNEE_NOT_MEMBER",
            assignmentMember(db, project, role = "AI") to "ASSIGNEE_AI",
            assignmentMember(db, project, memberRole = "AI") to "ASSIGNEE_AI",
            assignmentMember(db, project, role = "AI_APPROVER") to "ASSIGNEE_AI",
            Ulid.generate() to "ASSIGNEE_NOT_FOUND",
        )
        invalid.forEach { (id, code) ->
            assertEquals(code, assertFailsWith<InvalidOrderAssignment> { repo.reassign(order.orderId, id, actor) }.code)
            assertEquals(original, repo.getActive(order.orderId))
        }
        assertEquals("NO_PROJECT", assertFailsWith<InvalidOrderAssignment> { repo.assign(root(db, null).orderId, valid, actor) }.code)
        assertEquals("ORDER_NOT_FOUND", assertFailsWith<InvalidOrderAssignment> { repo.assign(Ulid.generate(), valid, actor) }.code)
        assertEquals(1, repo.listForOrder(order.orderId).size)
    }

    @Test
    fun `filters apply before limit and assignments never leak between projects`() = TestDb().use { db ->
        val project = project(db)
        val other = project(db)
        val orders = SelfDevOrderRepository(db.database)
        val a = root(db, project)
        val b = root(db, project)
        val c = root(db, other)
        val member = assignmentMember(db, project)
        orders.assignments.assign(b.orderId, member, actor)
        assertEquals(listOf(b.orderId), orders.list(1, ProjectFilter.Only(project), assigneeId = member).map { it.orderId })
        assertEquals(member, orders.get(b.orderId)!!.assigneeId)
        assertNotNull(orders.get(b.orderId)!!.assignedAt)
        assertEquals(listOf(a.orderId), orders.list(1, ProjectFilter.Only(project), unassigned = true).map { it.orderId })
        assertEquals(listOf(c.orderId), orders.list(10, ProjectFilter.Only(other), unassigned = true).map { it.orderId })
    }

    @Test
    fun `simultaneous first assignments and replacements preserve one active row`() = TestDb().use { db ->
        val project = project(db)
        val order = root(db, project)
        val repo = SelfDevOrderAssignmentRepository(db.database)
        val members = List(2) { assignmentMember(db, project) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) {
                val start = CountDownLatch(1)
                val jobs = members.map { member -> pool.submit<OrderAssignment> { start.await(); repo.reassign(order.orderId, member, actor) } }
                start.countDown()
                jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, repo.listForOrder(order.orderId).count { it.unassignedAt == null })
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `failed assignment rolls back a creation in the shared transaction`() = TestDb().use { db ->
        val project = project(db)
        val orders = SelfDevOrderRepository(db.database)
        assertFailsWith<InvalidOrderAssignment> {
            orders.assignments.atomic {
                val order = root(db, project)
                orders.assignments.assign(order.orderId, Ulid.generate(), actor)
            }
        }
        assertTrue(orders.list(10).isEmpty())
    }

    @Test
    fun `schema forbids multiple active rows and null active slots on open history`() = TestDb().use { db ->
        val project = project(db)
        val order = root(db, project)
        val member = assignmentMember(db, project)
        val repo = SelfDevOrderAssignmentRepository(db.database)
        repo.assign(order.orderId, member, actor)
        for (slot in listOf<Int?>(1, null)) {
            assertFails {
                transaction(db.database) {
                    execUpdate("INSERT INTO selfdev_order_assignments (assignment_id, order_id, assignee_id, assigned_by, assigned_at, active_slot) VALUES (?, ?, ?, ?, ?, ?)",
                        Ulid.generate(), order.orderId, member, actor, utcNow(Clock.systemUTC()), slot)
                }
            }
        }
        assertEquals(1, repo.listForOrder(order.orderId).size)
    }
}
