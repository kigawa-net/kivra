package net.kigawa.kivra.selfdev.autofix

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.github.GitHubPort

/**
 * The incident fix (issue #226) writes its own table and submits an autopilot task, and nothing else. It never reaches GitHub, never makes a
 * pull request ready, never merges or approves. The runner opens a fix's pull request as a draft and no other one. These tests read the
 * sources and the port, so a write outside that scope fails the build.
 */
class IncidentFixArchitectureTest {

    private val fixSources = File("src/main/kotlin/net/kigawa/kivra/selfdev/autofix")

    private fun sources() = fixSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no source of the fix names GitHub, a pull request write, a merge, an approval or an HTTP write`() {
        assertTrue(fixSources.isDirectory, "run the test from the kivra-app module directory: ${fixSources.absolutePath}")
        val forbidden = Regex(
            "GitHubPort|\\bgithub\\.|\\bcreateIssue\\b|\\bcreatePullRequest\\b|\\bensurePullRequest\\b|\\bcommitFiles\\b|\\bensureRunBranch\\b|" +
                "\\bmerge\\(|\\bmarkReady|ready_for_review|\\bAPPROVE\\b|\\bREQUEST_CHANGES\\b|\\bHttpMethod\\b|\"(PUT|PATCH|DELETE)\"|" +
                "\\bstartRun\\b|\\bcreatePullRequestReview\\b|\\bsubmitPullRequestReview\\b|\\bupdateRef\\b",
        )
        assertEquals(emptyList(), sources().filter { forbidden.containsMatchIn(it.readText()) }.map { it.name })
    }

    @Test
    fun `the task is submitted only through the operator submission, in one place`() {
        val hits = sources().filter { Regex("\\bsubmitTask\\(").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("IncidentFixWatch.kt"), hits)
    }

    @Test
    fun `the watch takes no GitHub port`() {
        val params = IncidentFixWatch::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertTrue(params.none { GitHubPort::class.java.isAssignableFrom(it) }, params.toString())
    }

    @Test
    fun `the runner sets a pull request draft only from the incident origin, and reads it before any GitHub write`() {
        val text = File("src/main/kotlin/net/kigawa/kivra/selfdev/SelfDevRunner.kt").readText()
        assertEquals(1, Regex("\\bdraft\\s*=").findAll(text).count())
        assertTrue(text.contains("draft = incidentIssue != null"))
        assertTrue(text.indexOf("incidentOrigin?.issueOf(runId)") in 1 until text.indexOf("github.ensureRunBranch(config.repo, runId"))
    }

    @Test
    fun `the port has no method that makes a pull request ready, merges it or approves it`() {
        val names = GitHubPort::class.java.methods.map { it.name }
        assertTrue(names.none { Regex("(?i)ready|merge|approve|requestchange").containsMatchIn(it) }, names.toString())
    }
}
