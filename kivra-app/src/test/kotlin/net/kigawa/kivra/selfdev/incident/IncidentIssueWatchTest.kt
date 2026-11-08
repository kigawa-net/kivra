package net.kigawa.kivra.selfdev.incident

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.FakeGitHub
import org.jetbrains.exposed.sql.transactions.transaction

private val REPO = RepoRef("kigawa-net", "kivra")
private val T0: Instant = Instant.parse("2026-11-08T00:00:00Z")
private val SETTINGS = IncidentIssueSettings(enabled = true, maxPerHour = 5, minFailedRuns = 3, windowHours = 24)

/** A clock the test moves by hand. The store and the watch read the same one. */
private class StepClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** The facts of the detector, set by the test. Counts every read, so a disabled watch can be shown to read nothing. */
private class FakeSource(var counts: List<RunFailureCount> = emptyList(), var breaker: Boolean = false) : IncidentSource {
    var reads = 0
    override fun failedRunCounts(since: Instant): List<RunFailureCount> {
        reads++
        return counts
    }

    override fun breakerOpen(): Boolean {
        reads++
        return breaker
    }
}

/** One database, one clock, one fake GitHub. [watch] builds a watch on the shared database (a second Pod is a second watch). */
private class Harness(val db: TestDb, val clock: StepClock, val source: FakeSource, val settings: IncidentIssueSettings = SETTINGS) {
    val github = FakeGitHub(REPO)

    fun watch(): IncidentIssueWatch = IncidentIssueWatch(
        settings = settings,
        store = DbIncidentStore(db.database, clock),
        source = source,
        github = github,
        repo = REPO,
        clock = clock,
    )
}

private fun failed(reason: String?, count: Long) = RunFailureCount("FAILED", reason, count)

class IncidentIssueWatchTest {

    @Test
    fun `a disabled watch reads nothing and writes nothing`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 50)), breaker = true)
        val h = Harness(db, StepClock(T0), source, IncidentIssueSettings.DISABLED)
        assertEquals(0, runBlocking { h.watch().tick() })
        assertEquals(0, source.reads)
        assertTrue(h.github.writes.isEmpty())
    }

    @Test
    fun `a run failure below the threshold files nothing, and the threshold files one issue with fixed text only`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 2)))
        val h = Harness(db, StepClock(T0), source)
        assertEquals(0, runBlocking { h.watch().tick() })
        assertTrue(h.github.issues.isEmpty())

        source.counts = listOf(failed("CI_TIMEOUT", 3))
        assertEquals(1, runBlocking { h.watch().tick() })
        val (title, body) = h.github.issues.single()
        assertEquals("[kivra-incident] RUN_FAILED CI_TIMEOUT", title)
        assertTrue(body.contains("- code: RUN_FAILED"), body)
        assertTrue(body.contains("- count: 3 (window: 24h)"), body)
        assertTrue(body.contains("- repository: kigawa-net/kivra"), body)
    }

    @Test
    fun `a repeated tick in the window files nothing more, and the only write is the issue creation`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 3)))
        val h = Harness(db, StepClock(T0), source)
        val watch = h.watch()
        assertEquals(1, runBlocking { watch.tick() })
        assertEquals(0, runBlocking { watch.tick() })
        assertEquals(1, h.github.issues.size)
        assertEquals(listOf("createIssue"), h.github.writes)
    }

    @Test
    fun `two watches on one database, as two Pods, file one issue for one cause`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 3)), breaker = true)
        val h = Harness(db, StepClock(T0), source)
        val first = h.watch()
        val second = h.watch()
        val opened = runBlocking { first.tick() + second.tick() }
        assertEquals(2, opened, "one RUN_FAILED and one BREAKER_OPEN, each once")
        assertEquals(2, h.github.issues.size)
        assertEquals(2, h.github.issues.map { it.first }.toSet().size)
    }

    @Test
    fun `a cause that recurs after its window is filed again`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 3)))
        val clock = StepClock(T0)
        val h = Harness(db, clock, source)
        runBlocking { h.watch().tick() }
        clock.now = T0.plus(Duration.ofHours(23))
        assertEquals(0, runBlocking { h.watch().tick() }, "still inside the window")
        clock.now = T0.plus(Duration.ofHours(25))
        assertEquals(1, runBlocking { h.watch().tick() }, "the window has passed")
        assertEquals(2, h.github.issues.size)
    }

    @Test
    fun `a lost create response is never sent again inside the window`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 3)))
        val clock = StepClock(T0)
        val h = Harness(db, clock, source)
        h.github.issueLost = true
        assertEquals(0, runBlocking { h.watch().tick() })
        clock.now = T0.plus(Duration.ofHours(1))
        assertEquals(0, runBlocking { h.watch().tick() })
        assertEquals(1, h.github.writes.size)
        assertEquals(1, h.github.issues.size)
    }

    @Test
    fun `a refused create is recorded and is not retried inside the window`() = TestDb().use { db ->
        val source = FakeSource(listOf(failed("CI_TIMEOUT", 3)))
        val h = Harness(db, StepClock(T0), source)
        h.github.issueFailure = GitHubFailureKind.PERMISSION
        assertEquals(0, runBlocking { h.watch().tick() })
        assertEquals(0, runBlocking { h.watch().tick() })
        assertEquals(1, h.github.writes.size)
        assertTrue(h.github.issues.isEmpty())
    }

    @Test
    fun `the hourly limit ends the pass after the first issue`() = TestDb().use { db ->
        val source = FakeSource(listOf(RunFailureCount("UNKNOWN", "EXTERNAL_HELD", 1)), breaker = true)
        val h = Harness(db, StepClock(T0), source, SETTINGS.copy(maxPerHour = 1))
        assertEquals(1, runBlocking { h.watch().tick() })
        assertEquals(1, h.github.issues.size)
        assertTrue(h.github.issues.single().first.startsWith("[kivra-incident] RUN_UNKNOWN"), h.github.issues.single().first)
    }

    @Test
    fun `a free-text reason and anything that looks like a secret never reach the issue`() = TestDb().use { db ->
        val secret = "sk-ant-api03-SECRETVALUE"
        val source = FakeSource(listOf(RunFailureCount("UNKNOWN", "token $secret leaked, run 01HZZZ", 1)))
        val h = Harness(db, StepClock(T0), source)
        assertEquals(1, runBlocking { h.watch().tick() })
        val (title, body) = h.github.issues.single()
        assertEquals("[kivra-incident] RUN_UNKNOWN OTHER", title)
        for (text in listOf(title, body)) {
            assertFalse(text.contains("SECRETVALUE"), text)
            assertFalse(text.contains("sk-ant"), text)
            assertFalse(text.contains("01HZZZ"), text)
        }
    }

    @Test
    fun `runs that ended in another state are not incidents`() = TestDb().use { db ->
        val source = FakeSource(listOf(RunFailureCount("CANCELLED", "OPERATOR_STOPPED", 50), RunFailureCount("COMPLETED", null, 9)))
        val h = Harness(db, StepClock(T0), source)
        assertEquals(0, runBlocking { h.watch().tick() })
        assertTrue(h.github.writes.isEmpty())
    }

    @Test
    fun `the breaker files one incident while it is open, and an unknown run files at one`() = TestDb().use { db ->
        val source = FakeSource(listOf(RunFailureCount("UNKNOWN", null, 1)), breaker = true)
        val h = Harness(db, StepClock(T0), source)
        assertEquals(2, runBlocking { h.watch().tick() })
        val titles = h.github.issues.map { it.first }.toSet()
        assertEquals(setOf("[kivra-incident] RUN_UNKNOWN NONE", "[kivra-incident] BREAKER_OPEN NONE"), titles)
    }

    @Test
    fun `a fingerprint depends on the codes and the repository only`() {
        val a = incidentFingerprint(REPO, IncidentCode.RUN_FAILED, "CI_TIMEOUT")
        assertEquals(64, a.length)
        assertEquals(a, incidentFingerprint(REPO, IncidentCode.RUN_FAILED, "CI_TIMEOUT"))
        assertNotEquals(a, incidentFingerprint(REPO, IncidentCode.RUN_UNKNOWN, "CI_TIMEOUT"))
        assertNotEquals(a, incidentFingerprint(RepoRef("kigawa-net", "other"), IncidentCode.RUN_FAILED, "CI_TIMEOUT"))
    }

    @Test
    fun `the settings read nothing while off, and a bad value names its variable without its value`() {
        assertEquals(IncidentIssueSettings.DISABLED, IncidentIssueSettings.fromEnv(mapOf(IncidentIssueSettings.ENV_MAX_PER_HOUR to "999")))
        val bad = runCatching {
            IncidentIssueSettings.fromEnv(
                mapOf(IncidentIssueSettings.ENV_ENABLED to "true", IncidentIssueSettings.ENV_MAX_PER_HOUR to "999"),
            )
        }.exceptionOrNull()
        assertTrue(bad != null && bad.message.orEmpty().contains(IncidentIssueSettings.ENV_MAX_PER_HOUR), bad?.message)
        assertFalse(bad?.message.orEmpty().contains("999"))
    }

    @Test
    fun `the source counts failed and unknown self-development runs with their reason`() = TestDb().use { db ->
        val run = (db.runs.submitTask("operator", "incident-src", "Incident source", selfDev = true, maxActiveSelfDev = null) as TaskSubmitResult.Created).run
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = ?, state_reason = ? WHERE run_id = ?", "FAILED", "RUN_FAILED", run.runId.value)
        }
        val source = DbIncidentSource(db.database, SelfDevAutopilotControlRepository(db.database))
        val since = Instant.now().minus(Duration.ofHours(1))
        assertEquals(listOf(RunFailureCount("FAILED", "RUN_FAILED", 1)), source.failedRunCounts(since))
        assertTrue(source.failedRunCounts(Instant.now().plus(Duration.ofHours(1))).isEmpty())
        assertFalse(source.breakerOpen())
    }
}
