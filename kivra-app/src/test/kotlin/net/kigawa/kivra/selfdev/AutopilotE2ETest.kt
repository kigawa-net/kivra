package net.kigawa.kivra.selfdev

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.TestDb

/**
 * The autopilot's happy path end to end (ADR 0011 sections 1, 3, 5, 6.1): a goal goes in over HTTP, the Planner plans it, the operator
 * approves the plan and the tasks over HTTP, the Dispatcher submits one task at a time, and a human merges each pull request. The
 * second task depends on the first, so it is submitted only after the first Run is COMPLETED.
 */
class AutopilotE2ETest {

    private val docsA = "docs/a.md"
    private val docsB = "docs/b.md"
    private val migration = "kivra-app/src/main/kotlin/net/kigawa/kivra/db/migration/Thing.kt"

    @Test
    fun `required - a goal goes from HTTP to DONE, one task at a time, and each task waits for its merged pull request`() =
        TestDb().use { db ->
            val model = ScriptedModel {
                planJson(
                    PlanTask("Docs A", "Update docs/a.md.", listOf(docsA)),
                    PlanTask("Docs B", "Update docs/b.md after task 1.", listOf(docsB), dependsOn = listOf(1)),
                )
            }
            val config = e2eConfig(AutopilotApproval.REQUIRED)
            val world = E2eWorld(db, config, model)
            testApplication {
                operatorApp(world, e2eSettings(config))
                val op = OperatorApi(client)

                // 1. The goal comes in over HTTP. The Planner plans it; the plan waits for the operator.
                val goalId = op.submit("Improve the docs", "Keep docs/a.md and docs/b.md in sync.", "key-required").goalIdOrFail()
                world.plan()
                assertEquals(GoalState.PLAN_PROPOSED, world.goal(goalId).state)
                assertEquals(listOf(TaskState.PENDING_APPROVAL, TaskState.PENDING_APPROVAL), world.tasks(goalId).states())
                assertEquals(listOf(1), world.task(goalId, 2).dependsOn)
                assertEquals(1, model.calls.size)

                // 2. A proposed plan is not dispatched.
                world.tick()
                assertEquals(0, world.runCount())

                // 3. The operator approves the plan. The tasks still wait: a plan approval does not approve a task.
                op.approvePlan(goalId, world.goal(goalId).version).assertOk()
                assertEquals(GoalState.TASKS_QUEUED, world.goal(goalId).state)
                world.tick()
                assertEquals(0, world.runCount())

                // 4. The operator approves both tasks. Only the first one is dispatched, because the second depends on it.
                val planId = world.planId(goalId)
                op.approveTask(planId, 1, world.task(goalId, 1).version).assertOk()
                op.approveTask(planId, 2, world.task(goalId, 2).version).assertOk()
                world.tick()
                assertEquals(1, world.runCount())
                assertEquals(TaskState.SUBMITTED, world.task(goalId, 1).state)
                assertEquals(TaskState.QUEUED, world.task(goalId, 2).state)
                assertEquals(GoalState.RUNNING, world.goal(goalId).state)
                val firstRun = world.runId(goalId, 1)
                assertEquals("op-e2e", db.runs.get(firstRun)!!.principal)

                // 5. The Run opens its pull request. While it is open, the second task is not dispatched.
                world.openPullRequest(firstRun, docsA)
                world.tick()
                assertEquals(1, world.runCount())
                assertEquals(TaskState.SUBMITTED, world.task(goalId, 1).state)
                assertEquals(TaskState.QUEUED, world.task(goalId, 2).state)

                // 6. A human merges it. The next tick completes task 1 and dispatches task 2 (a second Run).
                world.mergeAndComplete(firstRun)
                world.tick()
                assertEquals(TaskState.COMPLETED, world.task(goalId, 1).state)
                assertEquals(TaskState.SUBMITTED, world.task(goalId, 2).state)
                assertEquals(2, world.runCount())
                assertEquals(GoalState.RUNNING, world.goal(goalId).state)

                // 7. The second pull request is merged. The goal is DONE.
                val secondRun = world.runId(goalId, 2)
                world.openPullRequest(secondRun, docsB)
                world.mergeAndComplete(secondRun)
                world.tick()
                assertEquals(GoalState.DONE, world.goal(goalId).state)
                assertEquals(listOf(TaskState.COMPLETED, TaskState.COMPLETED), world.tasks(goalId).states())
                assertEquals(DevelopmentRunState.COMPLETED, world.runState(secondRun))

                // Kivra never merged anything itself, and the developer model was never called (the CLI runtime).
                assertEquals(0, world.developer.callCount)
                assertEquals(2, world.runCount())
                assertEquals(1, model.calls.size)
            }
        }

    @Test
    fun `auto - low-risk tasks run without a task approval, and a denylisted task waits for the operator`() =
        TestDb().use { db ->
            val model = ScriptedModel {
                planJson(
                    PlanTask("Docs A", "Update docs/a.md.", listOf(docsA)),
                    PlanTask("Test B", "Adjust the test after task 1.", listOf("kivra-core/src/test/kotlin/FooTest.kt"), dependsOn = listOf(1)),
                    // Denylisted by the planner (ADR 0011 section 4: **/db/migration/**). PatchPolicy allows it, so the Run can complete.
                    PlanTask("Migration change", "Change the migration object.", listOf(migration)),
                )
            }
            val config = e2eConfig(AutopilotApproval.AUTO)
            val world = E2eWorld(db, config, model)
            testApplication {
                operatorApp(world, e2eSettings(config))
                val op = OperatorApi(client)

                val goalId = op.submit("Auto goal", null, "key-auto").goalIdOrFail()
                world.plan()
                assertEquals(GoalState.PLAN_PROPOSED, world.goal(goalId).state)

                // The Planner marks the stage-2 tasks QUEUED. The denylisted one (auth) stays PENDING and is flagged.
                val planId = world.planId(goalId)
                assertEquals(
                    listOf(TaskState.QUEUED, TaskState.QUEUED, TaskState.PENDING_APPROVAL),
                    world.tasks(goalId).states(),
                )
                assertEquals(listOf(false, false, true), world.tasks(goalId).map { it.requiresApproval })

                // The plan approval is the only approval the goal needs for the low-risk tasks.
                op.approvePlan(goalId, world.goal(goalId).version).assertOk()
                world.tick()
                assertEquals(1, world.runCount())
                assertEquals(TaskState.SUBMITTED, world.task(goalId, 1).state)

                val firstRun = world.runId(goalId, 1)
                world.openPullRequest(firstRun, docsA)
                world.mergeAndComplete(firstRun)
                world.tick()
                assertEquals(TaskState.COMPLETED, world.task(goalId, 1).state)
                assertEquals(TaskState.SUBMITTED, world.task(goalId, 2).state)
                assertEquals(2, world.runCount())

                val secondRun = world.runId(goalId, 2)
                world.openPullRequest(secondRun, "kivra-core/src/test/kotlin/FooTest.kt")
                world.mergeAndComplete(secondRun)
                world.tick()
                assertEquals(TaskState.COMPLETED, world.task(goalId, 2).state)

                // Only the denylisted task is left. It is never auto-approved, however many ticks run.
                repeat(3) { world.tick() }
                assertEquals(TaskState.PENDING_APPROVAL, world.task(goalId, 3).state)
                assertEquals(2, world.runCount())
                assertEquals(GoalState.RUNNING, world.goal(goalId).state)

                // The operator approves it on its own. It is dispatched, and the goal finishes after its merge.
                op.approveTask(planId, 3, world.task(goalId, 3).version).assertOk()
                world.tick()
                assertEquals(3, world.runCount())
                val thirdRun = world.runId(goalId, 3)
                world.openPullRequest(thirdRun, migration)
                world.mergeAndComplete(thirdRun)
                world.tick()
                assertEquals(GoalState.DONE, world.goal(goalId).state)
                assertEquals(0, world.developer.callCount)
                assertEquals(HttpStatusCode.OK, op.status().status)
            }
        }
}
