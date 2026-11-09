package net.kigawa.kivra.coding

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.modelgateway.BudgetDenial
import org.jetbrains.exposed.sql.transactions.transaction

private const val AGENT = "agent-1"

/** 0.004268 USD at 150 yen per USD with the 10% and 10% margins (the same as CodingCostLedgerTest's USAGE). */
private val USAGE = CliUsage(BigDecimal("0.004268"), "claude-haiku-5-5", 4, 1200, 300, 5000, 800)
private val REPORTED_YEN = BigDecimal("0.774642")

/** The reserve of [costFixture] (0.50 USD): 90.750000 yen. */
private val RESERVE_YEN = BigDecimal("90.750000")

/**
 * The project scope of the CLI cost (ADR 0022, PR-E): a claim is refused for the Run's project only when that project's limit cannot hold a
 * reserve, a cost is booked to the Run's project as well as the tenant, and a Run without a project is booked to the tenant only.
 */
class CodingProjectBudgetTest {
    private val baseSha = "a".repeat(40)

    private inner class Harness(val db: TestDb, hard: String = "10000") {
        val fixture = costFixture(db, hard = hard)
        val repo = CodingAttemptRepository(db.database)
        val projects = ProjectRepository(db.database, COST_CLOCK)
        val service = CodingRuntimeService(
            attempts = repo,
            runs = DbCodingRunPort(db.runs, db.leases),
            results = DbCodingResultStore(db.database),
            tasks = CodingTaskSource { it.taskTitle },
            cost = fixture.ledger,
        )

        /** A DEVELOPING Run, in [projectId] when given. */
        fun run(projectId: String?): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            transaction(db.database) { execUpdate("UPDATE development_runs SET task_title = ? WHERE run_id = ?", "TITLE", runId) }
            db.forceState(runId, DevelopmentRunState.DEVELOPING)
            if (projectId != null) assertEquals(true, projects.assignRunToProject(runId, projectId))
            return runId
        }

        fun queued(runId: String) = assertIs<CodingEnqueueOutcome.Queued>(service.enqueue(runId, baseSha)).attempt

        fun project(name: String): String = projects.create(name, null).projectId

        fun state(attemptId: String): CodingAttemptState = repo.get(attemptId)!!.state
    }

    private fun goodResult() = CodingResult(
        summary = "done",
        changes = listOf(CodingChange("docs/a.md", ChangeKind.WRITE, "body")),
        exitStatus = 0,
        sessionId = "session-1",
    )

    @Test
    fun `a claim refused by its project's limit holds that project's attempt, and another project's attempt is handed out`() = TestDb().use { db ->
        val h = Harness(db)
        val limited = h.project("limited")
        val free = h.project("free")
        // 50 yen cannot hold the 90.75 yen reserve of one attempt.
        h.fixture.budget.setProjectBudget(limited, BigDecimal("50"), BigDecimal("0.8"))
        val refusedRun = h.run(limited)
        val freeRun = h.run(free)
        val refused = h.queued(refusedRun)
        val admitted = h.queued(freeRun)

        assertEquals(BudgetDenial.BUDGET, h.fixture.ledger.claimDenial(limited))
        assertIs<CodingClaimOutcome.Claimed>(h.service.claim(AGENT)).also { assertEquals(admitted.attemptId, it.input.attemptId) }
        assertEquals(CodingAttemptState.QUEUED, h.state(refused.attemptId))
    }

    @Test
    fun `a report of a Run in a project is booked to the project and the tenant`() = TestDb().use { db ->
        val h = Harness(db)
        val project = h.project("alpha")
        val attempt = h.queued(h.run(project))
        h.service.claim(AGENT)

        assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult(), USAGE))

        val b = h.fixture.budget.projectBudget(project)
        assertEquals(0, REPORTED_YEN.compareTo(BigDecimal.valueOf(b.committedMicroYen, 6)))
        assertEquals(0, REPORTED_YEN.compareTo(h.fixture.budget.usage(COST_TENANT).committed))
    }

    @Test
    fun `a Run without a project is booked to the tenant only`() = TestDb().use { db ->
        val h = Harness(db)
        val project = h.project("alpha")
        val attempt = h.queued(h.run(null))
        h.service.claim(AGENT)

        assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult(), USAGE))

        assertEquals(0L, h.fixture.budget.projectBudget(project).committedMicroYen)
        assertEquals(0, REPORTED_YEN.compareTo(h.fixture.budget.usage(COST_TENANT).committed))
    }

    @Test
    fun `an attempt that ends without a report is booked as an unpriced estimate to the project`() = TestDb().use { db ->
        val h = Harness(db)
        val project = h.project("alpha")
        val attempt = h.queued(h.run(project))
        h.service.claim(AGENT)

        h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.CLI_NONZERO)

        val b = h.fixture.budget.projectBudget(project)
        assertEquals(0, RESERVE_YEN.compareTo(BigDecimal.valueOf(b.committedMicroYen, 6)))
        assertEquals(1L, b.unpricedCalls)
    }

    @Test
    fun `a project without a limit is not refused at the claim, however much of the month it has spent`() = TestDb().use { db ->
        val h = Harness(db)
        val project = h.project("free")
        // 9,000 yen booked to the project (no limit row): still no gate for it, and the tenant has room for one reserve.
        h.fixture.budget.recordSpend("coding:old-attempt:1", jpyAmount("9000"), unpriced = false, projectId = project)

        assertNull(h.fixture.ledger.claimDenial(project))
        assertNull(h.fixture.ledger.claimDenial(null))
    }

    private fun jpyAmount(v: String) = net.kigawa.kivra.modelgateway.BudgetAmount(BigDecimal(v), "JPY", net.kigawa.kivra.modelgateway.Cost.UNIT_CURRENCY_UNITS)
}
