package net.kigawa.kivra.agent.workdir

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import net.kigawa.kivra.agent.docker.TaskFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiffCollectorTest {
    private val task = TaskInput(title = "Fix docs", body = "Body", truncated = false)

    /** A base repository, a work root, and a run directory prepared from the base commit. */
    private inner class Fixture(
        files: Map<String, String> = DEFAULT_FILES,
        limits: DiffLimits = DiffLimits(),
    ) {
        val root: Path = newWorkRoot()
        private val src = makeRepo(files = files, executables = setOf("docs/run.sh"))
        val prepared: PreparedWorkDir = archiveOf(src, Files.createTempDirectory("kivra-mirror")) { stream ->
            WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, stream, task)
        }
        val run: Path get() = prepared.runDir
        private val collector = DiffCollector(root, limits)

        fun collect(
            summary: String = "Fix docs",
            exit: Int = 0,
            session: String? = "sess-1",
            pathRules: PathRules = PathRules.BUILT_IN_ONLY,
        ): CollectOutcome = collector.collect(prepared.runDir, prepared.manifest, summary, exit, session, pathRules)

        fun write(rel: String, text: String) {
            val p = run.resolve(rel)
            Files.createDirectories(p.parent)
            Files.write(p, text.toByteArray(Charsets.UTF_8))
        }

        fun writeBytes(rel: String, bytes: ByteArray) {
            val p = run.resolve(rel)
            Files.createDirectories(p.parent)
            Files.write(p, bytes)
        }
    }

    private fun rejectedBy(outcome: CollectOutcome): Set<DiffRejectReason> {
        val rejected = assertIs<CollectOutcome.Rejected>(outcome)
        return rejected.reasons.toSet()
    }

    private fun assertRejects(reason: DiffRejectReason, setup: Fixture.() -> Unit) {
        val fixture = Fixture()
        fixture.setup()
        val reasons = rejectedBy(fixture.collect())
        assertTrue(reason in reasons, "expected $reason, got $reasons")
    }

    @Test
    fun theClaimPatternsAreCheckedOnTopOfTheBuiltInRules() {
        val fixture = Fixture()
        fixture.write("docs/a.md", "alpha changed\n")
        fixture.write("kivra-agent/src/x.kt", "fun x() = 2\n")
        // The built-in rules accept both files. The claim allows only kivra-*/src/**, so docs/a.md is refused.
        assertEquals(
            setOf(DiffRejectReason.PATH_NOT_ALLOWED),
            rejectedBy(fixture.collect(pathRules = PathRules.fromServer(listOf("kivra-*/src/**"), emptyList()))),
        )
        // The claim denies x.kt, so it is refused even though it is allowed.
        assertEquals(
            setOf(DiffRejectReason.PATH_DENIED),
            rejectedBy(fixture.collect(pathRules = PathRules.fromServer(listOf("kivra-*/src/**", "docs/**"), listOf("**/x.kt")))),
        )
        // No claim patterns: only the built-in rules, so both files are accepted.
        assertIs<CollectOutcome.Accepted>(fixture.collect(pathRules = PathRules.fromServer(emptyList(), emptyList())))
    }

    @Test
    fun aDeletionOutsideTheClaimAllowlistIsRefusedToo() {
        val fixture = Fixture()
        Files.delete(fixture.run.resolve("docs/gone.md"))
        fixture.write("kivra-agent/src/x.kt", "fun x() = 2\n")
        // docs/gone.md is a deletion (built-in allowed). The claim does not allow docs/, so it is refused.
        assertEquals(
            setOf(DiffRejectReason.PATH_NOT_ALLOWED),
            rejectedBy(fixture.collect(pathRules = PathRules.fromServer(listOf("kivra-*/src/**"), emptyList()))),
        )
    }

    @Test
    fun unchangedWorkDirIsAnEmptyPatch() {
        val fixture = Fixture()
        assertEquals(setOf(DiffRejectReason.EMPTY_PATCH), rejectedBy(fixture.collect()))
    }

    @Test
    fun modifiedAddedAndDeletedFilesAreReported() {
        val fixture = Fixture()
        fixture.write("docs/a.md", "alpha 2\n")
        Files.delete(fixture.run.resolve("docs/gone.md"))
        fixture.write("docs/new.md", "new\n")

        val outcome = assertIs<CollectOutcome.Accepted>(fixture.collect(session = "sess-1"))
        val result = outcome.result
        assertEquals(0, result.exitStatus)
        assertEquals("sess-1", result.sessionId)
        assertEquals(
            listOf(
                AgentChange("docs/a.md", AgentChangeKind.WRITE, "alpha 2\n"),
                AgentChange("docs/gone.md", AgentChangeKind.DELETE, null),
                AgentChange("docs/new.md", AgentChangeKind.WRITE, "new\n"),
            ),
            result.changes,
        )
        assertEquals("AgentResult(changes=3, summaryChars=8, exitStatus=0)", result.toString())
    }

    @Test
    fun kivraDirectoryIsExcludedAndNeverFollowed() {
        val fixture = Fixture()
        fixture.write(".kivra/junk.bin", "\u0000\u0001 token = \"abcdefghijklmnop\"")
        fixture.write(".kivra/sub/deep.md", "x\n")
        Files.write(fixture.run.resolve(TaskFile.RELATIVE_PATH), "{}".toByteArray())
        Files.createSymbolicLink(fixture.run.resolve(".kivra/link"), Path.of("/etc/passwd"))
        assertEquals(setOf(DiffRejectReason.EMPTY_PATCH), rejectedBy(fixture.collect()))
    }

    @Test
    fun touchedButUnchangedFileIsNotAChange() {
        val fixture = Fixture()
        Files.setLastModifiedTime(fixture.run.resolve("docs/a.md"), java.nio.file.attribute.FileTime.fromMillis(0))
        assertEquals(setOf(DiffRejectReason.EMPTY_PATCH), rejectedBy(fixture.collect()))
    }

    @Test
    fun deletingAllowedBaseFilesIsAccepted() {
        val fixture = Fixture()
        Files.delete(fixture.run.resolve("README.md"))
        Files.delete(fixture.run.resolve("kivra-agent/src/x.kt"))
        val outcome = assertIs<CollectOutcome.Accepted>(fixture.collect())
        assertEquals(
            listOf("README.md", "kivra-agent/src/x.kt"),
            outcome.result.changes.map { it.path },
        )
    }

    @Test
    fun symbolicLinkIsRefused() {
        assertRejects(DiffRejectReason.SYMLINK) {
            Files.createSymbolicLink(run.resolve("docs/link.md"), Path.of("/etc/passwd"))
        }
    }

    @Test
    fun symbolicLinkPointingAtBaseFileIsRefused() {
        assertRejects(DiffRejectReason.SYMLINK) {
            Files.delete(run.resolve("docs/a.md"))
            Files.createSymbolicLink(run.resolve("docs/a.md"), Path.of("b.md"))
        }
    }

    @Test
    fun hardLinkIsRefused() {
        assertRejects(DiffRejectReason.HARDLINK) {
            Files.createLink(run.resolve("docs/hl.md"), run.resolve("docs/a.md"))
        }
    }

    @Test
    fun newExecutableFileIsRefused() {
        assertRejects(DiffRejectReason.EXECUTABLE_BIT) {
            write("docs/x.md", "text\n")
            Files.setPosixFilePermissions(run.resolve("docs/x.md"), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun removedExecutableBitOfBaseFileIsRefused() {
        assertRejects(DiffRejectReason.EXECUTABLE_BIT) {
            Files.setPosixFilePermissions(run.resolve("docs/run.sh"), PosixFilePermissions.fromString("rw-rw-rw-"))
        }
    }

    @Test
    fun binaryFileIsRefused() {
        assertRejects(DiffRejectReason.BINARY_FILE) {
            writeBytes("docs/bin.md", byteArrayOf(65, 0, 66))
        }
    }

    @Test
    fun invalidUtf8IsRefused() {
        assertRejects(DiffRejectReason.INVALID_UTF8) {
            writeBytes("docs/bad.md", byteArrayOf(0xFF.toByte(), 0x41))
        }
    }

    @Test
    fun secretLikeContentIsRefusedAndNeverEchoed() {
        val secret = "abcdefghijklmnop"
        val fixture = Fixture()
        fixture.write("docs/s.md", "token = \"$secret\"\n")
        val outcome = fixture.collect()
        assertEquals(setOf(DiffRejectReason.SECRET_LIKE_CONTENT), rejectedBy(outcome))
        assertFalse(outcome.toString().contains(secret))
        assertFalse(rejectedBy(outcome).toString().contains(secret))
    }

    @Test
    fun fileOverTheSizeLimitIsRefused() {
        assertRejects(DiffRejectReason.FILE_TOO_LARGE) {
            writeBytes("docs/big.md", ByteArray(200 * 1024 + 1) { 'a'.code.toByte() })
        }
    }

    @Test
    fun longLineIsRefused() {
        assertRejects(DiffRejectReason.LINE_TOO_LONG) {
            write("docs/long.md", "a".repeat(10_001) + "\n")
        }
    }

    @Test
    fun totalSizeOverTheLimitIsRefused() {
        assertRejects(DiffRejectReason.TOTAL_TOO_LARGE) {
            for (i in 1..7) writeBytes("docs/t$i.md", ByteArray(150 * 1024) { if (it % 100 == 99) '\n'.code.toByte() else 'b'.code.toByte() })
        }
    }

    @Test
    fun tooManyChangesIsRefused() {
        val fixture = Fixture(limits = DiffLimits(maxChangedFiles = 2))
        fixture.write("docs/n1.md", "1\n")
        fixture.write("docs/n2.md", "2\n")
        fixture.write("docs/n3.md", "3\n")
        assertEquals(setOf(DiffRejectReason.TOO_MANY_CHANGES), rejectedBy(fixture.collect()))
    }

    @Test
    fun tooManyDeletesIsRefused() {
        val fixture = Fixture(limits = DiffLimits(maxDeletes = 0))
        Files.delete(fixture.run.resolve("docs/gone.md"))
        assertEquals(setOf(DiffRejectReason.TOO_MANY_DELETES), rejectedBy(fixture.collect()))
    }

    @Test
    fun walkLimitIsRefused() {
        val fixture = Fixture(limits = DiffLimits(maxWalkEntries = 3))
        for (i in 1..6) fixture.write("docs/w$i.md", "w\n")
        assertTrue(DiffRejectReason.TOO_MANY_ENTRIES in rejectedBy(fixture.collect()))
    }

    @Test
    fun deniedPathsAreRefused() {
        for (rel in listOf(".github/workflows/ci.yml", "docs/.gitignore", "docs/adr/0011.md", "gradle/wrapper/x", "kivra-policy/src/P.kt", "Dockerfile")) {
            assertRejects(DiffRejectReason.PATH_DENIED) { write(rel, "x\n") }
        }
    }

    @Test
    fun pathsOutsideTheAllowlistAreRefused() {
        for (rel in listOf("scripts/x.sh", "kivra-agent/x.kt", "LICENSE")) {
            assertRejects(DiffRejectReason.PATH_NOT_ALLOWED) { write(rel, "x\n") }
        }
    }

    @Test
    fun unsafePathShapesAreRefused() {
        assertRejects(DiffRejectReason.PATH_COLON) { write("docs/x:y.md", "x\n") }
        assertRejects(DiffRejectReason.PATH_BACKSLASH) { write("docs/a\\b.md", "x\n") }
        assertRejects(DiffRejectReason.PATH_CONTROL_CHARACTER) { write("docs/a\tb.md", "x\n") }
        assertRejects(DiffRejectReason.PATH_NOT_NFC) { write("docs/é.md", "x\n") }
        assertRejects(DiffRejectReason.PATH_TOO_LONG) { write("docs/${"a".repeat(200)}.md", "x\n") }
        assertRejects(DiffRejectReason.PATH_TILDE) { write("docs/~x.md", "x\n") }
    }

    @Test
    fun caseCollidingNewPathsAreRefused() {
        assertRejects(DiffRejectReason.CASE_COLLISION) {
            write("docs/Q.md", "1\n")
            write("docs/q.md", "2\n")
        }
    }

    @Test
    fun invalidSummaryAndResultFieldsAreRefused() {
        val fixture = Fixture()
        fixture.write("docs/a.md", "changed\n")
        assertTrue(DiffRejectReason.SUMMARY_EMPTY in rejectedBy(fixture.collect(summary = "  ")))
        assertTrue(DiffRejectReason.SUMMARY_TOO_LONG in rejectedBy(fixture.collect(summary = "s".repeat(4001))))
        assertTrue(DiffRejectReason.CLI_EXIT_NONZERO in rejectedBy(fixture.collect(exit = 1)))
        assertTrue(DiffRejectReason.RESULT_FIELD_INVALID in rejectedBy(fixture.collect(session = "../x")))
        val withSecret = rejectedBy(fixture.collect(summary = "ghp_" + "a".repeat(30)))
        assertTrue(DiffRejectReason.SECRET_LIKE_CONTENT in withSecret)
    }

    @Test
    fun brokenManifestIsRefused() {
        val fixture = Fixture()
        Files.writeString(fixture.prepared.manifest, "not a manifest\n")
        assertEquals(setOf(DiffRejectReason.MANIFEST_INVALID), rejectedBy(fixture.collect()))
    }

    @Test
    fun runDirectoryOutsideTheWorkRootIsRefused() {
        val fixture = Fixture()
        val outside = Files.createTempDirectory("kivra-outside")
        val outcome = DiffCollector(fixture.root).collect(outside, fixture.prepared.manifest, "s", 0, null)
        assertEquals(setOf(DiffRejectReason.RUN_DIR_INVALID), rejectedBy(outcome))
    }

    @Test
    fun refusalsDoNotCarryContentOrPaths() {
        val fixture = Fixture()
        fixture.write("docs/private-name.md", "password = \"hunter2hunter2\"\n")
        val outcome = fixture.collect()
        val text = outcome.toString()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("private-name"))
        assertContains(text, "Rejected")
    }

    private companion object {
        val DEFAULT_FILES = mapOf(
            "README.md" to "hello\n",
            "docs/a.md" to "alpha\n",
            "docs/b.md" to "beta\n",
            "docs/gone.md" to "gone\n",
            "docs/run.sh" to "#!/bin/sh\n",
            "kivra-agent/src/x.kt" to "fun x() = 1\n",
        )
    }
}
