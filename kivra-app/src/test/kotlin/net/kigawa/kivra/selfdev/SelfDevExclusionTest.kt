package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Failed

/**
 * The read exclusion (ADR 0008, the RepoReader contract): a binary file, a symlink and a submodule under the context prefix must not fail
 * the run. They are left out of the LLM context, and a write to them is refused as UNSEEN_OVERWRITE. A real read failure still fails.
 */
class SelfDevExclusionTest {
    private val entries = listOf(
        RepoEntry("README.md", 10),
        RepoEntry("docs/a.md", 10),
        RepoEntry("docs/logo.png", 4),
        RepoEntry("docs/link", 3, readable = false),
        RepoEntry("docs/sub", 0, readable = false),
    )
    private val texts = mapOf("README.md" to "# kivra\n", "docs/a.md" to "old text\n")

    /** Mirrors GitHubRepoReader: a path in [excluded] reads as `Success(null)`, a path in [broken] is a real failure. */
    private class Reader(
        private val entries: List<RepoEntry>,
        private val texts: Map<String, String>,
        private val excluded: Set<String> = emptySet(),
        private val broken: Set<String> = emptySet(),
    ) : RepoReader {
        override suspend fun listFiles(repo: RepoRef, commitSha: String): GitHubResult<List<RepoEntry>> = GitHubResult.Success(entries)

        override suspend fun readFile(repo: RepoRef, commitSha: String, path: String): GitHubResult<String?> = when (path) {
            in broken -> GitHubResult.Failure(GitHubFailureKind.SERVER_ERROR, GitHubFailureKind.SERVER_ERROR.code)
            in excluded -> GitHubResult.Success(null)
            else -> GitHubResult.Success(texts.getValue(path))
        }
    }

    private fun runner(db: TestDb, github: FakeGitHub, developer: DeveloperPort, reader: RepoReader) = SelfDevRunner(
        config = SelfDevConfig(enabled = true),
        runs = DbDevelopmentRunStore(db.runs),
        leases = db.leases,
        developer = developer,
        github = github,
        repoReader = reader,
        taskTexts = FixedTaskTextSource(DevFixtures.taskText),
    )

    @Test
    fun `excluded files do not fail the run and are left out of the context`() = runBlocking {
        TestDb().use { db ->
            val github = FakeGitHub()
            val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new text\n"))
            val runner = runner(db, github, developer, Reader(entries, texts, excluded = setOf("docs/logo.png")))
            val runId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING), runner.advance(runId))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(listOf("README.md", "docs/a.md"), developer.requests.single().repoContext.map { it.path })
            assertEquals(1, github.pullRequests().size)
        }
    }

    @Test
    fun `a write to an excluded file is refused as UNSEEN_OVERWRITE and nothing is committed`() = runBlocking {
        for (path in listOf("docs/logo.png", "docs/link", "docs/sub")) {
            TestDb().use { db ->
                val github = FakeGitHub()
                val developer = FakeDeveloper.returning(DevFixtures.proposed(path to "invented content\n"))
                val runner = runner(db, github, developer, Reader(entries, texts, excluded = setOf("docs/logo.png")))
                val runId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

                runner.advance(runId)
                runner.advance(runId)

                val run = db.runs.get(runId)!!
                assertEquals(DevelopmentRunState.FAILED, run.state, path)
                assertEquals("POLICY_REJECTED", run.stateReason, path)
                assertTrue(github.writes.isEmpty(), path)
            }
        }
    }

    @Test
    fun `a real read failure still fails the run before the model is called`() = runBlocking {
        TestDb().use { db ->
            val github = FakeGitHub()
            val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "x"))
            val runner = runner(db, github, developer, Reader(entries, texts, broken = setOf("docs/a.md")))
            val runId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

            runner.advance(runId)
            assertEquals(Failed(FailureCode.GITHUB_READ_FAILED), runner.advance(runId))
            assertEquals(0, developer.callCount)
            assertTrue(github.writes.isEmpty())
        }
    }
}
