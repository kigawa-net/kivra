package net.kigawa.kivra.agent.workdir

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicGitMirrorTest {
    @Test
    fun publicGithubSourceBuildsOnlyAnHttpsUrlWithoutCredentials() {
        val source = RepoSource.PublicGithub("kigawa-net", "kivra")
        assertEquals("https://github.com/kigawa-net/kivra.git", source.url())
    }

    @Test
    fun publicGithubNamesAreValidated() {
        assertFailsWith<IllegalArgumentException> { RepoSource.PublicGithub("../evil", "kivra") }
        assertFailsWith<IllegalArgumentException> { RepoSource.PublicGithub("kigawa-net", "x/../../y") }
        assertFailsWith<IllegalArgumentException> { RepoSource.PublicGithub("user:pass@host", "kivra") }
        assertFailsWith<IllegalArgumentException> { RepoSource.PublicGithub("kigawa-net", ".hidden") }
    }

    @Test
    fun mirrorSyncsAndArchivesTheCommitWithoutCredentials() {
        val src = makeRepo(files = mapOf("README.md" to "hello\n", "docs/a.md" to "alpha\n"))
        val mirrorRoot = Files.createTempDirectory("kivra-mirror")
        val mirror = PublicGitMirror(GIT_BIN, mirrorRoot.resolve("mirror.git"), Duration.ofMinutes(2))
        mirror.sync(LocalPath(src.repo))
        val names = mirror.openArchive(src.sha).use { stream ->
            val bytes = stream.readAllBytes()
            String(bytes, Charsets.ISO_8859_1)
        }
        assertTrue(names.contains("README.md"))
        assertTrue(names.contains("docs/a.md"))
    }

    @Test
    fun unknownCommitIsAGitFailure() {
        val src = makeRepo(files = mapOf("README.md" to "hello\n"))
        val mirror = PublicGitMirror(GIT_BIN, Files.createTempDirectory("kivra-mirror").resolve("m.git"), Duration.ofMinutes(2))
        mirror.sync(LocalPath(src.repo))
        assertFailsWith<WorkDirException> { mirror.openArchive("1".repeat(40)).use { it.readAllBytes() } }.also {
            assertEquals(WorkDirReason.GIT_FAILED, it.reason)
        }
    }

    @Test
    fun malformedCommitIsRefusedBeforeGitRuns() {
        val mirror = PublicGitMirror(GIT_BIN, Files.createTempDirectory("kivra-mirror").resolve("m.git"))
        assertFailsWith<WorkDirException> { mirror.openArchive("HEAD; rm -rf /") }
    }
}
