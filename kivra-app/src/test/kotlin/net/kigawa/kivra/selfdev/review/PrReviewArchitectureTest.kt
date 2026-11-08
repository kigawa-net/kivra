package net.kigawa.kivra.selfdev.review

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.github.GitHubPort

/**
 * The AI review is a comment only (ADR 0008 addendum). These tests scan the review's sources and the port, so that an approval, a
 * request for changes, a merge, a label, an edit or a branch update fails the build.
 */
class PrReviewArchitectureTest {

    private val reviewSources = File("src/main/kotlin/net/kigawa/kivra/selfdev/review")

    private fun sources() = reviewSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no source of the review names a write other than the comment, an approval, a merge or a verb of update`() {
        assertTrue(reviewSources.isDirectory, "run the test from the kivra-app module directory: ${reviewSources.absolutePath}")
        val writes = Regex(
            "\\.(updatePrBranch|squashMerge|commitFiles|createPullRequest|ensurePullRequest|ensureRunBranch|createBranch|" +
                "mergePullRequest|createPullRequestReview|submitPullRequestReview|addLabel|updatePullRequest|updateRef|deleteRef|" +
                "addReviewComment|requestReviewers)\\b|\\bmerge\\(|\"(PUT|PATCH|DELETE)\"|\\bAPPROVE\\b|\\bREQUEST_CHANGES\\b|\\bHttpMethod\\b",
        )
        val hits = sources().filter { writes.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the review reads and posts through three port methods only`() {
        val used = sources().flatMap { f -> Regex("\\bgithub\\.(\\w+)\\(").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(setOf("getPullRequest", "getPullRequestDiff", "createPullRequestComment"), used)
    }

    @Test
    fun `the port gained the comment and the diff read, and no review or approval method`() {
        val names = GitHubPort::class.java.methods.map { it.name }
        assertEquals(1, names.count { it == "createPullRequestComment" })
        assertEquals(1, names.count { it == "getPullRequestDiff" })
        assertTrue(names.none { Regex("(create|submit|update|dismiss|approve|merge).*Review|approve|requestChanges", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }
}
