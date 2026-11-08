package net.kigawa.kivra.selfdev.incident

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.github.GitHubPort

/**
 * The incident detector files issues and nothing else (issue #225). These tests scan its sources and the port, so that a comment, an edit,
 * a close, a label, a merge, an approval or a Run start fails the build.
 */
class IncidentArchitectureTest {

    private val incidentSources = File("src/main/kotlin/net/kigawa/kivra/selfdev/incident")

    private fun sources() = incidentSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no source of the detector names a write other than the issue creation`() {
        assertTrue(incidentSources.isDirectory, "run the test from the kivra-app module directory: ${incidentSources.absolutePath}")
        val writes = Regex(
            "\\.(updatePrBranch|squashMerge|commitFiles|createPullRequest|ensurePullRequest|ensureRunBranch|createBranch|" +
                "mergePullRequest|createPullRequestReview|submitPullRequestReview|createPullRequestComment|createIssueComment|" +
                "updateIssue|closeIssue|editIssue|addLabel|addIssueLabels|updatePullRequest|updateRef|deleteRef|requestReviewers)\\b|" +
                "\\bmerge\\(|\"(PUT|PATCH|DELETE)\"|\\bAPPROVE\\b|\\bREQUEST_CHANGES\\b|\\bHttpMethod\\b|\\bstartRun\\b|\\bsubmitTask\\b",
        )
        val hits = sources().filter { writes.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the detector reaches GitHub through the issue creation only`() {
        val used = sources().flatMap { f -> Regex("\\bgithub\\.(\\w+)\\(").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(setOf("createIssue"), used)
    }

    @Test
    fun `the port gained the issue creation and no issue edit, close, label or comment method`() {
        val names = GitHubPort::class.java.methods.map { it.name }
        assertEquals(1, names.count { it == "createIssue" })
        assertTrue(
            names.none { Regex("(update|edit|close|reopen|label|comment|lock|delete).*Issue|Issue.*(label|comment|close|edit)", RegexOption.IGNORE_CASE).containsMatchIn(it) },
            names.toString(),
        )
    }
}
