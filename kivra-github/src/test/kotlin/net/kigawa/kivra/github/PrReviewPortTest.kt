package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The two calls of the AI review: the diff read (GET, diff media type) and the comment (one POST, never re-sent). */
class PrReviewPortTest {

    private val repo = RepoRef("kigawa-net", "kivra")
    private val token = object : TokenProvider {
        override suspend fun token() = "ghs_PRREVIEW_TOKEN"
        override fun invalidate() {}
    }

    private class Remote(val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val requests = mutableListOf<HttpRequestData>()
    }

    private fun adapter(remote: Remote, token: TokenProvider) = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> remote.requests += req; remote.handler(this, req) }),
        token,
        backoff = {},
    )

    @Test
    fun `the diff is read with the diff media type, and a longer diff is refused`() = runTest {
        val diff = "diff --git a/x b/x\n+hello\n"
        val remote = Remote { respond(diff, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) }
        val ok = adapter(remote, token).getPullRequestDiff(repo, 7, maxBytes = 1_000)
        assertEquals(diff, assertIs<GitHubResult.Success<String>>(ok).value)
        val req = remote.requests.single()
        assertEquals(HttpMethod.Get, req.method)
        assertTrue(req.url.encodedPath.endsWith("/repos/kigawa-net/kivra/pulls/7"), req.url.encodedPath)
        assertEquals("application/vnd.github.diff", req.headers[HttpHeaders.Accept])

        val big = Remote { respond("x".repeat(2_000), HttpStatusCode.OK) }
        val tooLarge = adapter(big, token).getPullRequestDiff(repo, 7, maxBytes = 1_000)
        assertEquals(GitHubFailureKind.TOO_LARGE, assertIs<GitHubResult.Failure>(tooLarge).kind)
    }

    @Test
    fun `the comment is one POST to the issue comments of the pull request, and its id is returned`() = runTest {
        val remote = Remote { respond("""{"id":4242,"body":"x"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json")) }
        val result = adapter(remote, token).createPullRequestComment(repo, 7, "hello")
        assertEquals(4242L, assertIs<GitHubResult.Success<Long>>(result).value)
        val req = remote.requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.encodedPath.endsWith("/repos/kigawa-net/kivra/issues/7/comments"), req.url.encodedPath)
        val sent = (req.body as TextContent).text
        assertEquals("""{"body":"hello"}""", sent)
    }

    @Test
    fun `a comment whose response fails is not sent again`() = runTest {
        val remote = Remote { respond("", HttpStatusCode.InternalServerError) }
        val result = adapter(remote, token).createPullRequestComment(repo, 7, "hello")
        assertIs<GitHubResult.Unknown>(result)
        assertEquals(1, remote.requests.size)
    }
}
