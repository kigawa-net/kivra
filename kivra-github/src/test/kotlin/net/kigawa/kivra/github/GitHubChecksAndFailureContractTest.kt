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
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Contract tests for Checks / status pagination and the failure classification (issue #34 acceptance).
// All traffic goes through MockEngine; nothing reaches the network.

private const val CHK_SHA = "0123456789abcdef0123456789abcdef01234567"
private const val CHK_SHA2 = "fedcba9876543210fedcba9876543210fedcba98"
private val CHK_REPO = RepoRef("kigawa-net", "kivra")
private val CHK_JSON = headersOf(HttpHeaders.ContentType, "application/json")

private class ChkFake(
    maxPages: Int = GitHubAdapterConfig().maxPages,
    val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()
    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; handler(req) }),
        object : TokenProvider { override suspend fun token() = "ghs_CHK_SECRET" },
        GitHubAdapterConfig(maxPages = maxPages),
        backoff = {},
    )
    fun count(method: HttpMethod) = requests.count { it.method == method }
}

private fun chkHeaders(vararg h: Pair<String, String>) = headersOf(*h.map { it.first to listOf(it.second) }.toTypedArray())

private fun chkRun(id: Long, status: String = "completed", conclusion: String? = "success", sha: String = CHK_SHA): String =
    """{"id":$id,"name":"ci$id","head_sha":"$sha","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"}}"""

private fun chkRuns(from: Long, count: Int): String =
    (from until from + count).joinToString(",", "[", "]") { chkRun(it) }

private fun chkPage(total: Int, runs: String): String = """{"total_count":$total,"check_runs":$runs}"""

private fun chkCreate() = CreatePullRequest("r1", "main", "t", "b", CHK_SHA)

class GitHubChecksAndFailureContractTest {
    @Test
    fun `combined status rejects a response for another sha`() = runTest {
        val f = ChkFake {
            respond(
                """{"sha":"$CHK_SHA2","state":"success","statuses":[],"repository":{"full_name":"kigawa-net/kivra"}}""",
                HttpStatusCode.OK,
                CHK_JSON,
            )
        }
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(f.adapter.getCombinedStatus(CHK_REPO, CHK_SHA)).kind)
    }

    @Test
    fun `check runs are read page by page until total_count is reached`() = runTest {
        val f = ChkFake { req ->
            val page = req.url.parameters["page"]
            respond(if (page == "1") chkPage(101, chkRuns(1L, 100)) else chkPage(101, chkRuns(101L, 1)), HttpStatusCode.OK, CHK_JSON)
        }
        val s = assertIs<GitHubResult.Success<CheckRunsSummary>>(f.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).value
        assertEquals(101, s.runs.size)
        assertTrue(s.complete)
        assertTrue(s.allPassed)
        assertEquals(2, f.count(HttpMethod.Get))
    }

    @Test
    fun `an empty page before total_count is reached leaves the summary incomplete and not passed`() = runTest {
        val f = ChkFake { req ->
            respond(if (req.url.parameters["page"] == "1") chkPage(5, chkRuns(1L, 2)) else chkPage(5, "[]"), HttpStatusCode.OK, CHK_JSON)
        }
        val s = assertIs<GitHubResult.Success<CheckRunsSummary>>(f.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).value
        assertEquals(5, s.totalCount)
        assertFalse(s.complete)
        assertFalse(s.allPassed)
    }

    @Test
    fun `reaching maxPages leaves the summary incomplete and not passed, with no further request`() = runTest {
        val f = ChkFake(maxPages = 1) { respond(chkPage(101, chkRuns(1L, 100)), HttpStatusCode.OK, CHK_JSON) }
        val s = assertIs<GitHubResult.Success<CheckRunsSummary>>(f.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).value
        assertFalse(s.complete)
        assertFalse(s.allPassed)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `only a completed success passes, and any other run state does not`() = runTest {
        val notPassing = listOf(
            chkRun(1L, "completed", "failure"),
            chkRun(1L, "completed", "timed_out"),
            chkRun(1L, "completed", "cancelled"),
            chkRun(1L, "completed", "action_required"),
            chkRun(1L, "completed", "stale"),
            chkRun(1L, "completed", null),
            chkRun(1L, "in_progress", null),
            chkRun(1L, "queued", null),
        )
        for (run in notPassing) {
            val f = ChkFake { respond(chkPage(1, "[$run]"), HttpStatusCode.OK, CHK_JSON) }
            val s = assertIs<GitHubResult.Success<CheckRunsSummary>>(f.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).value
            assertFalse(s.allPassed, run)
        }
        val mixed = ChkFake { respond(chkPage(2, "[${chkRun(1L)},${chkRun(2L, "in_progress", null)}]"), HttpStatusCode.OK, CHK_JSON) }
        assertFalse(assertIs<GitHubResult.Success<CheckRunsSummary>>(mixed.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).value.allPassed)
    }

    @Test
    fun `checks and status reject a 404 as NOT_FOUND, retry a 5xx, and never accept a non-JSON body`() = runTest {
        val notFound = ChkFake { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, CHK_JSON) }
        assertEquals(GitHubFailureKind.NOT_FOUND, assertIs<GitHubResult.Failure>(notFound.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).kind)
        assertEquals(1, notFound.requests.size)

        val flaky = ChkFake { respond("boom", HttpStatusCode.ServiceUnavailable) }
        assertEquals(GitHubFailureKind.SERVER_ERROR, assertIs<GitHubResult.Failure>(flaky.adapter.getCombinedStatus(CHK_REPO, CHK_SHA)).kind)
        assertEquals(3, flaky.requests.size)

        val html = ChkFake { respond("<html>not json</html>", HttpStatusCode.OK, CHK_JSON) }
        assertEquals(GitHubFailureKind.PROTOCOL, assertIs<GitHubResult.Failure>(html.adapter.getCheckRuns(CHK_REPO, CHK_SHA)).kind)
        assertEquals(GitHubFailureKind.PROTOCOL, assertIs<GitHubResult.Failure>(html.adapter.getCombinedStatus(CHK_REPO, CHK_SHA)).kind)
    }

    @Test
    fun `check and status requests reject a non-lowercase-hex sha before any request`() = runTest {
        val f = ChkFake { respond(chkPage(0, "[]"), HttpStatusCode.OK, CHK_JSON) }
        assertFailsWith<IllegalArgumentException> { f.adapter.getCheckRuns(CHK_REPO, "main") }
        assertFailsWith<IllegalArgumentException> { f.adapter.getCombinedStatus(CHK_REPO, CHK_SHA.uppercase()) }
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `4xx failures map to fixed kinds, carry the HTTP status, and are sent exactly once`() = runTest {
        data class Case(val status: HttpStatusCode, val body: String, val headers: List<Pair<String, String>>, val kind: GitHubFailureKind)

        val cases = listOf(
            Case(HttpStatusCode.Forbidden, """{"message":"You have exceeded a secondary rate limit"}""", emptyList(), GitHubFailureKind.RATE_LIMITED),
            Case(HttpStatusCode.Forbidden, """{"message":"Resource not accessible by integration"}""", listOf("x-ratelimit-remaining" to "5"), GitHubFailureKind.PERMISSION),
            Case(HttpStatusCode.Forbidden, """{"message":"forbidden"}""", listOf("x-ratelimit-remaining" to "0"), GitHubFailureKind.RATE_LIMITED),
            Case(HttpStatusCode.TooManyRequests, "{}", emptyList(), GitHubFailureKind.RATE_LIMITED),
            Case(HttpStatusCode.NotFound, """{"message":"Not Found"}""", emptyList(), GitHubFailureKind.NOT_FOUND),
            Case(HttpStatusCode.Conflict, "{}", emptyList(), GitHubFailureKind.CONFLICT),
            Case(HttpStatusCode.UnprocessableEntity, """{"message":"Reference already exists"}""", emptyList(), GitHubFailureKind.CONFLICT),
            Case(HttpStatusCode.UnprocessableEntity, """{"message":"Validation Failed"}""", emptyList(), GitHubFailureKind.VALIDATION),
            Case(HttpStatusCode.BadRequest, "not json", emptyList(), GitHubFailureKind.VALIDATION),
        )
        for (c in cases) {
            val f = ChkFake { respond(c.body, c.status, chkHeaders(*c.headers.toTypedArray())) }
            val r = assertIs<GitHubResult.Failure>(f.adapter.getPullRequest(CHK_REPO, 7), "${c.status}")
            assertEquals(c.kind, r.kind, "${c.status} ${c.headers}")
            assertEquals(c.kind.code, r.message, "${c.status}")
            assertEquals(c.status.value, r.status, "${c.status}")
            assertEquals(1, f.requests.size, "${c.status} must not be retried")
        }
    }

    @Test
    fun `retry-after and x-ratelimit-reset are reported for rate limits only`() = runTest {
        val retryAfter = ChkFake { respond("{}", HttpStatusCode.TooManyRequests, chkHeaders(HttpHeaders.RetryAfter to "17")) }
        assertEquals(17L, assertIs<GitHubResult.Failure>(retryAfter.adapter.getPullRequest(CHK_REPO, 7)).retryAfterSeconds)

        val reset = (System.currentTimeMillis() / 1000 + 30).toString()
        val resetHeader = ChkFake {
            respond("{}", HttpStatusCode.Forbidden, chkHeaders("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset))
        }
        val delay = assertNotNull(assertIs<GitHubResult.Failure>(resetHeader.adapter.getPullRequest(CHK_REPO, 7)).retryAfterSeconds)
        assertTrue(delay in 25L..31L, "relative delay was $delay")

        val permission = ChkFake {
            respond("""{"message":"Resource not accessible by integration"}""", HttpStatusCode.Forbidden, CHK_JSON)
        }
        assertNull(assertIs<GitHubResult.Failure>(permission.adapter.getPullRequest(CHK_REPO, 7)).retryAfterSeconds)
    }

    @Test
    fun `a socket timeout is retried and NETWORK on a GET, and Unknown and never resent on a POST`() = runTest {
        val get = ChkFake { throw SocketTimeoutException("read timed out") }
        assertEquals(GitHubFailureKind.NETWORK, assertIs<GitHubResult.Failure>(get.adapter.getPullRequest(CHK_REPO, 7)).kind)
        assertEquals(3, get.requests.size)

        val post = ChkFake { throw SocketTimeoutException("read timed out") }
        assertIs<GitHubResult.Unknown>(post.adapter.createPullRequest(CHK_REPO, chkCreate()))
        assertEquals(1, post.requests.size)
    }

    @Test
    fun `a branch read that returns 404 but whose repository check fails is not read as no branch`() = runTest {
        val f = ChkFake { req ->
            if (req.url.encodedPath.contains("/git/ref/")) respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, CHK_JSON)
            else respond("boom", HttpStatusCode.ServiceUnavailable)
        }
        assertEquals(GitHubFailureKind.SERVER_ERROR, assertIs<GitHubResult.Failure>(f.adapter.getBranch(CHK_REPO, "kivra/run-r1")).kind)
        assertEquals(
            GitHubFailureKind.SERVER_ERROR,
            assertIs<GitHubResult.Failure>(f.adapter.ensureRunBranch(CHK_REPO, "r1", CHK_SHA)).kind,
        )
        assertEquals(0, f.count(HttpMethod.Post))
    }
}
