package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.PlanCommit
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The write path of the project scope (ADR 0022, PR-C): a new entity takes its project from its creator or its parent, and a dependency
 * may not cross a project. On H2 with the real repositories.
 */
class ProjectScopeWriteTest {
    private val clock: Clock = Clock.systemUTC()

    private fun TestDb.project(name: String): String = ProjectRepository(database, clock).create(name, null).projectId

    /** A raw write for a fixture the repositories do not offer (a dependency set by hand, a state set for the test). */
    private fun TestDb.exec(sql: String, vararg params: Any?) {
        transaction(database) { execUpdate(sql, *params) }
    }

    private fun root(orders: SelfDevOrderRepository, projectId: String?): net.kigawa.kivra.db.SelfDevOrder =
        (orders.createRoot("operator", "Order title", "Improve the docs", "kigawa-net/kivra", 3, projectId) as CreateRootResult.Created).order

    @Test
    fun `a cycle of a root order and the tasks planned under it carry the root's project`() {
        TestDb().use { db ->
            val project = db.project("P1")
            val orders = SelfDevOrderRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val order = root(orders, project)
            assertEquals(project, order.projectId)

            val started = assertIs<OrderResult.Moved<net.kigawa.kivra.db.CycleStarted>>(
                orders.startCycle(order.orderId, order.version, "operator"),
            ).value
            val cycleTask = assertNotNull(ops.task(started.goalId))
            assertEquals(project, cycleTask.projectId)

            val fence = assertNotNull(ops.claimForPlanning(cycleTask.taskId, "token", Duration.ofMinutes(10))).fence
            val planned = NewTask(
                title = "Docs", body = "body", targetPaths = listOf("docs/a.md"), sizeEstimate = "S", dependsOn = emptyList(),
                requiresApproval = false, initialState = TaskState.QUEUED,
            )
            assertIs<PlanCommit.Committed>(ops.commitPlan(fence, listOf(planned)))
            val children = ops.listTasks(100).filter { it.parentTaskId == cycleTask.taskId }
            assertEquals(1, children.size)
            assertEquals(project, children.single().projectId)
        }
    }

    @Test
    fun `a child order takes its parent's project, and a root without one stays unassigned`() {
        TestDb().use { db ->
            val project = db.project("P1")
            val orders = SelfDevOrderRepository(db.database)
            val parent = root(orders, project)
            val limits = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 20)
            val child = assertIs<ChildCreateResult.Created>(
                orders.createChild(parent.orderId, "Child", "Improve the docs", 0, "child-1", "operator", limits),
            ).order
            assertEquals(project, child.projectId)

            val unassigned = root(orders, null)
            assertNull(unassigned.projectId)
        }
    }

    @Test
    fun `an operator's task keeps its project, a replay under another project is a conflict, and the Run takes the task's project`() {
        TestDb().use { db ->
            val project = db.project("P1")
            val other = db.project("P2")
            val ops = SelfDevOperatorRepository(db.database)
            val task = assertIs<SelfDevSubmitResult.Created>(
                ops.submitTask("operator", "goal-1", "Title", null, "kigawa-net/kivra", project),
            ).task
            assertEquals(project, task.projectId)
            assertIs<SelfDevSubmitResult.KeyConflict>(ops.submitTask("operator", "goal-1", "Title", null, "kigawa-net/kivra", other))
            assertIs<SelfDevSubmitResult.Replayed>(ops.submitTask("operator", "goal-1", "Title", null, "kigawa-net/kivra", project))

            val runs: DevelopmentRunRepository = db.runs
            val run: DevelopmentRun = assertIs<TaskSubmitResult.Created>(
                runs.submitTask("operator", "run-1", "Title", selfDev = true, maxActiveSelfDev = null, body = null, projectId = project),
            ).run
            assertEquals(project, run.projectId)
            assertIs<TaskSubmitResult.ConflictingTitle>(
                runs.submitTask("operator", "run-1", "Title", selfDev = true, maxActiveSelfDev = null, body = null, projectId = other),
            )
        }
    }

    @Test
    fun `a task whose dependency is in another project is refused, and so is a mix with an unassigned one, but two unassigned tasks are not`() {
        TestDb().use { db ->
            val p1 = db.project("P1")
            val p2 = db.project("P2")
            val ops = SelfDevOperatorRepository(db.database)
            fun submit(key: String, project: String?): String =
                assertIs<SelfDevSubmitResult.Created>(ops.submitTask("operator", key, "T-$key", null, "kigawa-net/kivra", project)).task.taskId

            // A completed dependency in P1, and a dependent task that is queued.
            val dep1 = submit("dep1", p1)
            db.exec("UPDATE selfdev_tasks SET state = 'COMPLETED' WHERE task_id = ?", dep1)
            fun queued(key: String, project: String?, deps: List<String>): String {
                val id = submit(key, project)
                db.exec("UPDATE selfdev_tasks SET state = 'QUEUED', dependencies = ? WHERE task_id = ?", "[" + deps.joinToString { "\"$it\"" } + "]", id)
                return id
            }

            val crossProject = queued("cross", p2, listOf(dep1))
            val sameProject = queued("same", p1, listOf(dep1))
            assertIs<TaskTransition.Refused>(ops.beginSubmission(crossProject, ops.task(crossProject)!!.version))
            assertIs<TaskTransition.Moved>(ops.beginSubmission(sameProject, ops.task(sameProject)!!.version))

            // Unassigned: a dependency with no project is fine for an unassigned task, and refused for an assigned one.
            val depNull = submit("depnull", null)
            db.exec("UPDATE selfdev_tasks SET state = 'COMPLETED' WHERE task_id = ?", depNull)
            val nullOnNull = queued("nullnull", null, listOf(depNull))
            val assignedOnNull = queued("assignednull", p1, listOf(depNull))
            assertIs<TaskTransition.Moved>(ops.beginSubmission(nullOnNull, ops.task(nullOnNull)!!.version))
            assertIs<TaskTransition.Refused>(ops.beginSubmission(assignedOnNull, ops.task(assignedOnNull)!!.version))
        }
    }
}
