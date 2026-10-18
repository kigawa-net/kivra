package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.ClaimResult
import net.kigawa.kivra.db.CommitResult
import net.kigawa.kivra.db.GoalClaim
import net.kigawa.kivra.db.NewPlan
import net.kigawa.kivra.db.NewTask
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import org.slf4j.LoggerFactory

private const val OPERATOR = "op-a"
private const val SECOND_OPERATOR = "op-b"
private const val REPO = "kigawa-net/kivra"
private const val MARKER = "MARKER"
private val START: Instant = Instant.parse("2026-10-19T10:00:00Z")

/** The planner's view of a stage-2 task: S, a docs or test path, QUEUED at storage (ADR 0011 section 6.1). */
private fun autoTask(title: String, path: String = "docs/a.md") = NewTask(
    title = "$MARKER-$title",
    body = "$MARKER-body",
    targetPaths = listOf(path),
    sizeEstimate = "S",
    dependsOn = emptyList(),
    requiresApproval = false,
    initialState = TaskState.QUEUED,
)

private fun writes(vararg files: Pair<String, String>) = PatchSet(files.map { (p, c) -> Write(p, c) }, "summary")

private class RecheckClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** The rule of ADR 0011 section 6.2 on a change set, without a database. */
class AutoScopeRuleTest {
    private val noSize: (String) -> Long? = { null }

    @Test
    fun `docs, kivra test sources and README are in scope`() {
        val verdict = autoScopeOf(writes("docs/a.md" to "x\n", "kivra-core/src/test/kotlin/T.kt" to "y\n", "README.md" to "z\n"), noSize)
        assertEquals(AutoScopeVerdict.InScope, verdict)
    }

    @Test
    fun `a path outside the stage-2 allowlist is out of scope, even inside kivra main sources`() {
        assertEquals(
            AutoScopeVerdict.OutOfScope(paths = 1, lines = 2),
            autoScopeOf(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n"), noSize),
        )
    }

    @Test
    fun `a denylisted path inside an allowed test source is out of scope`() {
        assertEquals(
            AutoScopeVerdict.OutOfScope(paths = 1, lines = 1),
            autoScopeOf(writes("kivra-app/src/test/kotlin/net/kigawa/kivra/auth/Hook.kt" to "x\n"), noSize),
        )
    }

    @Test
    fun `more than three distinct paths is out of scope, and three is still in`() {
        val three = writes("docs/a.md" to "1\n", "docs/b.md" to "2\n", "docs/c.md" to "3\n")
        assertEquals(AutoScopeVerdict.InScope, autoScopeOf(three, noSize))
        val four = writes("docs/a.md" to "1\n", "docs/b.md" to "2\n", "docs/c.md" to "3\n", "docs/d.md" to "4\n")
        assertEquals(AutoScopeVerdict.OutOfScope(paths = 4, lines = 4), autoScopeOf(four, noSize))
    }

    @Test
    fun `changed lines are capped at 200, and 200 is still in`() {
        assertEquals(AutoScopeVerdict.InScope, autoScopeOf(writes("docs/a.md" to "x\n".repeat(200)), noSize))
        assertEquals(
            AutoScopeVerdict.OutOfScope(paths = 1, lines = 201),
            autoScopeOf(writes("docs/a.md" to "x\n".repeat(201)), noSize),
        )
    }

    @Test
    fun `a delete counts the bytes of the deleted file as an upper bound on its lines`() {
        assertEquals(AutoScopeVerdict.InScope, autoScopeOf(PatchSet(listOf(Delete("docs/a.md")), "s")) { 200L })
        assertEquals(
            AutoScopeVerdict.OutOfScope(paths = 1, lines = 201),
            autoScopeOf(PatchSet(listOf(Delete("docs/a.md")), "s")) { 201L },
        )
    }
}

/**
 * The re-check in the runner (ADR 0011 section 6.2): an auto-approved task whose real diff is out of scope ends its Run as a policy
 * rejection, moves back to the operator, and is not counted. H2 by default, as the dispatcher tests.
 */
class AutoScopeRecheckTest {

    /** A coding source that answers with a fixed change set. */
    private class FixedProposal(var next: CodingProposal) : CodingPatchSource {
        override fun proposal(run: net.kigawa.kivra.db.DevelopmentRun, baseSha: String): CodingProposal = next
    }

    private val files = mapOf(
        "README.md" to "# kivra\n",
        "docs/a.md" to "old text\n",
        "docs/b.md" to "old\n",
        "docs/c.md" to "old\n",
        "docs/d.md" to "old\n",
        "kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n",
        "kivra-app/src/test/kotlin/net/kigawa/kivra/auth/Hook.kt" to "fun h() = Unit\n",
    )

    private inner class Env(val db: TestDb, approval: AutopilotApproval = AutopilotApproval.AUTO) {
        val clock = RecheckClock(START)
        val cfg = AutopilotConfig(approval, 3, 3, 5, 50)
        val goals = SelfDevGoalRepository(db.database, clock)
        val plans = SelfDevPlanRepository(db.database, clock)
        val operator = SelfDevOperatorRepository(db.database, clock)
        val control = SelfDevAutopilotControlRepository(db.database, clock)
        val github = FakeGitHub()

        fun tick() = AutopilotDispatcher(db.database, db.runs, cfg, 10, { true }, clock).tick()

        fun runner(source: CodingPatchSource, guard: AutoScopeGuard? = PlanTaskAutoScopeGuard(plans)) = SelfDevRunner(
            config = SelfDevConfig(enabled = true, runtime = SelfDevRuntime.CLI),
            runs = DbDevelopmentRunStore(db.runs),
            leases = db.leases,
            developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "from the model\n")),
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
            clock = clock,
            coding = source,
            autoScope = guard,
        )

        /** A goal in TASKS_QUEUED with one plan. [approve] lists ordinals the operator approves (for a PENDING_APPROVAL task). */
        fun goal(key: String, tasks: List<NewTask>, approve: List<Int> = emptyList()): Pair<String, String> {
            val created = assertIs<SubmitResult.Created>(goals.submit(OPERATOR, key, "$MARKER-goal-$key", "$MARKER-goal-body", REPO)).goal
            val claimed = assertIs<ClaimResult.Claimed>(goals.claim(created.goalId, java.time.Duration.ofSeconds(60))).goal
            val committed = assertIs<CommitResult.Committed>(
                goals.commitPlan(
                    GoalClaim(created.goalId, claimed.version, claimed.claimToken!!),
                    NewPlan(summary = "summary", plannerModel = "claude-haiku-5-5", outputSha256 = "a".repeat(64), requestLedgerId = null),
                    tasks,
                ),
            )
            val planId = committed.plan.planId
            assertIs<Transition.Moved<SelfDevGoal>>(goals.transition(created.goalId, goal(created.goalId).version, GoalState.TASKS_QUEUED, null))
            for (ordinal in approve) {
                assertIs<Transition.Moved<SelfDevPlanTask>>(operator.approveTask(planId, ordinal, task(planId, ordinal).version, OPERATOR))
            }
            return created.goalId to planId
        }

        /** Drives the Run from CREATED to its first outcome: one advance to DEVELOPING, then the verdict. */
        suspend fun advanceToOutcome(runner: SelfDevRunner, runId: RunId): AdvanceResult {
            runner.advance(runId)
            return runner.advance(runId)
        }

        fun goal(goalId: String): SelfDevGoal = goals.get(goalId)!!
        fun task(planId: String, ordinal: Int): SelfDevPlanTask = plans.task(planId, ordinal)!!
        fun runOf(planId: String, ordinal: Int): RunId = RunId(task(planId, ordinal).runId!!)
    }

    private inline fun captureLogs(block: () -> Unit): List<String> {
        val appLogger = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); appLogger.addAppender(it) }
        val oldLevel = appLogger.level
        appLogger.level = Level.TRACE
        try {
            block()
        } finally {
            appLogger.detachAppender(appender)
            appLogger.level = oldLevel
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `(a) an auto-approved task whose diff is in scope is committed and opens a pull request`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (_, planId) = env.goal("in-scope", listOf(autoTask("docs")))
            env.tick()
            val runId = env.runOf(planId, 1)
            val outcome = env.advanceToOutcome(env.runner(FixedProposal(CodingProposal.Ready(writes("docs/a.md" to "new text\n")))), runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), outcome)
            assertTrue("commit" in env.github.writes)
            assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
        }
    }

    @Test
    fun `(b) an auto task whose diff leaves the scope is not committed, goes back to the operator, and is not counted`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (goalId, planId) = env.goal("out-of-scope", listOf(autoTask("docs")))
            env.tick()
            val runId = env.runOf(planId, 1)
            val source = FixedProposal(CodingProposal.Ready(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = 1\n")))
            val logs = captureLogs {
                val outcome = env.advanceToOutcome(env.runner(source), runId)
                assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), outcome)
                env.tick()
            }
            // No write reached GitHub: not a branch, not a commit, not a pull request.
            assertTrue(env.github.writes.isEmpty())
            val task = env.task(planId, 1)
            assertEquals(TaskState.PENDING_APPROVAL, task.state)
            assertEquals(AutopilotReason.AUTO_SCOPE_EXCEEDED, task.stateReason)
            assertEquals(GoalState.BLOCKED, env.goal(goalId).state)
            assertEquals(AutopilotReason.AUTO_SCOPE_EXCEEDED, env.goal(goalId).stateReason)
            // The run failed as a policy rejection, but the breaker did not count it, and the task was not approved again.
            assertEquals("POLICY_REJECTED", db.runs.get(runId)!!.stateReason)
            assertEquals(0, env.control.read().consecutiveFailures)
            assertTrue(logs.any { it.contains("AUTO_SCOPE_EXCEEDED") })
        }
    }

    @Test
    fun `(c) more than three paths is out of scope and is not committed`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (_, planId) = env.goal("four-paths", listOf(autoTask("docs")))
            env.tick()
            val four = writes("docs/a.md" to "1\n", "docs/b.md" to "2\n", "docs/c.md" to "3\n", "docs/d.md" to "4\n")
            val outcome = env.advanceToOutcome(env.runner(FixedProposal(CodingProposal.Ready(four))), env.runOf(planId, 1))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), outcome)
            assertTrue(env.github.writes.isEmpty())
            assertEquals(AutopilotReason.AUTO_SCOPE_EXCEEDED, env.task(planId, 1).stateReason)
        }
    }

    @Test
    fun `(c2) more than 200 changed lines is out of scope and is not committed`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (_, planId) = env.goal("long", listOf(autoTask("docs")))
            env.tick()
            val outcome = env.advanceToOutcome(
                env.runner(FixedProposal(CodingProposal.Ready(writes("docs/a.md" to "line\n".repeat(201))))),
                env.runOf(planId, 1),
            )
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), outcome)
            assertTrue(env.github.writes.isEmpty())
        }
    }

    @Test
    fun `(d) a denylisted path inside an allowed test source is not committed, even though PatchPolicy accepts it`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (_, planId) = env.goal("denied", listOf(autoTask("test", path = "kivra-app/src/test/kotlin/net/kigawa/kivra/auth/Hook.kt")))
            env.tick()
            val source = FixedProposal(CodingProposal.Ready(writes("kivra-app/src/test/kotlin/net/kigawa/kivra/auth/Hook.kt" to "fun h() = 1\n")))
            val outcome = env.advanceToOutcome(env.runner(source), env.runOf(planId, 1))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), outcome)
            assertTrue(env.github.writes.isEmpty())
            assertEquals(AutopilotReason.AUTO_SCOPE_EXCEEDED, env.task(planId, 1).stateReason)
        }
    }

    @Test
    fun `(e) a task the operator approved is not re-checked, so its out-of-scope diff is committed as the operator allowed`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db, approval = AutopilotApproval.REQUIRED)
            val task = NewTask(
                title = "$MARKER-approved", body = "$MARKER-body", targetPaths = listOf("docs/a.md"), sizeEstimate = "S",
                dependsOn = emptyList(), requiresApproval = false,
            )
            val (_, planId) = env.goal("approved", listOf(task), approve = listOf(1))
            env.tick()
            assertEquals(OPERATOR, env.task(planId, 1).approvedBy)
            val source = FixedProposal(CodingProposal.Ready(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = 1\n")))
            val outcome = env.advanceToOutcome(env.runner(source), env.runOf(planId, 1))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), outcome)
            assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
        }
    }

    @Test
    fun `(f) a denylisted (requires_approval) task is outside the re-check, so the guard does not look at it`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db, approval = AutopilotApproval.REQUIRED)
            val task = NewTask(
                title = "$MARKER-required", body = "$MARKER-body", targetPaths = listOf("kivra-app/src/test/kotlin/net/kigawa/kivra/auth/Hook.kt"),
                sizeEstimate = "S", dependsOn = emptyList(), requiresApproval = true,
            )
            val (_, planId) = env.goal("required", listOf(task), approve = listOf(1))
            env.tick()
            val runId = env.runOf(planId, 1)
            assertNull(env.plans.autoTaskOfRun(runId.value))
            val outcome = env.advanceToOutcome(
                env.runner(FixedProposal(CodingProposal.Ready(writes("kivra-app/src/test/kotlin/net/kigawa/kivra/auth/Hook.kt" to "fun h() = 1\n")))),
                runId,
            )
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), outcome)
        }
    }

    @Test
    fun `(g) the scope re-check is not a failure, so no breaker count`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (_, planId) = env.goal("counts", listOf(autoTask("docs")))
            env.tick()
            env.advanceToOutcome(
                env.runner(FixedProposal(CodingProposal.Ready(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n")))),
                env.runOf(planId, 1),
            )
            env.tick()
            val control = env.control.read()
            assertEquals(0, control.consecutiveFailures)
            assertFalse(control.breakerOpen)
        }
    }

    @Test
    fun `(h) the logs carry codes and counts, never the path or the content`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val secretPath = "kivra-app/src/main/kotlin/net/kigawa/kivra/${MARKER}_DIR/Foo.kt"
            val (_, planId) = env.goal("logs", listOf(autoTask("docs")))
            val logs = captureLogs {
                env.tick()
                env.advanceToOutcome(
                    env.runner(FixedProposal(CodingProposal.Ready(writes(secretPath to "$MARKER-CONTENT\n")))),
                    env.runOf(planId, 1),
                )
                env.tick()
            }
            assertTrue(logs.any { it.contains("AUTO_SCOPE_EXCEEDED") }, "the fixed code is logged")
            assertTrue(logs.none { it.contains(MARKER) }, "no path and no content in the logs: $logs")
            assertTrue(logs.none { it.contains("kivra-app/") }, "no path in the logs")
        }
    }

    @Test
    fun `a task that was sent back is not auto-approved again while its planned paths look eligible`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (goalId, planId) = env.goal("no-loop", listOf(autoTask("docs")))
            env.tick()
            env.advanceToOutcome(
                env.runner(FixedProposal(CodingProposal.Ready(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n")))),
                env.runOf(planId, 1),
            )
            repeat(3) { env.tick() }
            assertEquals(TaskState.PENDING_APPROVAL, env.task(planId, 1).state)
            assertEquals(GoalState.BLOCKED, env.goal(goalId).state)
            assertEquals(1, db.count("development_runs"), "no second run for the task")
        }
    }

    @Test
    fun `(i) after the operator approves the task again it runs with a new key, and the goal resumes`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (goalId, planId) = env.goal("reapprove", listOf(autoTask("docs")))
            env.tick()
            val firstRun = env.runOf(planId, 1)
            env.advanceToOutcome(
                env.runner(FixedProposal(CodingProposal.Ready(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n")))),
                firstRun,
            )
            val sent = env.task(planId, 1)
            assertIs<Transition.Moved<SelfDevPlanTask>>(env.operator.approveTask(planId, 1, sent.version, SECOND_OPERATOR))
            assertNotEquals(sent.idempotencyKey, env.task(planId, 1).idempotencyKey, "a new key, so the old Run is not replayed")

            env.tick()
            val second = env.task(planId, 1)
            assertEquals(TaskState.SUBMITTED, second.state)
            assertNotEquals(firstRun.value, second.runId, "a new run")
            assertEquals(SECOND_OPERATOR, db.runs.get(RunId(second.runId!!))!!.principal)
            assertEquals(2, db.count("development_runs"))
            assertEquals(GoalState.RUNNING, env.goal(goalId).state)
            assertNull(env.goal(goalId).stateReason)
        }
    }

    @Test
    fun `a run that is not an auto-approved task is not checked at all`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val run = db.runs.create("plain-task", "test", RunCompletionMode.PR_ONLY).runId
            val outcome = env.advanceToOutcome(
                env.runner(FixedProposal(CodingProposal.Ready(writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n")))),
                run,
            )
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), outcome)
        }
    }

    @Test
    fun `the settle step is idempotent, so a retried run writes the same state`() = runBlocking {
        TestDb().use { db ->
            val env = Env(db)
            val (_, planId) = env.goal("settle-twice", listOf(autoTask("docs")))
            env.tick()
            val runId = env.runOf(planId, 1)
            val guard = PlanTaskAutoScopeGuard(env.plans)
            assertIs<AutoScopeVerdict.OutOfScope>(
                guard.check(runId, writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n")) { null },
            )
            guard.settle(runId)
            // A retry of the same run sees the task already sent back, and gets the same verdict.
            assertIs<AutoScopeVerdict.OutOfScope>(
                guard.check(runId, writes("kivra-app/src/main/kotlin/net/kigawa/kivra/Foo.kt" to "fun a() = Unit\n// grown\n")) { null },
            )
            guard.settle(runId)
            assertEquals(TaskState.PENDING_APPROVAL, env.task(planId, 1).state)
            assertEquals(AutopilotReason.AUTO_SCOPE_EXCEEDED, env.task(planId, 1).stateReason)
        }
    }
}
