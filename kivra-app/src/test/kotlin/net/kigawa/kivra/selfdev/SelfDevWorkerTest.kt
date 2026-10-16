package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting

class SelfDevWorkerTest {
    private val a = RunId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val b = RunId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val waiting = Waiting(WaitReason.AWAITING_HUMAN_REVIEW)

    @Test
    fun `one tick gives each active run exactly one advance call - in order`() = runBlocking {
        val seen = mutableListOf<RunId>()
        val worker = SelfDevWorker({ listOf(a, b) }, { seen += it; waiting }, Duration.ofSeconds(30))
        assertEquals(2, worker.tick())
        assertEquals(listOf(a, b), seen)
    }

    @Test
    fun `a failure in one run is contained - the other runs and the next tick still advance`() = runBlocking {
        val seen = mutableListOf<RunId>()
        val worker = SelfDevWorker(
            { listOf(a, b) },
            { runId ->
                seen += runId
                if (runId == a) throw IllegalStateException("db detail that must not be logged")
                waiting
            },
            Duration.ofSeconds(30),
        )
        assertEquals(2, worker.tick())
        assertEquals(2, worker.tick())
        assertEquals(listOf(a, b, a, b), seen)
    }

    @Test
    fun `a listing failure is contained to the tick`() = runBlocking {
        val worker = SelfDevWorker({ throw IllegalStateException("database is down") }, { waiting }, Duration.ofSeconds(30))
        assertEquals(0, worker.tick())
    }

    @Test
    fun `cancellation is never swallowed`() = runBlocking {
        val worker = SelfDevWorker({ listOf(a) }, { throw CancellationException("stopped") }, Duration.ofSeconds(30))
        assertFailsWith<CancellationException> { worker.tick() }
    }

    @Test
    fun `the loop runs every interval and stops when its scope is cancelled`() = runTest {
        var lists = 0
        val worker = SelfDevWorker({ lists++; emptyList() }, { waiting }, Duration.ofSeconds(30))
        val job = worker.start(this)
        runCurrent()
        assertEquals(1, lists)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, lists)
        job.cancelAndJoin()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, lists)
    }

    @Test
    fun `end to end on fakes - a submitted self-development run reaches one pull request and is never merged by kivra`() = runBlocking {
        TestDb().use { db ->
            val github = FakeGitHub()
            val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new text\n"))
            val runner = SelfDevRunner(
                config = SelfDevConfig(enabled = true),
                runs = DbDevelopmentRunStore(db.runs),
                leases = db.leases,
                developer = developer,
                github = github,
                repoReader = InMemoryRepoReader(mapOf("README.md" to "# kivra\n", "docs/a.md" to "old\n"), github.baseSha),
                taskTexts = DbTaskTextSource,
            )
            val runId = db.runs.submitTask("alice", "key-1", "README を直す", selfDev = true).run.runId
            val worker = SelfDevWorker({ db.runs.activeSelfDevRunIds() }, { runner.advance(it) }, Duration.ofSeconds(30))

            worker.tick()
            worker.tick()
            assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(runId)!!.state)
            assertEquals(1, github.pullRequests().size)

            // Further ticks wait for a human. The run is never completed by kivra and no second pull request is opened.
            repeat(3) { worker.tick() }
            assertEquals(1, github.pullRequests().size)
            assertEquals(PullRequestState.OPEN, github.pullRequests().single().state)
            assertEquals(listOf("createRef", "commit", "createPr"), github.writes)
        }
    }

    // ---- outcome logging (fixed codes; a changed result is logged, a repeated one at most once a minute) ----

    /** A clock the test moves by hand. */
    private class ManualClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun captureSelfDevLogs(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        val oldLevel = root.level
        root.level = Level.TRACE
        try {
            block()
        } finally {
            root.detachAppender(appender)
            root.level = oldLevel
        }
        return appender.list.toList()
    }

    @Test
    fun `an unchanged waiting result is logged once within a minute, and a changed one at once`() {
        val clock = ManualClock(Instant.parse("2026-10-16T00:00:00Z"))
        var result: AdvanceResult = waiting
        val worker = SelfDevWorker({ listOf(a) }, { result }, Duration.ofSeconds(30), clock)
        val events = captureSelfDevLogs {
            runBlocking {
                worker.tick()
                clock.now = clock.now.plusSeconds(30); worker.tick()
                clock.now = clock.now.plusSeconds(20); worker.tick()
                result = Waiting(WaitReason.AWAITING_OPERATOR)
                worker.tick()
            }
        }
        val lines = events.filter { it.loggerName.endsWith("SelfDevWorker") }.map { it.formattedMessage }
        assertEquals(
            listOf(
                "self-development run waiting: runId=${a.value} reason=AWAITING_HUMAN_REVIEW",
                "self-development run waiting: runId=${a.value} reason=AWAITING_OPERATOR",
            ),
            lines,
        )
    }

    @Test
    fun `an unchanged waiting result is logged again after a minute`() {
        val clock = ManualClock(Instant.parse("2026-10-16T00:00:00Z"))
        val worker = SelfDevWorker({ listOf(a) }, { waiting }, Duration.ofSeconds(30), clock)
        val events = captureSelfDevLogs {
            runBlocking {
                worker.tick()
                clock.now = clock.now.plusSeconds(60); worker.tick()
            }
        }
        assertEquals(2, events.count { it.loggerName.endsWith("SelfDevWorker") })
    }

    @Test
    fun `an advance that moves the run is logged at INFO with the states and the reason code`() {
        val worker = SelfDevWorker(
            { listOf(a) },
            { AdvanceResult.Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "DEVELOPER_OUTCOME_UNKNOWN") },
            Duration.ofSeconds(30),
        )
        val events = captureSelfDevLogs { runBlocking { worker.tick() } }
        val event = events.single { it.loggerName.endsWith("SelfDevWorker") }
        assertEquals(Level.INFO, event.level)
        assertEquals(
            "self-development run advanced: runId=${a.value} from=DEVELOPING to=UNKNOWN reason=DEVELOPER_OUTCOME_UNKNOWN",
            event.formattedMessage,
        )
    }

    @Test
    fun `a failed step is logged at WARN with its code, and an exception only by class name`() {
        val worker = SelfDevWorker(
            { listOf(a, b) },
            { runId ->
                if (runId == a) AdvanceResult.Failed(FailureCode.GITHUB_READ_FAILED)
                else throw IllegalStateException("SECRET-PROVIDER-DETAIL-DDD")
            },
            Duration.ofSeconds(30),
        )
        val events = captureSelfDevLogs { runBlocking { worker.tick() } }
        val worker_ = events.filter { it.loggerName.endsWith("SelfDevWorker") }
        assertEquals(
            listOf(
                "self-development run step failed: runId=${a.value} code=GITHUB_READ_FAILED",
                "self-development worker: advance failed, runId=${b.value} reason=ADVANCE_FAILED exception=IllegalStateException",
            ),
            worker_.map { it.formattedMessage },
        )
        assertEquals(listOf(Level.WARN, Level.WARN), worker_.map { it.level })
        assertFalse(events.any { it.formattedMessage.contains("SECRET-PROVIDER-DETAIL-DDD") })
    }

    @Test
    fun `a listing failure logs its class name and never the message`() {
        val worker = SelfDevWorker({ throw IllegalStateException("SECRET-DB-URL-EEE") }, { waiting }, Duration.ofSeconds(30))
        val events = captureSelfDevLogs { runBlocking { worker.tick() } }
        assertEquals(
            listOf("self-development worker: listing failed, reason=LIST_FAILED exception=IllegalStateException"),
            events.filter { it.loggerName.endsWith("SelfDevWorker") }.map { it.formattedMessage },
        )
        assertFalse(events.any { it.formattedMessage.contains("SECRET-DB-URL-EEE") })
    }
}
