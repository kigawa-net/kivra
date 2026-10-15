package net.kigawa.kivra.selfdev

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
