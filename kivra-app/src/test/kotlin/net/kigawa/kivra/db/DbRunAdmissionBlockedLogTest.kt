package net.kigawa.kivra.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.read.ListAppender
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.AdmissionBlockReason
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.AdmissionResult
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import org.slf4j.LoggerFactory

/**
 * A blocked admission has no run, so it is not audited (Issue #26). It is observable through a WARN log that carries the
 * fixed reason code only, at most once per reason per minute. The log never holds the task id, title, body, requestId or an
 * exception message.
 */
class DbRunAdmissionBlockedLogTest {
    private val policy = AdmissionPolicy(
        statusField = "status",
        admittableStatuses = setOf("ready"),
        cancelledStatuses = setOf("cancelled"),
        dependenciesField = "dependencies",
        targetField = "target",
        allowedTargets = setOf("A", "B"),
        repositoryField = "repository",
    )

    private val logger = LoggerFactory.getLogger(DbRunAdmission::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var previousLevel: Level

    /** Fake monotonic clock: the throttle reads it, tests move it forward explicitly. */
    private val nanos = AtomicLong(0)

    @BeforeTest
    fun attach() {
        previousLevel = logger.level ?: Level.INFO
        logger.level = Level.INFO
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }

    private fun TestDb.admission(policy: AdmissionPolicy? = this@DbRunAdmissionBlockedLogTest.policy) =
        DbRunAdmission(database, runs, audit, policy, nanoTime = { nanos.get() })

    private fun task(
        n: Long = 1,
        id: String = "fusha:test:$n",
        title: String = "title $n",
        description: String = "body $n",
        status: String = "\"ready\"",
        dependencies: String? = "[]",
        target: String? = "\"A\"",
        repository: String? = "\"kigawa/app\"",
        updatedAt: Instant = T0,
    ) = TaskSnapshot(
        id = TaskId(id),
        title = title,
        description = description,
        assigneeId = null,
        dueDate = null,
        fields = buildMap {
            put("status", status)
            dependencies?.let { put("dependencies", it) }
            target?.let { put("target", it) }
            repository?.let { put("repository", it) }
        },
        nextTaskIds = emptyList(),
        relatedTaskIds = emptyList(),
        updatedAt = updatedAt,
        fetchedAt = T0,
    )

    private fun warnings(): List<ILoggingEvent> = appender.list.filter { it.level == Level.WARN }

    private fun admit(admission: DbRunAdmission, snapshot: TaskSnapshot, requestId: String = "req") =
        runBlocking { admission.admit(snapshot, requestId) }

    @Test
    fun `each blocked reason is logged once at WARN with its reason code`() = TestDb().use { db ->
        val cases = listOf(
            AdmissionBlockReason.INVALID_TASK_ID to (db.admission() to task(id = "not a task key")),
            AdmissionBlockReason.INVALID_REQUEST_ID to (db.admission() to task(1)),
            AdmissionBlockReason.POLICY_UNCONFIRMED to (db.admission(null) to task(2)),
            AdmissionBlockReason.STATUS_UNKNOWN to (db.admission() to task(3, status = "7")),
            AdmissionBlockReason.STATUS_NOT_ADMITTABLE to (db.admission() to task(4, status = "\"draft\"")),
            AdmissionBlockReason.CANCELLED to (db.admission() to task(5, status = "\"cancelled\"")),
            AdmissionBlockReason.DEPENDENCIES_UNKNOWN to (db.admission() to task(6, dependencies = null)),
            AdmissionBlockReason.DEPENDENCIES_UNRESOLVED to (db.admission() to task(7, dependencies = "[\"fusha:test:99\"]")),
            AdmissionBlockReason.TARGET_UNKNOWN to (db.admission() to task(8, target = "\"C\"")),
            AdmissionBlockReason.REPOSITORY_UNKNOWN to (db.admission() to task(9, repository = null)),
        )
        for ((reason, pair) in cases) {
            val (admission, snapshot) = pair
            val requestId = if (reason == AdmissionBlockReason.INVALID_REQUEST_ID) "" else "req"
            assertEquals(AdmissionResult.Blocked(reason), admit(admission, snapshot, requestId))
            val last = warnings().last()
            assertEquals(Level.WARN, last.level)
            assertEquals("task admission blocked: reason=${reason.name}", last.formattedMessage)
        }
        assertEquals(cases.size, warnings().size, "one line per reason")
    }

    @Test
    fun `STALE_SNAPSHOT is logged after the transaction, with its reason code`() = TestDb().use { db ->
        val admission = db.admission()
        val newer = T0.plusSeconds(60)
        val accepted = assertIs<AdmissionResult.Accepted>(admit(admission, task(21, updatedAt = newer)))
        db.forceState(accepted.runId.value, DevelopmentRunState.FAILED)
        runBlocking { admission.requestRerun(TaskId("fusha:test:21"), "op", "rerun") }
        assertEquals(AdmissionResult.Blocked(AdmissionBlockReason.STALE_SNAPSHOT), admit(admission, task(21, updatedAt = T0)))
        assertEquals(listOf("task admission blocked: reason=STALE_SNAPSHOT"), warnings().map { it.formattedMessage })
    }

    @Test
    fun `the same reason twenty times in a row is logged once, and once more after a minute`() = TestDb().use { db ->
        val admission = db.admission()
        repeat(20) { assertEquals(AdmissionResult.Blocked(AdmissionBlockReason.CANCELLED), admit(admission, task(30, status = "\"cancelled\""))) }
        assertEquals(1, warnings().size, "flood of the same reason is collapsed")

        nanos.addAndGet(59_999_999_999L)
        admit(admission, task(30, status = "\"cancelled\""))
        assertEquals(1, warnings().size, "not yet one minute")

        nanos.addAndGet(1L)
        admit(admission, task(30, status = "\"cancelled\""))
        assertEquals(2, warnings().size, "logged again after one minute")
    }

    @Test
    fun `different reasons are logged separately, each with its own throttle`() = TestDb().use { db ->
        val admission = db.admission()
        admit(admission, task(40, status = "\"cancelled\""))
        admit(admission, task(41, status = "\"draft\""))
        admit(admission, task(42, status = "\"cancelled\""))
        admit(admission, task(43, status = "\"draft\""))
        val messages = warnings().map { it.formattedMessage }
        assertEquals(
            listOf("task admission blocked: reason=CANCELLED", "task admission blocked: reason=STATUS_NOT_ADMITTABLE"),
            messages,
        )
    }

    @Test
    fun `the log never contains the task id, title, body or requestId`() = TestDb().use { db ->
        val admission = db.admission()
        admit(
            admission,
            task(50, id = "fusha:test:SECRET-ID-xyz", title = "SECRET-TITLE-xyz", description = "SECRET-BODY-xyz", status = "\"draft\""),
            requestId = "req-SECRET",
        )
        admit(
            admission,
            task(51, title = "SECRET-TITLE-xyz", description = "SECRET-BODY-xyz", target = "\"C\""),
            requestId = "req-SECRET",
        )
        admit(admission, task(52, title = "SECRET-TITLE-xyz"), requestId = "")
        assertEquals(3, warnings().size)
        val all = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        for (secret in listOf("SECRET", "req-SECRET", "fusha:test:")) {
            assertFalse(all.contains(secret), "log must not contain $secret")
        }
        assertTrue(appender.list.all { it.throwableProxy == null }, "no stack trace")
    }

    @Test
    fun `accepted and already-active admissions are not logged`() = TestDb().use { db ->
        val admission = db.admission()
        val accepted = admit(admission, task(60))
        assertIs<AdmissionResult.Accepted>(accepted)
        assertIs<AdmissionResult.AlreadyActive>(admit(admission, task(60)))
        assertTrue(appender.list.isEmpty(), "normal outcomes are not logged")
    }

    @Test
    fun `a failing logger does not change the admission result`() = TestDb().use { db ->
        val thrower = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                throw IllegalStateException("log sink is down")
            }
        }
        thrower.start()
        logger.addAppender(thrower)
        try {
            val admission = db.admission()
            assertEquals(
                AdmissionResult.Blocked(AdmissionBlockReason.CANCELLED),
                admit(admission, task(70, status = "\"cancelled\"")),
            )
            assertIs<AdmissionResult.Accepted>(admit(admission, task(71)))
            assertEquals(1, db.runCount(71))
        } finally {
            logger.detachAppender(thrower)
            thrower.stop()
        }
    }

    private fun TestDb.runCount(n: Long): Int = count("development_runs", "task_id = 'fusha:test:$n'")

    private companion object {
        val T0: Instant = Instant.parse("2026-10-15T00:00:00Z")
    }
}
