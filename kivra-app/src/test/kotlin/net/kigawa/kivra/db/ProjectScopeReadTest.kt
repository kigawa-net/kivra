package net.kigawa.kivra.db

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The project scope of the reads (ADR 0022 PR-D) on H2 with the real repositories. A list narrowed to a project gives its rows only, the
 * `unassigned` choice gives the rows with no project, and no choice gives every row. The autonomous loops still see the rows with no
 * project: their queries are not narrowed.
 */
class ProjectScopeReadTest {
    private val operator = "1:i:oper"

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun TestDb.projectA(): String = ProjectRepository(database, Clock.systemUTC()).create("Alpha", null).projectId

    private fun TestDb.task(key: String, projectId: String?): String =
        assertIs<SelfDevSubmitResult.Created>(
            SelfDevOperatorRepository(database).submitTask(operator, key, "Task $key", null, "kigawa-net/kivra", projectId),
        ).task.taskId

    private fun TestDb.order(title: String, projectId: String?): String =
        assertIs<CreateRootResult.Created>(
            SelfDevOrderRepository(database).createRoot(operator, title, "policy", "kigawa-net/kivra", 1, projectId),
        ).order.orderId

    private fun TestDb.run(key: String, projectId: String?): RunId =
        (runs.submitTask(operator, key, "Run $key", projectId = projectId) as TaskSubmitResult.Created).run.runId

    @Test
    fun `the run list is narrowed to a project, to the rows with no project, or not at all`() = TestDb().use { db ->
        val alpha = db.projectA()
        val inAlpha = db.run("in-alpha", alpha)
        val unassigned = db.run("unassigned", null)

        assertEquals(setOf(inAlpha, unassigned), db.runs.listAllTasks(50).map { it.runId }.toSet(), "no choice: every row")
        assertEquals(listOf(inAlpha), db.runs.listAllTasks(50, project = ProjectFilter.Only(alpha)).map { it.runId })
        assertEquals(listOf(unassigned), db.runs.listAllTasks(50, project = ProjectFilter.Unassigned).map { it.runId })
        assertEquals(listOf(inAlpha), db.runs.listTasks(operator, 50, project = ProjectFilter.Only(alpha)).map { it.runId })
        assertEquals(listOf(unassigned), db.runs.listTasks(operator, 50, project = ProjectFilter.Unassigned).map { it.runId })
        assertEquals(setOf(inAlpha, unassigned), db.runs.listTasks(operator, 50).map { it.runId }.toSet())
    }

    @Test
    fun `the task and order lists are narrowed the same way, and an unknown project is an empty list`() = TestDb().use { db ->
        val alpha = db.projectA()
        val taskAlpha = db.task("a", alpha)
        val taskNone = db.task("n", null)
        val orderAlpha = db.order("alpha order", alpha)
        val orderNone = db.order("none order", null)
        val unknown = ProjectFilter.Only("01UNKNOWN" + "Z".repeat(17))

        val ops = SelfDevOperatorRepository(db.database)
        assertEquals(setOf(taskAlpha, taskNone), ops.listTasks(50).map { it.taskId }.toSet())
        assertEquals(listOf(taskAlpha), ops.listTasks(50, ProjectFilter.Only(alpha)).map { it.taskId })
        assertEquals(listOf(taskNone), ops.listTasks(50, ProjectFilter.Unassigned).map { it.taskId })
        assertEquals(emptyList(), ops.listTasks(50, unknown).map { it.taskId })

        val orders = SelfDevOrderRepository(db.database)
        assertEquals(setOf(orderAlpha, orderNone), orders.list(50).map { it.orderId }.toSet())
        assertEquals(listOf(orderAlpha), orders.list(50, ProjectFilter.Only(alpha)).map { it.orderId })
        assertEquals(listOf(orderNone), orders.list(50, ProjectFilter.Unassigned).map { it.orderId })
    }

    @Test
    fun `the autonomous loops still take the rows with no project, because they are never narrowed`() = TestDb().use { db ->
        val alpha = db.projectA()
        val taskNone = db.task("loop-n", null)
        val taskAlpha = db.task("loop-a", alpha)
        val orderNone = db.order("loop order", null)
        val runNone = db.run("loop-run", null)

        // The dispatcher's queries, which take no project: a task with no project is still one of its tasks.
        val dispatch = SelfDevDispatchQueries(db.database)
        val submitted = dispatch.taskIdsInStates(setOf(TaskState.TASK_SUBMITTED))
        assertTrue(taskNone in submitted, "a task with no project is still seen by the loop")
        assertTrue(taskAlpha in submitted)

        // The order guards: an ACTIVE order with no project is still active.
        assertTrue(SelfDevOrderRepository(db.database).activeOrders(50).any { it.orderId == orderNone })

        // The Run loop: a Run with no project is still a Run the loop sees.
        assertTrue(db.runs.countActiveRuns() >= 1, "the Run with no project is still active")
        assertTrue(runNone in db.runs.findActiveRuns().map { it.runId }, "the Run with no project is still found by the loop")
    }

    @Test
    fun `the stop requests of pending orders are narrowed by the order's project, with the project id on each`() = TestDb().use { db ->
        val alpha = db.projectA()
        val orderAlpha = db.order("alpha order", alpha)
        db.order("none order", null)
        val orders = SelfDevOrderRepository(db.database)
        val reason = StopRequestReason.REQUESTABLE.first()
        val reqAlpha = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            orders.requestStop(orderAlpha, orders.get(orderAlpha)!!.version, operator, OrderActor.HUMAN, reason),
        ).value.requestId
        // Only the project's order gets a stop request here: a second request in this test is refused by the repository's transition
        // rules, which this test does not cover. The unassigned list is therefore checked to be empty.
        assertEquals(setOf(reqAlpha), orders.pendingStopRequests(50).map { it.requestId }.toSet())
        val inAlpha = orders.pendingStopRequests(50, ProjectFilter.Only(alpha))
        assertEquals(listOf(reqAlpha), inAlpha.map { it.requestId })
        assertEquals(alpha, inAlpha.single().projectId)
        assertEquals(emptyList(), orders.pendingStopRequests(50, ProjectFilter.Unassigned).map { it.requestId })
        assertEquals(alpha, orders.projectIdOfStopRequest(reqAlpha))
    }

    @Test
    fun `the memory search and count are narrowed by the record's project`() = TestDb().use { db ->
        val alpha = db.projectA()
        val goalAlpha = db.task("goal-a", alpha)
        val goalNone = db.task("goal-n", null)
        val memory = SelfDevMemoryRepository(db.database, enabled = true)
        fun save(goal: String, body: String): String = assertIs<MemorySaveResult.Written>(
            memory.save(
                MemoryCandidate(
                    kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operator,
                    goalId = goal, body = body,
                ),
            ),
        ).recordId
        val recAlpha = save(goalAlpha, "CI を速くする")
        val recNone = save(goalNone, "ログを減らす")

        val filter = MemoryFilter()
        assertEquals(setOf(recAlpha, recNone), memory.search(filter, 50).map { it.recordId }.toSet(), "no choice: every record")
        assertEquals(listOf(recAlpha), memory.search(filter, 50, 0, ProjectFilter.Only(alpha)).map { it.recordId })
        assertEquals(alpha, memory.search(filter, 50, 0, ProjectFilter.Only(alpha)).single().projectId)
        assertEquals(listOf(recNone), memory.search(filter, 50, 0, ProjectFilter.Unassigned).map { it.recordId })
        assertEquals(1, memory.count(filter, ProjectFilter.Only(alpha)))
        assertEquals(1, memory.count(filter, ProjectFilter.Unassigned))
        assertEquals(2, memory.count(filter))
    }

    @Test
    fun `the assignees are narrowed by project membership, and the unassigned are those of no project`() = TestDb().use { db ->
        val alpha = db.projectA()
        val repo = SelfDevAssigneeRepository(db.database)
        val member = assertIs<AssigneeResult.Moved<Assignee>>(repo.create("member", AssigneeRole.OPERATOR, operator)).value.assigneeId
        val loner = assertIs<AssigneeResult.Moved<Assignee>>(repo.create("loner", AssigneeRole.VIEWER, operator)).value.assigneeId
        db.exec("INSERT INTO project_members (project_id, assignee_id, role, created_at) VALUES (?, ?, 'OPERATOR', CURRENT_TIMESTAMP)", alpha, member)

        assertEquals(setOf(member, loner), repo.listSummaries().map { it.assignee.assigneeId }.toSet())
        assertEquals(listOf(member), repo.listSummaries(ProjectFilter.Only(alpha)).map { it.assignee.assigneeId })
        assertEquals(listOf(loner), repo.listSummaries(ProjectFilter.Unassigned).map { it.assignee.assigneeId })
    }

    @Test
    fun `the membership store reads the role of a member, and no role for anyone else`() = TestDb().use { db ->
        val alpha = db.projectA()
        val member = assertIs<AssigneeResult.Moved<Assignee>>(
            SelfDevAssigneeRepository(db.database).create("member", AssigneeRole.APPROVER, operator),
        ).value.assigneeId
        db.exec("INSERT INTO project_members (project_id, assignee_id, role, created_at) VALUES (?, ?, 'APPROVER', CURRENT_TIMESTAMP)", alpha, member)

        val store = ProjectMemberStore(db.database)
        assertEquals(net.kigawa.kivra.auth.Role.APPROVER, store.roleIn(alpha, member))
        assertEquals(null, store.roleIn("01UNKNOWN" + "Z".repeat(17), member))
        assertEquals(null, store.roleIn(alpha, "01NOBODY" + "Z".repeat(18)))
    }
}
