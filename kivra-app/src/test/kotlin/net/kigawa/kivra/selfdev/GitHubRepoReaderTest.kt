package net.kigawa.kivra.selfdev

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.github.GitHubAdapterConfig
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.HttpGitHubAdapter
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.TokenProvider
import net.kigawa.kivra.github.githubHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SHA = "0123456789abcdef0123456789abcdef01234567"
private val REPO = RepoRef("kigawa-net", "kivra")
private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

/** Runs [GitHubRepoReader] over the real [HttpGitHubAdapter] with MockEngine, so the adapter's own checks apply. */
private class Remote(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val methods = mutableListOf<HttpMethod>()
    private val engine = MockEngine { req ->
        methods += req.method
        handler(req)
    }
    private val tokens = object : TokenProvider {
        override suspend fun token() = "tok"
        override fun invalidate() {}
    }
    val reader = GitHubRepoReader(
        HttpGitHubAdapter(githubHttpClient(engine, GitHubAdapterConfig()), tokens, backoff = {}),
        maxFileBytes = 100,
    )
}

private fun treeBody(truncated: Boolean, entries: String) = """{"sha":"$SHA","truncated":$truncated,"tree":[$entries]}"""

private fun blob(path: String, mode: String = "100644", size: Int = 1) =
    """{"path":"$path","mode":"$mode","type":"blob","sha":"$SHA","size":$size}"""

private fun fileBody(path: String, bytes: ByteArray) =
    """{"type":"file","path":"$path","size":${bytes.size},"encoding":"base64","content":"${Base64.getMimeEncoder().encodeToString(bytes)}"}"""

class GitHubRepoReaderTest {
    @Test
    fun `listFiles returns blobs, symlinks and submodules as existing paths, and no directories`() = runTest {
        val remote = Remote {
            respond(
                treeBody(
                    truncated = false,
                    entries = listOf(
                        blob("a.md", size = 5),
                        blob("bin/run.sh", mode = "100755", size = 7),
                        """{"path":"docs","mode":"040000","type":"tree","sha":"$SHA"}""",
                        blob("link", mode = "120000", size = 3),
                        """{"path":"sub","mode":"160000","type":"commit","sha":"$SHA"}""",
                    ).joinToString(","),
                ),
                HttpStatusCode.OK,
                JSON,
            )
        }
        val entries = assertIs<GitHubResult.Success<List<RepoEntry>>>(remote.reader.listFiles(REPO, SHA)).value
        assertEquals(
            listOf(
                RepoEntry("a.md", 5),
                RepoEntry("bin/run.sh", 7),
                RepoEntry("link", 3),
                RepoEntry("sub", 0),
            ),
            entries,
        )
        assertTrue(remote.methods.all { it == HttpMethod.Get })
    }

    @Test
    fun `a truncated tree fails the listing and never returns a partial list`() = runTest {
        val remote = Remote {
            respond(treeBody(truncated = true, entries = blob("a.md")), HttpStatusCode.OK, JSON)
        }
        val result = remote.reader.listFiles(REPO, SHA)
        assertIs<GitHubResult.Failure>(result)
        assertEquals(GitHubFailureKind.TREE_TRUNCATED, result.kind)
    }

    @Test
    fun `a listing failure is passed through, not turned into an empty tree`() = runTest {
        val remote = Remote { respond("", HttpStatusCode.NotFound) }
        val result = remote.reader.listFiles(REPO, SHA)
        assertIs<GitHubResult.Failure>(result)
        assertEquals(GitHubFailureKind.NOT_FOUND, result.kind)
    }

    @Test
    fun `readFile returns the text of a text file`() = runTest {
        val remote = Remote { respond(fileBody("a.md", "hello".toByteArray()), HttpStatusCode.OK, JSON) }
        assertEquals(GitHubResult.Success("hello"), remote.reader.readFile(REPO, SHA, "a.md"))
    }

    @Test
    fun `binary files fail the read and are never returned as text`() = runTest {
        val remote = Remote { respond(fileBody("bin.dat", byteArrayOf(1, 0, 2)), HttpStatusCode.OK, JSON) }
        val result = remote.reader.readFile(REPO, SHA, "bin.dat")
        assertIs<GitHubResult.Failure>(result)
        assertEquals(GitHubFailureKind.VALIDATION, result.kind)
    }

    @Test
    fun `a file over the reader limit fails the read`() = runTest {
        val remote = Remote { respond("""{"type":"file","path":"big.md","size":101,"encoding":"none","content":""}""", HttpStatusCode.OK, JSON) }
        val result = remote.reader.readFile(REPO, SHA, "big.md")
        assertIs<GitHubResult.Failure>(result)
        assertEquals(GitHubFailureKind.TOO_LARGE, result.kind)
    }

    @Test
    fun `a file that disappeared after the listing fails the read, it is not an empty file`() = runTest {
        val remote = Remote { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON) }
        val result = remote.reader.readFile(REPO, SHA, "gone.md")
        assertIs<GitHubResult.Failure>(result)
        assertEquals(GitHubFailureKind.NOT_FOUND, result.kind)
    }

    @Test
    fun `a symlink or submodule path fails the read`() = runTest {
        val remote = Remote { respond("""{"type":"symlink","path":"link","size":3}""", HttpStatusCode.OK, JSON) }
        val result = remote.reader.readFile(REPO, SHA, "link")
        assertIs<GitHubResult.Failure>(result)
        assertEquals(GitHubFailureKind.VALIDATION, result.kind)
    }
}
