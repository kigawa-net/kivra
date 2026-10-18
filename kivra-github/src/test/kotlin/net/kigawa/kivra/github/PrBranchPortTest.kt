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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// PrBranchPort (issue #276): the update request and the read-only checks it depends on. All traffic goes through MockEngine.

private const val PB_HEAD = "0123456789abcdef0123456789abcdef01234567"
private const val PB_BASE = "89abcdef0123456789abcdef0123456789abcdef"
private const val PB_OTHER = "fedcba9876543210fedcba9876543210fedcba98"
private val PB_REPO = RepoRef("kigawa-net", "kivra")
private val PB_JSON = headersOf(HttpHeaders.ContentType, "application/json")

private class BranchRemote(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    private val tokens = object : TokenProvider {
        override suspend fun token() = "ghs_PB_SECRET_TOKEN_VALUE"
        override fun invalidate() {}
    }
    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; handler(req) }),
        tokens,
        backoff = {},
    )
}

private fun prJson(number: Int = 7, headSha: String = PB_HEAD, state: String = "open", mergeableState: String = "behind"): String =
    """{"number":$number,"state":"$state","merged":false,"draft":false,"head":{"ref":"kivra/run-r1","sha":"$headSha"},
        "base":{"ref":"main","sha":"$PB_BASE","repo":{"full_name":"kigawa-net/kivra","id":42}},
        "body":"x","html_url":"https://example.invalid/pull/$number","node_id":"PR_node","user":{"id":9001},
        "mergeable_state":"$mergeableState"}"""

class PrBranchPortTest {
    @Test
    fun `the update is one PUT with expected_head_sha, and 202 is a success`() = runTest {
        val f = BranchRemote { respond("""{"message":"Updating pull request branch."}""", HttpStatusCode.Accepted, PB_JSON) }
        val result = f.adapter.updatePrBranch(PB_REPO, 7, PB_HEAD)
        assertIs<GitHubResult.Success<Unit>>(result)
        val req = f.requests.single()
        assertEquals(HttpMethod.Put, req.method)
        assertEquals("/repos/kigawa-net/kivra/pulls/7/update-branch", req.url.encodedPath)
        val body = (req.body as TextContent).text
        assertEquals("""{"expected_head_sha":"$PB_HEAD"}""", body)
    }

    @Test
    fun `a refused update (422, head moved or conflict) is a conflict and is sent once`() = runTest {
        val f = BranchRemote { respond("""{"message":"expected_head_sha does not match"}""", HttpStatusCode.UnprocessableEntity, PB_JSON) }
        val result = f.adapter.updatePrBranch(PB_REPO, 7, PB_HEAD)
        assertEquals(GitHubFailureKind.CONFLICT, assertIs<GitHubResult.Failure>(result).kind)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `a server error on the update is unknown and is never re-sent`() = runTest {
        val f = BranchRemote { respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, PB_JSON) }
        assertIs<GitHubResult.Unknown>(f.adapter.updatePrBranch(PB_REPO, 7, PB_HEAD))
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `an expected head that is not a commit id is refused before any request`() = runTest {
        val f = BranchRemote { respond("{}", HttpStatusCode.OK, PB_JSON) }
        assertIs<GitHubResult.Failure>(f.adapter.updatePrBranch(PB_REPO, 7, "main"))
        assertEquals(0, f.requests.size)
    }

    @Test
    fun `the pull request view carries mergeable_state and the same identity fields as getPullRequest`() = runTest {
        val f = BranchRemote { respond(prJson(mergeableState = "behind"), HttpStatusCode.OK, PB_JSON) }
        val view = assertIs<GitHubResult.Success<PrRebaseView>>(f.adapter.getPrRebaseView(PB_REPO, 7)).value
        assertEquals("behind", view.mergeableState)
        assertEquals(PB_HEAD, view.pullRequest.headSha)
        assertEquals(42L, view.pullRequest.baseRepositoryId)
        assertEquals(9001L, view.pullRequest.authorId)
        assertTrue(f.requests.all { it.method == HttpMethod.Get })
    }

    @Test
    fun `a pull request view for another number is an identity mismatch`() = runTest {
        val f = BranchRemote { respond(prJson(number = 8), HttpStatusCode.OK, PB_JSON) }
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(f.adapter.getPrRebaseView(PB_REPO, 7)).kind)
    }

    @Test
    fun `the parents of a commit are read in order and a commit that answers for another sha is refused`() = runTest {
        val ok = BranchRemote {
            respond("""{"sha":"$PB_HEAD","parents":[{"sha":"$PB_BASE"},{"sha":"$PB_OTHER"}]}""", HttpStatusCode.OK, PB_JSON)
        }
        assertEquals(listOf(PB_BASE, PB_OTHER), assertIs<GitHubResult.Success<List<String>>>(ok.adapter.getCommitParents(PB_REPO, PB_HEAD)).value)

        val wrong = BranchRemote { respond("""{"sha":"$PB_BASE","parents":[]}""", HttpStatusCode.OK, PB_JSON) }
        assertEquals(GitHubFailureKind.IDENTITY_MISMATCH, assertIs<GitHubResult.Failure>(wrong.adapter.getCommitParents(PB_REPO, PB_HEAD)).kind)
    }

    @Test
    fun `the merge base is read from the comparison`() = runTest {
        val f = BranchRemote { respond("""{"merge_base_commit":{"sha":"$PB_BASE"},"status":"ahead"}""", HttpStatusCode.OK, PB_JSON) }
        assertEquals(PB_BASE, assertIs<GitHubResult.Success<String>>(f.adapter.mergeBaseOf(PB_REPO, PB_HEAD, PB_OTHER)).value)
        assertEquals("/repos/kigawa-net/kivra/compare/$PB_HEAD...$PB_OTHER", f.requests.single().url.encodedPath)
    }

    @Test
    fun `the tree listing keeps the object id of each entry`() = runTest {
        val tree = """{"sha":"$PB_HEAD","truncated":false,"tree":[
            {"path":"docs/a.md","mode":"100644","type":"blob","sha":"$PB_OTHER","size":4}]}"""
        val f = BranchRemote { respond(tree, HttpStatusCode.OK, PB_JSON) }
        val entry = assertIs<GitHubResult.Success<RepoTree>>(f.adapter.listTreeAt(PB_REPO, PB_HEAD)).value.entries.single()
        assertEquals(PB_OTHER, entry.sha)
        assertEquals("100644", entry.mode)
    }
}
