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
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SHA = "0123456789abcdef0123456789abcdef01234567"
private const val SHA2 = "fedcba9876543210fedcba9876543210fedcba98"
private val REPO = RepoRef("kigawa-net", "kivra")
private val JSON_H = headersOf(HttpHeaders.ContentType, "application/json")

private class Fake(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val tokens = CountingTokens()
    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; handler(req) }),
        tokens,
        backoff = {},
    )
    fun count(method: HttpMethod) = requests.count { it.method == method }
}

private class CountingTokens : TokenProvider {
    var invalidations = 0
    var issued = 0
    override suspend fun token() = "tok${++issued}"
    override fun invalidate() { invalidations++ }
}

private fun prJson(number: Int = 7, head: String = "kivra/run-r1", repo: String = "kigawa-net/kivra", body: String = "x\\n\\nkivra-run:r1", state: String = "open") =
    """{"number":$number,"state":"$state","draft":false,"merged":false,"body":"$body","html_url":"https://github.com/$repo/pull/$number",
        "head":{"ref":"$head","sha":"$SHA","label":"kigawa-net:$head","repo":{"full_name":"$repo"}},
        "base":{"ref":"main","sha":"$SHA2","repo":{"full_name":"$repo"}}}"""

private val CREATE = CreatePullRequest("r1", "main", "t", "b", SHA)

class HttpGitHubAdapterTest {
    @Test
    fun `port exposes no merge force push delete or ref update`() {
        val forbidden = Regex("merge|force|push|delete|update|remove|close", RegexOption.IGNORE_CASE)
        val names = GitHubPort::class.java.methods.map { it.name }
        assertTrue(names.none { forbidden.containsMatchIn(it) }, "forbidden operation on GitHubPort: $names")
    }

    @Test
    fun `runbranch name is deterministic and validated`() {
        assertEquals("kivra/run-abc_1", RunBranch.name("abc_1"))
        assertFailsWithIllegalArgument { RunBranch.name("a/../b") }
        assertFailsWithIllegalArgument { RunBranch.name("") }
    }

    private fun assertFailsWithIllegalArgument(f: () -> Unit) {
        try { f(); throw AssertionError("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `GitHub API endpoint requires HTTPS and an approved origin`() {
        assertFailsWithIllegalArgument { GitHubAdapterConfig(apiBase = "http://api.github.com") }
        assertFailsWithIllegalArgument { GitHubAdapterConfig(apiBase = "https://unapproved.example") }
        assertFailsWithIllegalArgument { GitHubAdapterConfig(apiBase = "https://user:pass@api.github.com") }
        assertFailsWithIllegalArgument { GitHubAdapterConfig(apiBase = "https://api.github.com?token=secret") }
        assertFailsWithIllegalArgument { GitHubAdapterConfig(apiBase = "https://api.github.com/#unsafe") }
        assertFailsWithIllegalArgument { GitHubAdapterConfig(apiBase = "https://api.github.com/../evil") }
        val enterprise = GitHubAdapterConfig(
            apiBase = "https://github.enterprise.example/api/v3",
            trustedApiHosts = setOf("github.enterprise.example"),
        )
        assertEquals("https://github.enterprise.example/api/v3", enterprise.apiBase)
    }

    @Test
    fun `bearer authenticated GitHub API redirects are never followed`() = runTest {
        val f = Fake {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://untrusted.example/steal"),
            )
        }
        val result = f.adapter.getPullRequest(REPO, 7)
        assertIs<GitHubResult.Failure>(result)
        assertEquals(1, f.requests.size, "no second request may be sent to the redirect target")
        assertEquals("api.github.com", f.requests.single().url.host)
        assertTrue(f.requests.single().headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true)
        assertTrue(f.requests.none { it.url.host == "untrusted.example" })
    }

    @Test
    fun `getBranch returns null for missing ref when repo exists`() = runTest {
        val f = Fake { req ->
            if (req.url.encodedPath.contains("/git/ref/")) respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON_H)
            else respond("""{"full_name":"kigawa-net/kivra"}""", HttpStatusCode.OK, JSON_H)
        }
        assertEquals(GitHubResult.Success(null), f.adapter.getBranch(REPO, "kivra/run-r1"))
    }

    @Test
    fun `getBranch is NOT_FOUND failure when the repo itself is missing`() = runTest {
        val f = Fake { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON_H) }
        val r = f.adapter.getBranch(REPO, "kivra/run-r1")
        assertEquals(GitHubFailureKind.NOT_FOUND, assertIs<GitHubResult.Failure>(r).kind)
    }

    @Test
    fun `getBranch rejects a response for a different ref`() = runTest {
        val f = Fake { respond("""{"ref":"refs/heads/kivra/run-other","object":{"sha":"$SHA"}}""", HttpStatusCode.OK, JSON_H) }
        val r = f.adapter.getBranch(REPO, "kivra/run-r1")
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(r).kind)
    }

    @Test
    fun `ensureRunBranch reuses an existing branch without POST`() = runTest {
        val f = Fake { respond("""{"ref":"refs/heads/kivra/run-r1","object":{"sha":"$SHA2"}}""", HttpStatusCode.OK, JSON_H) }
        val r = assertIs<GitHubResult.Success<EnsuredBranch>>(f.adapter.ensureRunBranch(REPO, "r1", SHA))
        assertFalse(r.value.created)
        assertEquals(SHA2, r.value.branch.headSha)
        assertEquals(0, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensureRunBranch creates when absent`() = runTest {
        val f = Fake { req ->
            when {
                req.method == HttpMethod.Post -> respond("""{"ref":"refs/heads/kivra/run-r1","object":{"sha":"$SHA"}}""", HttpStatusCode.Created, JSON_H)
                req.url.encodedPath.contains("/git/ref/") -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON_H)
                else -> respond("""{"full_name":"kigawa-net/kivra"}""", HttpStatusCode.OK, JSON_H)
            }
        }
        val r = assertIs<GitHubResult.Success<EnsuredBranch>>(f.adapter.ensureRunBranch(REPO, "r1", SHA))
        assertTrue(r.value.created)
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `createBranch timeout is Unknown and not resent`() = runTest {
        val f = Fake { throw IOException("timeout") }
        val r = f.adapter.createBranch(REPO, "kivra/run-r1", SHA)
        assertIs<GitHubResult.Unknown>(r)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `HTTP engine exception details are never exposed as an Unknown result`() = runTest {
        val secret = "Authorization: Bearer DO-NOT-LOG-THIS-TOKEN"
        val f = Fake { throw IOException("network error $secret") }
        val result = assertIs<GitHubResult.Unknown>(
            f.adapter.createBranch(REPO, "kivra/run-r1", SHA),
        )
        assertFalse(result.cause.contains(secret))
        assertTrue(result.cause.contains("IOException"))
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensureRunBranch after unknown create adopts the branch found by name`() = runTest {
        var gets = 0
        val f = Fake { req ->
            when {
                req.method == HttpMethod.Post -> throw IOException("timeout")
                req.url.encodedPath.contains("/git/ref/") && gets++ == 0 -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON_H)
                req.url.encodedPath.contains("/git/ref/") -> respond("""{"ref":"refs/heads/kivra/run-r1","object":{"sha":"$SHA"}}""", HttpStatusCode.OK, JSON_H)
                else -> respond("""{"full_name":"kigawa-net/kivra"}""", HttpStatusCode.OK, JSON_H)
            }
        }
        val r = assertIs<GitHubResult.Success<EnsuredBranch>>(f.adapter.ensureRunBranch(REPO, "r1", SHA))
        assertFalse(r.value.created)
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `createPullRequest sends deterministic head and markers`() = runTest {
        var sent = ""
        val f = Fake { req ->
            sent = (req.body as io.ktor.http.content.TextContent).text
            respond(prJson(), HttpStatusCode.Created, JSON_H)
        }
        val r = assertIs<GitHubResult.Success<PullRequest>>(f.adapter.createPullRequest(REPO, CREATE))
        assertEquals(7, r.value.number)
        assertTrue(sent.contains("\"head\":\"kivra/run-r1\""), sent)
        assertTrue(sent.contains("kivra-run:r1") && sent.contains("kivra-head-sha:$SHA"), sent)
    }

    @Test
    fun `createPullRequest 5xx is Unknown and never resent`() = runTest {
        val f = Fake { respond("boom", HttpStatusCode.BadGateway) }
        assertIs<GitHubResult.Unknown>(f.adapter.createPullRequest(REPO, CREATE))
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `createPullRequest rejects response for another repo`() = runTest {
        val f = Fake { respond(prJson(repo = "evil/other"), HttpStatusCode.Created, JSON_H) }
        val r = f.adapter.createPullRequest(REPO, CREATE)
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(r).kind)
    }

    @Test
    fun `getPullRequest rejects a different PR number`() = runTest {
        val f = Fake { respond(prJson(number = 8), HttpStatusCode.OK, JSON_H) }
        val r = f.adapter.getPullRequest(REPO, 7)
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(r).kind)
    }

    @Test
    fun `ensurePullRequest does not duplicate when a PR already exists`() = runTest {
        val f = Fake { respond("[${prJson()}]", HttpStatusCode.OK, JSON_H) }
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(REPO, CREATE))
        assertFalse(r.value.created)
        assertEquals(7, r.value.pullRequest.number)
        assertEquals(0, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensurePullRequest retried after lost response finds the PR instead of creating another`() = runTest {
        var created = false
        val f = Fake { req ->
            if (req.method == HttpMethod.Post) { created = true; throw IOException("timeout") }
            respond(if (created) "[${prJson()}]" else "[]", HttpStatusCode.OK, JSON_H)
        }
        // First call: search empty, create times out, re-search finds the PR created server-side.
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(REPO, CREATE))
        assertEquals(7, r.value.pullRequest.number)
        // Second call (retry of the same run): found by head, no further POST.
        assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(REPO, CREATE))
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensurePullRequest stays Unknown when the PR cannot be found after a timeout`() = runTest {
        val f = Fake { req -> if (req.method == HttpMethod.Post) throw IOException("timeout") else respond("[]", HttpStatusCode.OK, JSON_H) }
        assertIs<GitHubResult.Unknown>(f.adapter.ensurePullRequest(REPO, CREATE))
        assertEquals(1, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensurePullRequest isolates a foreign PR on our head`() = runTest {
        val f = Fake { respond("[${prJson(body = "hand written")}]", HttpStatusCode.OK, JSON_H) }
        val r = f.adapter.ensurePullRequest(REPO, CREATE)
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(r).kind)
        assertEquals(0, f.count(HttpMethod.Post))
    }

    @Test
    fun `ensurePullRequest recovers from 422 already exists by searching`() = runTest {
        var posted = false
        val f = Fake { req ->
            if (req.method == HttpMethod.Post) { posted = true; respond("""{"message":"Validation Failed: A pull request already exists"}""", HttpStatusCode.UnprocessableEntity, JSON_H) }
            else respond(if (posted) "[${prJson()}]" else "[]", HttpStatusCode.OK, JSON_H)
        }
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(REPO, CREATE))
        assertFalse(r.value.created)
    }

    @Test
    fun `search rejects PRs whose head differs from the requested branch`() = runTest {
        val f = Fake { respond("[${prJson(head = "someone/else")}]", HttpStatusCode.OK, JSON_H) }
        val r = f.adapter.findPullRequestsByHead(REPO, "kivra/run-r1")
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(r).kind)
    }

    @Test
    fun `GET retries on 5xx and then succeeds`() = runTest {
        var n = 0
        val f = Fake { if (n++ < 2) respond("x", HttpStatusCode.ServiceUnavailable) else respond(prJson(), HttpStatusCode.OK, JSON_H) }
        assertIs<GitHubResult.Success<PullRequest>>(f.adapter.getPullRequest(REPO, 7))
        assertEquals(3, f.requests.size)
    }

    @Test
    fun `GET gives up after max attempts with SERVER_ERROR`() = runTest {
        val f = Fake { respond("x", HttpStatusCode.BadGateway) }
        val r = f.adapter.getPullRequest(REPO, 7)
        assertEquals(GitHubFailureKind.SERVER_ERROR, assertIs<GitHubResult.Failure>(r).kind)
        assertEquals(3, f.requests.size)
    }

    @Test
    fun `GET retries I-O errors then reports NETWORK`() = runTest {
        val f = Fake { throw IOException("reset") }
        val r = f.adapter.getPullRequest(REPO, 7)
        assertEquals(GitHubFailureKind.NETWORK, assertIs<GitHubResult.Failure>(r).kind)
        assertEquals(3, f.requests.size)
    }

    @Test
    fun `401 on GET refreshes the token once`() = runTest {
        var n = 0
        val f = Fake { if (n++ == 0) respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, JSON_H) else respond(prJson(), HttpStatusCode.OK, JSON_H) }
        assertIs<GitHubResult.Success<PullRequest>>(f.adapter.getPullRequest(REPO, 7))
        assertEquals(1, f.tokens.invalidations)
        assertEquals("Bearer tok2", f.requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `persistent 401 is AUTH`() = runTest {
        val f = Fake { respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, JSON_H) }
        assertEquals(GitHubFailureKind.AUTH, assertIs<GitHubResult.Failure>(f.adapter.getPullRequest(REPO, 7)).kind)
    }

    @Test
    fun `failures are classified`() = runTest {
        suspend fun kindOf(status: HttpStatusCode, body: String = "{}", vararg h: Pair<String, String>): GitHubResult.Failure {
            val f = Fake { respond(body, status, headersOf(*h.map { it.first to listOf(it.second) }.toTypedArray())) }
            return assertIs<GitHubResult.Failure>(f.adapter.getPullRequest(REPO, 7))
        }
        assertEquals(GitHubFailureKind.PERMISSION, kindOf(HttpStatusCode.Forbidden, """{"message":"Resource not accessible by integration"}""").kind)
        val rl = kindOf(HttpStatusCode.Forbidden, """{"message":"API rate limit exceeded"}""", "x-ratelimit-remaining" to "0", "retry-after" to "42")
        assertEquals(GitHubFailureKind.RATE_LIMITED, rl.kind)
        assertEquals(42L, rl.retryAfterSeconds)
        assertEquals(GitHubFailureKind.RATE_LIMITED, kindOf(HttpStatusCode.TooManyRequests).kind)
        assertEquals(GitHubFailureKind.NOT_FOUND, kindOf(HttpStatusCode.NotFound).kind)
        assertEquals(GitHubFailureKind.CONFLICT, kindOf(HttpStatusCode.Conflict).kind)
        assertEquals(GitHubFailureKind.VALIDATION, kindOf(HttpStatusCode.UnprocessableEntity, """{"message":"Validation Failed"}""").kind)
    }

    @Test
    fun `check runs verify head sha and paginate`() = runTest {
        fun run(id: Int, sha: String = SHA) = """{"id":$id,"name":"ci$id","head_sha":"$sha","status":"completed","conclusion":"success"}"""
        val f = Fake { respond("""{"total_count":2,"check_runs":[${run(1)},${run(2)}]}""", HttpStatusCode.OK, JSON_H) }
        val s = assertIs<GitHubResult.Success<CheckRunsSummary>>(f.adapter.getCheckRuns(REPO, SHA)).value
        assertTrue(s.allPassed)
        val bad = Fake { respond("""{"total_count":1,"check_runs":[${run(1, SHA2)}]}""", HttpStatusCode.OK, JSON_H) }
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(bad.adapter.getCheckRuns(REPO, SHA)).kind)
    }

    @Test
    fun `no check runs is not passed and failure conclusion is not passed`() = runTest {
        val empty = Fake { respond("""{"total_count":0,"check_runs":[]}""", HttpStatusCode.OK, JSON_H) }
        assertFalse(assertIs<GitHubResult.Success<CheckRunsSummary>>(empty.adapter.getCheckRuns(REPO, SHA)).value.allPassed)
        val failed = Fake { respond("""{"total_count":1,"check_runs":[{"id":1,"name":"a","head_sha":"$SHA","status":"completed","conclusion":"failure"}]}""", HttpStatusCode.OK, JSON_H) }
        assertFalse(assertIs<GitHubResult.Success<CheckRunsSummary>>(failed.adapter.getCheckRuns(REPO, SHA)).value.allPassed)
    }

    @Test
    fun `combined status verifies sha and repo`() = runTest {
        val ok = Fake { respond("""{"sha":"$SHA","state":"success","statuses":[{"context":"ci","state":"success"}],"repository":{"full_name":"kigawa-net/kivra"}}""", HttpStatusCode.OK, JSON_H) }
        assertEquals("success", assertIs<GitHubResult.Success<CombinedStatus>>(ok.adapter.getCombinedStatus(REPO, SHA)).value.state)
        val bad = Fake { respond("""{"sha":"$SHA","state":"success","statuses":[],"repository":{"full_name":"x/y"}}""", HttpStatusCode.OK, JSON_H) }
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(bad.adapter.getCombinedStatus(REPO, SHA)).kind)
    }

    @Test
    fun `reviews verify the target pull request`() = runTest {
        fun rv(url: String) = """[{"id":1,"state":"APPROVED","commit_id":"$SHA","user":{"login":"bot"},"pull_request_url":"$url"}]"""
        val ok = Fake { respond(rv("https://api.github.com/repos/kigawa-net/kivra/pulls/7"), HttpStatusCode.OK, JSON_H) }
        val r = assertIs<GitHubResult.Success<List<Review>>>(ok.adapter.getReviews(REPO, 7)).value
        assertEquals("APPROVED", r.single().state)
        assertEquals(SHA, r.single().commitId)
        val bad = Fake { respond(rv("https://api.github.com/repos/kigawa-net/kivra/pulls/8"), HttpStatusCode.OK, JSON_H) }
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(bad.adapter.getReviews(REPO, 7)).kind)
    }

    @Test
    fun `installation token is cached and refreshed before expiry`() = runTest {
        var now = java.time.Instant.parse("2026-10-12T00:00:00Z")
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        var minted = 0
        val p = InstallationTokenProvider("1", 2, { _, _ -> "jwt" }, { jwt, id ->
            assertEquals("jwt", jwt); assertEquals(2L, id)
            InstallationToken("t${++minted}", now.plusSeconds(3600))
        }, clock)
        assertEquals("t1", p.token())
        assertEquals("t1", p.token())
        now = now.plusSeconds(3600 - 60)
        assertEquals("t2", p.token())
        p.invalidate()
        assertEquals("t3", p.token())
    }

    @Test
    fun `rsa signer produces a three part jwt`() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----"
        val jwt = RsaJwtSigner(pem).sign("123", java.time.Instant.parse("2026-10-12T00:00:00Z"))
        assertEquals(3, jwt.split('.').size)
        assertNotNull(jwt)
    }
}
