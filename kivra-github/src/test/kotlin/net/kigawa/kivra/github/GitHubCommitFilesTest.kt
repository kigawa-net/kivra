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
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Contract tests for GitHubPort.commitFiles (issue #56). All traffic goes through MockEngine; nothing reaches the network.

private const val CF_HEAD = "0123456789abcdef0123456789abcdef01234567"
private const val CF_OTHER = "fedcba9876543210fedcba9876543210fedcba98"
private const val CF_BASE_TREE = "1111111111111111111111111111111111111111"
private const val CF_NEW_TREE = "2222222222222222222222222222222222222222"
private const val CF_NEW_COMMIT = "3333333333333333333333333333333333333333"
private const val CF_TOKEN = "ghs_CF_SECRET_TOKEN_VALUE"
private const val CF_BASE = "/repos/kigawa-net/kivra"
private const val CF_BRANCH = "kivra/run-r1"
private const val CF_UNTRUSTED = "https://untrusted.example/steal"
private val CF_REPO = RepoRef("kigawa-net", "kivra")
private val CF_JSON = headersOf(HttpHeaders.ContentType, "application/json")
private val CF_JSON_LIB = Json { ignoreUnknownKeys = true }

private enum class PatchMode {
    OK,
    /** 500 and the ref did move: the response was lost after the update applied. */
    ERROR_APPLIED,
    /** 500 and the ref did not move. */
    ERROR_NOT_APPLIED,
    /** Transport failure after the update applied. */
    TIMEOUT_APPLIED,
    /** Transport failure, ref did not move. */
    TIMEOUT_NOT_APPLIED,
    /** 500 while another writer moved the branch first. */
    ERROR_OTHER_WRITER,
    UNPROCESSABLE,
    REDIRECT,
}

/** A stateful stand-in for the GitHub Git Data API on one branch. */
private class CommitRemote {
    var head = CF_HEAD
    var refMissing = false
    var patchMode = PatchMode.OK
    /** One-shot reply for the next blob POST; null means the normal success reply. */
    var nextBlobReply: (suspend MockRequestHandleScope.() -> HttpResponseData)? = null
    var badBlobSha = false
    val requests = mutableListOf<HttpRequestData>()

    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; route(req) }),
        StaticTokenProvider(CF_TOKEN),
        backoff = {},
    )

    fun count(method: HttpMethod) = requests.count { it.method == method }
    fun writes() = requests.filter { it.method != HttpMethod.Get }
    fun paths() = requests.map { "${it.method.value} ${it.url.encodedPath.removePrefix(CF_BASE)}" }

    suspend fun MockRequestHandleScope.route(req: HttpRequestData): HttpResponseData {
        val p = req.url.encodedPath.removePrefix(CF_BASE)
        return when {
            req.method == HttpMethod.Get && p == "/git/ref/heads/$CF_BRANCH" ->
                if (refMissing) respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, CF_JSON)
                else respond(refJson(head), HttpStatusCode.OK, CF_JSON)
            req.method == HttpMethod.Get && p == "/git/commits/$head" ->
                respond("""{"sha":"$head","tree":{"sha":"$CF_BASE_TREE"}}""", HttpStatusCode.OK, CF_JSON)
            req.method == HttpMethod.Post && p == "/git/blobs" -> {
                val reply = nextBlobReply
                nextBlobReply = null
                reply?.invoke(this) ?: respond("""{"sha":"${blobShaOf(req)}"}""", HttpStatusCode.Created, CF_JSON)
            }
            req.method == HttpMethod.Post && p == "/git/trees" ->
                respond("""{"sha":"$CF_NEW_TREE"}""", HttpStatusCode.Created, CF_JSON)
            req.method == HttpMethod.Post && p == "/git/commits" ->
                respond("""{"sha":"$CF_NEW_COMMIT","tree":{"sha":"$CF_NEW_TREE"}}""", HttpStatusCode.Created, CF_JSON)
            req.method == HttpMethod.Patch && p == "/git/refs/heads/$CF_BRANCH" -> patchReply()
            else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, CF_JSON)
        }
    }

    private fun MockRequestHandleScope.patchReply(): HttpResponseData = when (patchMode) {
        PatchMode.OK -> { head = CF_NEW_COMMIT; respond(refJson(CF_NEW_COMMIT), HttpStatusCode.OK, CF_JSON) }
        PatchMode.ERROR_APPLIED -> { head = CF_NEW_COMMIT; respond("{}", HttpStatusCode.InternalServerError, CF_JSON) }
        PatchMode.ERROR_NOT_APPLIED -> respond("{}", HttpStatusCode.InternalServerError, CF_JSON)
        PatchMode.TIMEOUT_APPLIED -> { head = CF_NEW_COMMIT; throw IOException("timeout while reading response") }
        PatchMode.TIMEOUT_NOT_APPLIED -> throw IOException("timeout before response")
        PatchMode.ERROR_OTHER_WRITER -> { head = CF_OTHER; respond("{}", HttpStatusCode.InternalServerError, CF_JSON) }
        PatchMode.UNPROCESSABLE ->
            respond("""{"message":"Update is not a fast forward"}""", HttpStatusCode.UnprocessableEntity, CF_JSON)
        PatchMode.REDIRECT -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CF_UNTRUSTED))
    }

    private fun refJson(sha: String) =
        """{"ref":"refs/heads/$CF_BRANCH","object":{"sha":"$sha","type":"commit"}}"""

    private fun blobShaOf(req: HttpRequestData): String {
        val body = CF_JSON_LIB.parseToJsonElement((req.body as TextContent).text).jsonObject
        val bytes = Base64.getDecoder().decode(body.getValue("content").jsonPrimitive.content)
        return if (badBlobSha) "f".repeat(40) else gitBlobSha(bytes)
    }
}

private fun gitBlobSha(content: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
    digest.update(content)
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun request(vararg changes: FileWrite, expected: String = CF_HEAD, message: String = "kivra: update") =
    CommitFilesRequest(CF_BRANCH, expected, message, changes.toList())

private fun bodyOf(req: HttpRequestData): JsonObject =
    CF_JSON_LIB.parseToJsonElement((req.body as TextContent).text).jsonObject

private fun assertFailureKind(expected: GitHubFailureKind, result: GitHubResult<*>) {
    assertEquals(expected, assertIs<GitHubResult.Failure>(result).kind)
}

class GitHubCommitFilesTest {
    // (a) normal path and request shape

    @Test
    fun `happy path sends the Git Data API steps in order and moves the ref without force`() = runTest {
        val remote = CommitRemote()
        val result = remote.adapter.commitFiles(
            CF_REPO,
            request(FileWrite.Write("docs/a.md", "hello\n"), FileWrite.Write("src/b.bin", byteArrayOf(0, 1, 2)), FileWrite.Delete("old.txt")),
        )

        assertEquals(GitHubResult.Success(CommittedFiles(CF_NEW_COMMIT, CF_NEW_TREE, created = true)), result)
        assertEquals(
            listOf(
                "GET /git/ref/heads/$CF_BRANCH",
                "GET /git/commits/$CF_HEAD",
                "POST /git/blobs",
                "POST /git/blobs",
                "POST /git/trees",
                "POST /git/commits",
                "PATCH /git/refs/heads/$CF_BRANCH",
            ),
            remote.paths(),
        )
        val patch = bodyOf(remote.requests.last())
        assertEquals(JsonPrimitive(false), patch["force"])
        assertEquals(JsonPrimitive(CF_NEW_COMMIT), patch["sha"])
    }

    @Test
    fun `blob, tree and commit bodies carry base64, mode 100644, null deletes and the verified parent`() = runTest {
        val remote = CommitRemote()
        remote.adapter.commitFiles(
            CF_REPO,
            request(FileWrite.Write("docs/a.md", "hello\n"), FileWrite.Delete("old.txt")),
        )

        val blob = bodyOf(remote.requests.single { it.url.encodedPath.endsWith("/git/blobs") })
        assertEquals(JsonPrimitive("base64"), blob["encoding"])
        assertEquals("aGVsbG8K", blob.getValue("content").jsonPrimitive.content)

        val tree = bodyOf(remote.requests.single { it.url.encodedPath.endsWith("/git/trees") })
        assertEquals(JsonPrimitive(CF_BASE_TREE), tree["base_tree"])
        val entries = tree.getValue("tree").jsonArray.map { it.jsonObject }
        assertEquals(setOf("100644"), entries.map { it.getValue("mode").jsonPrimitive.content }.toSet())
        assertEquals(setOf("blob"), entries.map { it.getValue("type").jsonPrimitive.content }.toSet())
        val written = entries.single { it.getValue("path").jsonPrimitive.content == "docs/a.md" }
        assertEquals(JsonPrimitive("ce013625030ba8dba906f756967f9e9ca394464a"), written["sha"])
        val deleted = entries.single { it.getValue("path").jsonPrimitive.content == "old.txt" }
        assertEquals(JsonNull, deleted["sha"])

        val commit = bodyOf(remote.requests.single { it.url.encodedPath.endsWith("/git/commits") && it.method == HttpMethod.Post })
        assertEquals(JsonPrimitive("kivra: update"), commit["message"])
        assertEquals(JsonPrimitive(CF_NEW_TREE), commit["tree"])
        assertEquals(listOf(CF_HEAD), commit.getValue("parents").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `result toString and request toString do not reveal message, paths or contents`() = runTest {
        val req = request(FileWrite.Write("secret/path.txt", "BODY_SECRET"), message = "MSG_SECRET")
        assertFalse(req.toString().contains("MSG_SECRET"))
        assertFalse(req.toString().contains("secret/path"))
        assertFalse(req.changes.single().toString().contains("secret/path"))
        assertFalse(req.changes.single().toString().contains("BODY_SECRET"))
        assertFalse(FileWrite.Delete("gone/secret.txt").toString().contains("gone/secret"))
    }

    // (b) expectedHeadSha

    @Test
    fun `a head other than expectedHeadSha is CONFLICT and nothing is written`() = runTest {
        val remote = CommitRemote()
        remote.head = CF_OTHER
        val result = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x")))

        assertFailureKind(GitHubFailureKind.CONFLICT, result)
        assertEquals(0, remote.writes().size)
        assertEquals(1, remote.requests.size, "only the ref read may be sent")
    }

    // (c) branch allow-list, before any request

    @Test
    fun `branches other than kivra run branches are rejected with no request`() = runTest {
        val rejected = listOf(
            "main", "master", "release/1.0", "feature/x", "kivra/run-", "kivra/run-a/b", "kivra/run-../x",
            "kivra/run-r1/../../main", "refs/heads/main", "kivra/run-r 1", "kivra/run-" + "a".repeat(65),
        )
        for (branch in rejected) {
            val remote = CommitRemote()
            val result = remote.adapter.commitFiles(
                CF_REPO,
                CommitFilesRequest(branch, CF_HEAD, "m", listOf(FileWrite.Write("a.txt", "x"))),
            )
            assertFailureKind(GitHubFailureKind.VALIDATION, result)
            assertEquals(0, remote.requests.size, "request sent for branch $branch")
        }
    }

    // (d) safety ceilings and path rules, before any request

    @Test
    fun `file count is capped at 50 inclusive`() = runTest {
        val ok = CommitRemote().apply { refMissing = true }
        ok.adapter.commitFiles(CF_REPO, CommitFilesRequest(CF_BRANCH, CF_HEAD, "m", (1..50).map { FileWrite.Write("f$it.txt", "x") }))
        assertEquals(1, ok.requests.size, "50 files must reach the network")

        val over = CommitRemote()
        val result = over.adapter.commitFiles(CF_REPO, CommitFilesRequest(CF_BRANCH, CF_HEAD, "m", (1..51).map { FileWrite.Write("f$it.txt", "x") }))
        assertFailureKind(GitHubFailureKind.VALIDATION, result)
        assertEquals(0, over.requests.size)
    }

    @Test
    fun `file size is capped at 1 MiB per file and 5 MiB in total`() = runTest {
        val exactly = CommitRemote().apply { refMissing = true }
        exactly.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.bin", ByteArray(1_048_576))))
        assertEquals(1, exactly.requests.size)

        val over = CommitRemote()
        assertFailureKind(
            GitHubFailureKind.VALIDATION,
            over.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.bin", ByteArray(1_048_577)))),
        )
        assertEquals(0, over.requests.size)

        val total = CommitRemote().apply { refMissing = true }
        total.adapter.commitFiles(CF_REPO, CommitFilesRequest(CF_BRANCH, CF_HEAD, "m", (1..5).map { FileWrite.Write("b$it.bin", ByteArray(1_048_576)) }))
        assertEquals(1, total.requests.size, "5 MiB in total must reach the network")

        val totalOver = CommitRemote()
        val sixFiles = (1..5).map { FileWrite.Write("b$it.bin", ByteArray(1_048_576)) } + FileWrite.Write("c.bin", ByteArray(1))
        assertFailureKind(GitHubFailureKind.VALIDATION, totalOver.adapter.commitFiles(CF_REPO, CommitFilesRequest(CF_BRANCH, CF_HEAD, "m", sixFiles)))
        assertEquals(0, totalOver.requests.size)
    }

    @Test
    fun `unsafe paths are rejected with no request and the length limit is inclusive`() = runTest {
        val rejected = listOf(
            "", "/abs.txt", "a\\b.txt", "a/../b.txt", "../x", "./x", "a//b", "a/", ".git/config", "a/.git/x",
            ".GIT/config", "a\u0001b.txt", "a\u007fb.txt", "a\nb.txt", "x".repeat(256),
        )
        for (path in rejected) {
            val remote = CommitRemote()
            val result = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write(path, "x")))
            assertFailureKind(GitHubFailureKind.VALIDATION, result)
            assertEquals(0, remote.requests.size, "request sent for path length ${path.length}")
        }
        val boundary = CommitRemote().apply { refMissing = true }
        boundary.adapter.commitFiles(CF_REPO, request(FileWrite.Write("x".repeat(255), "x")))
        assertEquals(1, boundary.requests.size)
        // Not a dot segment and not .git: these are allowed.
        val allowed = CommitRemote().apply { refMissing = true }
        allowed.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a..b/.github/c.txt", "x")))
        assertEquals(1, allowed.requests.size)
    }

    @Test
    fun `duplicate paths and an empty change list are rejected with no request`() = runTest {
        val dup = CommitRemote()
        assertFailureKind(
            GitHubFailureKind.VALIDATION,
            dup.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "1"), FileWrite.Delete("a.txt"))),
        )
        assertEquals(0, dup.requests.size)

        val empty = CommitRemote()
        assertFailureKind(GitHubFailureKind.VALIDATION, empty.adapter.commitFiles(CF_REPO, request()))
        assertEquals(0, empty.requests.size)
    }

    // (k) commit message

    @Test
    fun `control characters other than LF, blank and oversized messages are rejected with no request`() = runTest {
        val rejected = listOf("   ", "a\u0007b", "line\rbreak", "tab\there", "x".repeat(4_001), "")
        for (message in rejected) {
            val remote = CommitRemote()
            assertFailureKind(
                GitHubFailureKind.VALIDATION,
                remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"), message = message)),
            )
            assertEquals(0, remote.requests.size, "request sent for a rejected message")
        }
        val multiline = CommitRemote().apply { refMissing = true }
        multiline.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"), message = "title\n\nbody"))
        assertEquals(1, multiline.requests.size)
    }

    // (e) failures before the ref update: the ref is untouched and a re-run converges

    @Test
    fun `a blob 500 or a lost blob response is Unknown, leaves the ref alone and a re-run succeeds`() = runTest {
        val remote = CommitRemote()
        remote.nextBlobReply = { respond("{}", HttpStatusCode.InternalServerError, CF_JSON) }
        val first = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x")))
        assertIs<GitHubResult.Unknown>(first)
        assertEquals(0, remote.count(HttpMethod.Patch), "the ref must not move when a blob fails")
        assertEquals(CF_HEAD, remote.head)

        val second = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x")))
        assertEquals(GitHubResult.Success(CommittedFiles(CF_NEW_COMMIT, CF_NEW_TREE, created = true)), second)
    }

    @Test
    fun `a timeout on the blob step is Unknown and a re-run converges`() = runTest {
        val remote = CommitRemote()
        remote.nextBlobReply = { throw IOException("timeout") }
        assertIs<GitHubResult.Unknown>(remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(0, remote.count(HttpMethod.Patch))
        assertEquals(
            GitHubResult.Success(CommittedFiles(CF_NEW_COMMIT, CF_NEW_TREE, created = true)),
            remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))),
        )
    }

    @Test
    fun `a blob whose sha does not match the local git blob id is an identity mismatch and the ref does not move`() = runTest {
        val remote = CommitRemote().apply { badBlobSha = true }
        assertFailureKind(GitHubFailureKind.IDENTITY_MISMATCH, remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(0, remote.count(HttpMethod.Patch))
        assertEquals(1, remote.count(HttpMethod.Post), "only the blob was posted")
    }

    // (f) ref update: never re-sent, reconciled by reading the ref

    @Test
    fun `a lost ref update where the ref did not move is Unknown with exactly one PATCH and no resend`() = runTest {
        for (mode in listOf(PatchMode.ERROR_NOT_APPLIED, PatchMode.TIMEOUT_NOT_APPLIED)) {
            val remote = CommitRemote().apply { patchMode = mode }
            val result = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x")))
            assertIs<GitHubResult.Unknown>(result, "mode $mode")
            assertEquals(1, remote.count(HttpMethod.Patch), "mode $mode must not re-send the PATCH")
            assertEquals(CF_HEAD, remote.head)
        }
    }

    @Test
    fun `a lost ref update where the ref did move is reconciled to success`() = runTest {
        for (mode in listOf(PatchMode.ERROR_APPLIED, PatchMode.TIMEOUT_APPLIED)) {
            val remote = CommitRemote().apply { patchMode = mode }
            val result = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x")))
            assertEquals(GitHubResult.Success(CommittedFiles(CF_NEW_COMMIT, CF_NEW_TREE, created = true)), result, "mode $mode")
            assertEquals(1, remote.count(HttpMethod.Patch), "mode $mode must not re-send the PATCH")
        }
    }

    @Test
    fun `a lost ref update after another writer moved the branch is CONFLICT`() = runTest {
        val remote = CommitRemote().apply { patchMode = PatchMode.ERROR_OTHER_WRITER }
        assertFailureKind(GitHubFailureKind.CONFLICT, remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(1, remote.count(HttpMethod.Patch))
    }

    @Test
    fun `reconcileCommit separates not applied, our commit, and another writer`() = runTest {
        val remote = CommitRemote()
        suspend fun ours() = remote.adapter.reconcileCommit(CF_REPO, CF_BRANCH, CF_HEAD, CF_NEW_COMMIT, CF_NEW_TREE)

        remote.head = CF_HEAD
        assertIs<GitHubResult.Unknown>(ours())
        remote.head = CF_NEW_COMMIT
        assertEquals(GitHubResult.Success(CommittedFiles(CF_NEW_COMMIT, CF_NEW_TREE, created = true)), ours())
        remote.head = CF_OTHER
        assertFailureKind(GitHubFailureKind.CONFLICT, ours())
        remote.refMissing = true
        assertIs<GitHubResult.Unknown>(ours())
        assertEquals(0, remote.count(HttpMethod.Patch), "reconciliation never writes")
    }

    @Test
    fun `a PATCH unprocessable as non fast forward is CONFLICT`() = runTest {
        val remote = CommitRemote().apply { patchMode = PatchMode.UNPROCESSABLE }
        assertFailureKind(GitHubFailureKind.CONFLICT, remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(1, remote.count(HttpMethod.Patch))
    }

    // (g) 422 is covered above; redirects are never followed

    @Test
    fun `redirects are not followed on reads or writes`() = runTest {
        val onRead = CommitRemote()
        val readRemote = HttpGitHubAdapter(
            githubHttpClient(MockEngine { req ->
                onRead.requests += req
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CF_UNTRUSTED))
            }),
            StaticTokenProvider(CF_TOKEN),
            backoff = {},
        )
        assertFailureKind(GitHubFailureKind.REDIRECT, readRemote.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(1, onRead.requests.size)
        assertTrue(onRead.requests.none { it.url.host == "untrusted.example" })

        val onBlob = CommitRemote()
        onBlob.nextBlobReply = { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CF_UNTRUSTED)) }
        assertIs<GitHubResult.Unknown>(onBlob.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(3, onBlob.requests.size, "GET ref, GET commit, and the one blob POST")
        assertTrue(onBlob.requests.none { it.url.host == "untrusted.example" })
    }

    @Test
    fun `a redirected ref update is Unknown and is not followed`() = runTest {
        val remote = CommitRemote().apply { patchMode = PatchMode.REDIRECT }
        assertIs<GitHubResult.Unknown>(remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x"))))
        assertEquals(1, remote.count(HttpMethod.Patch))
        assertTrue(remote.requests.none { it.url.host == "untrusted.example" })
    }

    // (i) the token never reaches results, bodies or logs

    @Test
    fun `token and transport detail do not appear in results or request bodies`() = runTest {
        val remote = CommitRemote().apply { patchMode = PatchMode.TIMEOUT_NOT_APPLIED }
        val result = remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "BODY_SECRET")))
        assertFalse(result.toString().contains(CF_TOKEN))
        assertFalse(result.toString().contains("timeout"))
        for (req in remote.requests) {
            val text = (req.body as? TextContent)?.text.orEmpty()
            assertFalse(text.contains(CF_TOKEN))
        }

        val failing = CommitRemote().apply { refMissing = true }
        val notFound = failing.adapter.commitFiles(CF_REPO, request(FileWrite.Write("a.txt", "x")))
        assertFalse(notFound.toString().contains(CF_TOKEN))
    }

    // (j) only regular files; no mode can be chosen by the caller

    @Test
    fun `every tree entry is 100644, so executable, symlink and submodule modes are never written`() = runTest {
        val remote = CommitRemote()
        remote.adapter.commitFiles(CF_REPO, request(FileWrite.Write("bin/run.sh", "#!/bin/sh\n"), FileWrite.Write("link", "target")))
        val modes = bodyOf(remote.requests.single { it.url.encodedPath.endsWith("/git/trees") })
            .getValue("tree").jsonArray.map { it.jsonObject.getValue("mode").jsonPrimitive.content }
        assertEquals(listOf("100644", "100644"), modes)
    }

    // Port surface and the default-branch guard stay as they were.

    @Test
    fun `commitFiles is the only ref-writing method on the port`() {
        val names = GitHubPort::class.java.methods.map { it.name }
        assertEquals(1, names.count { it == "commitFiles" })
        assertFalse(names.any { it.contains("force", ignoreCase = true) })
    }
}
