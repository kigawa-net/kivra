package net.kigawa.kivra.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW_EPOCH = 1_800_000_000L

class AttemptDebugKeepTest {
    private val now: Instant = Instant.ofEpochSecond(NOW_EPOCH)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val workRoot: Path = Files.createTempDirectory("kivra-debug-keep").toRealPath()
    private val debug: Path = workRoot.resolve("debug")
    private val keep = AttemptDebugKeep(workRoot, clock)

    private fun taskFile(text: String = """{"title":"TASKTEXT"}"""): Path {
        val dir = Files.createTempDirectory(workRoot, "task")
        return Files.writeString(dir.resolve("task.json"), text)
    }

    private fun perms(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun keepWritesThreeFilesWithTheRequiredModes() {
        keep.keep("run-1", "att-1", "code=CLI_NONZERO\nreasons=EMPTY_PATCH\n", "stream text", taskFile())

        val dir = debug.resolve("run-1-att-1")
        assertEquals("rwx------", perms(debug), "the debug root is 0700")
        assertEquals("rwx------", perms(dir), "the entry is 0700")
        assertEquals("stream text", Files.readString(dir.resolve("stream.jsonl")))
        assertEquals("""{"title":"TASKTEXT"}""", Files.readString(dir.resolve("task.json")))
        assertEquals("code=CLI_NONZERO\nreasons=EMPTY_PATCH\n", Files.readString(dir.resolve("outcome.txt")))
        for (name in listOf("stream.jsonl", "task.json", "outcome.txt")) {
            assertEquals("rw-------", perms(dir.resolve(name)), "$name is 0600")
        }
    }

    @Test
    fun partsThatNeverExistedAreLeftOut() {
        keep.keep("run-2", "att-2", "code=EXCEPTION\n", stdout = null, taskFile = null)

        val dir = debug.resolve("run-2-att-2")
        assertEquals(listOf("outcome.txt"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun theStreamIsCutToItsLimitFromTheStart() {
        val head = "{\"type\":\"system\"}\n"
        val big = head + "x".repeat(AttemptDebugKeep.STREAM_LIMIT_BYTES + 1_000_000)
        keep.keep("run-3", "att-3", "code=BAD_OUTPUT\n", big, null)

        val saved = Files.readAllBytes(debug.resolve("run-3-att-3").resolve("stream.jsonl"))
        assertEquals(AttemptDebugKeep.STREAM_LIMIT_BYTES, saved.size)
        assertTrue(String(saved, Charsets.UTF_8).startsWith(head))
    }

    @Test
    fun aTaskFileThatIsASymbolicLinkIsNotFollowed() {
        val target = Files.writeString(workRoot.resolve("outside.txt"), "SECRETLINK")
        val link = Files.createTempDirectory(workRoot, "link").resolve("task.json")
        Files.createSymbolicLink(link, target)

        keep.keep("run-4", "att-4", "code=BAD_OUTPUT\n", "s", link)

        assertFalse(Files.exists(debug.resolve("run-4-att-4").resolve("task.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun anInvalidIdIsRefusedAndNothingIsWritten() {
        keep.keep("../escape", "att-1", "code=CLI_NONZERO\n", "s", null)
        keep.keep("run-5", "a/b", "code=CLI_NONZERO\n", "s", null)

        assertFalse(Files.exists(debug))
        assertFalse(Files.exists(workRoot.resolve("escape-att-1")))
    }

    @Test
    fun pruneRemovesEntriesOlderThanADayAndKeepsTheNewestTwenty() {
        Files.createDirectories(debug)
        val old = Files.createDirectory(debug.resolve("old-entry"))
        Files.setLastModifiedTime(old, FileTime.from(now.minus(Duration.ofHours(25))))
        Files.writeString(old.resolve("outcome.txt"), "code=X\n")
        // 22 fresh entries plus "fresh-now" (23 fresh), one hour apart. The three oldest go, the newest 20 stay.
        for (i in 0 until 22) {
            val dir = Files.createDirectory(debug.resolve("fresh-%02d".format(i)))
            Files.setLastModifiedTime(dir, FileTime.from(now.minus(Duration.ofHours((22 - i).toLong()))))
        }
        val recent = Files.createDirectory(debug.resolve("fresh-now"))
        Files.setLastModifiedTime(recent, FileTime.from(now))

        keep.prune()

        val names = Files.list(debug).use { s -> s.map { it.fileName.toString() }.toList() }.toSet()
        assertFalse("old-entry" in names, "an entry older than 24 hours is removed")
        assertEquals(20, names.size)
        assertFalse("fresh-00" in names)
        assertFalse("fresh-01" in names)
        assertFalse("fresh-02" in names)
        assertTrue("fresh-03" in names)
        assertTrue("fresh-now" in names)
    }

    @Test
    fun pruneWithoutADebugRootDoesNothing() {
        keep.prune()
        assertFalse(Files.exists(debug))
    }
}
