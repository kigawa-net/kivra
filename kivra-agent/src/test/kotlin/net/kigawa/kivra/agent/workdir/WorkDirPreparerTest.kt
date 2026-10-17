package net.kigawa.kivra.agent.workdir

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkDirPreparerTest {
    private val task = TaskInput(title = "Fix the parser", body = "Body text", truncated = false)

    private fun reasonOf(block: () -> Unit): WorkDirReason = assertFailsWith<WorkDirException>(block = block).reason

    private fun prepareBytes(bytes: ByteArray, limits: ExtractLimits = ExtractLimits()): WorkDirReason {
        val root = newWorkRoot()
        val preparer = WorkDirPreparer(root, limits)
        val reason = reasonOf { preparer.prepare(TEST_RUN, TEST_ATTEMPT, ByteArrayInputStream(bytes), task) }
        assertFalse(Files.exists(root.resolve("$TEST_RUN-$TEST_ATTEMPT")), "run directory is removed after a refusal")
        assertFalse(Files.exists(root.resolve("$TEST_RUN-$TEST_ATTEMPT.base")), "manifest is removed after a refusal")
        return reason
    }

    @Test
    fun normalArchiveIsExtractedWithTaskFileAndManifest() {
        val src = makeRepo(
            files = mapOf(
                "README.md" to "hello\n",
                "docs/a.md" to "alpha\n",
                "kivra-agent/src/x.kt" to "fun x() = 1\n",
                "bin/run.sh" to "#!/bin/sh\n",
            ),
            executables = setOf("bin/run.sh"),
        )
        val root = newWorkRoot()
        val prepared = archiveOf(src, Files.createTempDirectory("kivra-mirror")) { stream ->
            WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, stream, task)
        }
        val run = prepared.runDir
        assertEquals(root.resolve("$TEST_RUN-$TEST_ATTEMPT"), run)
        assertEquals("hello\n", run.resolve("README.md").text())
        assertEquals("fun x() = 1\n", run.resolve("kivra-agent/src/x.kt").text())

        val taskDoc = Json.parseToJsonElement(run.resolve(".kivra/task.json").text()).jsonObject
        assertEquals("UNTRUSTED", taskDoc["trust"]!!.jsonPrimitive.content)
        assertEquals("Fix the parser", taskDoc["title"]!!.jsonPrimitive.content)

        assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), Files.getPosixFilePermissions(run))
        assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), Files.getPosixFilePermissions(run.resolve(".kivra")))
        assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), Files.getPosixFilePermissions(run.resolve("bin/run.sh")))
        assertEquals(PosixFilePermissions.fromString("rw-rw-rw-"), Files.getPosixFilePermissions(run.resolve("README.md")))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(prepared.manifest))

        val manifest = BaseManifest.read(prepared.manifest)
        assertEquals(setOf("README.md", "docs/a.md", "kivra-agent/src/x.kt", "bin/run.sh"), manifest.keys)
        assertTrue(manifest.getValue("bin/run.sh").executable)
        assertFalse(manifest.getValue("README.md").executable)
        assertEquals(6L, manifest.getValue("README.md").size)
    }

    @Test
    fun taskDirectoryAndFileAreReachableByTheContainerUid() {
        // The container uid is not the host owner, so it reads through the "other" bits (runbook 6.8).
        val src = makeRepo(files = mapOf("README.md" to "hello\n"))
        val root = newWorkRoot()
        val prepared = archiveOf(src, Files.createTempDirectory("kivra-mirror")) { stream ->
            WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, stream, task)
        }
        val dir = prepared.runDir.resolve(".kivra")
        val file = dir.resolve("task.json")
        val dirOther = Files.getPosixFilePermissions(dir)
        val fileOther = Files.getPosixFilePermissions(file)
        assertTrue(dirOther.containsAll(setOf(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)))
        assertTrue(dirOther.contains(PosixFilePermission.OTHERS_WRITE))
        assertTrue(fileOther.contains(PosixFilePermission.OTHERS_READ))
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), fileOther)
    }

    @Test
    fun existingRunDirectoryIsRefused() {
        val root = newWorkRoot()
        Files.createDirectory(root.resolve("$TEST_RUN-$TEST_ATTEMPT"))
        val reason = reasonOf {
            WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, ByteArrayInputStream(tarOf(emptyList())), task)
        }
        assertEquals(WorkDirReason.WORKDIR_EXISTS, reason)
    }

    @Test
    fun workRootReadableByOthersIsRefused() {
        val root = newWorkRoot()
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"))
        assertEquals(WorkDirReason.WORKROOT_INVALID, reasonOf { WorkDirPreparer(root) })
    }

    @Test
    fun invalidRunIdIsRefused() {
        val root = newWorkRoot()
        val preparer = WorkDirPreparer(root)
        assertEquals(WorkDirReason.RUN_ID_INVALID, reasonOf { preparer.prepare("../x", TEST_ATTEMPT, ByteArrayInputStream(ByteArray(0)), task) })
    }

    @Test
    fun symbolicLinkInRepositoryIsRefusedAndNothingIsLeft() {
        val src = makeRepo(files = mapOf("README.md" to "x\n"), extra = { repo -> Files.createSymbolicLink(repo.resolve("lnk"), Path.of("README.md")) })
        val root = newWorkRoot()
        val reason = reasonOf {
            archiveOf(src, Files.createTempDirectory("kivra-mirror")) { stream -> WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, stream, task) }
        }
        assertEquals(WorkDirReason.ARCHIVE_ENTRY_TYPE, reason)
        assertFalse(Files.exists(root.resolve("$TEST_RUN-$TEST_ATTEMPT")))
    }

    @Test
    fun unknownCommitFailsInGit() {
        val src = makeRepo(files = mapOf("README.md" to "x\n"))
        val mirrorRoot = Files.createTempDirectory("kivra-mirror")
        val reason = reasonOf {
            archiveOf(src, mirrorRoot, sha = "0".repeat(40)) { stream -> WorkDirPreparer(newWorkRoot()).prepare(TEST_RUN, TEST_ATTEMPT, stream, task) }
        }
        assertEquals(WorkDirReason.GIT_FAILED, reason)
    }

    @Test
    fun symbolicLinkEntryIsRefused() {
        val bytes = tarOf(listOf(TarSpec("a.txt", '0', "x".toByteArray()), TarSpec("lnk", '2', linkName = "/etc/passwd")))
        assertEquals(WorkDirReason.ARCHIVE_ENTRY_TYPE, prepareBytes(bytes))
    }

    @Test
    fun hardLinkEntryIsRefused() {
        val bytes = tarOf(listOf(TarSpec("a.txt", '1', linkName = "b.txt")))
        assertEquals(WorkDirReason.ARCHIVE_ENTRY_TYPE, prepareBytes(bytes))
    }

    @Test
    fun deviceAndGnuLongNameEntriesAreRefused() {
        assertEquals(WorkDirReason.ARCHIVE_ENTRY_TYPE, prepareBytes(tarOf(listOf(TarSpec("dev", '3')))))
        assertEquals(WorkDirReason.ARCHIVE_ENTRY_TYPE, prepareBytes(tarOf(listOf(TarSpec("fifo", '6')))))
        assertEquals(WorkDirReason.ARCHIVE_ENTRY_TYPE, prepareBytes(tarOf(listOf(TarSpec("././@LongLink", 'L', "x".toByteArray())))))
    }

    @Test
    fun absoluteAndParentPathsAreRefused() {
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(tarOf(listOf(TarSpec("/etc/passwd", '0', "x".toByteArray())))))
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(tarOf(listOf(TarSpec("../escape.txt", '0', "x".toByteArray())))))
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(tarOf(listOf(TarSpec("a/../../b", '0', "x".toByteArray())))))
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(tarOf(listOf(TarSpec("a//b", '0', "x".toByteArray())))))
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(tarOf(listOf(TarSpec("a\\b", '0', "x".toByteArray())))))
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(tarOf(listOf(TarSpec("a\u0001b", '0', "x".toByteArray())))))
    }

    @Test
    fun reservedKivraDirectoryInArchiveIsRefused() {
        assertEquals(WorkDirReason.ARCHIVE_RESERVED_PATH, prepareBytes(tarOf(listOf(TarSpec(".kivra/task.json", '0', "{}".toByteArray())))))
        assertEquals(WorkDirReason.ARCHIVE_RESERVED_PATH, prepareBytes(tarOf(listOf(TarSpec(".kivra/", '5')))))
    }

    @Test
    fun parentThatIsAFileIsRefused() {
        val bytes = tarOf(listOf(TarSpec("a", '0', "x".toByteArray()), TarSpec("a/b", '0', "y".toByteArray())))
        assertEquals(WorkDirReason.ARCHIVE_PATH, prepareBytes(bytes))
    }

    @Test
    fun duplicateAndCaseCollidingPathsAreRefused() {
        assertEquals(
            WorkDirReason.ARCHIVE_DUPLICATE,
            prepareBytes(tarOf(listOf(TarSpec("a.txt", '0', "x".toByteArray()), TarSpec("a.txt", '0', "y".toByteArray())))),
        )
        assertEquals(
            WorkDirReason.ARCHIVE_DUPLICATE,
            prepareBytes(tarOf(listOf(TarSpec("A.txt", '0', "x".toByteArray()), TarSpec("a.txt", '0', "y".toByteArray())))),
        )
    }

    @Test
    fun badChecksumIsRefused() {
        val bytes = tarOf(listOf(TarSpec("a.txt", '0', "x".toByteArray())), badChecksumAt = 0)
        assertEquals(WorkDirReason.ARCHIVE_INVALID, prepareBytes(bytes))
    }

    @Test
    fun truncatedArchiveIsRefused() {
        val full = tarOf(listOf(TarSpec("a.txt", '0', ByteArray(2000) { 1 })))
        assertEquals(WorkDirReason.ARCHIVE_TRUNCATED, prepareBytes(full.copyOf(700)))
    }

    @Test
    fun tooManyEntriesIsRefused() {
        val bytes = tarOf((1..3).map { TarSpec("f$it.txt", '0', "x".toByteArray()) })
        assertEquals(WorkDirReason.ARCHIVE_TOO_MANY_ENTRIES, prepareBytes(bytes, ExtractLimits(maxEntries = 2)))
    }

    @Test
    fun oversizedFileIsRefused() {
        val bytes = tarOf(listOf(TarSpec("big.txt", '0', ByteArray(11) { 65 })))
        assertEquals(WorkDirReason.ARCHIVE_FILE_TOO_LARGE, prepareBytes(bytes, ExtractLimits(maxFileBytes = 10)))
    }

    @Test
    fun oversizedTotalIsRefused() {
        val bytes = tarOf(listOf(TarSpec("a.txt", '0', ByteArray(10) { 65 }), TarSpec("b.txt", '0', ByteArray(10) { 66 })))
        assertEquals(WorkDirReason.ARCHIVE_TOO_LARGE, prepareBytes(bytes, ExtractLimits(maxTotalBytes = 15)))
    }

    @Test
    fun longPathWithPaxRecordIsExtracted() {
        val dir = "d".repeat(120)
        val name = "$dir/file.txt"
        val root = newWorkRoot()
        val prepared = WorkDirPreparer(root).prepare(
            TEST_RUN,
            TEST_ATTEMPT,
            ByteArrayInputStream(tarOf(listOf(TarSpec(name, '0', "ok".toByteArray())))),
            task,
        )
        assertEquals("ok", prepared.runDir.resolve(name).text())
    }

    @Test
    fun invalidTaskFieldIsRefusedAndNothingIsLeft() {
        val root = newWorkRoot()
        val huge = TaskInput(title = "t".repeat(2001), body = "b", truncated = false)
        val reason = reasonOf {
            WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, ByteArrayInputStream(tarOf(listOf(TarSpec("a.txt", '0', "x".toByteArray())))), huge)
        }
        assertEquals(WorkDirReason.TASK_INVALID, reason)
        assertFalse(Files.exists(root.resolve("$TEST_RUN-$TEST_ATTEMPT")))
    }

    @Test
    fun runDirectoryCleanupRemovesEverything() {
        val root = newWorkRoot()
        val prepared = WorkDirPreparer(root).prepare(
            TEST_RUN,
            TEST_ATTEMPT,
            ByteArrayInputStream(tarOf(listOf(TarSpec("docs/a.md", '0', "x".toByteArray())))),
            task,
        )
        assertTrue(RunDirCleanup.delete(prepared.runDir, prepared.manifest))
        assertFalse(Files.exists(prepared.runDir))
        assertFalse(Files.exists(prepared.manifest))
    }

    @Test
    fun failingGitOnCloseRemovesTheWrittenTree() {
        val bytes = tarOf(listOf(TarSpec("a.txt", '0', "x".toByteArray())))
        val failing = object : ByteArrayInputStream(bytes) {
            override fun close() {
                throw WorkDirException(WorkDirReason.GIT_FAILED)
            }
        }
        val root = newWorkRoot()
        val reason = reasonOf { WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, failing, task) }
        assertEquals(WorkDirReason.GIT_FAILED, reason)
        assertFalse(Files.exists(root.resolve("$TEST_RUN-$TEST_ATTEMPT")))
    }

    @Test
    fun existingRunDirectoryStillClosesTheStream() {
        val root = newWorkRoot()
        Files.createDirectory(root.resolve("$TEST_RUN-$TEST_ATTEMPT"))
        var closed = false
        val stream = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() {
                closed = true
            }
        }
        assertEquals(WorkDirReason.WORKDIR_EXISTS, reasonOf { WorkDirPreparer(root).prepare(TEST_RUN, TEST_ATTEMPT, stream, task) })
        assertTrue(closed)
        assertTrue(Files.isDirectory(root.resolve("$TEST_RUN-$TEST_ATTEMPT")), "a directory that was not made here is left alone")
    }

    @Test
    fun emptyStreamIsRefusedAsTruncated() {
        assertEquals(WorkDirReason.ARCHIVE_TRUNCATED, prepareBytes(ByteArray(0)))
    }
}
