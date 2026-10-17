package net.kigawa.kivra.coding

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.CodingRefusal
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.Cost
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private const val AGENT = "agent-1"
private const val TITLE = "TITLE-MARKER README を直す"
private const val SUMMARY = "SUMMARY-MARKER 説明"
private const val CONTENT = "CONTENT-MARKER body"
private const val PATH = ".github/path-marker.yml"

/** 0.50 USD reserve at 150 JPY/USD, with 10% FX and 10% tax margins: 90.750000 yen. */
private val RESERVE_YEN = BigDecimal("90.750000")

/** The USD cost of [USAGE] at the same rate: 0.004268 * 150 * 1.1 * 1.1 = 0.774642 yen. */
private val REPORTED_YEN = BigDecimal("0.774642")
private val USAGE = CliUsage(BigDecimal("0.004268"), "claude-haiku-5-5", 4, 1200, 300, 5000, 800)

/**
 * The CLI runtime's cost in the monthly budget (ADR 0010 addendum 4, Issue #230): booking, the duplicate guard, the reserve for an attempt
 * without a report, the claim gate, FX and price flags, the operator acknowledgement, and the logs. Real DbBudgetStore on H2 (or MariaDB).
 */
class CodingCostLedgerTest {
    private val baseSha = "a".repeat(40)

    private inner class Harness(val db: TestDb, val fixture: CostFixture = costFixture(db), cost: CodingCostPort = fixture.ledger) {
        val repo = CodingAttemptRepository(db.database)
        val service = CodingRuntimeService(
            attempts = repo,
            runs = DbCodingRunPort(db.runs, db.leases),
            results = DbCodingResultStore(db.database),
            tasks = CodingTaskSource { it.taskTitle },
            cost = cost,
        )

        fun run(): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            db.exec("UPDATE development_runs SET task_title = ? WHERE run_id = ?", TITLE, runId)
            db.forceState(runId, DevelopmentRunState.DEVELOPING)
            return runId
        }

        fun queued(runId: String): CodingAttempt =
            assertIs<CodingEnqueueOutcome.Queued>(service.enqueue(runId, baseSha)).attempt

        fun claimed(): CodingInput = assertIs<CodingClaimOutcome.Claimed>(service.claim(AGENT)).input

        fun state(attemptId: String): CodingAttemptState = repo.get(attemptId)!!.state

        fun usage() = fixture.budget.usage(COST_TENANT)
    }

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun goodResult(summary: String = SUMMARY) = CodingResult(
        summary = summary,
        changes = listOf(CodingChange("docs/a.md", ChangeKind.WRITE, CONTENT)),
        exitStatus = 0,
        sessionId = "session-1",
    )

    /** Runs [block] with the root logger capturing every formatted message. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    // ---- booking a report ----

    @Test
    fun `a reported usage is booked in yen into the monthly total, with its usage row`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()

        assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult(), USAGE))

        val usage = h.usage()
        assertEquals(0, REPORTED_YEN.compareTo(usage.committed))
        assertEquals(0L, usage.unpricedCalls)
        val row = h.fixture.usageRows.get(attempt.attemptId, 1)!!
        assertEquals("REPORTED", row.source)
        assertEquals("claude-haiku-5-5", row.model)
        assertEquals(4, row.numTurns)
        assertEquals(800L, row.cacheReadTokens)
        assertEquals(0, BigDecimal("0.004268").compareTo(row.costUsd))
    }

    @Test
    fun `a second report for the same attempt and generation is booked once`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()

        assertTrue(h.fixture.ledger.recordReported(attempt.attemptId, 1, USAGE))
        // A different number for the same generation is still one charge: the first report counts.
        assertTrue(h.fixture.ledger.recordReported(attempt.attemptId, 1, USAGE.copy(totalCostUsd = BigDecimal("50"))))

        assertEquals(0, REPORTED_YEN.compareTo(h.usage().committed))
        assertEquals(0L, h.usage().unpricedCalls)
        assertEquals(0, BigDecimal("0.004268").compareTo(h.fixture.usageRows.get(attempt.attemptId, 1)!!.costUsd))
    }

    @Test
    fun `an API booking and a CLI booking add up in the same monthly total`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()

        assertEquals(net.kigawa.kivra.modelgateway.ReserveResult.Reserved, h.fixture.budget.reserve("api-1", listOf(COST_TENANT), BudgetAmount(BigDecimal("100"), "JPY", Cost.UNIT_CURRENCY_UNITS)))
        h.fixture.budget.commit("api-1", BudgetAmount(BigDecimal("100"), "JPY", Cost.UNIT_CURRENCY_UNITS))
        assertTrue(h.fixture.ledger.recordReported(attempt.attemptId, 1, USAGE))

        assertEquals(0, BigDecimal("100.774642").compareTo(h.usage().committed))
    }

    // ---- attempts without a report ----

    @Test
    fun `a result without usage is booked at the reserve as unpriced`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()

        assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult()))

        assertEquals(0, RESERVE_YEN.compareTo(h.usage().committed))
        assertEquals(1L, h.usage().unpricedCalls)
        assertEquals("ESTIMATED_RESERVE", h.fixture.usageRows.get(attempt.attemptId, 1)!!.source)
    }

    @Test
    fun `a fail without usage is booked at the reserve as unpriced`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()

        assertEquals(CodingAttemptState.FAILED, (h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.CLI_NONZERO) as CodingAttemptResult.Ok).attempt.state)

        assertEquals(0, RESERVE_YEN.compareTo(h.usage().committed))
        assertEquals(1L, h.usage().unpricedCalls)
    }

    @Test
    fun `a fail with usage is booked at the reported cost, and not at the reserve`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()

        h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.TIMEOUT, USAGE)

        assertEquals(0, REPORTED_YEN.compareTo(h.usage().committed))
        assertEquals(0L, h.usage().unpricedCalls)
        assertEquals("REPORTED", h.fixture.usageRows.get(attempt.attemptId, 1)!!.source)
    }

    @Test
    fun `an ORPHANED attempt is booked at the reserve as unpriced, once`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        h.service.reconcile()
        h.service.reconcile()

        assertEquals(CodingAttemptState.ORPHANED, h.state(attempt.attemptId))
        assertEquals(0, RESERVE_YEN.compareTo(h.usage().committed))
        assertEquals(1L, h.usage().unpricedCalls)
    }

    @Test
    fun `an EXPIRED attempt that was never claimed is booked at the reserve as unpriced`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        h.service.reconcile()

        assertEquals(CodingAttemptState.EXPIRED, h.state(attempt.attemptId))
        assertEquals(0, RESERVE_YEN.compareTo(h.usage().committed))
        assertEquals(1L, h.usage().unpricedCalls)
    }

    @Test
    fun `a late report from an attempt that was orphaned is refused, so the reserve stands`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)
        h.service.reconcile()

        assertEquals(CodingRefusal.NOT_ACTIVE, assertIs<CodingSubmitOutcome.Refused>(h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult(), USAGE)).reason)
        assertEquals(0, RESERVE_YEN.compareTo(h.usage().committed))
    }

    // ---- the claim gate ----

    @Test
    fun `a claim is refused when the monthly budget cannot hold one reserve, and the Run keeps its attempt queued`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, hard = "50"))
        val runId = h.run()
        val attempt = h.queued(runId)

        assertEquals(net.kigawa.kivra.modelgateway.BudgetDenial.BUDGET, h.fixture.ledger.claimDenial())
        assertEquals(CodingClaimOutcome.NothingToClaim, h.service.claim(AGENT))
        assertEquals(CodingAttemptState.QUEUED, h.state(attempt.attemptId))
        assertEquals(DevelopmentRunState.DEVELOPING, db.runs.get(RunId(runId))!!.state)
    }

    @Test
    fun `a claim is admitted when the monthly budget holds one reserve`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, hard = "100"))
        val attempt = h.queued(h.run())

        assertNull(h.fixture.ledger.claimDenial())
        assertEquals(attempt.attemptId, h.claimed().attemptId)
    }

    @Test
    fun `reported spend in the month counts against the next claim`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, hard = "100"))
        val first = h.queued(h.run())
        h.claimed()
        // 0.5 USD reported = 90.75 yen: the rest of the month (9.25 yen) cannot hold another 90.75 yen reserve.
        h.service.fail(first.attemptId, AGENT, 1, CodingErrorCode.CLI_NONZERO, USAGE.copy(totalCostUsd = BigDecimal("0.5")))
        h.queued(h.run())

        assertEquals(net.kigawa.kivra.modelgateway.BudgetDenial.BUDGET, h.fixture.ledger.claimDenial())
        assertEquals(CodingClaimOutcome.NothingToClaim, h.service.claim(AGENT))
    }

    // ---- FX, prices, acknowledgement ----

    @Test
    fun `an expired FX rate refuses every claim as UNKNOWN_COST, and a report is booked as unpriced at zero`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, fx = costFx(COST_NOW.minusSeconds(8L * 86_400))))
        val attempt = h.queued(h.run())

        assertEquals(net.kigawa.kivra.modelgateway.BudgetDenial.UNKNOWN_COST, h.fixture.ledger.claimDenial())
        assertEquals(CodingClaimOutcome.NothingToClaim, h.service.claim(AGENT))
        assertEquals(CodingAttemptState.QUEUED, h.state(attempt.attemptId))

        assertTrue(h.fixture.ledger.recordReported(attempt.attemptId, 1, USAGE))
        assertEquals(0, BigDecimal.ZERO.compareTo(h.usage().committed))
        assertEquals(1L, h.usage().unpricedCalls)
    }

    @Test
    fun `no FX settings at all refuse every claim as UNKNOWN_COST`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, fx = null))
        h.queued(h.run())

        assertEquals(net.kigawa.kivra.modelgateway.BudgetDenial.UNKNOWN_COST, h.fixture.ledger.claimDenial())
    }

    @Test
    fun `a model that is not in the price table is booked and flagged, and the claims wait for an acknowledgement`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, priced = false))
        val attempt = h.queued(h.run())
        h.claimed()

        h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.TIMEOUT, USAGE)

        assertEquals(0, REPORTED_YEN.compareTo(h.usage().committed))
        assertEquals(1L, h.usage().unpricedCalls)
        assertEquals(net.kigawa.kivra.modelgateway.BudgetDenial.UNKNOWN_COST, h.fixture.ledger.claimDenial())

        val key = DbCodingCostLedger.reservationKey(attempt.attemptId, 1)
        val ack = h.fixture.budget.acknowledgeUnpriced(
            AcknowledgeUnpriced(
                scopeId = COST_TENANT,
                period = "2026-10",
                reservationKey = key,
                actualAmount = null,
                evidence = "console checked",
                operatorId = "operator-1",
                reason = "test",
            ),
        )
        assertIs<AcknowledgeResult.Acknowledged>(ack)
        assertEquals(0L, h.usage().unpricedCalls)
        assertNull(h.fixture.ledger.claimDenial())
    }

    @Test
    fun `an estimate is flagged, so the next claim is refused as UNKNOWN_COST until an acknowledgement`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()
        h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.CLI_NONZERO)

        assertEquals(net.kigawa.kivra.modelgateway.BudgetDenial.UNKNOWN_COST, h.fixture.ledger.claimDenial())
        h.queued(h.run())
        assertEquals(CodingClaimOutcome.NothingToClaim, h.service.claim(AGENT))
    }

    // ---- ledger failure ----

    /** A cost port whose ledger cannot write. Every booking fails; the claim gate is open. */
    private val failingCost = object : CodingCostPort {
        override fun claimDenial(): String? = null
        override fun recordReported(attemptId: String, leaseGeneration: Long, usage: CliUsage): Boolean = false
        override fun recordEstimated(attemptId: String, leaseGeneration: Long): Boolean = false
    }

    @Test
    fun `when the ledger cannot record a report, the result is StoreFailed and the attempt is unchanged`() = TestDb().use { db ->
        val h = Harness(db, cost = failingCost)
        val attempt = h.queued(h.run())
        h.claimed()

        assertIs<CodingSubmitOutcome.StoreFailed>(h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult(), USAGE))
        assertEquals(CodingAttemptState.CLAIMED, h.state(attempt.attemptId))
    }

    @Test
    fun `when the ledger cannot record a fail, the fail throws and the attempt stays claimed, so the holder may send it again`() = TestDb().use { db ->
        val h = Harness(db, cost = failingCost)
        val attempt = h.queued(h.run())
        h.claimed()

        assertFailsWithCostUnavailable { h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.CLI_NONZERO) }
        assertEquals(CodingAttemptState.CLAIMED, h.state(attempt.attemptId))
    }

    private fun assertFailsWithCostUnavailable(block: () -> Unit) {
        try {
            block()
        } catch (_: CodingCostUnavailableException) {
            return
        }
        kotlin.test.fail("expected CodingCostUnavailableException")
    }

    // ---- logs ----

    @Test
    fun `the logs of a booked report carry identifiers and fixed codes, never the title, the summary, a change or the usage text`() = TestDb().use { db ->
        val h = Harness(db, costFixture(db, priced = false))
        val attempt = h.queued(h.run())
        h.claimed()

        val logs = captureLogs {
            h.service.submitResult(attempt.attemptId, AGENT, 1, goodResult(summary = SUMMARY + " $PATH"), USAGE)
        }

        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            assertFalse(line.contains(TITLE) || line.contains(SUMMARY) || line.contains(CONTENT) || line.contains(PATH), "log leaks: $line")
        }
    }

    @Test
    fun `an invalid usage is logged by attempt id and a fixed reason only`() = TestDb().use { db ->
        val attemptId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val logs = captureLogs {
            assertNull(CliUsageParser.parse(attemptId, Json.parseToJsonElement("""{"totalCostUsd":"SECRET-MARKER"}""")))
        }
        assertEquals(listOf("coding usage ignored: attemptId=$attemptId reason=USAGE_INVALID"), logs)
    }

    // ---- the usage contract ----

    private fun parse(text: String): CliUsage? = CliUsageParser.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV", Json.parseToJsonElement(text))

    private val validUsage = """{"totalCostUsd":"0.004268","model":"claude-haiku-5-5","numTurns":4,"inputTokens":1200,"outputTokens":300,"cacheCreationTokens":5000,"cacheReadTokens":800}"""

    @Test
    fun `a well-formed usage is read as it was sent`() {
        assertEquals(USAGE, parse(validUsage))
        assertEquals(USAGE.copy(totalCostUsd = BigDecimal("100.000000"), numTurns = 1000), parse(validUsage.replace("0.004268", "100.000000").replace("\"numTurns\":4", "\"numTurns\":1000")))
    }

    @Test
    fun `absent or null usage is no usage, and gives no log line`() {
        val logs = captureLogs {
            assertNull(CliUsageParser.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV", null))
            assertNull(CliUsageParser.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV", JsonNull))
        }
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `every malformed usage is ignored, not read`() {
        val bad = listOf(
            // a missing field, an extra field
            validUsage.replace(",\"numTurns\":4", ""),
            validUsage.dropLast(1) + ",\"extra\":1}",
            // the cost: as a number, negative, exponent, above 100, more than six decimals, empty, padded
            validUsage.replace("\"0.004268\"", "0.004268"),
            validUsage.replace("\"0.004268\"", "\"-0.1\""),
            validUsage.replace("\"0.004268\"", "\"1e-3\""),
            validUsage.replace("\"0.004268\"", "\"100.0000001\""),
            validUsage.replace("\"0.004268\"", "\"101\""),
            validUsage.replace("\"0.004268\"", "\"\""),
            validUsage.replace("\"0.004268\"", "\" 0.1\""),
            // the model: upper case, empty, too long
            validUsage.replace("claude-haiku-5-5", "Claude-Haiku"),
            validUsage.replace("claude-haiku-5-5", ""),
            validUsage.replace("claude-haiku-5-5", "a".repeat(65)),
            // the counts: out of range, a string, a fraction
            validUsage.replace("\"numTurns\":4", "\"numTurns\":1001"),
            validUsage.replace("\"numTurns\":4", "\"numTurns\":-1"),
            validUsage.replace("\"numTurns\":4", "\"numTurns\":\"4\""),
            validUsage.replace("\"numTurns\":4", "\"numTurns\":4.5"),
            validUsage.replace("\"inputTokens\":1200", "\"inputTokens\":-1"),
            validUsage.replace("\"inputTokens\":1200", "\"inputTokens\":10000000001"),
            validUsage.replace("\"inputTokens\":1200", "\"inputTokens\":\"1200\""),
            validUsage.replace("\"inputTokens\":1200", "\"inputTokens\":null"),
            // not an object
            "\"usage\"",
            "[]",
            "42",
        )
        for (text in bad) assertNull(parse(text), "accepted: $text")
    }
}
