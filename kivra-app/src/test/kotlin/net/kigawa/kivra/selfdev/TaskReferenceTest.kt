package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.coding.CodingClaimOutcome
import net.kigawa.kivra.coding.CodingInput
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.coding.TaskBodyText
import net.kigawa.kivra.coding.Trust
import net.kigawa.kivra.coding.UnmeteredCodingCost
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubIssue
import net.kigawa.kivra.github.GitHubIssueReader
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Task references (Issue #229, addendum). The resolver, the body composition, the runner (resolve once, fail the run on a bad reference,
 * never touch another run), the claim, the idempotency rule and the log hygiene. Issue reads go to [FakeIssueReader]; nothing reaches GitHub.
 */
class TaskReferenceTest {
    private val target = RepoRef("kigawa-net", "kivra")
    private val issueBodyMarker = "ISSUE-BODY-MARKER"
    private val issueTitleMarker = "ISSUE-TITLE-MARKER"
    private val files = mapOf("README.md" to "# kivra\n", "docs/a.md" to "old text\n")

    private fun issue(number: Long, title: String = "$issueTitleMarker fix", body: String = "$issueBodyMarker 手順", open: Boolean = true, pr: Boolean = false) =
        GitHubResult.Success(GitHubIssue(target, number, title, body, open, pr))

    private fun resolver(reader: GitHubIssueReader) = GitHubIssueResolver(reader, target)

    // ---- resolver ----

    @Test
    fun `the three reference forms resolve to the same issue, with its title and body`() = runBlocking {
        val reader = FakeIssueReader(mapOf(226L to issue(226)))
        val r = resolver(reader)
        for (title in listOf("https://github.com/kigawa-net/kivra/issues/226", "#226", "kigawa-net/kivra#226")) {
            val result = assertIs<TaskReferenceResult.Resolved>(r.resolve(title), title)
            assertEquals("kigawa-net/kivra#226", result.source)
            assertTrue(result.text.startsWith("Issue #226: $issueTitleMarker fix\n\n$issueBodyMarker 手順"), result.text)
        }
        assertEquals(listOf(target to 226L, target to 226L, target to 226L), reader.calls)
    }

    @Test
    fun `the owner and repo are compared without case, and the source is the canonical form`() = runBlocking {
        val result = assertIs<TaskReferenceResult.Resolved>(resolver(FakeIssueReader(mapOf(7L to issue(7)))).resolve("Kigawa-Net/KIVRA#7"))
        assertEquals("kigawa-net/kivra#7", result.source)
    }

    @Test
    fun `a title that is not a reference as a whole is not applicable and reads nothing`() = runBlocking {
        val reader = FakeIssueReader(emptyMap())
        val r = resolver(reader)
        for (title in listOf("README を直す", "see #226", "#226 を直す", "https://example.com/kigawa-net/kivra/issues/226", "#0", "kigawa-net/kivra#")) {
            assertEquals(TaskReferenceResult.NotApplicable, r.resolve(title), title)
        }
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `a repository other than the target is refused before any read`() = runBlocking {
        val reader = FakeIssueReader(mapOf(1L to issue(1)))
        val r = resolver(reader)
        for (title in listOf("https://github.com/kigawa-net/other/issues/1", "other/kivra#1", "https://github.com/someone/kivra/issues/1")) {
            assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.REPO_NOT_ALLOWED), r.resolve(title), title)
        }
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `a pull request, a closed issue, a missing issue and a forbidden one each have their fixed code`() = runBlocking {
        val reader = FakeIssueReader(
            mapOf(
                1L to issue(1, pr = true),
                2L to issue(2, open = false),
                3L to GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, "NOT_FOUND"),
                4L to GitHubResult.Failure(GitHubFailureKind.PERMISSION, "PERMISSION"),
                5L to GitHubResult.Failure(GitHubFailureKind.SERVER_ERROR, "SERVER_ERROR"),
                6L to GitHubResult.Unknown("getIssue", "timeout"),
                8L to GitHubResult.Failure(GitHubFailureKind.TOO_LARGE, "TOO_LARGE"),
            ),
        )
        val r = resolver(reader)
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.PULL_REQUEST), r.resolve("#1"))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.CLOSED), r.resolve("#2"))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.NOT_FOUND), r.resolve("#3"))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.FORBIDDEN), r.resolve("#4"))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.UNAVAILABLE), r.resolve("#5"))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.UNAVAILABLE), r.resolve("#6"))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.RESPONSE_TOO_LARGE), r.resolve("#8"))
    }

    @Test
    fun `a huge issue body is cut to the limit with the truncation mark`() = runBlocking {
        val huge = "あ".repeat(TaskBodyText.MAX_CHARS * 2)
        val result = assertIs<TaskReferenceResult.Resolved>(resolver(FakeIssueReader(mapOf(9L to issue(9, body = huge)))).resolve("#9"))
        assertTrue(TaskBodyText.codePoints(result.text) <= TaskBodyText.MAX_CHARS)
        assertTrue(TaskBodyText.isTruncated(result.text))
    }

    @Test
    fun `control characters from the issue are removed, and the title stays on one line`() = runBlocking {
        val result = assertIs<TaskReferenceResult.Resolved>(
            resolver(FakeIssueReader(mapOf(10L to issue(10, title = "a\nb", body = "x\u0007y\r\nz\tw")))).resolve("#10"),
        )
        assertEquals("Issue #10: a b\n\nxy\nz\tw", result.text)
    }

    @Test
    fun `resolvers are tried in order and the first answer that is not not-applicable wins`() = runBlocking {
        val first = TaskReferenceResolver { title -> if (title == "special") TaskReferenceResult.Failed(TaskReferenceFailure.CLOSED) else TaskReferenceResult.NotApplicable }
        val second = resolver(FakeIssueReader(mapOf(1L to issue(1))))
        val chain = TaskReferenceResolvers(listOf(first, second))
        assertEquals(TaskReferenceResult.Failed(TaskReferenceFailure.CLOSED), chain.resolve("special"))
        assertIs<TaskReferenceResult.Resolved>(chain.resolve("#1"))
        assertEquals(TaskReferenceResult.NotApplicable, TaskReferenceResolvers(emptyList()).resolve("#1"))
    }

    // ---- body composition ----

    @Test
    fun `a manual body is kept first and the reference follows after the separator, within the limit`() {
        val composed = assertIs<String>(TaskBodyText.compose("MANUAL", "Issue #1: t\n\nbody"))
        assertEquals("MANUAL" + TaskBodyText.REFERENCE_SEPARATOR + "Issue #1: t\n\nbody", composed)
    }

    @Test
    fun `a long manual body leaves room for the reference only up to the limit, and clips it with the mark`() {
        val manual = "m".repeat(7_000)
        val composed = assertIs<String>(TaskBodyText.compose(manual, "r".repeat(TaskBodyText.MAX_CHARS)))
        assertTrue(TaskBodyText.codePoints(composed) <= TaskBodyText.MAX_CHARS)
        assertTrue(composed.startsWith(manual + TaskBodyText.REFERENCE_SEPARATOR))
        assertTrue(TaskBodyText.isTruncated(composed))
    }

    @Test
    fun `a manual body that leaves too little room fails the composition instead of dropping the reference`() {
        assertNull(TaskBodyText.compose("m".repeat(7_900), "r"))
    }

    // ---- database rules: idempotency of the submission, and the one-time reference ----

    @Test
    fun `the same key with the same body replays, another body conflicts, and no body equals an empty one`() = TestDb().use { db ->
        val first = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "k1", "README を直す", selfDev = true, body = "手順 A"))
        assertIs<TaskSubmitResult.Replayed>(db.runs.submitTask("alice", "k1", "README を直す", selfDev = true, body = "手順 A"))
        assertIs<TaskSubmitResult.ConflictingTitle>(db.runs.submitTask("alice", "k1", "README を直す", selfDev = true, body = "手順 B"))
        assertIs<TaskSubmitResult.ConflictingTitle>(db.runs.submitTask("alice", "k1", "README を直す", selfDev = true, body = null))
        assertEquals("手順 A", first.run.taskBody)

        val plain = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "k2", "README を直す", selfDev = true))
        assertNull(plain.run.taskBody)
        assertIs<TaskSubmitResult.Replayed>(db.runs.submitTask("alice", "k2", "README を直す", selfDev = true, body = null))
    }

    @Test
    fun `a title-only submission stores no body and no source`() = TestDb().use { db ->
        val run = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "k3", "README を直す", selfDev = true)).run
        assertNull(run.taskBody)
        assertNull(run.taskSource)
    }

    // ---- runner ----

    private inner class Harness(val db: TestDb, reader: GitHubIssueReader) {
        val github = FakeGitHub(target)
        val service = CodingRuntimeService(
            attempts = CodingAttemptRepository(db.database),
            runs = DbCodingRunPort(db.runs, db.leases),
            results = DbCodingResultStore(db.database),
            tasks = CodingTaskSource { it.taskTitle },
            cost = UnmeteredCodingCost,
        )
        val runner = SelfDevRunner(
            config = SelfDevConfig(enabled = true, runtime = SelfDevRuntime.CLI, repo = target),
            runs = DbDevelopmentRunStore(db.runs),
            leases = db.leases,
            developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "x\n")),
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = DbTaskTextSource,
            clock = Clock.systemUTC(),
            coding = CodingAttemptPatchSource(service),
            references = TaskReferenceResolvers(listOf(resolver(reader))),
        )

        fun submit(title: String, body: String? = null, key: String = "key-${System.nanoTime()}"): RunId =
            db.runs.submitTask("alice", key, title, selfDev = true, body = body).run.runId

        /** CREATED -> DEVELOPING, then the second advance resolves (if needed) and queues the attempt. */
        suspend fun toDeveloping(runId: RunId) {
            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING), runner.advance(runId))
        }

        fun state(runId: RunId): DevelopmentRunState = db.runs.get(runId)!!.state
    }

    @Test
    fun `a reference title is read once, stored with the run, and the attempt is queued with it`() = runBlocking {
        TestDb().use { db ->
            val reader = FakeIssueReader(mapOf(226L to issue(226)))
            val h = Harness(db, reader)
            val runId = h.submit("https://github.com/kigawa-net/kivra/issues/226")
            h.toDeveloping(runId)
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), h.runner.advance(runId))
            // A later advance never reads the issue again (idempotent: the source is already stored).
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), h.runner.advance(runId))
            assertEquals(1, reader.calls.size)

            val run = db.runs.get(runId)!!
            assertEquals("kigawa-net/kivra#226", run.taskSource)
            assertTrue(run.taskBody!!.contains(issueBodyMarker))

            val input = assertIs<CodingClaimOutcome.Claimed>(h.service.claim("agent-1")).input
            assertEquals(Trust.UNTRUSTED, input.taskBody.trust)
            assertEquals("task_body", input.taskBody.origin)
            assertTrue(input.taskBody.value.contains(issueBodyMarker))
            assertFalse(input.taskBody.truncated)
        }
    }

    @Test
    fun `a manual body with a reference gives the manual text first, then the separator and the issue`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, FakeIssueReader(mapOf(3L to issue(3))))
            val runId = h.submit("#3", body = "先に読む: docs/a.md")
            h.toDeveloping(runId)
            h.runner.advance(runId)
            val body = db.runs.get(runId)!!.taskBody!!
            assertTrue(body.startsWith("先に読む: docs/a.md" + TaskBodyText.REFERENCE_SEPARATOR + "Issue #3:"), body)
        }
    }

    @Test
    fun `a bad reference fails only its own run, with the state reason and the sub reason, and no attempt is queued`() = runBlocking {
        TestDb().use { db ->
            val reader = FakeIssueReader(mapOf(1L to issue(1)))
            val h = Harness(db, reader)
            val bad = h.submit("https://github.com/kigawa-net/other/issues/1")
            val good = h.submit("README を直す")
            h.toDeveloping(bad)
            h.toDeveloping(good)

            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "TASK_REFERENCE_UNRESOLVED"),
                h.runner.advance(bad),
            )
            val failed = db.runs.get(bad)!!
            assertEquals(DevelopmentRunState.FAILED, failed.state)
            assertEquals("TASK_REFERENCE_UNRESOLVED", failed.stateReason)
            assertNull(h.service.latest(bad.value))
            assertTrue(db.runs.auditTrail(bad).any { it.toState == "FAILED" && it.reasonCode == "TASK_REFERENCE_REPO_NOT_ALLOWED" })
            assertTrue(reader.calls.isEmpty())

            // The other run is not held back by the failure.
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), h.runner.advance(good))
            assertEquals(DevelopmentRunState.DEVELOPING, h.state(good))
        }
    }

    @Test
    fun `a title that is not a reference keeps the old behaviour with no read and no body`() = runBlocking {
        TestDb().use { db ->
            val reader = FakeIssueReader(emptyMap())
            val h = Harness(db, reader)
            val runId = h.submit("README を直す")
            h.toDeveloping(runId)
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), h.runner.advance(runId))
            assertTrue(reader.calls.isEmpty())
            val run = db.runs.get(runId)!!
            assertNull(run.taskBody)
            assertNull(run.taskSource)
            val input: CodingInput = assertIs<CodingClaimOutcome.Claimed>(h.service.claim("agent-1")).input
            assertEquals("", input.taskBody.value)
            assertFalse(input.taskBody.truncated)
        }
    }

    @Test
    fun `the stored reference is written once and a second save is refused`() = TestDb().use { db ->
        val runId = db.runs.submitTask("alice", "k9", "README を直す", selfDev = true).run.runId
        transaction(db.database) { execUpdate("UPDATE development_runs SET state = 'DEVELOPING' WHERE run_id = ?", runId.value) }
        val lease = db.leases.acquire(runId, "w1", java.time.Duration.ofMinutes(2), java.time.Duration.ZERO)!!
        val store = DbDevelopmentRunStore(db.runs)
        val version = db.runs.get(runId)!!.version
        store.saveTaskReference(lease, version, "body", "kigawa-net/kivra#1")
        val again = runCatching { store.saveTaskReference(lease, version + 1, "other", "kigawa-net/kivra#2") }
        assertTrue(again.isFailure)
        assertEquals("kigawa-net/kivra#1", db.runs.get(runId)!!.taskSource)
    }

    // ---- log hygiene ----

    @Test
    fun `the issue body, the issue title and the task body never reach the logs`() = runBlocking {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            TestDb().use { db ->
                val h = Harness(db, FakeIssueReader(mapOf(1L to issue(1), 2L to issue(2, open = false))))
                val ok = h.submit("#1", body = "MANUAL-BODY-MARKER")
                val closed = h.submit("#2")
                h.toDeveloping(ok)
                h.toDeveloping(closed)
                h.runner.advance(ok)
                h.runner.advance(closed)
            }
            val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.argumentArray ?: emptyArray()).joinToString(" ") }
            assertTrue(logged.contains("task reference resolved"), "the resolution is logged with its fixed fields")
            for (marker in listOf(issueBodyMarker, issueTitleMarker, "MANUAL-BODY-MARKER")) {
                assertFalse(logged.contains(marker), "a log line carries $marker")
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    // ---- test doubles ----

    private class FakeIssueReader(private val issues: Map<Long, GitHubResult<GitHubIssue>>) : GitHubIssueReader {
        val calls = mutableListOf<Pair<RepoRef, Long>>()

        override suspend fun getIssue(repo: RepoRef, number: Long): GitHubResult<GitHubIssue> {
            calls += repo to number
            return issues[number] ?: GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, GitHubFailureKind.NOT_FOUND.code)
        }
    }
}
