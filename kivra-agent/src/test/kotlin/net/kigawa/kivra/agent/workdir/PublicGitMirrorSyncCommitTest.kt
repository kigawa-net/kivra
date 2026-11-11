package net.kigawa.kivra.agent.workdir

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicGitMirrorSyncCommitTest {
    private fun mirror() = PublicGitMirror(GIT_BIN, Files.createTempDirectory("kivra-mirror").resolve("m.git"), Duration.ofMinutes(2))

    @Test
    fun fetchesOneCommitAndArchivesIt() {
        val repo = makeRepo(mapOf("docs/a.md" to "hello\n"))
        val m = mirror()
        m.syncCommit(LocalPath(repo.repo), repo.sha)
        val tar = m.openArchive(repo.sha).use { it.readAllBytes() }
        assertTrue(String(tar, Charsets.ISO_8859_1).contains("docs/a.md"))
    }

    @Test
    fun aCommitThatIsNotAFullObjectNameIsRefusedBeforeGitRuns() {
        val repo = makeRepo(mapOf("docs/a.md" to "x\n"))
        for (bad in listOf("main", "HEAD", repo.sha.take(7), "--upload-pack=x", repo.sha.uppercase())) {
            val e = assertFailsWith<WorkDirException> { mirror().syncCommit(LocalPath(repo.repo), bad) }
            assertEquals(WorkDirReason.GIT_FAILED, e.reason)
        }
    }

    @Test
    fun aMissingCommitFails() {
        val repo = makeRepo(mapOf("docs/a.md" to "x\n"))
        assertFailsWith<WorkDirException> { mirror().syncCommit(LocalPath(repo.repo), "0".repeat(40)) }
    }
}
