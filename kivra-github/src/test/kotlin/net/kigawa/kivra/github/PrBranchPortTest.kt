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

// The shape of a live response for a pull request of a repository without the "require up to date" protection: `mergeable` is a JSON
// boolean (or null while computing), and `mergeable_state` is "unstable" (or "clean"), never "behind" for a head that is merely old.
private fun prJson(
    number: Int = 7,
    headSha: String = PB_HEAD,
    state: String = "open",
    mergeableState: String = "unstable",
    mergeable: String = "true",
): String =
    """{"number":$number,"state":"$state","merged":false,"draft":false,"head":{"ref":"kivra/run-r1","sha":"$headSha"},
        "base":{"ref":"main","sha":"$PB_BASE","repo":{"full_name":"kigawa-net/kivra","id":42}},
        "body":"x","html_url":"https://example.invalid/pull/$number","node_id":"PR_node","user":{"id":9001},
        "mergeable":$mergeable,"rebaseable":true,"mergeable_state":"$mergeableState"}"""

// The shape of `GET /repos/{owner}/{repo}/compare/{base}...{head}` as the live API sent it for a head 1 commit ahead and 9 behind `main`
// (checked read-only, the key set only). `behind_by` is a JSON integer; the other keys are the ones the live response carries.
private fun compareJson(behindBy: String, aheadBy: Int = 1): String =
    """{"url":"https://api.github.com/repos/kigawa-net/kivra/compare/main...$PB_HEAD",
        "html_url":"https://github.com/kigawa-net/kivra/compare/main...$PB_HEAD",
        "permalink_url":"https://github.com/kigawa-net/kivra/compare/main@{1}...$PB_HEAD",
        "diff_url":"https://github.com/kigawa-net/kivra/compare/main...$PB_HEAD.diff",
        "patch_url":"https://github.com/kigawa-net/kivra/compare/main...$PB_HEAD.patch",
        "base_commit":{"sha":"$PB_BASE"},"merge_base_commit":{"sha":"$PB_OTHER"},
        "status":"diverged","ahead_by":$aheadBy,"behind_by":$behindBy,"total_commits":${aheadBy + 1},
        "commits":[],"files":[]}"""

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
    fun `the behind distance is read from the compare API and a live diverged head reports nine`() = runTest {
        val f = BranchRemote { respond(compareJson(behindBy = "9"), HttpStatusCode.OK, PB_JSON) }
        assertEquals(9, assertIs<GitHubResult.Success<Int>>(f.adapter.getBehindBy(PB_REPO, "main", PB_HEAD)).value)
        val req = f.requests.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/repos/kigawa-net/kivra/compare/main...$PB_HEAD", req.url.encodedPath)
    }

    @Test
    fun `a head that contains the base reports zero behind`() = runTest {
        val f = BranchRemote { respond(compareJson(behindBy = "0", aheadBy = 2), HttpStatusCode.OK, PB_JSON) }
        assertEquals(0, assertIs<GitHubResult.Success<Int>>(f.adapter.getBehindBy(PB_REPO, "main", PB_HEAD)).value)
    }

    @Test
    fun `the ahead distance is read from the compare API between two commit ids, and zero means an ancestor`() = runTest {
        val f = BranchRemote { respond(compareJson(behindBy = "1", aheadBy = 0), HttpStatusCode.OK, PB_JSON) }
        assertEquals(0, assertIs<GitHubResult.Success<Int>>(f.adapter.getAheadBy(PB_REPO, PB_BASE, PB_HEAD)).value)
        assertEquals("/repos/kigawa-net/kivra/compare/$PB_BASE...$PB_HEAD", f.requests.single().url.encodedPath)
    }

    @Test
    fun `an ahead_by that is not a non-negative integer is a protocol failure, and a base that is not a commit id is refused`() = runTest {
        for (shape in listOf("\"0\"", "-1", "0.5", "null", "1e3")) {
            val f = BranchRemote { respond(compareJson(behindBy = "1", aheadBy = 0).replace("\"ahead_by\":0", "\"ahead_by\":$shape"), HttpStatusCode.OK, PB_JSON) }
            val result = f.adapter.getAheadBy(PB_REPO, PB_BASE, PB_HEAD)
            assertTrue(result is GitHubResult.Failure, "ahead_by=$shape read as $result")
            assertEquals(GitHubFailureKind.PROTOCOL, result.kind, shape)
        }
        val f = BranchRemote { respond(compareJson(behindBy = "1", aheadBy = 0), HttpStatusCode.OK, PB_JSON) }
        assertIs<GitHubResult.Failure>(f.adapter.getAheadBy(PB_REPO, "main", PB_HEAD))
        assertEquals(0, f.requests.size)
    }

    @Test
    fun `a compare answer whose behind_by is not a non-negative integer is a protocol failure`() = runTest {
        // Each of these is a shape GitHub does not send for `behind_by`; none may be read as a count.
        val shapes = listOf("\"9\"", "9.5", "-1", "true", "null", "1e3")
        for (shape in shapes) {
            val f = BranchRemote { respond(compareJson(behindBy = shape), HttpStatusCode.OK, PB_JSON) }
            val result = f.adapter.getBehindBy(PB_REPO, "main", PB_HEAD)
            assertTrue(result is GitHubResult.Failure, "behind_by=$shape read as $result")
            assertEquals(GitHubFailureKind.PROTOCOL, result.kind, shape)
        }
        val missing = BranchRemote { respond("""{"status":"diverged","ahead_by":1}""", HttpStatusCode.OK, PB_JSON) }
        assertEquals(GitHubFailureKind.PROTOCOL, assertIs<GitHubResult.Failure>(missing.adapter.getBehindBy(PB_REPO, "main", PB_HEAD)).kind)
    }

    @Test
    fun `a base that is not a branch name or a head that is not a commit id is refused before any request`() = runTest {
        val f = BranchRemote { respond(compareJson(behindBy = "1"), HttpStatusCode.OK, PB_JSON) }
        assertIs<GitHubResult.Failure>(f.adapter.getBehindBy(PB_REPO, "main\n", PB_HEAD))
        assertIs<GitHubResult.Failure>(f.adapter.getBehindBy(PB_REPO, "main", "main"))
        assertEquals(0, f.requests.size)
    }

    @Test
    fun `the pull request view carries mergeable and mergeable_state as GitHub sends them`() = runTest {
        val f = BranchRemote { respond(prJson(mergeableState = "unstable", mergeable = "true"), HttpStatusCode.OK, PB_JSON) }
        val view = assertIs<GitHubResult.Success<PrRebaseView>>(f.adapter.getPrRebaseView(PB_REPO, 7)).value
        assertEquals("unstable", view.mergeableState)
        assertEquals(true, view.mergeable)
        assertEquals(PB_HEAD, view.pullRequest.headSha)
        assertEquals(42L, view.pullRequest.baseRepositoryId)
        assertEquals(9001L, view.pullRequest.authorId)
        assertTrue(f.requests.all { it.method == HttpMethod.Get })
    }

    @Test
    fun `a mergeable that GitHub has not computed yet is null, and a conflict is false`() = runTest {
        val pending = BranchRemote { respond(prJson(mergeable = "null", mergeableState = "unknown"), HttpStatusCode.OK, PB_JSON) }
        assertEquals(null, assertIs<GitHubResult.Success<PrRebaseView>>(pending.adapter.getPrRebaseView(PB_REPO, 7)).value.mergeable)
        val conflict = BranchRemote { respond(prJson(mergeable = "false", mergeableState = "dirty"), HttpStatusCode.OK, PB_JSON) }
        assertEquals(false, assertIs<GitHubResult.Success<PrRebaseView>>(conflict.adapter.getPrRebaseView(PB_REPO, 7)).value.mergeable)
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

    @Test
    fun `the compare files are read in order with their status and patch, and a missing or null patch is null`() = runTest {
        val body = """{"status":"diverged","files":[
            {"filename":"docs/a.md","status":"modified","patch":"@@ -1 +1 @@\n-old\n+new\n"},
            {"filename":"bin/x.png","status":"added"},
            {"filename":"docs/b.md","status":"removed","patch":null}]}"""
        val f = BranchRemote { respond(body, HttpStatusCode.OK, PB_JSON) }
        val files = assertIs<GitHubResult.Success<List<ComparedFile>>>(f.adapter.getCompareFiles(PB_REPO, PB_BASE, PB_HEAD)).value
        assertEquals(
            listOf(
                ComparedFile("docs/a.md", "modified", "@@ -1 +1 @@\n-old\n+new\n"),
                ComparedFile("bin/x.png", "added", null),
                ComparedFile("docs/b.md", "removed", null),
            ),
            files,
        )
        val req = f.requests.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/repos/kigawa-net/kivra/compare/$PB_BASE...$PB_HEAD", req.url.encodedPath)
    }

    @Test
    fun `an empty compare has no files`() = runTest {
        val f = BranchRemote { respond("""{"status":"identical","files":[]}""", HttpStatusCode.OK, PB_JSON) }
        assertEquals(emptyList(), assertIs<GitHubResult.Success<List<ComparedFile>>>(f.adapter.getCompareFiles(PB_REPO, PB_BASE, PB_HEAD)).value)
    }

    @Test
    fun `a compare answer whose files or entries have a wrong shape is a protocol failure`() = runTest {
        val shapes = listOf(
            """{"status":"diverged"}""",
            """{"status":"diverged","files":{"filename":"a","status":"added"}}""",
            """{"status":"diverged","files":["a"]}""",
            """{"status":"diverged","files":[{"status":"added"}]}""",
            """{"status":"diverged","files":[{"filename":"a"}]}""",
            """{"status":"diverged","files":[{"filename":5,"status":"added"}]}""",
            """{"status":"diverged","files":[{"filename":"a","status":"added","patch":5}]}""",
            """{"status":"diverged","files":[{"filename":"a","status":"added","patch":true}]}""",
            """{"status":"diverged","files":[{"filename":"a","status":"added","patch":["+x"]}]}""",
            """{"status":"diverged","files":[{"filename":"a","status":"added","patch":{"x":1}}]}""",
            "not json",
        )
        for (body in shapes) {
            val f = BranchRemote { respond(body, HttpStatusCode.OK, PB_JSON) }
            val result = f.adapter.getCompareFiles(PB_REPO, PB_BASE, PB_HEAD)
            assertEquals(GitHubFailureKind.PROTOCOL, assertIs<GitHubResult.Failure>(result).kind, body)
        }
    }

    @Test
    fun `a compare of commit ids that are not 40-hex is refused before any request`() = runTest {
        val f = BranchRemote { respond("""{"files":[]}""", HttpStatusCode.OK, PB_JSON) }
        assertIs<GitHubResult.Failure>(f.adapter.getCompareFiles(PB_REPO, "main", PB_HEAD))
        assertIs<GitHubResult.Failure>(f.adapter.getCompareFiles(PB_REPO, PB_BASE, "$PB_HEAD\n"))
        assertIs<GitHubResult.Failure>(f.adapter.getCompareFiles(PB_REPO, PB_BASE, PB_HEAD.uppercase()))
        assertEquals(0, f.requests.size)
    }

    @Test
    fun `a compare that GitHub refuses is a failure with its kind`() = runTest {
        val f = BranchRemote { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, PB_JSON) }
        assertEquals(GitHubFailureKind.NOT_FOUND, assertIs<GitHubResult.Failure>(f.adapter.getCompareFiles(PB_REPO, PB_BASE, PB_HEAD)).kind)
    }

    private fun filesPage(count: Int, from: Int = 1): String = (from until from + count).joinToString(",", "[", "]") {
        """{"filename":"kivra-app/src/test/kotlin/F$it.kt","status":"modified","additions":1,"deletions":2}"""
    }

    @Test
    fun `the pull request files are read page by page, and a short page ends the list`() = runTest {
        val f = BranchRemote { respond(filesPage(2), HttpStatusCode.OK, PB_JSON) }
        val files = assertIs<GitHubResult.Success<PullRequestFiles>>(f.adapter.listPullRequestFiles(PB_REPO, 7)).value
        assertEquals(false, files.truncated)
        assertEquals(listOf("kivra-app/src/test/kotlin/F1.kt", "kivra-app/src/test/kotlin/F2.kt"), files.files.map { it.path })
        assertEquals(PullRequestFile("kivra-app/src/test/kotlin/F1.kt", "modified", 1, 2), files.files[0])
        val req = f.requests.single()
        assertEquals("/repos/kigawa-net/kivra/pulls/7/files", req.url.encodedPath)
        assertEquals("100", req.url.parameters["per_page"])
    }

    @Test
    fun `a full page is followed by the next one, and reaching 300 files is truncated, never partial`() = runTest {
        val f = BranchRemote { request ->
            val page = request.url.parameters["page"]!!.toInt()
            respond(filesPage(100, from = (page - 1) * 100 + 1), HttpStatusCode.OK, PB_JSON)
        }
        val files = assertIs<GitHubResult.Success<PullRequestFiles>>(f.adapter.listPullRequestFiles(PB_REPO, 7)).value
        assertEquals(300, files.files.size)
        assertEquals(true, files.truncated)
        assertEquals(3, f.requests.size)
    }

    @Test
    fun `a file entry without a count is a protocol failure`() = runTest {
        val f = BranchRemote { respond("""[{"filename":"a.kt","status":"modified","deletions":1}]""", HttpStatusCode.OK, PB_JSON) }
        assertEquals(GitHubFailureKind.PROTOCOL, assertIs<GitHubResult.Failure>(f.adapter.listPullRequestFiles(PB_REPO, 7)).kind)
    }

    @Test
    fun `the labels of a pull request are its label names, and absent when the response has no array`() = runTest {
        val labelled = BranchRemote { respond(prJson().replace("\"user\":{\"id\":9001}", "\"user\":{\"id\":9001},\"labels\":[{\"name\":\"kivra:hold\"}]"), HttpStatusCode.OK, PB_JSON) }
        val view = assertIs<GitHubResult.Success<PrRebaseView>>(labelled.adapter.getPrRebaseView(PB_REPO, 7)).value
        assertEquals(listOf("kivra:hold"), view.pullRequest.labels)
        val unlabelled = BranchRemote { respond(prJson(), HttpStatusCode.OK, PB_JSON) }
        val bare = assertIs<GitHubResult.Success<PrRebaseView>>(unlabelled.adapter.getPrRebaseView(PB_REPO, 7)).value
        assertEquals(null, bare.pullRequest.labels)
    }

    @Test
    fun `a check run carries its started_at, and an unparseable one is absent`() = runTest {
        val checks = """{"total_count":2,"check_runs":[
            {"id":1,"name":"test","head_sha":"$PB_HEAD","status":"in_progress","conclusion":null,"started_at":"2026-10-22T10:00:00Z"},
            {"id":2,"name":"build","head_sha":"$PB_HEAD","status":"completed","conclusion":"success","started_at":"not-a-time"}]}"""
        val f = BranchRemote { respond(checks, HttpStatusCode.OK, PB_JSON) }
        val summary = assertIs<GitHubResult.Success<CheckRunsSummary>>(f.adapter.getCheckRuns(PB_REPO, PB_HEAD)).value
        assertEquals(java.time.Instant.parse("2026-10-22T10:00:00Z"), summary.runs[0].startedAt)
        assertEquals(null, summary.runs[1].startedAt)
    }
}
