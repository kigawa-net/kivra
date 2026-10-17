package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import org.slf4j.LoggerFactory

/**
 * Issue #56 diagnosis: the rejection reasons are logged as fixed names, and paths named in the task text are shown to the model first.
 * The UNSEEN_OVERWRITE rule is not relaxed by any of this: a file the model was not shown is still refused.
 */
class SelfDevPolicyDiagnosisTest {
    private val docs33: Map<String, String> = (0 until 33).associate { "docs/a%02d.md".format(it) to "text $it\n" } +
        mapOf("README.md" to "# kivra\n", "src/Main.kt" to "fun main() = Unit\n")

    private fun runnerFor(
        db: TestDb,
        developer: DeveloperPort,
        files: Map<String, String>,
        github: FakeGitHub,
        title: String,
        config: SelfDevConfig = SelfDevConfig(enabled = true),
    ) = SelfDevRunner(
        config = config,
        runs = DbDevelopmentRunStore(db.runs),
        leases = db.leases,
        developer = developer,
        github = github,
        repoReader = InMemoryRepoReader(files, github.baseSha),
        taskTexts = FixedTaskTextSource(TaskText(title, title)),
    )

    private fun newRun(db: TestDb) = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

    // ---- rejection reasons in the log ----

    @Test
    fun aRejectionLogsOnlyTheFixedReasonNamesAndCount() = runBlocking {
        val pathMarker = "PATHMARK-zz"
        val secret = "hunter2hunter2"
        val logger = LoggerFactory.getLogger(SelfDevRunner::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            TestDb().use { db ->
                val dev = FakeDeveloper.returning(
                    DevFixtures.proposed(".github/$pathMarker/ci.yml" to "password = \"$secret\"\n", summary = "SUMMARY-MARK"),
                )
                val github = FakeGitHub()
                val runner = runnerFor(db, dev, docs33, github, title = "CI を直す")
                val runId = newRun(db)
                runner.advance(runId)
                assertEquals(
                    Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"),
                    runner.advance(runId),
                )
                assertEquals(emptyList(), github.writes)
            }
        } finally {
            logger.detachAppender(appender)
        }
        val rejected = appender.list.filter { it.formattedMessage.startsWith("self-development patch rejected") }
        assertEquals(1, rejected.size, appender.list.joinToString { it.formattedMessage })
        val line = rejected.single()
        assertEquals(Level.WARN, line.level)
        assertTrue(line.formattedMessage.contains("reasons=PATH_DENIED,SECRET_LIKE_CONTENT count=2"), line.formattedMessage)
        assertFalse(line.formattedMessage.contains("\n"))
        appender.list.forEach {
            val m = it.formattedMessage
            listOf(pathMarker, ".github", secret, "password", "SUMMARY-MARK", "CI を直す").forEach { leak ->
                assertFalse(m.contains(leak), "log leaked: $leak in $m")
            }
        }
    }

    // ---- the task text names the file to change ----

    @Test
    fun aFileNamedInTheTaskIsShownEvenWhenDocsHasManyFiles() = runBlocking {
        TestDb().use { db ->
            val files = docs33 + ("docs/selfdev-hello.md" to "hello\n")
            val dev = FakeDeveloper.returning(DevFixtures.proposed("docs/selfdev-hello.md" to "hello\nworld\n"))
            val github = FakeGitHub()
            val runner = runnerFor(db, dev, files, github, title = "docs/selfdev-hello.md に 1 行追記する")
            val runId = newRun(db)
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            val shown = dev.requests.single().repoContext.map { it.path }
            assertEquals("docs/selfdev-hello.md", shown.first())
            assertEquals(10, shown.size)
        }
    }

    @Test
    fun withoutTheNamedFileTheSameFileIsNotShown() {
        val files = docs33 + ("docs/selfdev-hello.md" to "hello\n")
        val entries = files.map { RepoEntry(it.key, it.value.toByteArray().size.toLong()) }
        val picked = ContextSelection.select(entries, SelfDevConfig(enabled = true)).map { it.path }
        assertFalse("docs/selfdev-hello.md" in picked)
    }

    @Test
    fun anUnseenFileIsStillRefusedWhenTheTaskDoesNotNameIt() = runBlocking {
        TestDb().use { db ->
            // docs/a32.md is in the tree but is beyond the 10-file context, and the title names only docs/a00.md.
            val dev = FakeDeveloper.returning(DevFixtures.proposed("docs/a32.md" to "replaced\n"))
            val github = FakeGitHub()
            val runner = runnerFor(db, dev, docs33, github, title = "docs/a00.md を直す")
            val runId = newRun(db)
            runner.advance(runId)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"),
                runner.advance(runId),
            )
            assertEquals(emptyList(), github.writes)
        }
    }

    // ---- extraction and selection rules ----

    @Test
    fun unsafeOrMissingPathsInTheTaskAreIgnored() {
        val text = "see /etc/passwd.md, ../x/y.md, a/../b.md, .git/config, docs/a.md. and README.md and docs/missing.md"
        assertEquals(listOf("docs/a.md", "README.md", "docs/missing.md"), TitlePaths.extract(text))

        val entries = listOf(
            RepoEntry("docs/a.md", 1),
            RepoEntry("README.md", 1, readable = false), // a symlink in the tree
            RepoEntry("kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Jwt.kt", 1), // denied for writes, so not read either
            RepoEntry("docs/big.md", 40L * 1024), // over the per-file limit
        )
        val picked = ContextSelection.select(
            entries,
            SelfDevConfig(enabled = true),
            titlePaths = listOf("docs/a.md", "README.md", "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Jwt.kt", "docs/big.md", "docs/missing.md", "../etc/x.md"),
            policy = PatchPolicy(),
        ).map { it.path }
        assertEquals(listOf("docs/a.md"), picked)
    }

    @Test
    fun namedFilesKeepTheTotalAndFileCountLimits() {
        val config = SelfDevConfig(enabled = true, maxContextFiles = 2, maxContextFileBytes = 100, maxContextTotalBytes = 15)
        val entries = listOf(
            RepoEntry("docs/a.md", 6),
            RepoEntry("docs/b.md", 6),
            RepoEntry("docs/c.md", 6),
            RepoEntry("README.md", 3),
        )
        val picked = ContextSelection.select(entries, config, titlePaths = listOf("docs/c.md", "docs/a.md", "docs/b.md", "README.md"))
            .map { it.path }
        // c and a fit the byte limit (12 of 15). The file count then stops the selection at 2, so b and README.md are not reached.
        assertEquals(listOf("docs/c.md", "docs/a.md"), picked)
    }

    @Test
    fun aTitleCannotPushTheSelectionPastItsLimits() {
        val config = SelfDevConfig(enabled = true, maxContextFiles = 3, maxContextTotalBytes = 1000)
        val entries = (1..30).map { RepoEntry("docs/f%02d.md".format(it), 1) }
        val title = (1..30).joinToString(" ") { "docs/f%02d.md".format(it) }
        val picked = ContextSelection.select(entries, config, TitlePaths.extract(title))
        assertEquals(3, picked.size)
        assertEquals(listOf("docs/f01.md", "docs/f02.md", "docs/f03.md"), picked.map { it.path })
    }

    @Test
    fun theTitleExtractionIsCappedAndDeduplicated() {
        val text = "docs/a.md docs/a.md docs/b.md docs/c.md docs/d.md docs/e.md docs/f.md"
        assertEquals(listOf("docs/a.md", "docs/b.md", "docs/c.md", "docs/d.md", "docs/e.md"), TitlePaths.extract(text))
    }
}
