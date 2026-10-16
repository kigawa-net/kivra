package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeVerification
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val HEAD = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
private const val BASE_SHA = "fedcba9876543210fedcba9876543210fedcba98"
private const val MERGE = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"
private const val SECRET = "ghs_SECRET_TOKEN_VALUE"
private const val BOT_ID = 9001L
private val JSON_H = headersOf(HttpHeaders.ContentType, "application/json")

private val BINDING = PullRequestBinding(
    runId = RunId("01HZRUN0000000000000000001"),
    repository = "kigawa-net/kivra",
    repositoryId = 42L,
    prNumber = 7L,
    prNodeId = "PR_node_7",
    baseRef = "main",
    headSha = HEAD,
    policyRevision = "policy-1",
    reviewManifestDigest = "manifest-1",
)

private fun prJson(
    number: Int = 7,
    state: String = "closed",
    merged: Boolean = true,
    head: String = HEAD,
    base: String = "main",
    repo: String = "kigawa-net/kivra",
    repoId: Long = 42,
    nodeId: String? = "PR_node_7",
    mergeSha: String? = MERGE,
    body: String = "kivra-run:r1 $SECRET",
    authorId: Long? = BOT_ID,
) = """{"number":$number,"state":"$state","merged":$merged,"draft":false,${authorId?.let { "\"user\":{\"login\":\"kivra-app[bot]\",\"id\":$it}," } ?: ""}"body":"$body",
    "html_url":"https://github.com/$repo/pull/$number","node_id":${nodeId?.let { "\"$it\"" } ?: "null"},
    "merge_commit_sha":${mergeSha?.let { "\"$it\"" } ?: "null"},
    "head":{"ref":"kivra/run-r1","sha":"$head","repo":{"full_name":"$repo"}},
    "base":{"ref":"$base","sha":"$BASE_SHA","repo":{"id":$repoId,"full_name":"$repo"}}}"""

private class Harness(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) {
    val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
    val authority = GitHubPrMergeAuthority(
        HttpGitHubAdapter(
            githubHttpClient(MockEngine { req -> requests += req; handler(req) }),
            object : TokenProvider {
                override suspend fun token() = "tok-$SECRET"
                override fun invalidate() {}
            },
            backoff = {},
        ),
        expectedAuthorId = BOT_ID,
    )
}

class GitHubPrMergeAuthorityTest {
    @Test
    fun `a merged PR whose identity matches the binding verifies, with the merge commit kept apart from the head`() = runTest {
        val h = Harness { respond(prJson(), HttpStatusCode.OK, JSON_H) }
        val v = assertIs<PrMergeVerification.Verified>(h.authority.verifyMerge(BINDING)).merge
        assertEquals(MERGE, v.mergeCommitSha)
        assertEquals(HEAD, v.headSha)
        assertEquals(7L, v.prNumber)
        assertEquals(42L, v.repositoryId)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `an open PR and a PR closed without merge are refused, not treated as merged`() = runTest {
        val open = Harness { respond(prJson(state = "open", merged = false, mergeSha = null), HttpStatusCode.OK, JSON_H) }
        assertEquals("NOT_MERGED", assertIs<PrMergeVerification.Refused>(open.authority.verifyMerge(BINDING)).reason)
        val closed = Harness { respond(prJson(merged = false, mergeSha = null), HttpStatusCode.OK, JSON_H) }
        assertEquals("NOT_MERGED", assertIs<PrMergeVerification.Refused>(closed.authority.verifyMerge(BINDING)).reason)
    }

    @Test
    fun `a response about another PR number, another repository or another base is refused`() = runTest {
        val otherNumber = Harness { respond(prJson(number = 8), HttpStatusCode.OK, JSON_H) }
        assertEquals("GITHUB_IDENTITY_MISMATCH", assertIs<PrMergeVerification.Refused>(otherNumber.authority.verifyMerge(BINDING)).reason)
        val otherRepo = Harness { respond(prJson(repo = "kigawa-net/other"), HttpStatusCode.OK, JSON_H) }
        assertEquals("GITHUB_IDENTITY_MISMATCH", assertIs<PrMergeVerification.Refused>(otherRepo.authority.verifyMerge(BINDING)).reason)
        val otherBase = Harness { respond(prJson(base = "release"), HttpStatusCode.OK, JSON_H) }
        assertEquals("BASE_REF_MISMATCH", assertIs<PrMergeVerification.Refused>(otherBase.authority.verifyMerge(BINDING)).reason)
    }

    @Test
    fun `a PR opened by another user, or with no author id, is refused and never verified`() = runTest {
        val other = Harness { respond(prJson(authorId = BOT_ID + 1), HttpStatusCode.OK, JSON_H) }
        assertEquals("AUTHOR_MISMATCH", assertIs<PrMergeVerification.Refused>(other.authority.verifyMerge(BINDING)).reason)
        val none = Harness { respond(prJson(authorId = null), HttpStatusCode.OK, JSON_H) }
        assertEquals("AUTHOR_MISMATCH", assertIs<PrMergeVerification.Refused>(none.authority.verifyMerge(BINDING)).reason)
    }

    @Test
    fun `a renamed or recreated repository with another immutable id, a node id change and a moved head are refused`() = runTest {
        val otherId = Harness { respond(prJson(repoId = 43), HttpStatusCode.OK, JSON_H) }
        assertEquals("REPOSITORY_ID_MISMATCH", assertIs<PrMergeVerification.Refused>(otherId.authority.verifyMerge(BINDING)).reason)
        val otherNode = Harness { respond(prJson(nodeId = "PR_other"), HttpStatusCode.OK, JSON_H) }
        assertEquals("PR_NODE_ID_MISMATCH", assertIs<PrMergeVerification.Refused>(otherNode.authority.verifyMerge(BINDING)).reason)
        val noNode = Harness { respond(prJson(nodeId = null), HttpStatusCode.OK, JSON_H) }
        assertEquals("PR_NODE_ID_MISMATCH", assertIs<PrMergeVerification.Refused>(noNode.authority.verifyMerge(BINDING)).reason)
        val movedHead = Harness { respond(prJson(head = "d4".repeat(20)), HttpStatusCode.OK, JSON_H) }
        assertEquals("HEAD_SHA_MISMATCH", assertIs<PrMergeVerification.Refused>(movedHead.authority.verifyMerge(BINDING)).reason)
    }

    @Test
    fun `a merged PR without a merge commit SHA is refused`() = runTest {
        val h = Harness { respond(prJson(mergeSha = null), HttpStatusCode.OK, JSON_H) }
        assertEquals("MERGE_COMMIT_SHA_MISSING", assertIs<PrMergeVerification.Refused>(h.authority.verifyMerge(BINDING)).reason)
    }

    @Test
    fun `a 404 or a redirect is refused, and a redirect is never followed`() = runTest {
        val notFound = Harness { respond("{\"message\":\"Not Found $SECRET\"}", HttpStatusCode.NotFound, JSON_H) }
        assertEquals("GITHUB_NOT_FOUND", assertIs<PrMergeVerification.Refused>(notFound.authority.verifyMerge(BINDING)).reason)
        val redirect = Harness { respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://evil.example/x")) }
        assertEquals("GITHUB_REDIRECT", assertIs<PrMergeVerification.Refused>(redirect.authority.verifyMerge(BINDING)).reason)
        assertEquals(1, redirect.requests.size)
    }

    @Test
    fun `a malformed response is refused`() = runTest {
        val h = Harness { respond("<html>$SECRET</html>", HttpStatusCode.OK, JSON_H) }
        assertEquals("GITHUB_PROTOCOL", assertIs<PrMergeVerification.Refused>(h.authority.verifyMerge(BINDING)).reason)
    }

    @Test
    fun `5xx, 401 and transport failures are unavailable, never a merge or a refusal`() = runTest {
        val server = Harness { respond("{\"message\":\"Bearer $SECRET\"}", HttpStatusCode.InternalServerError, JSON_H) }
        val e = assertFailsWith<PrMergeAuthorityUnavailableException> { server.authority.verifyMerge(BINDING) }
        assertFalse(e.message.orEmpty().contains(SECRET))
        assertTrue(server.requests.size >= 2)

        val unauthorized = Harness { respond("{\"message\":\"Bad credentials $SECRET\"}", HttpStatusCode.Unauthorized, JSON_H) }
        val u = assertFailsWith<PrMergeAuthorityUnavailableException> { unauthorized.authority.verifyMerge(BINDING) }
        assertFalse(u.message.orEmpty().contains(SECRET))

        val io = Harness { throw java.io.IOException("connection reset $SECRET") }
        val n = assertFailsWith<PrMergeAuthorityUnavailableException> { io.authority.verifyMerge(BINDING) }
        assertFalse(n.message.orEmpty().contains(SECRET))
    }

    @Test
    fun `a response body that carries a bearer token never appears in the verification result`() = runTest {
        val h = Harness { respond(prJson(body = "Authorization: Bearer $SECRET"), HttpStatusCode.OK, JSON_H) }
        val result = h.authority.verifyMerge(BINDING)
        assertFalse(result.toString().contains(SECRET))
        assertIs<PrMergeVerification.Verified>(result)
    }
}
