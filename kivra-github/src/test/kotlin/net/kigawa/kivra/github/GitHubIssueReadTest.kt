package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// GitHubIssueReader.getIssue. All traffic goes through MockEngine. Redirects are never followed (the client is built with followRedirects=false).

private const val IR_TOKEN = "ghs_IR_SECRET_TOKEN_VALUE"
private val IR_REPO = RepoRef("kigawa-net", "kivra")
private val IR_JSON = headersOf(HttpHeaders.ContentType, "application/json")

private class IssueRemote(val handler: MockRequestHandleScope.(HttpRequestData, Int) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    private val tokens = object : TokenProvider {
        override suspend fun token() = IR_TOKEN
        override fun invalidate() {}
    }
    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; handler(req, requests.size) }),
        tokens,
        backoff = {},
    )
}

private fun issueJson(number: Long = 226, title: String = "Title", body: String? = "Body", state: String = "open", pr: Boolean = false): String {
    val bodyJson = if (body == null) "null" else "\"" + body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    val prJson = if (pr) ",\"pull_request\":{\"url\":\"https://api.github.com/x\"}" else ""
    return """{"number":$number,"title":"$title","body":$bodyJson,"state":"$state"$prJson}"""
}

class GitHubIssueReadTest {
    @Test
    fun `reads number title body and state from the issues endpoint with a GET`() = runTest {
        val remote = IssueRemote { _, _ -> respond(issueJson(title = "Fix it", body = "line1\nline2"), HttpStatusCode.OK, IR_JSON) }
        val result = remote.adapter.getIssue(IR_REPO, 226)
        val issue = assertIs<GitHubResult.Success<GitHubIssue>>(result).value
        assertEquals(IR_REPO, issue.repo)
        assertEquals(226L, issue.number)
        assertEquals("Fix it", issue.title)
        assertEquals("line1\nline2", issue.body)
        assertTrue(issue.open)
        assertTrue(!issue.isPullRequest)
        assertEquals(HttpMethod.Get, remote.requests.single().method)
        assertTrue(remote.requests.single().url.encodedPath.endsWith("/repos/kigawa-net/kivra/issues/226"))
    }

    @Test
    fun `a null body reads as an empty body and a closed issue is reported as not open`() = runTest {
        val remote = IssueRemote { _, _ -> respond(issueJson(body = null, state = "closed"), HttpStatusCode.OK, IR_JSON) }
        val issue = assertIs<GitHubResult.Success<GitHubIssue>>(remote.adapter.getIssue(IR_REPO, 226)).value
        assertEquals("", issue.body)
        assertTrue(!issue.open)
    }

    @Test
    fun `a pull request answered by the issues endpoint is marked`() = runTest {
        val remote = IssueRemote { _, _ -> respond(issueJson(pr = true), HttpStatusCode.OK, IR_JSON) }
        val issue = assertIs<GitHubResult.Success<GitHubIssue>>(remote.adapter.getIssue(IR_REPO, 226)).value
        assertTrue(issue.isPullRequest)
    }

    @Test
    fun `a number that differs from the request is an identity mismatch`() = runTest {
        val remote = IssueRemote { _, _ -> respond(issueJson(number = 999), HttpStatusCode.OK, IR_JSON) }
        val r = assertIs<GitHubResult.Failure>(remote.adapter.getIssue(IR_REPO, 226))
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, r.kind)
    }

    @Test
    fun `404 and 403 are returned at once, without a retry`() = runTest {
        val notFound = IssueRemote { _, _ -> respond("{\"message\":\"Not Found\"}", HttpStatusCode.NotFound, IR_JSON) }
        assertEquals(GitHubFailureKind.NOT_FOUND, assertIs<GitHubResult.Failure>(notFound.adapter.getIssue(IR_REPO, 226)).kind)
        assertEquals(1, notFound.requests.size)

        val forbidden = IssueRemote { _, _ -> respond("{\"message\":\"Resource not accessible\"}", HttpStatusCode.Forbidden, IR_JSON) }
        assertEquals(GitHubFailureKind.PERMISSION, assertIs<GitHubResult.Failure>(forbidden.adapter.getIssue(IR_REPO, 226)).kind)
        assertEquals(1, forbidden.requests.size)
    }

    @Test
    fun `5xx is retried and a later success is returned`() = runTest {
        val remote = IssueRemote { _, n -> if (n < 2) respond("oops", HttpStatusCode.BadGateway) else respond(issueJson(), HttpStatusCode.OK, IR_JSON) }
        assertIs<GitHubResult.Success<GitHubIssue>>(remote.adapter.getIssue(IR_REPO, 226))
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun `persistent 5xx is retried a fixed number of times and then fails`() = runTest {
        val remote = IssueRemote { _, _ -> respond("oops", HttpStatusCode.InternalServerError) }
        assertEquals(GitHubFailureKind.SERVER_ERROR, assertIs<GitHubResult.Failure>(remote.adapter.getIssue(IR_REPO, 226)).kind)
        assertEquals(3, remote.requests.size) // one try and two retries (GitHubAdapterConfig.maxGetAttempts)
    }

    @Test
    fun `a network failure is retried and then fails`() = runTest {
        val remote = IssueRemote { _, _ -> throw IOException("boom") }
        assertEquals(GitHubFailureKind.NETWORK, assertIs<GitHubResult.Failure>(remote.adapter.getIssue(IR_REPO, 226)).kind)
        assertEquals(3, remote.requests.size)
    }

    @Test
    fun `a redirect is not followed`() = runTest {
        val remote = IssueRemote { _, _ ->
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://evil.example/issues/226"))
        }
        assertEquals(GitHubFailureKind.REDIRECT, assertIs<GitHubResult.Failure>(remote.adapter.getIssue(IR_REPO, 226)).kind)
        assertEquals(1, remote.requests.size)
        assertTrue(remote.requests.all { it.url.host == "api.github.com" })
    }

    @Test
    fun `a response above the size limit is too large and is not decoded`() = runTest {
        val huge = "x".repeat(ISSUE_MAX_RESPONSE_BYTES + 10)
        val remote = IssueRemote { _, _ -> respond("{\"number\":226,\"title\":\"t\",\"body\":\"$huge\",\"state\":\"open\"}", HttpStatusCode.OK, IR_JSON) }
        assertEquals(GitHubFailureKind.TOO_LARGE, assertIs<GitHubResult.Failure>(remote.adapter.getIssue(IR_REPO, 226)).kind)
        assertEquals(1, remote.requests.size)
    }

    @Test
    fun `a non-positive number is refused before any request`() = runTest {
        val remote = IssueRemote { _, _ -> respond(issueJson(), HttpStatusCode.OK, IR_JSON) }
        assertIs<GitHubResult.Failure>(remote.adapter.getIssue(IR_REPO, 0))
        assertEquals(0, remote.requests.size)
    }
}
