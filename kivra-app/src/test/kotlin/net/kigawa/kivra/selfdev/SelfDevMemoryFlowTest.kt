package net.kigawa.kivra.selfdev

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder.Companion.identityOf
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * ADR 0016 P1 through the real flows: the Planner, the operator's HTTP routes and the Dispatcher, with the memory recorder attached.
 * The subject is what is written (the facts and the title only), that a failing write does not change the flow, that the Planner's
 * prompt is the same with the memory on, and that with the memory off nothing is written.
 */
class SelfDevMemoryFlowTest {

    private val docsA = "docs/a.md"
    private val docsB = "docs/b.md"
    private val goalBody = "GOAL-BODY-SENTINEL keep docs in sync"
    private val planSummary = "e2e plan"
    private val config = e2eConfig(AutopilotApproval.REQUIRED)

    private fun twoTaskModel() = ScriptedModel {
        planJson(
            PlanTask("Docs A", "TASK-BODY-SENTINEL update docs/a.md.", listOf(docsA)),
            PlanTask("Docs B", "Update docs/b.md after task 1.", listOf(docsB), dependsOn = listOf(1)),
        )
    }

    /** One memory row as stored (ACTIVE only). */
    private data class Row(val kind: String, val code: String?, val goalId: String?, val count: Int?, val body: String?, val identity: String?)

    private fun rows(db: TestDb): List<Row> = transaction(db.database) {
        execQuery(
            "SELECT kind, code, goal_id, count_value, body, identity_sha256 FROM selfdev_memory_records WHERE status = 'ACTIVE' ORDER BY created_at, record_id",
        ) { rs ->
            Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4)?.let { (it as Number).toInt() }, rs.getString(5), rs.getString(6))
        }
    }

    /** The whole happy path, over HTTP for the operator's actions: plan, approve the plan and both tasks, run both tasks to DONE. */
    private suspend fun completeTwoTaskGoal(world: E2eWorld, op: OperatorApi, key: String, title: String): String {
        val goalId = op.submit(title, goalBody, key).goalIdOrFail()
        world.plan()
        op.approvePlan(goalId, world.goal(goalId).version).assertOk()
        val planId = world.planId(goalId)
        op.approveTask(planId, 1, world.task(goalId, 1).version).assertOk()
        op.approveTask(planId, 2, world.task(goalId, 2).version).assertOk()
        world.tick()
        val first = world.runId(goalId, 1)
        world.openPullRequest(first, docsA)
        world.mergeAndComplete(first)
        world.tick()
        val second = world.runId(goalId, 2)
        world.openPullRequest(second, docsB)
        world.mergeAndComplete(second)
        world.tick()
        assertEquals(GoalState.DONE, world.goal(goalId).state)
        return goalId
    }

    @Test
    fun `a completed goal leaves its facts, and the goal's title is the only text in the memory`() = TestDb().use { db ->
        val recorder = SelfDevMemoryRecorder.of(SelfDevMemoryRepository(db.database, enabled = true))
        val world = E2eWorld(db, config, twoTaskModel(), memory = recorder)
        testApplication {
            operatorApp(world, e2eSettings(config))
            val goalId = completeTwoTaskGoal(world, OperatorApi(client), "key-facts", "Improve the docs")

            val all = rows(db)
            assertEquals(1, all.count { it.kind == "GOAL_TITLE" })
            assertEquals("Improve the docs", all.single { it.kind == "GOAL_TITLE" }.body)
            assertEquals(1, all.count { it.kind == "PLAN_FACT" && it.code == "PLAN_PROPOSED" && it.count == 2 })
            assertEquals(2, all.count { it.kind == "TASK_FACT" && it.code == "OPERATOR_APPROVED" })
            assertEquals(2, all.count { it.kind == "TASK_FACT" && it.code == "PR_MERGED" })
            assertEquals(2, all.count { it.kind == "RUN_FACT" && it.code == "PR_MERGED" })
            assertEquals(1, all.count { it.kind == "GOAL_FACT" && it.code == "ALL_TASKS_COMPLETED" })
            assertTrue(all.all { it.goalId == goalId })

            // Every fact has no text; the goal body, the plan summary and the task text are not in any row.
            assertTrue(all.filter { it.kind != "GOAL_TITLE" }.all { it.body == null })
            val everything = all.joinToString("\n") { listOfNotNull(it.body, it.code, it.identity).joinToString(" ") }
            assertTrue(!everything.contains("GOAL-BODY-SENTINEL"))
            assertTrue(!everything.contains("TASK-BODY-SENTINEL"))
            assertTrue(!everything.contains(planSummary))
            // The identity of a task is the hash of its paths, not the paths.
            val taskIdentities = all.filter { it.kind == "TASK_FACT" }.mapNotNull { it.identity }.toSet()
            assertEquals(setOf(identityOf(listOf(docsA)), identityOf(listOf(docsB))), taskIdentities)
            assertTrue(!everything.contains(docsA))
        }
    }

    @Test
    fun `a write that always fails changes nothing in the flow - the goal still reaches DONE, with the same states and runs`() = TestDb().use { db ->
        val failing = SelfDevMemoryRecorder.withStore { error("the memory database is down") }
        val world = E2eWorld(db, config, twoTaskModel(), memory = failing)
        testApplication {
            operatorApp(world, e2eSettings(config))
            val goalId = completeTwoTaskGoal(world, OperatorApi(client), "key-failing", "Improve the docs")
            assertEquals(listOf(TaskState.COMPLETED, TaskState.COMPLETED), world.tasks(goalId).states())
            assertEquals(2, world.runCount())
            assertEquals(0, db.count("selfdev_memory_records"))
        }
    }

    @Test
    fun `the Planner's prompt is the same with the memory on, and a recorded fact never reaches it`() {
        // The first goal is planned alone, so its PLAN_FACT is stored before the second goal is planned. The second goal's prompt is then
        // compared between the two worlds: it must be identical, and it must not contain anything the memory recorded.
        fun promptsOf(memory: SelfDevMemoryRecorder, db: TestDb): List<List<Pair<String, String>>> {
            val world = E2eWorld(db, config, twoTaskModel(), memory = memory)
            // The goals are submitted to the repository, as the route does; the route also records the title, so the recorder does it here.
            val first = world.goals.submit("op-e2e", "key-first", "SENTINEL-FIRST-TITLE", "first body", E2E_REPO) as SubmitResult.Created
            memory.goalTitle(first.goal.goalId, "SENTINEL-FIRST-TITLE", "op-e2e")
            runBlocking { world.plan() }
            val second = world.goals.submit("op-e2e", "key-second", "SECOND-TITLE", "second body", E2E_REPO) as SubmitResult.Created
            memory.goalTitle(second.goal.goalId, "SECOND-TITLE", "op-e2e")
            runBlocking { world.plan() }
            return world.model.calls.map { request ->
                request.messages.map { it.role.name to it.parts.filterIsInstance<Part.Text>().joinToString("") { p -> p.text } }
            }
        }
        var memoryOnPrompts: List<List<Pair<String, String>>> = emptyList()
        TestDb().use { on ->
            val repo = SelfDevMemoryRepository(on.database, enabled = true)
            memoryOnPrompts = promptsOf(SelfDevMemoryRecorder.of(repo), on)
            // The memory has the first plan's fact and the first goal's title, so the test is not vacuous.
            assertTrue(rows(on).any { it.kind == "GOAL_TITLE" && it.body == "SENTINEL-FIRST-TITLE" })
            assertTrue(rows(on).any { it.kind == "PLAN_FACT" })
        }
        val memoryOffPrompts = TestDb().use { off -> promptsOf(SelfDevMemoryRecorder.OFF, off) }
        assertEquals(memoryOffPrompts, memoryOnPrompts)
        // The second request is the second goal's: it does not carry the first goal's title, nor any record.
        val second = memoryOnPrompts[1].joinToString("\n") { it.second }
        assertTrue(!second.contains("SENTINEL-FIRST-TITLE"))
        assertTrue(!second.contains("first body"))
        assertNull(memoryOnPrompts.getOrNull(2))
    }

    @Test
    fun `an operator's rejection and a stop are recorded, with the goal they belong to`() = TestDb().use { db ->
        val world = E2eWorld(db, config, twoTaskModel(), memory = SelfDevMemoryRecorder.of(SelfDevMemoryRepository(db.database, enabled = true)))
        testApplication {
            operatorApp(world, e2eSettings(config))
            val op = OperatorApi(client)
            val goalId = op.submit("Reject and stop", goalBody, "key-reject").goalIdOrFail()
            world.plan()
            op.approvePlan(goalId, world.goal(goalId).version).assertOk()
            val planId = world.planId(goalId)
            op.rejectTask(planId, 2, world.task(goalId, 2).version).assertOk()

            val rejected = rows(db).single { it.code == "OPERATOR_SKIPPED" }
            assertEquals("TASK_FACT", rejected.kind)
            assertEquals(goalId, rejected.goalId)
            assertEquals(identityOf(listOf(docsB)), rejected.identity)

            op.stop(goalId, world.goal(goalId).version, "stop for the test").assertOk()
            val stopped = rows(db).single { it.code == "OPERATOR_STOPPED" }
            assertEquals("GOAL_FACT", stopped.kind)
            assertEquals(goalId, stopped.goalId)
        }
    }

    @Test
    fun `with the memory off, nothing is written and the flow is the same as before`() = TestDb().use { db ->
        val world = E2eWorld(db, config, twoTaskModel())
        testApplication {
            operatorApp(world, e2eSettings(config))
            val goalId = completeTwoTaskGoal(world, OperatorApi(client), "key-off", "Improve the docs")
            assertEquals(listOf(TaskState.COMPLETED, TaskState.COMPLETED), world.tasks(goalId).states())
        }
        assertEquals(0, db.count("selfdev_memory_records"))
        assertEquals(0, db.count("selfdev_memory_events"))
    }
}
