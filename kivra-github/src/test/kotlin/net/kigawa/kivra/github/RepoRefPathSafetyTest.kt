package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Issue #138: dot segments in owner/repo/branch must never reach an API path. Only MockEngine is used. */
class RepoRefPathSafetyTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val repo = RepoRef("kigawa-net", "kivra")

    private class CountingTokens : TokenProvider {
        var issued = 0
        override suspend fun token() = "tok${++issued}"
        override fun invalidate() {}
    }

    private class Harness {
        val requests = mutableListOf<HttpRequestData>()
        val tokens = CountingTokens()
        val adapter = HttpGitHubAdapter(
            githubHttpClient(MockEngine { req ->
                requests += req
                respond(
                    """{"ref":"refs/heads/feature..x","object":{"sha":"0123456789abcdef0123456789abcdef01234567"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            tokens,
            backoff = {},
        )
    }

    @Test
    fun `dot and dot-dot owners or repos and values containing dot-dot are rejected`() {
        assertFailsWith<IllegalArgumentException> { RepoRef("..", "x") }
        assertFailsWith<IllegalArgumentException> { RepoRef("x", "..") }
        assertFailsWith<IllegalArgumentException> { RepoRef(".", "x") }
        assertFailsWith<IllegalArgumentException> { RepoRef("x", ".") }
        assertFailsWith<IllegalArgumentException> { RepoRef("x", "a..b") }
        assertFailsWith<IllegalArgumentException> { RepoRef("a..b", "x") }
        assertFailsWith<IllegalArgumentException> { RepoRef("x", "repo.git") }
        assertFailsWith<IllegalArgumentException> { RepoRef("x", "") }
        assertFailsWith<IllegalArgumentException> { RepoRef("x", "a/b") }
    }

    @Test
    fun `owner follows the GitHub naming rule of alphanumerics and hyphens`() {
        assertFailsWith<IllegalArgumentException> { RepoRef("my_org", "x") }
        assertFailsWith<IllegalArgumentException> { RepoRef("my.org", "x") }
        assertFailsWith<IllegalArgumentException> { RepoRef("a".repeat(40), "x") }
    }

    @Test
    fun `valid owner and repo values are accepted`() {
        listOf(
            "kigawa-net" to "kivra",
            "kigawa-net" to "my.repo",
            "kigawa-net" to "a-b_c",
            "kigawa-net" to "kigawa-net-k8s",
            "kigawa" to "x",
        ).forEach { (owner, name) ->
            assertEquals("$owner/$name", RepoRef(owner, name).toString())
        }
    }

    @Test
    fun `rejection message echoes the value but no token`() {
        val ex = assertFailsWith<IllegalArgumentException> { RepoRef("x", "..") }
        assertFalse(ex.message.orEmpty().contains("tok"))
    }

    @Test
    fun `dot-segment branch is rejected before any request is sent`() = runTest {
        val h = Harness()
        assertFailsWith<IllegalArgumentException> { h.adapter.getBranch(repo, "..") }
        assertFailsWith<IllegalArgumentException> { h.adapter.getBranch(repo, ".") }
        assertFailsWith<IllegalArgumentException> { h.adapter.getBranch(repo, "feature/../main") }
        assertFailsWith<IllegalArgumentException> { h.adapter.getBranch(repo, "feature/./main") }
        assertFailsWith<IllegalArgumentException> { h.adapter.createBranch(repo, "..", sha) }
        assertFailsWith<IllegalArgumentException> { h.adapter.createBranch(repo, "a/../b", sha) }
        assertTrue(h.requests.isEmpty(), "no request may be sent for a dot-segment branch")
        assertEquals(0, h.tokens.issued, "no token may be issued for a rejected branch")
    }

    @Test
    fun `dot-dot inside a single branch segment is an ordinary name and is encoded`() = runTest {
        val h = Harness()
        val result = h.adapter.getBranch(repo, "feature..x")
        assertIs<GitHubResult.Success<*>>(result)
        assertEquals(1, h.requests.size)
        val segments = h.requests.single().url.encodedPath.split('/')
        assertEquals("feature..x", segments.last())
        assertFalse(segments.any { it == "." || it == ".." })
    }

    @Test
    fun `no request URL contains a dot segment`() = runTest {
        val h = Harness()
        h.adapter.getBranch(repo, "kivra/run-r1")
        h.adapter.getBranch(repo, "feature/x")
        h.adapter.getPullRequest(repo, 7)
        h.adapter.getCheckRuns(repo, sha)
        h.adapter.getCombinedStatus(repo, sha)
        h.adapter.getReviews(repo, 7)
        assertTrue(h.requests.isNotEmpty())
        h.requests.forEach { req ->
            val segments = req.url.encodedPath.split('/')
            assertFalse(segments.any { it == "." || it == ".." }, "dot segment in ${req.url.encodedPath}")
            assertFalse(req.url.toString().contains("/../"), "dot segment in request URL")
        }
    }

    @Test
    fun `failure results never contain the token`() = runTest {
        val h = Harness()
        runCatching { h.adapter.getBranch(repo, "..") }
        val ex = assertFailsWith<IllegalArgumentException> { h.adapter.getBranch(repo, "../x") }
        assertFalse(ex.toString().contains("tok"))
        assertFalse(ex.toString().contains("Bearer"))
        assertEquals(0, h.tokens.issued)
    }
}
