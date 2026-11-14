package net.kigawa.kivra.chat

import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.selfdev.RepoEntry
import net.kigawa.kivra.selfdev.RepoReader

private const val OPERATOR = "1:i:oper"

/**
 * The chat AI's repository reads (ADR 0023 追補 7) with a fake reader: the path rules (`..`, absolute, `.git`, the context prefixes), the
 * repositories allowed (the project's mapped ones, or the self-development target), the size and text checks, the secret filter, the length and
 * call limits of a turn, GitHub's failures as fixed codes, and no tool without the self-development reader.
 */
class ChatRepoToolsTest {
    private val openDbs = mutableListOf<TestDb>()

    @AfterTest
    fun close() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private val target = RepoRef("kigawa-net", "kivra")

    /** A reader over [files] (path to text; null text is a binary file). It records the calls. */
    private class FakeReader(
        val files: Map<String, String?>,
        val sizes: Map<String, Long> = emptyMap(),
        val listFailure: GitHubResult<List<RepoEntry>>? = null,
        val readFailure: GitHubResult<String?>? = null,
    ) : RepoReader {
        val listed = mutableListOf<RepoRef>()
        val read = mutableListOf<Pair<RepoRef, String>>()

        override suspend fun listFiles(repo: RepoRef, commitSha: String): GitHubResult<List<RepoEntry>> {
            listed += repo
            listFailure?.let { return it }
            return GitHubResult.Success(files.map { (p, t) -> RepoEntry(p, sizes[p] ?: (t?.length?.toLong() ?: 10L)) })
        }

        override suspend fun readFile(repo: RepoRef, commitSha: String, path: String): GitHubResult<String?> {
            read += repo to path
            readFailure?.let { return it }
            return GitHubResult.Success(files[path])
        }
    }

    private inner class World(
        reader: FakeReader? = FakeReader(mapOf("README.md" to "# kivra", "docs/a.md" to "line1\nline2\nline3", "kivra-app/Main.kt" to "fun main()")),
        guard: PermissionGuard = PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, null, null, null)),
        head: GitHubResult<String?> = GitHubResult.Success("sha1"),
        val maxFileBytes: Long = 1_000_000,
    ) {
        val db = TestDb().also { openDbs += it }
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val project = projects.create("Repo", null).projectId
        val source = reader?.let {
            ChatRepoSource(it, { _, _ -> head }, target, "main", listOf("README.md", "docs/"), maxFileBytes)
        }
        val deps = ChatToolDeps(projects, db.runs, ProjectMemberRepository(db.database, Clock.systemUTC()), guard, repo = source)

        fun turn() = deps.forTurn(project, OPERATOR)
    }

    private fun args(vararg pairs: Pair<String, String>): JsonElement = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun ChatToolExecutor.read(vararg pairs: Pair<String, String>) = run("repo_read_file", args(*pairs)).outcome

    private fun ChatToolExecutor.tree(vararg pairs: Pair<String, String>) = run("repo_tree", args(*pairs)).outcome

    @Test
    fun `the repository tools are offered only with the self-development reader and the text permission`() {
        assertTrue(ChatTool.REPO_TREE in World().turn().offered)
        assertTrue(ChatTool.REPO_READ_FILE in World().turn().offered)
        assertFalse(World(reader = null).turn().offered.any { it.needsRepo }, "self-development off: no repository tool")
        val viewer = PermissionGuard(
            PermissionService(
                PermissionMode.ENFORCE, RolesDefault.NONE, null, null,
                object : AssigneeLookup {
                    override fun accessOf(ownerKey: String) = if (ownerKey == OPERATOR) AssigneeAccess(Role.VIEWER, true, "v") else null
                },
            ),
        )
        assertFalse(World(guard = viewer).turn().offered.any { it.needsRepo }, "no READ_TEXT: no repository tool")
    }

    @Test
    fun `a path is normalized, and parent, absolute and git paths are refused`() {
        assertEquals("docs/a.md", ChatRepoSource.normalizedPath(" docs/a.md "))
        for (bad in listOf("../etc/passwd", "docs/../secret", "/etc/passwd", "docs//a.md", "./README.md", ".git/config", "docs\\a.md", "a\u0000b")) {
            assertNull(ChatRepoSource.normalizedPath(bad), bad)
        }
        val w = World()
        assertEquals(ChatToolOutcome(ChatToolStatus.DENIED, ChatToolExecutor.CODE_PATH_REFUSED), w.turn().read("path" to "../README.md"))
        assertEquals(ChatToolOutcome(ChatToolStatus.DENIED, ChatToolExecutor.CODE_PATH_REFUSED), w.turn().read("path" to "/README.md"))
    }

    @Test
    fun `a file outside the context prefixes is refused, and the tree lists the allowed files only`() {
        val w = World()
        assertEquals(ChatToolOutcome(ChatToolStatus.DENIED, ChatToolExecutor.CODE_PATH_NOT_ALLOWED), w.turn().read("path" to "kivra-app/Main.kt"))
        val files = w.turn().tree().data["files"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals(listOf("README.md", "docs/a.md"), files)
        assertEquals(listOf("docs/a.md"), w.turn().tree("prefix" to "docs/").data["files"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals(ChatToolExecutor.CODE_NOT_FOUND, w.turn().read("path" to "docs/missing.md").code)
    }

    @Test
    fun `a file is read in its line range`() {
        val w = World()
        val out = w.turn().read("path" to "docs/a.md", "startLine" to "2", "endLine" to "3")
        assertEquals(ChatToolStatus.OK, out.status)
        assertEquals("line2\nline3", out.data["content"]!!.jsonPrimitive.content)
        assertEquals("3", out.data["total_lines"]!!.jsonPrimitive.content)
        assertEquals(ChatToolExecutor.CODE_INVALID_ARGUMENTS, w.turn().read("path" to "docs/a.md", "startLine" to "3", "endLine" to "2").code)
    }

    @Test
    fun `a large or binary file is not read`() {
        val reader = FakeReader(mapOf("docs/big.md" to "x", "docs/bin.png" to null), sizes = mapOf("docs/big.md" to 200L))
        val w = World(reader = reader, maxFileBytes = 100)
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, ChatToolExecutor.CODE_FILE_TOO_LARGE), w.turn().read("path" to "docs/big.md"))
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, ChatToolExecutor.CODE_NOT_TEXT), w.turn().read("path" to "docs/bin.png"))
        assertTrue(reader.read.none { it.second == "docs/big.md" }, "a file over the limit is never fetched")
    }

    @Test
    fun `only the project's mapped repositories, or the target when none is mapped, may be read`() {
        val reader = FakeReader(mapOf("README.md" to "x"))
        val w = World(reader = reader)
        w.turn().read("path" to "README.md")
        assertEquals(listOf(target), reader.read.map { it.first }, "no mapping: the self-development target")

        w.projects.addRepo(w.project, "owner/app")
        assertEquals(ChatToolOutcome(ChatToolStatus.DENIED, ChatToolExecutor.CODE_REPO_NOT_ALLOWED), w.turn().read("path" to "README.md", "repo" to "kigawa-net/kivra"))
        assertEquals(ChatToolOutcome(ChatToolStatus.DENIED, ChatToolExecutor.CODE_REPO_NOT_ALLOWED), w.turn().read("path" to "README.md", "repo" to "evil/repo"))
        assertEquals(ChatToolStatus.OK, w.turn().read("path" to "README.md", "repo" to "owner/app").status)
        assertEquals(RepoRef("owner", "app"), reader.read.last().first)
    }

    @Test
    fun `a secret in a file is redacted`() {
        val w = World(reader = FakeReader(mapOf("docs/env.md" to "token ghp_abcdefghijklmnopqrstuvwxyz0123456789 end")))
        val content = w.turn().read("path" to "docs/env.md").data["content"]!!.jsonPrimitive.content
        assertFalse(content.contains("ghp_"))
        assertTrue(content.contains("[REDACTED:"))
    }

    @Test
    fun `one file gives at most 8000 characters and a turn at most 40000`() {
        val big = "a".repeat(9_000)
        val files = (1..6).associate { "docs/f$it.md" to big }
        val w = World(reader = FakeReader(files))
        val turn = w.turn()
        val first = turn.read("path" to "docs/f1.md")
        assertEquals(ChatToolExecutor.REPO_FILE_CHARS, first.data["content"]!!.jsonPrimitive.content.length)
        assertEquals("true", first.data["truncated"]!!.jsonPrimitive.content)
        (2..5).forEach { assertEquals(ChatToolStatus.OK, turn.read("path" to "docs/f$it.md").status) }
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, ChatToolExecutor.CODE_TURN_READ_LIMIT), turn.read("path" to "docs/f6.md"))
    }

    @Test
    fun `a turn makes at most twelve repository calls, and lists the tree once`() {
        val reader = FakeReader(mapOf("README.md" to "x"))
        val w = World(reader = reader)
        val turn = w.turn()
        repeat(ChatToolExecutor.REPO_CALLS_PER_TURN) { assertEquals(ChatToolStatus.OK, turn.tree().status) }
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, ChatToolExecutor.CODE_REPO_CALL_LIMIT), turn.read("path" to "README.md"))
        assertEquals(1, reader.listed.size)
    }

    @Test
    fun `GitHub's failures are fixed codes`() {
        val limited = World(reader = FakeReader(emptyMap(), listFailure = GitHubResult.Failure(GitHubFailureKind.RATE_LIMITED, "rate limited")))
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, "GITHUB_RATE_LIMITED"), limited.turn().tree())
        val gone = World(reader = FakeReader(mapOf("README.md" to "x"), readFailure = GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, "nf")))
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, "GITHUB_NOT_FOUND"), gone.turn().read("path" to "README.md"))
        val unknown = World(head = GitHubResult.Unknown("getBranch", "timeout"))
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, ChatToolExecutor.CODE_GITHUB_UNAVAILABLE), unknown.turn().tree())
        val noBranch = World(head = GitHubResult.Success(null))
        assertEquals(ChatToolOutcome(ChatToolStatus.FAILED, ChatToolExecutor.CODE_BRANCH_NOT_FOUND), noBranch.turn().tree())
    }

    @Test
    fun `the repository arguments are checked`() {
        val ok = mapOf("path" to "docs/a.md")
        assertTrue(ChatToolArguments.isValid(ChatTool.REPO_READ_FILE, ok + ("startLine" to "1") + ("endLine" to "9")))
        assertFalse(ChatToolArguments.isValid(ChatTool.REPO_READ_FILE, emptyMap()), "the path is required")
        assertFalse(ChatToolArguments.isValid(ChatTool.REPO_READ_FILE, ok + ("startLine" to "0")))
        assertFalse(ChatToolArguments.isValid(ChatTool.REPO_READ_FILE, ok + ("ref" to "dev")), "the branch is the server's")
        assertTrue(ChatToolArguments.isValid(ChatTool.REPO_TREE, emptyMap()))
        assertFalse(ChatToolArguments.isValid(ChatTool.REPO_TREE, mapOf("limit" to "201")))
    }
}
