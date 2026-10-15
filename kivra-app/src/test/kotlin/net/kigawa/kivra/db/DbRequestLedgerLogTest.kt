package net.kigawa.kivra.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.modelgateway.LedgerClaim
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * The ledger's logs and exceptions carry no response body, prompt or full requestId. Every application logger is at TRACE
 * (third-party loggers keep their production level, as in [net.kigawa.kivra.routes.BudgetAdminLogTest]).
 */
class DbRequestLedgerLogTest {
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appLogger = LoggerFactory.getLogger("net.kigawa") as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var previousAppLevel: Level

    @BeforeTest
    fun attach() {
        previousAppLevel = appLogger.level ?: Level.INFO
        appLogger.level = Level.TRACE
        appender.start()
        root.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        root.detachAppender(appender)
        appender.stop()
        appLogger.level = previousAppLevel
    }

    private fun loggedText(e: ILoggingEvent): String = buildString {
        append(e.loggerName).append(' ').append(e.level).append(' ').append(e.formattedMessage)
        e.argumentArray?.forEach { append(' ').append(it) }
        e.throwableProxy?.let { append(' ').append(it.className).append(' ').append(it.message.orEmpty()) }
    }

    @Test
    fun failuresAndCorruptReplayNeverLogTheBodyOrTheFullRequestId() = TestDb().use { db ->
        val secret = "SECRET-PROMPT-BODY-7f3a"
        val id = "req-logsecret-0123456789-abcdef"
        val ledger = DbRequestLedger(db.database)
        ledger.begin(id, DIGEST_A); ledger.markSending(id); ledger.complete(id, sampleResponse(id, text = secret))

        // A stored response that cannot be decoded: the replay is refused by returning Completed(null), and the body is not logged.
        transaction(db.database) {
            execUpdate("UPDATE request_ledger SET response_json = ? WHERE request_id = ?", "not-json $secret", id)
        }
        assertTrue(ledger.begin(id, DIGEST_A) == LedgerClaim.Completed(null))

        // Store unavailable: every failing operation logs and fails closed.
        val ds = db.replicaDataSource()
        val broken = DbRequestLedger(connectDatabase(ds))
        closeDataSource(ds)
        assertTrue(broken.begin(id, DIGEST_A).let { it is LedgerClaim.Rejected && it.code == "STORE_UNAVAILABLE" })
        assertFailsWith<RequestLedgerWriteException> { broken.markSending(id) }
        broken.markOutcomeUnknown(id)

        val text = appender.list.joinToString("\n") { loggedText(it) }
        assertFalse(text.contains(secret), "response body must not be logged")
        assertFalse(text.contains(id), "the full requestId must not be logged")
        assertTrue(text.contains(id.take(8)), "the logs identify the request by its prefix")
        assertTrue(appender.list.any { it.loggerName == "net.kigawa.kivra.db.DbRequestLedger" && it.level == Level.ERROR })
    }
}
