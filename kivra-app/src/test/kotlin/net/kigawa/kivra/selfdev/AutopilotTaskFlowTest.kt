package net.kigawa.kivra.selfdev

import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.db.PlanCommit
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostBasis
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelEntry
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Price
import net.kigawa.kivra.modelgateway.ProviderEntry
import net.kigawa.kivra.modelgateway.ProviderHealth
import net.kigawa.kivra.modelgateway.ProviderHealthState
import net.kigawa.kivra.modelgateway.ProviderModelInfo
import net.kigawa.kivra.modelgateway.QualityTier
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.Purpose
import kotlinx.coroutines.flow.Flow
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The autopilot's task flow on the V40 table (ADR 0011): planning, the approval rules, dispatch, the Run's outcome and the guards.
 * The model is a scripted fake (no network). The database is the real one (TestDb), and the Runs are the real Run repository.
 */
class AutopilotTaskFlowTest {

    /** Replies in order. An exhausted script throws, which the Planner reports as a failed call (PLAN_CALL_FAILED). */
    private class ScriptedProvider(override val providerId: String, private val replies: MutableList<String>) : ModelProvider {
        var calls = 0
        override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
        override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
        override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
            calls++
            val text = if (replies.isEmpty()) throw IllegalStateException("no scripted reply") else replies.removeAt(0)
            return ModelResponse(
                requestId = req.requestId, attemptId = ctx.attemptId,
                selected = ModelRef(providerId, PLANNER_MODEL_ID, "provider-haiku"),
                message = Message(Role.ASSISTANT, text), structured = null, finishReason = FinishReason.STOP,
                usage = Usage(1000, 500), cost = Cost(BigDecimal("0.01"), "USD", CostBasis.ESTIMATED),
                latencyMs = 5, routingDecisionId = ctx.routingDecisionId, usageComplete = true,
            )
        }
        override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> =
            throw UnsupportedOperationException("not used by this test")
    }

    private inner class Harness(val db: TestDb, replies: List<String> = emptyList()) {
        val ops = SelfDevOperatorRepository(db.database)
        val control = SelfDevAutopilotControlRepository(db.database)
        val provider = ScriptedProvider("anthropic", replies.toMutableList())
        val gateway = ModelGateway(
            ModelRegistry(
                "test-v1",
                listOf(
                    ProviderEntry(
                        "anthropic", "fake", TrustZone.EXTERNAL,
                        DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms"),
                        true, "https://ext.example.test", "EXT_KEY",
                    ),
                ),
                listOf(
                    ModelEntry(
                        PLANNER_MODEL_ID, "anthropic", "haiku", Lifecycle.ACTIVE, null,
                        setOf(Capability.TEXT, Capability.SYSTEM_PROMPT), 100_000, 4096,
                        listOf(Price(BigDecimal("3"), BigDecimal("15"))), QualityTier.A,
                    ),
                ),
            ),
            mapOf("anthropic" to provider),
            BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("100")))),
            ledger = net.kigawa.kivra.db.DbRequestLedger(db.database),
        )

        fun planner(approval: AutopilotApproval = AutopilotApproval.REQUIRED) = AutopilotPlanner(
            operations = ops,
            gateway = gateway,
            config = PlannerConfig(budgetScopes = listOf("tenant"), autopilot = autopilot(approval)),
        )

        fun dispatcher(maxActive: Int = 5, budget: Boolean = true, maxPendingPrs: Int = 3) = AutopilotDispatcher(
            database = db.database,
            runs = db.runs,
            config = autopilot(AutopilotApproval.REQUIRED, maxPendingPrs = maxPendingPrs),
            maxActiveSelfDev = maxActive,
            budgetAllows = { budget },
        )

        /** Every task of these tests belongs to one project (ADR 0022 PR-F: a task without one is refused). */
        val project: String = net.kigawa.kivra.db.ProjectRepository(db.database, java.time.Clock.systemUTC()).create("Autopilot", null).projectId

        fun submitRoot(key: String = "key-${System.nanoTime()}"): SelfDevTask =
            (ops.submitTask("operator", key, "Root task", "Improve the docs", "kigawa-net/kivra", project) as SelfDevSubmitResult.Created).task

        fun planned(rootId: String): List<SelfDevTask> = ops.listTasks(500).filter { it.parentTaskId == rootId }

        fun setRunState(runId: String, state: String) = transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", state, runId)
        }
    }

    private fun autopilot(approval: AutopilotApproval, maxPendingPrs: Int = 3) = AutopilotConfig(
        approval = approval, maxPendingPrs = maxPendingPrs, maxConsecutiveFailures = 3, maxRunsPerDay = 5, budgetSharePercent = 50,
    )

    private fun plan(vararg tasks: String) = """{"summary":"plan","tasks":[${tasks.joinToString(",")}]}"""

    private fun task(title: String, paths: String = "\"docs/a.md\"", size: String = "S", deps: String = "[]") =
        """{"title":"$title","body":"body of $title","targetPaths":[$paths],"sizeEstimate":"$size","dependsOn":$deps}"""

    @Test
    fun plannerWritesPlannedTasksAndTheRootWaitsForApproval() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("First"), task("Second", deps = "[1]"))))
        val root = h.submitRoot()
        val attempt = runBlocking { h.planner().planTask(root.taskId) }
        assertEquals(PlanAttempt.Proposed(taskCount = 2, queuedCount = 0), attempt)

        val stored = h.ops.task(root.taskId)!!
        assertEquals(TaskState.PENDING_APPROVAL, stored.state)
        assertEquals(1, stored.revision)
        assertTrue(stored.isPlanRoot)

        val children = h.planned(root.taskId).sortedBy { it.title }
        assertEquals(listOf("First", "Second"), children.map { it.title })
        assertTrue(children.all { it.state == TaskState.PENDING_APPROVAL && it.approvedBy == null && it.requiresApproval })
        assertEquals(listOf(children[0].taskId), children[1].dependencies)
        assertTrue(children[0].dependencies.isEmpty())
    }

    @Test
    fun autoApprovalQueuesOnlyTheTasksTheStageRulesAllow() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs", size = "S"), task("Code", paths = "\"kivra-app/src/main/kotlin/x.kt\""))))
        val root = h.submitRoot()
        val attempt = runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        assertEquals(PlanAttempt.Proposed(taskCount = 2, queuedCount = 1), attempt)

        val byTitle = h.planned(root.taskId).associateBy { it.title }
        assertEquals(TaskState.QUEUED, byTitle.getValue("Docs").state)
        assertEquals(AutopilotReason.AUTO_APPROVED, byTitle.getValue("Docs").stateReason)
        assertEquals(TaskState.PENDING_APPROVAL, byTitle.getValue("Code").state)
    }

    @Test
    fun anInvalidAnswerIsRetriedOnceThenBlocksAsPlanInvalid() = TestDb().use { db ->
        val h = Harness(db, listOf("not json", "still not json"))
        val root = h.submitRoot()
        val attempt = runBlocking { h.planner().planTask(root.taskId) }
        assertEquals(PlanAttempt.Blocked(AutopilotReason.PLAN_INVALID), attempt)
        assertEquals(2, h.provider.calls)

        val stored = h.ops.task(root.taskId)!!
        assertEquals(TaskState.BLOCKED, stored.state)
        assertEquals(AutopilotReason.PLAN_INVALID, stored.stateReason)
        assertEquals(0, stored.revision)
        assertTrue(h.planned(root.taskId).isEmpty())
    }

    @Test
    fun aFailedCallBlocksAsPlanCallFailedAndIsNotResent() = TestDb().use { db ->
        val h = Harness(db, emptyList())
        val root = h.submitRoot()
        val attempt = runBlocking { h.planner().planTask(root.taskId) }
        assertEquals(PlanAttempt.Blocked(AutopilotReason.PLAN_CALL_FAILED), attempt)
        assertEquals(1, h.provider.calls)
        assertEquals(AutopilotReason.PLAN_CALL_FAILED, h.ops.task(root.taskId)!!.stateReason)
    }

    @Test
    fun aStaleClaimCannotCommitAPlan() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.submitRoot()
        // A claim whose time is already over, then a re-claim: the first fence must not write anything.
        val old = assertNotNull(h.ops.claimForPlanning(root.taskId, "old-token", Duration.ofSeconds(-1))).fence
        assertEquals(listOf(root.taskId), h.ops.expireStalePlanClaims(10))
        assertEquals(TaskState.TASK_SUBMITTED, h.ops.task(root.taskId)!!.state)
        val fresh = assertNotNull(h.ops.claimForPlanning(root.taskId, "new-token", Duration.ofMinutes(10))).fence

        val tasks = listOf(
            net.kigawa.kivra.selfdev.NewTask("X", "x", listOf("docs/x.md"), "S", emptyList(), true),
        )
        assertEquals(PlanCommit.Stale, h.ops.commitPlan(old, tasks))
        assertTrue(h.planned(root.taskId).isEmpty())
        assertEquals(TaskState.PLANNING, h.ops.task(root.taskId)!!.state)

        assertTrue(h.ops.blockPlanning(fresh, AutopilotReason.PLAN_INVALID))
        assertEquals(TaskState.BLOCKED, h.ops.task(root.taskId)!!.state)
    }

    @Test
    fun aQueuedTaskIsSubmittedAsARunAndRecordsItsRun() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()

        val child = h.planned(root.taskId).single()
        assertEquals(TaskState.SUBMITTED, child.state)
        val runId = assertNotNull(child.runId)
        val run = assertNotNull(h.db.runs.get(RunId(runId)))
        assertTrue(run.taskId.startsWith("selfdev:"))
        assertEquals(child.title, run.taskTitle)
    }

    @Test
    fun aDependentTaskWaitsUntilItsDependencyIsCompleted() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("First"), task("Second", deps = "[1]"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val byTitle = h.planned(root.taskId).associateBy { it.title }
        assertEquals(TaskState.SUBMITTED, h.ops.task(byTitle.getValue("First").taskId)!!.state)
        assertEquals(TaskState.QUEUED, h.ops.task(byTitle.getValue("Second").taskId)!!.state)

        // The first task's merge is a fact of the Run path; here it is set directly to test the gate only.
        transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET state = 'COMPLETED' WHERE task_id = ?", byTitle.getValue("First").taskId) }
        h.dispatcher().tick()
        assertEquals(TaskState.SUBMITTED, h.ops.task(byTitle.getValue("Second").taskId)!!.state)
    }

    @Test
    fun aFailedRunFailsItsTaskAndCountsTowardTheBreaker() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        h.setRunState(child.runId!!, "FAILED")
        h.dispatcher().tick()

        val after = h.ops.task(child.taskId)!!
        assertEquals(TaskState.FAILED, after.state)
        assertEquals(AutopilotReason.RUN_FAILED, after.stateReason)
        assertEquals(1, h.control.read().consecutiveFailures)
    }

    @Test
    fun thePendingPullRequestLimitBlocksAndClearsTheTask() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        // One Run already waits for its pull request: the limit of 1 holds the task.
        val waiting = (db.runs.submitTask("operator", "pr-wait", "Waiting", selfDev = true, maxActiveSelfDev = null) as net.kigawa.kivra.db.TaskSubmitResult.Created).run
        h.setRunState(waiting.runId.value, "PULL_REQUEST")
        h.dispatcher(maxPendingPrs = 1).tick()
        val child = h.planned(root.taskId).single()
        assertEquals(TaskState.BLOCKED, child.state)
        assertEquals(AutopilotReason.PENDING_PR_LIMIT, child.stateReason)

        h.setRunState(waiting.runId.value, "FAILED")
        h.dispatcher(maxPendingPrs = 1).tick()
        assertEquals(TaskState.SUBMITTED, h.ops.task(child.taskId)!!.state)
    }

    @Test
    fun theBreakerBlocksUntilTheOperatorResetsIt() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = 1)
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        assertEquals(TaskState.BLOCKED, child.state)
        assertEquals(AutopilotReason.CIRCUIT_OPEN, child.stateReason)

        h.control.resetBreaker()
        h.dispatcher().tick()
        assertEquals(TaskState.SUBMITTED, h.ops.task(child.taskId)!!.state)
    }

    @Test
    fun aPausedAutopilotSubmitsNothingAndChangesNoState() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.control.setPaused(true, "operator")
        h.dispatcher().tick()
        assertEquals(TaskState.QUEUED, h.planned(root.taskId).single().state)
    }

    @Test
    fun aRunOverTheActiveLimitReturnsTheTaskToQueued() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        db.runs.submitTask("operator", "busy", "Busy", selfDev = true, maxActiveSelfDev = null)
        h.dispatcher(maxActive = 1).tick()
        val child = h.planned(root.taskId).single()
        assertEquals(TaskState.QUEUED, child.state)
        assertEquals(AutopilotReason.ACTIVE_RUN_LIMIT, child.stateReason)
    }

    @Test
    fun anOutOfScopeAutoTaskReturnsToPendingApprovalWithItsRun() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        PlanTaskAutoScopeGuard(h.ops).settle(RunId(child.runId!!))

        val after = h.ops.task(child.taskId)!!
        assertEquals(TaskState.PENDING_APPROVAL, after.state)
        assertEquals(AutopilotReason.AUTO_SCOPE_EXCEEDED, after.stateReason)
    }

    @Test
    fun theGuardJudgesAnAutoQueuedTaskUnderItsOwnApprovalMode() = TestDb().use { db ->
        // A stage 3 task outside the stage 2 scope: queued under full, dispatched, and re-checked against the real Run's task.
        val h = Harness(db, listOf(plan(task("Code", paths = "\"kivra-app/src/main/kotlin/x.kt\"", size = "L"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.FULL).planTask(root.taskId) }
        assertEquals(TaskState.QUEUED, h.planned(root.taskId).single().state)
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        assertEquals(TaskState.SUBMITTED, child.state)
        val runId = RunId(child.runId!!)
        val inside = PatchSet(listOf(Write("kivra-app/src/main/kotlin/x.kt", "x\n")), "summary")
        val denied = PatchSet(listOf(Write("kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt", "x\n")), "summary")

        assertEquals(AutoScopeVerdict.InScope, PlanTaskAutoScopeGuard(h.ops, AutopilotApproval.FULL).check(runId, inside) { null })
        assertEquals(AutoScopeVerdict.OutOfScope(1, 1), PlanTaskAutoScopeGuard(h.ops, AutopilotApproval.FULL).check(runId, denied) { null })
        // The same Run under the stage 2 rule is out of scope: the path is not docs, test sources or README.
        assertEquals(AutoScopeVerdict.OutOfScope(1, 1), PlanTaskAutoScopeGuard(h.ops, AutopilotApproval.AUTO).check(runId, inside) { null })
    }

    @Test
    fun theOperatorApprovesAPlannedTaskAndItIsDispatched() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Code", paths = "\"kivra-app/src/main/kotlin/x.kt\""))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.REQUIRED).planTask(root.taskId) }
        val child = h.planned(root.taskId).single()
        assertEquals(TaskState.PENDING_APPROVAL, child.state)
        h.dispatcher().tick()
        assertEquals(TaskState.PENDING_APPROVAL, h.ops.task(child.taskId)!!.state)

        assertTrue(h.ops.approveTask(child.taskId, child.version, "operator") is TaskTransition.Moved)
        h.dispatcher().tick()
        val after = h.ops.task(child.taskId)!!
        assertEquals(TaskState.SUBMITTED, after.state)
        assertEquals("operator", after.approvedBy)
    }

    @Test
    fun aPlanRootIsNotApprovedAsATask() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner().planTask(root.taskId) }
        val planned: SelfDevTask = h.ops.task(root.taskId)!!
        assertEquals(TaskState.PENDING_APPROVAL, planned.state)
        assertTrue(h.ops.approveTask(root.taskId, planned.version, "operator") is TaskTransition.Refused)
        assertTrue(h.ops.skipTask(root.taskId, planned.version) is TaskTransition.Refused)
    }

    @Test
    fun stoppingTheRootSkipsItsUnsubmittedPlannedTasks() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"), task("More", deps = "[1]"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        val current = h.ops.task(root.taskId)!!
        assertTrue(h.ops.stopTask(root.taskId, current.version) is TaskTransition.Moved)

        val children = h.planned(root.taskId)
        assertTrue(children.all { it.state == TaskState.SKIPPED && it.stateReason == AutopilotReason.TASK_STOPPED })
        assertNull(children.single { it.title == "More" }.runId)
    }

    @Test
    fun aPlanRootIsDoneWhenEveryPlannedTaskCompletes() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        h.setRunState(child.runId!!, "COMPLETED")
        h.dispatcher().tick()

        assertEquals(TaskState.COMPLETED, h.ops.task(child.taskId)!!.state)
        val settled = h.ops.task(root.taskId)!!
        assertEquals(TaskState.DONE, settled.state)
        assertEquals(AutopilotReason.ALL_TASKS_COMPLETED, settled.stateReason)
    }

    @Test
    fun aPlanRootStopsWithTasksIncompleteWhenAPlannedTaskFails() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        h.setRunState(child.runId!!, "FAILED")
        h.dispatcher().tick()

        assertEquals(AutopilotReason.RUN_FAILED, h.ops.task(child.taskId)!!.stateReason)
        val settled = h.ops.task(root.taskId)!!
        assertEquals(TaskState.STOPPED, settled.state)
        assertEquals(AutopilotReason.TASKS_INCOMPLETE, settled.stateReason)
    }

    @Test
    fun aPolicyRefusedRunFailsItsTaskAsPolicyRejectedAndCountsInThePromotionReadout() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.REQUIRED).planTask(root.taskId) }
        val child = h.planned(root.taskId).single()
        assertTrue(h.ops.approveTask(child.taskId, child.version, "operator") is TaskTransition.Moved)
        h.dispatcher().tick()
        val submitted = h.ops.task(child.taskId)!!
        assertEquals(TaskState.SUBMITTED, submitted.state)
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = 'FAILED', state_reason = 'POLICY_REJECTED' WHERE run_id = ?", submitted.runId)
        }
        h.dispatcher().tick()

        val after = h.ops.task(child.taskId)!!
        assertEquals(TaskState.FAILED, after.state)
        assertEquals(AutopilotReason.POLICY_REJECTED, after.stateReason)
        assertEquals(1L, h.ops.promotionCounts().approvedPolicyRejected)
    }

    @Test
    fun aRunOutcomeCountsOnlyWhenItsMoveHappens() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()

        val stale = h.ops.settleRunOutcome(child.taskId, child.version + 5, TaskState.FAILED, AutopilotReason.RUN_FAILED, false, 3)
        assertEquals(TaskTransition.StaleVersion, stale)
        assertEquals(0, h.control.read().consecutiveFailures)
        assertEquals(TaskState.SUBMITTED, h.ops.task(child.taskId)!!.state)

        val moved = h.ops.settleRunOutcome(child.taskId, child.version, TaskState.FAILED, AutopilotReason.RUN_FAILED, false, 3)
        assertTrue(moved is TaskTransition.Moved)
        assertEquals(1, h.control.read().consecutiveFailures)
    }

    @Test
    fun aPlanLeftUnsettledIsSettledByTheNextCatchUp() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        val child = h.planned(root.taskId).single()
        // The child ended, but the root's settlement did not run (as if the process stopped between the two writes).
        transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET state = 'COMPLETED' WHERE task_id = ?", child.taskId) }
        assertEquals(TaskState.PENDING_APPROVAL, h.ops.task(root.taskId)!!.state)

        assertEquals(1, h.ops.settleFinishedPlans(10))
        assertEquals(TaskState.DONE, h.ops.task(root.taskId)!!.state)
        assertEquals(0, h.ops.settleFinishedPlans(10))
    }

    @Test
    fun aStoppedPlanDoesNotRequeueItsFailedTask() = TestDb().use { db ->
        val h = Harness(db, listOf(plan(task("Docs"))))
        val root = h.submitRoot()
        runBlocking { h.planner(AutopilotApproval.AUTO).planTask(root.taskId) }
        h.dispatcher().tick()
        val child = h.planned(root.taskId).single()
        h.setRunState(child.runId!!, "FAILED")
        h.dispatcher().tick()
        assertEquals(TaskState.STOPPED, h.ops.task(root.taskId)!!.state)

        val failed = h.ops.task(child.taskId)!!
        val requeue = h.ops.moveTask(child.taskId, failed.version, TaskState.QUEUED, AutopilotReason.OPERATOR_REQUEUED)
        assertEquals(TaskTransition.Refused(TransitionRefusal.GUARD_FAILED), requeue)
    }
}
