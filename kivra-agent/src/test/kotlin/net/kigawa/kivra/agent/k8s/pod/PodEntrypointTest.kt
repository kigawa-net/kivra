package net.kigawa.kivra.agent.k8s.pod

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.workdir.TEST_ATTEMPT
import net.kigawa.kivra.agent.workdir.TEST_RUN
import net.kigawa.kivra.agent.workdir.TarSpec
import net.kigawa.kivra.agent.workdir.WorkDirException
import net.kigawa.kivra.agent.workdir.WorkDirReason
import net.kigawa.kivra.agent.workdir.newWorkRoot
import net.kigawa.kivra.agent.workdir.tarOf
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffLimits
import net.kigawa.kivra.agent.workdir.DiffRejectReason
import net.kigawa.kivra.agent.workdir.PathRules

class PodEntrypointTest {
    private val commit = "b".repeat(40)
    private val root: Path = newWorkRoot()
    private val kivraDir: Path = root.resolve(".kivra")

    private fun writeInput(body: String = "Please fix") {
        Files.createDirectories(kivraDir)
        Files.write(
            kivraDir.resolve("input.json"),
            PodInput.encode(PodInput(TEST_RUN, TEST_ATTEMPT, "kigawa-net", "kivra", commit, "Fix docs", body, false)),
        )
    }

    private fun tar(vararg files: Pair<String, String>): ByteArray =
        tarOf(files.map { (name, text) -> TarSpec(name, content = text.toByteArray()) })

    private fun entrypoint(
        fetcher: BaseFetcher = BaseFetcher { ByteArrayInputStream(tar("docs/a.md" to "old\n", "README.md" to "readme\n")) },
        cli: CliProcess = CliProcess { _, _ -> 0 },
        limits: DiffLimits = DiffLimits(),
        wait: Duration = Duration.ofSeconds(5),
    ) = PodEntrypoint(root, kivraDir, fetcher, cli, limits, wait, pollMillis = 5)

    private fun result(): PodReadout = PodResultCodec.decode(Files.readAllBytes(kivraDir.resolve("result.json")))

    @Test
    fun collectsTheChangeTheCliMadeAndWritesTheResultFile() {
        writeInput()
        var seenArgv: List<String>? = null
        var seenDir: Path? = null
        entrypoint(
            cli = CliProcess { argv, dir ->
                seenArgv = argv
                seenDir = dir
                Files.writeString(dir.resolve("docs/a.md"), "new\n")
                0
            },
        ).run(listOf("claude", "-p", "x"))
        assertEquals(listOf("claude", "-p", "x"), seenArgv)
        assertEquals(root.resolve("$TEST_RUN-$TEST_ATTEMPT"), seenDir)
        val readout = assertIs<PodReadout.Collected>(result())
        assertEquals(0, readout.exitStatus)
        val change = readout.changes!!.single()
        assertEquals("docs/a.md", change.path)
        assertEquals("new\n", change.content)
        // The result is written through a rename: no temporary file is left.
        assertFalse(Files.exists(kivraDir.resolve("result.json.tmp")))
    }

    @Test
    fun theTaskIsAFileOfTheWorkDirectoryAndNotAChange() {
        writeInput(body = "the task body")
        var taskText: String? = null
        entrypoint(
            cli = CliProcess { _, dir ->
                taskText = Files.readString(dir.resolve(".kivra/task.json"))
                Files.writeString(dir.resolve("docs/a.md"), "new\n")
                0
            },
        ).run(listOf("claude"))
        assertTrue("the task body" in taskText!!)
        val readout = assertIs<PodReadout.Collected>(result())
        assertEquals(listOf("docs/a.md"), readout.changes!!.map { it.path })
    }

    @Test
    fun aNonZeroExitOfTheCliIsRecordedAsARefusal() {
        writeInput()
        entrypoint(cli = CliProcess { _, dir -> Files.writeString(dir.resolve("docs/a.md"), "new\n"); 3 }).run(listOf("claude"))
        val readout = assertIs<PodReadout.Collected>(result())
        assertEquals(3, readout.exitStatus)
        assertTrue(DiffRejectReason.CLI_EXIT_NONZERO in readout.reasons)
    }

    @Test
    fun noChangeIsAnEmptyPatchRefusal() {
        writeInput()
        entrypoint().run(listOf("claude"))
        assertTrue(DiffRejectReason.EMPTY_PATCH in assertIs<PodReadout.Collected>(result()).reasons)
    }

    @Test
    fun theSameRulesAsTheHostSideApply() {
        writeInput()
        entrypoint(
            cli = CliProcess { _, dir ->
                Files.createSymbolicLink(dir.resolve("docs/link"), Path.of("/etc/passwd"))
                0
            },
        ).run(listOf("claude"))
        assertTrue(DiffRejectReason.SYMLINK in assertIs<PodReadout.Collected>(result()).reasons)
    }

    @Test
    fun theEntrypointsPlaceholderSummaryIsNotTheFinalSummary() {
        writeInput()
        entrypoint(cli = CliProcess { _, dir -> Files.writeString(dir.resolve("docs/a.md"), "new\n"); 0 }).run(listOf("claude"))
        val readout = assertIs<PodReadout.Collected>(result())
        // The agent's own summary check still applies, so an empty summary is refused even though the Pod used a placeholder.
        assertTrue(DiffRejectReason.SUMMARY_EMPTY in assertIs<CollectOutcome.Rejected>(readout.finish("", null, DiffLimits(), PathRules.BUILT_IN_ONLY)).reasons)
    }

    @Test
    fun aMissingInputIsAnErrorResultAfterTheWait() {
        var started = false
        entrypoint(cli = CliProcess { _, _ -> started = true; 0 }, wait = Duration.ofMillis(50)).run(listOf("claude"))
        assertEquals("INPUT_TIMEOUT", assertIs<PodReadout.Error>(result()).reason)
        assertFalse(started)
    }

    @Test
    fun anInvalidInputIsAnErrorResultAndTheCliDoesNotRun() {
        Files.createDirectories(kivraDir)
        Files.writeString(kivraDir.resolve("input.json"), "{\"schemaVersion\":1}")
        var started = false
        entrypoint(cli = CliProcess { _, _ -> started = true; 0 }).run(listOf("claude"))
        assertEquals("INPUT_MALFORMED", assertIs<PodReadout.Error>(result()).reason)
        assertFalse(started)
    }

    @Test
    fun aFailedFetchIsAnErrorResultAndTheCliDoesNotRun() {
        writeInput()
        var started = false
        entrypoint(
            fetcher = BaseFetcher { throw WorkDirException(WorkDirReason.GIT_FAILED) },
            cli = CliProcess { _, _ -> started = true; 0 },
        ).run(listOf("claude"))
        assertEquals("WORKDIR_FAILED", assertIs<PodReadout.Error>(result()).reason)
        assertFalse(started)
    }

    @Test
    fun anUnexpectedFailureIsAnErrorResultWithAFixedCode() {
        writeInput()
        entrypoint(cli = CliProcess { _, _ -> throw IllegalStateException("secret detail") }).run(listOf("claude"))
        val bytes = Files.readString(kivraDir.resolve("result.json"))
        assertEquals("ENTRYPOINT_FAILED", assertIs<PodReadout.Error>(result()).reason)
        assertFalse("secret detail" in bytes)
    }

    @Test
    fun theResultFileIsReadable() {
        writeInput()
        entrypoint().run(listOf("claude"))
        assertNotNull(result())
    }
}
