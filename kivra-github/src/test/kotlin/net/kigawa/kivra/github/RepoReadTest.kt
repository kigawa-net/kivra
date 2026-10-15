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
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Read-only GitHubPort operations (listTree, readFile). All traffic goes through MockEngine; nothing reaches the network.

private const val RR_SHA = "0123456789abcdef0123456789abcdef01234567"
private const val RR_TOKEN = "ghs_RR_SECRET_TOKEN_VALUE"
private val RR_REPO = RepoRef("kigawa-net", "kivra")
private val RR_JSON = headersOf(HttpHeaders.ContentType, "application/json")

private class ReadRemote(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    private val tokens = object : TokenProvider {
        override suspend fun token() = RR_TOKEN
        override fun invalidate() {}
    }
    val adapter = HttpGitHubAdapter(
        githubHttpClient(MockEngine { req -> requests += req; handler(req) }),
        tokens,
        backoff = {},
    )
}

private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

/** Base64 with a line break every 60 characters, the way the contents API wraps it. */
private fun b64Wrapped(bytes: ByteArray): String = Base64.getMimeEncoder(60, "\n".toByteArray()).encodeToString(bytes)

private fun contentsJson(path: String, bytes: ByteArray, type: String = "file", size: Long = bytes.size.toLong(), encoding: String = "base64") =
    """{"type":"$type","path":"$path","size":$size,"encoding":"$encoding","content":"${b64Wrapped(bytes).replace("\n", "\\n")}"}"""

private fun treeJson(sha: String = RR_SHA, truncated: Boolean = false, entries: String) =
    """{"sha":"$sha","truncated":$truncated,"tree":[$entries]}"""

private fun assertGetOnly(requests: List<HttpRequestData>) {
    assertTrue(requests.all { it.method == HttpMethod.Get }, "every request must be GET: ${requests.map { it.method.value }}")
}

private fun assertKind(kind: GitHubFailureKind, result: GitHubResult<*>) {
    assertIs<GitHubResult.Failure>(result)
    assertEquals(kind, result.kind)
}

class RepoReadTest {
    // ---- listTree ----

    @Test
    fun `listTree returns the entries of a complete tree`() = runTest {
        val f = ReadRemote {
            respond(
                treeJson(entries = """{"path":"README.md","mode":"100644","type":"blob","sha":"$RR_SHA","size":12},""" +
                    """{"path":"docs","mode":"040000","type":"tree","sha":"$RR_SHA"},""" +
                    """{"path":"link","mode":"120000","type":"blob","sha":"$RR_SHA","size":3},""" +
                    """{"path":"sub","mode":"160000","type":"commit","sha":"$RR_SHA"}"""),
                HttpStatusCode.OK,
                RR_JSON,
            )
        }
        val tree = assertIs<GitHubResult.Success<RepoTree>>(f.adapter.listTree(RR_REPO, "main")).value
        assertEquals(RR_SHA, tree.sha)
        assertFalse(tree.truncated)
        assertEquals(
            listOf(
                TreeEntry("README.md", TreeEntryType.BLOB, "100644", 12),
                TreeEntry("docs", TreeEntryType.TREE, "040000", null),
                TreeEntry("link", TreeEntryType.BLOB, "120000", 3),
                TreeEntry("sub", TreeEntryType.COMMIT, "160000", null),
            ),
            tree.entries,
        )
        val req = f.requests.single()
        assertEquals("/repos/kigawa-net/kivra/git/trees/main", req.url.encodedPath)
        assertEquals("1", req.url.parameters["recursive"])
        assertEquals("Bearer $RR_TOKEN", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a truncated tree is a failure and no partial list is returned`() = runTest {
        val f = ReadRemote {
            respond(treeJson(truncated = true, entries = """{"path":"a.md","mode":"100644","type":"blob","sha":"$RR_SHA","size":1}"""), HttpStatusCode.OK, RR_JSON)
        }
        val result = f.adapter.listTree(RR_REPO, "main")
        assertKind(GitHubFailureKind.TREE_TRUNCATED, result)
    }

    @Test
    fun `a tree that answers for a different commit than the requested sha is an identity mismatch`() = runTest {
        val other = "fedcba9876543210fedcba9876543210fedcba98"
        val f = ReadRemote { respond(treeJson(sha = other, entries = ""), HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.IDENTITY_MISMATCH, f.adapter.listTree(RR_REPO, RR_SHA))
    }

    @Test
    fun `a blob without a size or an unknown entry type is a protocol failure`() = runTest {
        val noSize = ReadRemote { respond(treeJson(entries = """{"path":"a","mode":"100644","type":"blob","sha":"$RR_SHA"}"""), HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.PROTOCOL, noSize.adapter.listTree(RR_REPO, "main"))
        val unknownType = ReadRemote { respond(treeJson(entries = """{"path":"a","mode":"100644","type":"weird","sha":"$RR_SHA"}"""), HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.PROTOCOL, unknownType.adapter.listTree(RR_REPO, "main"))
    }

    // ---- readFile ----

    @Test
    fun `readFile decodes wrapped base64 strictly and returns text`() = runTest {
        val body = "# 題名\nline two\n"
        val f = ReadRemote { respond(contentsJson("docs/a.md", body.toByteArray()), HttpStatusCode.OK, RR_JSON) }
        val content = assertIs<GitHubResult.Success<RepoFileContent?>>(f.adapter.readFile(RR_REPO, "main", "docs/a.md", 1024)).value
        assertIs<RepoFileContent>(content)
        assertTrue(content.isText)
        assertEquals(body, content.text)
        assertEquals("main", f.requests.single().url.parameters["ref"])
    }

    @Test
    fun `a missing file is Success null`() = runTest {
        val f = ReadRemote { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, RR_JSON) }
        assertNull(assertIs<GitHubResult.Success<RepoFileContent?>>(f.adapter.readFile(RR_REPO, RR_SHA, "docs/none.md", 1024)).value)
    }

    @Test
    fun `directories, symlinks and submodules are rejected as not a file`() = runTest {
        val dir = ReadRemote { respond("[]", HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.VALIDATION, dir.adapter.readFile(RR_REPO, "main", "docs", 1024))
        val symlink = ReadRemote { respond("""{"type":"symlink","path":"l","size":3,"encoding":"base64","content":""}""", HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.VALIDATION, symlink.adapter.readFile(RR_REPO, "main", "l", 1024))
        val submodule = ReadRemote { respond("""{"type":"submodule","path":"s","size":0}""", HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.VALIDATION, submodule.adapter.readFile(RR_REPO, "main", "s", 1024))
    }

    @Test
    fun `a file over the byte limit is refused from its reported size and nothing is decoded or re-requested`() = runTest {
        // Same shape the contents API returns for a large file: the reported size is over the limit and the content is not inline.
        val body = """{"type":"file","path":"big.md","size":2000,"encoding":"none","content":""}"""
        val f = ReadRemote { respond(body, HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.TOO_LARGE, f.adapter.readFile(RR_REPO, "main", "big.md", 1_000))
        assertEquals(1, f.requests.size, "an oversize file must not cause a second request")
    }

    @Test
    fun `invalid base64 and a length that disagrees with the reported size are protocol failures`() = runTest {
        val badChars = ReadRemote {
            respond("""{"type":"file","path":"a.md","size":3,"encoding":"base64","content":"!!!!"}""", HttpStatusCode.OK, RR_JSON)
        }
        assertKind(GitHubFailureKind.PROTOCOL, badChars.adapter.readFile(RR_REPO, "main", "a.md", 1024))
        val wrongSize = ReadRemote {
            respond("""{"type":"file","path":"a.md","size":9,"encoding":"base64","content":"${b64("abc")}"}""", HttpStatusCode.OK, RR_JSON)
        }
        assertKind(GitHubFailureKind.PROTOCOL, wrongSize.adapter.readFile(RR_REPO, "main", "a.md", 1024))
    }

    @Test
    fun `binary and non-UTF-8 content is returned with isText false and no text`() = runTest {
        val withNul = byteArrayOf(0x41, 0x00, 0x42)
        val f = ReadRemote { respond(contentsJson("bin.dat", withNul), HttpStatusCode.OK, RR_JSON) }
        val bin = assertIs<GitHubResult.Success<RepoFileContent?>>(f.adapter.readFile(RR_REPO, "main", "bin.dat", 1024)).value
        assertIs<RepoFileContent>(bin)
        assertFalse(bin.isText)
        assertNull(bin.text)

        val badUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        val g = ReadRemote { respond(contentsJson("bad.txt", badUtf8), HttpStatusCode.OK, RR_JSON) }
        val bad = assertIs<GitHubResult.Success<RepoFileContent?>>(g.adapter.readFile(RR_REPO, "main", "bad.txt", 1024)).value
        assertIs<RepoFileContent>(bad)
        assertFalse(bad.isText)
    }

    @Test
    fun `a response for a different path is an identity mismatch`() = runTest {
        val f = ReadRemote { respond(contentsJson("other.md", "x".toByteArray()), HttpStatusCode.OK, RR_JSON) }
        assertKind(GitHubFailureKind.IDENTITY_MISMATCH, f.adapter.readFile(RR_REPO, "main", "docs/a.md", 1024))
    }

    @Test
    fun `spaces in a path are percent-encoded, not sent as plus`() = runTest {
        val f = ReadRemote { respond(contentsJson("docs/a b.md", "x".toByteArray()), HttpStatusCode.OK, RR_JSON) }
        f.adapter.readFile(RR_REPO, "main", "docs/a b.md", 1024)
        assertTrue(f.requests.single().url.encodedPath.endsWith("/contents/docs/a%20b.md"), f.requests.single().url.encodedPath)
    }

    // ---- request validation: nothing is sent ----

    @Test
    fun `unsafe paths are rejected before any request`() = runTest {
        val paths = listOf("", ".", "..", "a/../b", "a/./b", "/abs.md", "a\\b.md", "a//b", "a\u0000b", "a\nb", ".git/config", "x/.GIT/y")
        for (path in paths) {
            val f = ReadRemote { respond("", HttpStatusCode.OK) }
            assertKind(GitHubFailureKind.VALIDATION, f.adapter.readFile(RR_REPO, "main", path, 1024))
            assertEquals(0, f.requests.size, "no request for path: $path")
        }
    }

    @Test
    fun `unsafe refs are rejected before any request for both operations`() = runTest {
        val refs = listOf("", "..", "a/../b", "a/./b", "a//b", "main name", "x\u0000y", "a/" + "x".repeat(256))
        for (ref in refs) {
            val f = ReadRemote { respond("", HttpStatusCode.OK) }
            assertKind(GitHubFailureKind.VALIDATION, f.adapter.listTree(RR_REPO, ref))
            assertKind(GitHubFailureKind.VALIDATION, f.adapter.readFile(RR_REPO, ref, "a.md", 1024))
            assertEquals(0, f.requests.size, "no request for ref: $ref")
        }
    }

    // ---- GET only, token, redirects, failure classes ----

    @Test
    fun `every read operation sends only GET and the token appears only in Authorization`() = runTest {
        val f = ReadRemote { req ->
            if (req.url.encodedPath.contains("/git/trees/")) {
                respond(treeJson(entries = """{"path":"a.md","mode":"100644","type":"blob","sha":"$RR_SHA","size":1}"""), HttpStatusCode.OK, RR_JSON)
            } else {
                respond(contentsJson("a.md", "x".toByteArray()), HttpStatusCode.OK, RR_JSON)
            }
        }
        f.adapter.listTree(RR_REPO, "main")
        f.adapter.readFile(RR_REPO, "main", "a.md", 1024)
        assertEquals(2, f.requests.size)
        assertGetOnly(f.requests)
        for (req in f.requests) {
            assertFalse(req.url.toString().contains(RR_TOKEN))
            assertEquals("Bearer $RR_TOKEN", req.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `failures never carry the token or the provider body`() = runTest {
        val f = ReadRemote { respond("""{"message":"leaked $RR_TOKEN"}""", HttpStatusCode.Forbidden, RR_JSON) }
        val result = f.adapter.readFile(RR_REPO, "main", "a.md", 1024)
        assertKind(GitHubFailureKind.PERMISSION, result)
        assertFalse(result.toString().contains(RR_TOKEN))
    }

    @Test
    fun `a redirect is reported as REDIRECT and is not followed`() = runTest {
        val f = ReadRemote { respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://untrusted.example/x")) }
        assertKind(GitHubFailureKind.REDIRECT, f.adapter.listTree(RR_REPO, "main"))
        assertKind(GitHubFailureKind.REDIRECT, f.adapter.readFile(RR_REPO, "main", "a.md", 1024))
        assertTrue(f.requests.none { it.url.host == "untrusted.example" })
        assertEquals(2, f.requests.size)
    }

    @Test
    fun `a 5xx on a GET is retried and then SERVER_ERROR, a 429 is RATE_LIMITED without retry`() = runTest {
        val always500 = ReadRemote { respond("", HttpStatusCode.BadGateway) }
        assertKind(GitHubFailureKind.SERVER_ERROR, always500.adapter.listTree(RR_REPO, "main"))
        assertEquals(3, always500.requests.size)

        var calls = 0
        val flaky = ReadRemote {
            calls++
            if (calls == 1) respond("", HttpStatusCode.InternalServerError) else respond(treeJson(entries = ""), HttpStatusCode.OK, RR_JSON)
        }
        assertIs<GitHubResult.Success<RepoTree>>(flaky.adapter.listTree(RR_REPO, "main"))
        assertEquals(2, flaky.requests.size)

        val limited = ReadRemote { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7")) }
        val result = limited.adapter.readFile(RR_REPO, "main", "a.md", 1024)
        assertKind(GitHubFailureKind.RATE_LIMITED, result)
        assertEquals(1, limited.requests.size)
    }

    @Test
    fun `a timeout on a GET is retried and then NETWORK`() = runTest {
        val f = ReadRemote { throw IOException("timed out") }
        assertKind(GitHubFailureKind.NETWORK, f.adapter.readFile(RR_REPO, "main", "a.md", 1024))
        assertEquals(3, f.requests.size)
        assertGetOnly(f.requests)
    }
}
