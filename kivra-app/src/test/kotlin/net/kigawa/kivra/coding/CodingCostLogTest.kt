package net.kigawa.kivra.coding

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.db.TestDb
import org.slf4j.LoggerFactory

private const val LOG_ATTEMPT = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
private const val LOG_ESTIMATE_ATTEMPT = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
private const val LOG_SECRET = "SECRET-BODY-MARKER-LOG"
private val LOG_USAGE = CliUsage(BigDecimal("0.004268"), "claude-haiku-5-5", 4, 1200, 300, 5000, 800)
private val RECORDED_LINE = Regex(
    "^coding cost recorded: attemptId=\\S+ generation=\\d+ source=(REPORTED|ESTIMATED_RESERVE) unpriced=(true|false) " +
        "costUsd=\\S+ amountMicroYen=\\d+ month=\\d{4}-\\d{2} monthCommittedMicroYen=\\d+$",
)

/**
 * The INFO line written when a CLI cost is booked, and the DEBUG line for a repeated booking (Issue #230 operator visibility).
 * The collector hangs on the net.kigawa logger only, not on root (see SelfDevAdminRoutesTest).
 */
class CodingCostLogTest {
    private fun <T> collectAppLogs(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val appLogger = LoggerFactory.getLogger("net.kigawa") as Logger
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                events.add(event)
            }
        }.also { it.context = appLogger.loggerContext; it.start(); appLogger.addAppender(it) }
        val oldLevel = appLogger.level
        appLogger.level = Level.TRACE
        try {
            return block() to events.toList()
        } finally {
            appLogger.detachAppender(appender)
            appLogger.level = oldLevel
        }
    }

    private fun List<ILoggingEvent>.costLines() = filter { it.loggerName == CodingCostLedgerLogger.NAME }

    @Test
    fun `a booked report logs one fixed INFO line with the numbers and the month total, and no model name or text`() = TestDb().use { db ->
        val fixture = costFixture(db)
        val (ok, events) = collectAppLogs { fixture.ledger.recordReported(LOG_ATTEMPT, 1, LOG_USAGE) }
        assertTrue(ok)
        val lines = events.costLines().filter { it.level == Level.INFO }
        assertEquals(1, lines.size, "one INFO line per booking")
        val line = lines.single().formattedMessage
        assertTrue(RECORDED_LINE.matches(line), "unexpected format: $line")
        assertEquals(
            "coding cost recorded: attemptId=$LOG_ATTEMPT generation=1 source=REPORTED unpriced=false costUsd=0.004268 " +
                "amountMicroYen=774642 month=2026-10 monthCommittedMicroYen=774642",
            line,
        )
        assertFalse(line.contains("claude"), "the model name is not logged")
    }

    @Test
    fun `an unpriced model and an estimate are marked in the same fixed line, and the month total is cumulative`() = TestDb().use { db ->
        val fixture = costFixture(db, priced = false)
        val (_, events) = collectAppLogs {
            fixture.ledger.recordReported(LOG_ATTEMPT, 1, LOG_USAGE)
            fixture.ledger.recordEstimated(LOG_ESTIMATE_ATTEMPT, 1)
        }
        val lines = events.costLines().filter { it.level == Level.INFO }.map { it.formattedMessage }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("source=REPORTED unpriced=true") && lines[0].endsWith("monthCommittedMicroYen=774642"), lines[0])
        // The estimate is the 0.50 USD reserve: 90.750000 yen = 90750000 micro-yen, added to the 774642 already booked.
        assertEquals(
            "coding cost recorded: attemptId=$LOG_ESTIMATE_ATTEMPT generation=1 source=ESTIMATED_RESERVE unpriced=true costUsd=0.50 " +
                "amountMicroYen=90750000 month=2026-10 monthCommittedMicroYen=91524642",
            lines[1],
        )
    }

    @Test
    fun `a repeated report for the same attempt and generation logs a DEBUG line and no second booking line`() = TestDb().use { db ->
        val fixture = costFixture(db)
        fixture.ledger.recordReported(LOG_ATTEMPT, 1, LOG_USAGE)
        val (ok, events) = collectAppLogs { fixture.ledger.recordReported(LOG_ATTEMPT, 1, LOG_USAGE) }
        assertTrue(ok)
        val lines = events.costLines()
        assertEquals(listOf("coding cost already recorded: attemptId=$LOG_ATTEMPT generation=1"), lines.map { it.formattedMessage })
        assertEquals(Level.DEBUG, lines.single().level)
        assertFalse(events.any { it.formattedMessage.startsWith("coding cost recorded") })
    }

    @Test
    fun `no booking log carries a secret placed in the summary or the usage text`() = TestDb().use { db ->
        val fixture = costFixture(db)
        val (_, events) = collectAppLogs {
            fixture.ledger.recordReported(LOG_ATTEMPT, 1, LOG_USAGE)
            fixture.ledger.recordReported(LOG_ATTEMPT, 1, LOG_USAGE.copy(model = LOG_SECRET))
        }
        assertFalse(events.any { it.formattedMessage.contains(LOG_SECRET) || (it.throwableProxy?.message ?: "").contains(LOG_SECRET) })
    }
}

/** The logger name the cost ledger logs under. */
private object CodingCostLedgerLogger {
    val NAME: String = DbCodingCostLedger::class.java.name
}
