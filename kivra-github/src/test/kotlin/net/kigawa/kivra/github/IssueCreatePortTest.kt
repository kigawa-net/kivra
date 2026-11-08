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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The issue creation of the incident detector (issue #225): one POST to the issues of the repository, never re-sent. */
class IssueCreatePortTest {

    private val repo = RepoRef("kigawa-net", "kivra")
    private val token = object : TokenProvider {
        override suspend fun token() = "ghs_ISSUE_TOKEN"
        override fun invalidate() {}
    }

    private class Remote(val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val requests = mutableListOf<HttpRequestData>()
    }

    private fun adapter(remote: Remote) = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> remote.requests += req; remote.handler(this, req) }),
        token,
        backoff = {},
    )

    @Test
    fun `the issue is one POST to the repository issues with its title and body, and its number is returned`() = runTest {
        val remote = Remote { respond("""{"number":321,"title":"t"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json")) }
        val result = adapter(remote).createIssue(repo, CreateIssue(title = "[kivra-incident] RUN_FAILED X", body = "hello"))
        assertEquals(321L, assertIs<GitHubResult.Success<Long>>(result).value)
        val req = remote.requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.encodedPath.endsWith("/repos/kigawa-net/kivra/issues"), req.url.encodedPath)
        assertEquals("""{"title":"[kivra-incident] RUN_FAILED X","body":"hello"}""", (req.body as TextContent).text)
    }

    @Test
    fun `a failed or unknown create is not sent again`() = runTest {
        val failing = Remote { respond("", HttpStatusCode.Forbidden) }
        val refused = adapter(failing).createIssue(repo, CreateIssue("t", "b"))
        assertIs<GitHubResult.Failure>(refused)
        assertEquals(1, failing.requests.size)

        val lost = Remote { respond("", HttpStatusCode.InternalServerError) }
        val unknown = adapter(lost).createIssue(repo, CreateIssue("t", "b"))
        assertIs<GitHubResult.Unknown>(unknown)
        assertEquals(1, lost.requests.size)
    }

    @Test
    fun `the request bounds its title and body before any call`() {
        assertFailsWith<IllegalArgumentException> { CreateIssue(title = "", body = "b") }
        assertFailsWith<IllegalArgumentException> { CreateIssue(title = "x".repeat(CreateIssue.MAX_TITLE + 1), body = "b") }
        assertFailsWith<IllegalArgumentException> { CreateIssue(title = "t", body = "x".repeat(CreateIssue.MAX_BODY + 1)) }
    }
}
