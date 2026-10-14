package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [CheckRunsSummary.allPassed] (issue #34, P2). `neutral` and `skipped` count as passed, matching GitHub's
 * branch protection. The merge decision must still be made on Required Checks by name.
 */
class CheckRunsConclusionTest {
    private val repo = RepoRef("kigawa-net", "kivra")
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Runs the real parser: one check run with the given status and conclusion (JSON literal, may be `null`). */
    private fun passedFor(status: String, conclusion: String?): Boolean {
        val conclusionJson = conclusion?.let { "\"$it\"" } ?: "null"
        val body = """{"total_count":1,"check_runs":[{"id":1,"name":"ci","head_sha":"$sha","status":"$status","conclusion":$conclusionJson}]}"""
        val adapter = HttpGitHubAdapter(
            githubHttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }),
            object : TokenProvider {
                override suspend fun token() = "tok-test"
                override fun invalidate() {}
            },
            backoff = {},
        )
        val summary = runBlocking { adapter.getCheckRuns(repo, sha) }
        return assertIs<GitHubResult.Success<CheckRunsSummary>>(summary).value.allPassed
    }

    @Test
    fun `success neutral and skipped count as passed`() {
        assertTrue(passedFor("completed", "success"))
        assertTrue(passedFor("completed", "neutral"))
        assertTrue(passedFor("completed", "skipped"))
    }

    @Test
    fun `failure cancelled timed out action required and stale do not count as passed`() {
        assertFalse(passedFor("completed", "failure"))
        assertFalse(passedFor("completed", "cancelled"))
        assertFalse(passedFor("completed", "timed_out"))
        assertFalse(passedFor("completed", "action_required"))
        assertFalse(passedFor("completed", "stale"))
    }

    @Test
    fun `an unrecognised conclusion does not count as passed`() {
        assertFalse(passedFor("completed", "something_new"))
    }

    @Test
    fun `an unfinished run does not count as passed even with a passing-looking conclusion`() {
        assertFalse(passedFor("queued", null))
        assertFalse(passedFor("in_progress", null))
        assertFalse(passedFor("in_progress", "success"))
    }

    @Test
    fun `an incomplete listing does not count as passed`() = runTest {
        // total_count 2 but only one run returned on the single page allowed: the summary is not authoritative.
        val body = """{"total_count":2,"check_runs":[{"id":1,"name":"ci","head_sha":"$sha","status":"completed","conclusion":"success"}]}"""
        val adapter = HttpGitHubAdapter(
            githubHttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }),
            object : TokenProvider {
                override suspend fun token() = "tok-test"
                override fun invalidate() {}
            },
            config = GitHubAdapterConfig(maxPages = 1),
            backoff = {},
        )
        val s = assertIs<GitHubResult.Success<CheckRunsSummary>>(adapter.getCheckRuns(repo, sha)).value
        assertFalse(s.complete)
        assertFalse(s.allPassed)
    }

    @Test
    fun `an empty listing does not count as passed`() = runTest {
        val summary = CheckRunsSummary(sha, emptyList(), totalCount = 0, complete = true)
        assertFalse(summary.allPassed)
    }
}
