package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Contract tests for PR / branch idempotency (issue #34 acceptance: no duplicate PR on retry, safe unknown outcomes).
// All traffic goes through MockEngine; nothing reaches the network.

private const val IDEM_SHA = "0123456789abcdef0123456789abcdef01234567"
private const val IDEM_SHA2 = "fedcba9876543210fedcba9876543210fedcba98"
private const val IDEM_TOKEN = "ghs_IDEM_SECRET_TOKEN"
private val IDEM_REPO = RepoRef("kigawa-net", "kivra")
private val IDEM_JSON = headersOf(HttpHeaders.ContentType, "application/json")

private class IdemFake(
    maxPages: Int = GitHubAdapterConfig().maxPages,
    val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()
    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; handler(req) }),
        object : TokenProvider { override suspend fun token() = IDEM_TOKEN },
        GitHubAdapterConfig(maxPages = maxPages),
        backoff = {},
    )
    fun count(method: HttpMethod) = requests.count { it.method == method }
}

private fun idemPr(
    number: Int,
    body: String = "run\n\nkivra-run:r1",
    state: String = "open",
    head: String = "kivra/run-r1",
    headRepo: String = "kigawa-net/kivra",
    headSha: String = IDEM_SHA,
): String = """{"number":$number,"state":"$state","merged":false,"draft":false,"body":${JsonPrimitive(body)},
    "html_url":"https://github.com/kigawa-net/kivra/pull/$number",
    "head":{"ref":"$head","sha":"$headSha","repo":{"full_name":"$headRepo"}},
    "base":{"ref":"main","sha":"$IDEM_SHA2","repo":{"full_name":"kigawa-net/kivra"}}}"""

private fun idemPage(from: Int, count: Int): String =
    (from until from + count).joinToString(",", "[", "]") { idemPr(it) }

private fun idemCreate() = CreatePullRequest("r1", "main", "title", "body", IDEM_SHA)

class GitHubPrIdempotencyContractTest {
    @Test
    fun `ensurePullRequest creates exactly one PR when none exists`() = runTest {
        val f = IdemFake { req ->
            if (req.method == HttpMethod.Post) respond(idemPr(7), HttpStatusCode.Created, IDEM_JSON)
            else respond("[]", HttpStatusCode.OK, IDEM_JSON)
        }
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).value
        assertTrue(r.created)
        assertEquals(7, r.pullRequest.number)
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensurePullRequest adopts the open own PR over closed ones, and the newest closed own PR otherwise`() = runTest {
        val mixed = IdemFake {
            respond("[${idemPr(3, state = "closed")},${idemPr(9)},${idemPr(12, state = "closed")}]", HttpStatusCode.OK, IDEM_JSON)
        }
        val open = assertIs<GitHubResult.Success<EnsuredPullRequest>>(mixed.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).value
        assertEquals(9, open.pullRequest.number)
        assertEquals(PullRequestState.OPEN, open.pullRequest.state)
        assertFalse(open.created)
        assertEquals(0, mixed.count(HttpMethod.Post))

        // A closed PR is returned as found (created=false) and nothing is re-created; the caller decides what a closed PR means.
        val closedOnly = IdemFake {
            respond("[${idemPr(3, state = "closed")},${idemPr(5, state = "closed")}]", HttpStatusCode.OK, IDEM_JSON)
        }
        val closed = assertIs<GitHubResult.Success<EnsuredPullRequest>>(closedOnly.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).value
        assertEquals(5, closed.pullRequest.number)
        assertEquals(PullRequestState.CLOSED, closed.pullRequest.state)
        assertFalse(closed.created)
        assertEquals(0, closedOnly.count(HttpMethod.Post))
    }

    @Test
    fun `a PR on our head whose body only resembles our marker is foreign and isolated, never adopted`() = runTest {
        // "kivra-run:r10" must not match run r1 (marker is compared line by line, exactly).
        val prefix = IdemFake { respond("[${idemPr(4, body = "kivra-run:r10")}]", HttpStatusCode.OK, IDEM_JSON) }
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(prefix.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).kind)

        val inline = IdemFake { respond("[${idemPr(4, body = "see kivra-run:r1 for details")}]", HttpStatusCode.OK, IDEM_JSON) }
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(inline.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).kind)

        // Our PR plus a hand-written PR on the same head: the foreign one wins and nothing is posted.
        val mixed = IdemFake { respond("[${idemPr(9)},${idemPr(10, body = "written by hand")}]", HttpStatusCode.OK, IDEM_JSON) }
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(mixed.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).kind)

        assertEquals(0, prefix.count(HttpMethod.Post) + inline.count(HttpMethod.Post) + mixed.count(HttpMethod.Post))
    }

    @Test
    fun `unknown create outcome with a foreign PR found afterwards is isolated, not adopted`() = runTest {
        var posted = false
        val f = IdemFake { req ->
            if (req.method == HttpMethod.Post) {
                posted = true
                throw IOException("timeout")
            }
            respond(if (posted) "[${idemPr(20, body = "written by hand")}]" else "[]", HttpStatusCode.OK, IDEM_JSON)
        }
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).kind)
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `a 409 on create followed by finding our own PR adopts it without a second POST`() = runTest {
        var posted = false
        val f = IdemFake { req ->
            if (req.method == HttpMethod.Post) {
                posted = true
                respond("""{"message":"conflict"}""", HttpStatusCode.Conflict, IDEM_JSON)
            } else {
                respond(if (posted) "[${idemPr(7)}]" else "[]", HttpStatusCode.OK, IDEM_JSON)
            }
        }
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).value
        assertFalse(r.created)
        assertEquals(7, r.pullRequest.number)
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `a 5xx on create is unknown, reconciled by search, and never re-POSTed`() = runTest {
        val absent = IdemFake { req ->
            if (req.method == HttpMethod.Post) respond("boom", HttpStatusCode.BadGateway) else respond("[]", HttpStatusCode.OK, IDEM_JSON)
        }
        assertIs<GitHubResult.Unknown>(absent.adapter.ensurePullRequest(IDEM_REPO, idemCreate()))
        assertEquals(1, absent.count(HttpMethod.Post))

        var posted = false
        val found = IdemFake { req ->
            if (req.method == HttpMethod.Post) {
                posted = true
                respond("boom", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(if (posted) "[${idemPr(8)}]" else "[]", HttpStatusCode.OK, IDEM_JSON)
            }
        }
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(found.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).value
        assertFalse(r.created)
        assertEquals(8, r.pullRequest.number)
        assertEquals(1, found.count(HttpMethod.Post))
    }

    @Test
    fun `when the reconciling search fails after an unknown create the outcome stays Unknown and nothing is re-POSTed`() = runTest {
        var posted = false
        val f = IdemFake { req ->
            when {
                req.method == HttpMethod.Post -> {
                    posted = true
                    throw IOException("timeout")
                }
                posted -> respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, IDEM_JSON)
                else -> respond("[]", HttpStatusCode.OK, IDEM_JSON)
            }
        }
        assertIs<GitHubResult.Unknown>(f.adapter.ensurePullRequest(IDEM_REPO, idemCreate()))
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `a 422 already-exists on create with no PR visible stays a CONFLICT failure and is not re-POSTed`() = runTest {
        val f = IdemFake { req ->
            if (req.method == HttpMethod.Post) {
                respond("""{"message":"Validation Failed: A pull request already exists"}""", HttpStatusCode.UnprocessableEntity, IDEM_JSON)
            } else {
                respond("[]", HttpStatusCode.OK, IDEM_JSON)
            }
        }
        assertEquals(GitHubFailureKind.CONFLICT, assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).kind)
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `findPullRequestsByHead follows pages until a short page`() = runTest {
        val f = IdemFake { req ->
            val page = req.url.parameters["page"]
            respond(
                when (page) {
                    "1" -> idemPage(1, 100)
                    "2" -> idemPage(101, 1)
                    else -> "[]"
                },
                HttpStatusCode.OK,
                IDEM_JSON,
            )
        }
        val prs = assertIs<GitHubResult.Success<List<PullRequest>>>(f.adapter.findPullRequestsByHead(IDEM_REPO, "kivra/run-r1")).value
        assertEquals(101, prs.size)
        assertEquals(2, f.count(HttpMethod.Get))
    }

    @Test
    fun `findPullRequestsByHead stops with PROTOCOL when pages never end within maxPages`() = runTest {
        val f = IdemFake(maxPages = 2) { respond(idemPage(1, 100), HttpStatusCode.OK, IDEM_JSON) }
        assertEquals(
            GitHubFailureKind.PROTOCOL,
            assertIs<GitHubResult.Failure>(f.adapter.findPullRequestsByHead(IDEM_REPO, "kivra/run-r1")).kind,
        )
        assertEquals(2, f.count(HttpMethod.Get))
    }

    @Test
    fun `a PR from a fork carrying the same branch name is an identity mismatch and is not adopted`() = runTest {
        val f = IdemFake { respond("[${idemPr(7, headRepo = "evil/kivra")}]", HttpStatusCode.OK, IDEM_JSON) }
        assertEquals(
            GitHubFailureKind.IDENTITY_MISMATCH,
            assertIs<GitHubResult.Failure>(f.adapter.findPullRequestsByHead(IDEM_REPO, "kivra/run-r1")).kind,
        )
        assertEquals(
            GitHubFailureKind.IDENTITY_MISMATCH,
            assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(IDEM_REPO, idemCreate())).kind,
        )
        assertEquals(0, f.count(HttpMethod.Post))
    }

    @Test
    fun `invalid run id is rejected before any request, and a non-lowercase-hex base sha is rejected before any POST`() = runTest {
        val f = IdemFake { respond("[]", HttpStatusCode.OK, IDEM_JSON) }
        assertFailsWith<IllegalArgumentException> { f.adapter.ensureRunBranch(IDEM_REPO, "../x", IDEM_SHA) }
        assertFailsWith<IllegalArgumentException> { f.adapter.createPullRequest(IDEM_REPO, CreatePullRequest("r1", "main", "t", "b", "xyz")) }
        assertTrue(f.requests.isEmpty())

        // The base sha is only needed on the create path: the branch is absent, so the adapter reaches createBranch.
        val absent = IdemFake { req ->
            if (req.url.encodedPath.contains("/git/ref/")) respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, IDEM_JSON)
            else respond("{}", HttpStatusCode.OK, IDEM_JSON)
        }
        assertFailsWith<IllegalArgumentException> { absent.adapter.ensureRunBranch(IDEM_REPO, "r1", IDEM_SHA.uppercase()) }
        assertEquals(0, absent.count(HttpMethod.Post))
    }

    @Test
    fun `createBranch 409 or 422 reference-exists is reconciled by name and reports the existing sha`() = runTest {
        for (status in listOf(HttpStatusCode.Conflict, HttpStatusCode.UnprocessableEntity)) {
            var posted = false
            val f = IdemFake { req ->
                when {
                    req.method == HttpMethod.Post -> {
                        posted = true
                        respond("""{"message":"Reference already exists"}""", status, IDEM_JSON)
                    }
                    req.url.encodedPath.contains("/git/ref/") && posted ->
                        respond("""{"ref":"refs/heads/kivra/run-r1","object":{"sha":"$IDEM_SHA2"}}""", HttpStatusCode.OK, IDEM_JSON)
                    req.url.encodedPath.contains("/git/ref/") -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, IDEM_JSON)
                    else -> respond("{}", HttpStatusCode.OK, IDEM_JSON)
                }
            }
            val r = assertIs<GitHubResult.Success<EnsuredBranch>>(f.adapter.ensureRunBranch(IDEM_REPO, "r1", IDEM_SHA)).value
            assertFalse(r.created, "status $status")
            assertEquals(IDEM_SHA2, r.branch.headSha, "status $status: caller compares the sha itself")
            assertEquals(1, f.count(HttpMethod.Post), "status $status")
        }
    }

    @Test
    fun `createBranch whose response points at another sha is an identity mismatch, not success`() = runTest {
        val f = IdemFake { respond("""{"ref":"refs/heads/kivra/run-r1","object":{"sha":"$IDEM_SHA2"}}""", HttpStatusCode.Created, IDEM_JSON) }
        assertEquals(
            GitHubFailureKind.IDENTITY_MISMATCH,
            assertIs<GitHubResult.Failure>(f.adapter.createBranch(IDEM_REPO, "kivra/run-r1", IDEM_SHA)).kind,
        )
    }

    @Test
    fun `a failed branch read never leads to a branch creation`() = runTest {
        val forbidden = IdemFake { respond("""{"message":"Resource not accessible by integration"}""", HttpStatusCode.Forbidden, IDEM_JSON) }
        assertEquals(
            GitHubFailureKind.PERMISSION,
            assertIs<GitHubResult.Failure>(forbidden.adapter.ensureRunBranch(IDEM_REPO, "r1", IDEM_SHA)).kind,
        )
        assertEquals(0, forbidden.count(HttpMethod.Post))

        val timeout = IdemFake { throw IOException("timeout") }
        assertEquals(GitHubFailureKind.NETWORK, assertIs<GitHubResult.Failure>(timeout.adapter.ensureRunBranch(IDEM_REPO, "r1", IDEM_SHA)).kind)
        assertEquals(0, timeout.count(HttpMethod.Post))
    }

    @Test
    fun `every operation the adapter performs is a GET or one of the two creation POSTs`() = runTest {
        val f = IdemFake { req ->
            if (req.method == HttpMethod.Post) respond(idemPr(7), HttpStatusCode.Created, IDEM_JSON)
            else respond("[]", HttpStatusCode.OK, IDEM_JSON)
        }
        val port: GitHubPort = f.adapter
        val create = idemCreate()
        port.getBranch(IDEM_REPO, "kivra/run-r1")
        port.ensureRunBranch(IDEM_REPO, "r1", IDEM_SHA)
        port.createBranch(IDEM_REPO, "kivra/run-r1", IDEM_SHA)
        port.createPullRequest(IDEM_REPO, create)
        port.ensurePullRequest(IDEM_REPO, create)
        port.getPullRequest(IDEM_REPO, 7)
        port.findPullRequestsByHead(IDEM_REPO, "kivra/run-r1")
        port.getCheckRuns(IDEM_REPO, IDEM_SHA)
        port.getCombinedStatus(IDEM_REPO, IDEM_SHA)
        port.getReviews(IDEM_REPO, 7)

        val allowedPosts = setOf("/repos/kigawa-net/kivra/git/refs", "/repos/kigawa-net/kivra/pulls")
        assertTrue(f.requests.isNotEmpty())
        val offending = f.requests.filterNot {
            it.method == HttpMethod.Get || (it.method == HttpMethod.Post && it.url.encodedPath in allowedPosts)
        }.map { "${it.method.value} ${it.url.encodedPath}" }
        assertTrue(offending.isEmpty(), "unexpected requests: $offending")
    }

    @Test
    fun `the installation token reaches only the Authorization header, never the URL or the body`() = runTest {
        val f = IdemFake { respond(idemPr(7), HttpStatusCode.Created, IDEM_JSON) }
        assertIs<GitHubResult.Success<PullRequest>>(f.adapter.createPullRequest(IDEM_REPO, idemCreate()))
        val req = f.requests.single()
        assertEquals("Bearer $IDEM_TOKEN", req.headers[HttpHeaders.Authorization])
        assertFalse(req.url.toString().contains(IDEM_TOKEN))
        assertFalse((req.body as TextContent).text.contains(IDEM_TOKEN))
    }
}
