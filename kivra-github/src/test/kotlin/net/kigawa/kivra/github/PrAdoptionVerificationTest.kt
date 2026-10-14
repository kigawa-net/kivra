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
import kotlin.test.assertTrue

/** Issue #34 P1: a PR on our head is adopted only when marker, head repo, head ref, head sha and base ref all match. */
class PrAdoptionVerificationTest {
    private val repo = RepoRef("kigawa-net", "kivra")
    private val requested = "0123456789abcdef0123456789abcdef01234567"
    private val stale = "fedcba9876543210fedcba9876543210fedcba98"
    private val baseSha = "1111111111111111111111111111111111111111"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /**
     * [pulls] is evaluated per GET, so a test can change the listing after a POST. [post] answers the POST.
     * The adapter is built on a MockEngine only: no request ever reaches the network.
     */
    private inner class Fake(
        val pulls: () -> String,
        val post: suspend MockRequestHandleScope.() -> HttpResponseData = {
            respond("{}", HttpStatusCode.Created, jsonHeaders)
        },
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val adapter = HttpGitHubAdapter(
            githubHttpClient(
                MockEngine { req ->
                    requests += req
                    if (req.method == HttpMethod.Post) post() else respond(pulls(), HttpStatusCode.OK, jsonHeaders)
                },
            ),
            StaticTokens,
            backoff = {},
        )
        fun posts() = requests.count { it.method == HttpMethod.Post }
    }

    private object StaticTokens : TokenProvider {
        override suspend fun token() = "tok-test-value"
        override fun invalidate() {}
    }

    /** A PR JSON object. [body] must already be JSON-escaped (use `\\n` for newlines). */
    private fun pr(
        number: Int = 7,
        state: String = "open",
        head: String = "kivra/run-r1",
        headRepo: String = "kigawa-net/kivra",
        headSha: String = requested,
        base: String = "main",
        body: String = "task\\n\\nkivra-run:r1",
    ) = """{"number":$number,"state":"$state","merged":false,"draft":false,"body":"$body",
        "html_url":"https://github.com/kigawa-net/kivra/pull/$number","node_id":"PR_$number",
        "head":{"ref":"$head","sha":"$headSha","repo":{"full_name":"$headRepo"}},
        "base":{"ref":"$base","sha":"$baseSha","repo":{"full_name":"kigawa-net/kivra"}}}"""

    private fun list(vararg prs: String) = prs.joinToString(prefix = "[", postfix = "]")

    private val create = CreatePullRequest("r1", "main", "title", "body", requested)

    @Test
    fun `an open PR with matching marker head sha and base is adopted without POST`() = runTest {
        val f = Fake({ list(pr()) })
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(repo, create))
        assertFalse(r.value.created)
        assertEquals(7, r.value.pullRequest.number)
        assertEquals(0, f.posts())
    }

    @Test
    fun `an open PR whose head sha is not the requested sha is not adopted and no PR is created`() = runTest {
        val f = Fake({ list(pr(headSha = stale)) })
        val r = assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(repo, create))
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, r.kind)
        assertEquals(0, f.posts())
    }

    @Test
    fun `an open PR with a different base ref is not adopted and no PR is created`() = runTest {
        val f = Fake({ list(pr(base = "release")) })
        val r = assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(repo, create))
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, r.kind)
        assertEquals(0, f.posts())
    }

    @Test
    fun `a fork PR on the same head name is not adopted`() = runTest {
        val f = Fake({ list(pr(headRepo = "someone-else/kivra")) })
        val r = assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(repo, create))
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, r.kind)
        assertEquals(0, f.posts())
    }

    @Test
    fun `a marker that only partly matches the run id is treated as foreign`() = runTest {
        val abc = CreatePullRequest("abc", "main", "title", "body", requested)
        val longer = Fake({ list(pr(head = "kivra/run-abc", body = "task\\n\\nkivra-run:abcd")) })
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(longer.adapter.ensurePullRequest(repo, abc)).kind)
        assertEquals(0, longer.posts())

        val shorter = CreatePullRequest("abcd", "main", "title", "body", requested)
        val shortMarker = Fake({ list(pr(head = "kivra/run-abcd", body = "task\\n\\nkivra-run:abc")) })
        assertEquals(GitHubFailureKind.FOREIGN_OBJECT, assertIs<GitHubResult.Failure>(shortMarker.adapter.ensurePullRequest(repo, shorter)).kind)
        assertEquals(0, shortMarker.posts())
    }

    @Test
    fun `one matching PR among several is adopted and the stale one is ignored`() = runTest {
        val f = Fake({ list(pr(number = 5, headSha = stale), pr(number = 7)) })
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(repo, create))
        assertEquals(7, r.value.pullRequest.number)
        assertFalse(r.value.created)
        assertEquals(0, f.posts())
    }

    @Test
    fun `several matching open PRs are a conflict and nothing is chosen or created`() = runTest {
        val f = Fake({ list(pr(number = 7), pr(number = 8)) })
        val r = assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(repo, create))
        assertEquals(GitHubFailureKind.CONFLICT, r.kind)
        assertEquals(0, f.posts())
    }

    @Test
    fun `a matching closed PR does not rescue a stale open PR`() = runTest {
        val f = Fake({ list(pr(number = 6, state = "closed"), pr(number = 9, headSha = stale)) })
        val r = assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(repo, create))
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, r.kind)
        assertEquals(0, f.posts())
    }

    @Test
    fun `closed only keeps the existing behaviour and reports the most recent closed PR with created false`() = runTest {
        val f = Fake({ list(pr(number = 6, state = "closed", headSha = stale), pr(number = 9, state = "closed", headSha = stale)) })
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(repo, create))
        assertFalse(r.value.created)
        assertEquals(9, r.value.pullRequest.number)
        assertEquals(0, f.posts())
    }

    @Test
    fun `no PR on the head creates one and reports created true`() = runTest {
        var posted = false
        val f = Fake(
            pulls = { if (posted) list(pr()) else "[]" },
            post = {
                posted = true
                respond(pr(), HttpStatusCode.Created, jsonHeaders)
            },
        )
        val r = assertIs<GitHubResult.Success<EnsuredPullRequest>>(f.adapter.ensurePullRequest(repo, create))
        assertTrue(r.value.created)
        assertEquals(1, f.posts())
    }

    @Test
    fun `failures never carry the PR body or the token`() = runTest {
        val secretBody = "PR-BODY-SECRET-CONTENT"
        val mismatch = Fake({ list(pr(headSha = stale, body = "$secretBody\\n\\nkivra-run:r1")) })
        val r = assertIs<GitHubResult.Failure>(mismatch.adapter.ensurePullRequest(repo, create))
        val text = r.toString()
        assertFalse(text.contains(secretBody))
        assertFalse(text.contains("tok-test-value"))

        // Outcome of the create is unknown, and the re-search finds a stale PR. The unknown error text must not leak.
        var posted = false
        val unknown = Fake(
            pulls = { if (posted) list(pr(headSha = stale, body = "$secretBody\\n\\nkivra-run:r1")) else "[]" },
            post = {
                posted = true
                throw IOException("Authorization: Bearer tok-test-value $secretBody")
            },
        )
        val u = assertIs<GitHubResult.Failure>(unknown.adapter.ensurePullRequest(repo, create))
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, u.kind)
        assertFalse(u.toString().contains(secretBody))
        assertFalse(u.toString().contains("tok-test-value"))
        assertEquals(1, unknown.posts())
    }

    @Test
    fun `unknown create followed by a stale PR is a failure and the POST is not resent`() = runTest {
        var posted = false
        val f = Fake(
            pulls = { if (posted) list(pr(headSha = stale)) else "[]" },
            post = {
                posted = true
                throw IOException("timeout")
            },
        )
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(f.adapter.ensurePullRequest(repo, create)).kind)
        assertEquals(1, f.posts())
    }
}
