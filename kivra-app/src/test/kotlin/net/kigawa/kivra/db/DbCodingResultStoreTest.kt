package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Contract of [DbCodingResultStore] (V15 `coding_results`). Runs on H2 by default and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 * Each test gets a freshly migrated database and one attempt.
 */
class DbCodingResultStoreTest {
    private val digest = "b".repeat(64)
    private val otherDigest = "c".repeat(64)

    private fun TestDb.attemptId(): String {
        val runId = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE).runId.value
        val enqueued = CodingAttemptRepository(database).enqueue(runId, "a".repeat(40), Duration.ofHours(24))
        return assertIs<EnqueueResult.Enqueued>(enqueued).attempt.attemptId
    }

    private fun TestDb.store() = DbCodingResultStore(database)

    private fun TestDb.rowCount(attemptId: String): Int = transaction(database) {
        execQuery("SELECT COUNT(*) FROM coding_results WHERE attempt_id = ?", attemptId) { it.getInt(1) }.single()
    }

    private val sample = PatchSet(
        files = listOf(
            Write("src/Main.kt", "fun main() {\n\tprintln(\"hello \\\\ 世界 🎉\")\n}\n"),
            Write("docs/note.md", "control \u0001 char and   separator"),
            Delete("src/Old.kt"),
        ),
        summary = "Summary with quotes \" and newlines\nsecond line",
        rationale = "Rationale text",
    )

    @Test
    fun `save then load returns the same PatchSet`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        db.store().save(attemptId, digest, sample)
        assertEquals(sample, db.store().load(attemptId, digest))
    }

    @Test
    fun `a PatchSet without rationale round-trips`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        val patch = PatchSet(files = listOf(Delete("a.txt")), summary = "only a delete")
        db.store().save(attemptId, digest, patch)
        assertEquals(patch, db.store().load(attemptId, digest))
    }

    @Test
    fun `saving the same digest twice is idempotent`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        db.store().save(attemptId, digest, sample)
        db.store().save(attemptId, digest, sample)
        assertEquals(1, db.rowCount(attemptId))
        assertEquals(sample, db.store().load(attemptId, digest))
    }

    @Test
    fun `saving a different digest for the same attempt is refused and the stored result is kept`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        db.store().save(attemptId, digest, sample)
        val other = PatchSet(files = listOf(Write("x.txt", "replacement")), summary = "replacement")
        val refused = assertFailsWith<IllegalStateException> { db.store().save(attemptId, otherDigest, other) }
        assertTrue(refused.message.orEmpty().let { !it.contains("replacement") && !it.contains(otherDigest) })
        assertEquals(1, db.rowCount(attemptId))
        assertEquals(sample, db.store().load(attemptId, digest))
        assertNull(db.store().load(attemptId, otherDigest))
    }

    @Test
    fun `load with a digest that does not match the stored one returns null`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        db.store().save(attemptId, digest, sample)
        assertNull(db.store().load(attemptId, otherDigest))
    }

    @Test
    fun `load for an attempt with no stored result returns null`() = TestDb().use { db ->
        assertNull(db.store().load(db.attemptId(), digest))
    }

    @Test
    fun `saving for an attempt that does not exist is refused by the foreign key`() = TestDb().use { db ->
        val missing = "Z".repeat(26)
        assertFailsWith<Exception> { db.store().save(missing, digest, sample) }
        assertEquals(0, db.count("coding_results"))
    }

    @Test
    fun `a change set over the parser limit is refused and nothing is stored`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        val big = PatchSet(files = listOf(Write("big.txt", "a".repeat(2 * 1024 * 1024 + 1))), summary = "big")
        assertFailsWith<IllegalArgumentException> { db.store().save(attemptId, digest, big) }
        assertEquals(0, db.rowCount(attemptId))
    }

    @Test
    fun `control characters that JSON escapes to six bytes count against the limit`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        // 400 KiB of raw control characters is 2.4 MiB as JSON (\u0001 is six bytes), over the 2 MiB parser limit.
        val escaped = PatchSet(files = listOf(Write("ctl.txt", "\u0001".repeat(400 * 1024))), summary = "ctl")
        assertFailsWith<IllegalArgumentException> { db.store().save(attemptId, digest, escaped) }
        assertEquals(0, db.rowCount(attemptId))
    }

    @Test
    fun `a malformed digest is refused before anything is written`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        assertFailsWith<IllegalArgumentException> { db.store().save(attemptId, "ABC", sample) }
        assertFailsWith<IllegalArgumentException> { db.store().save(attemptId, "B".repeat(64), sample) }
        assertEquals(0, db.rowCount(attemptId))
    }

    @Test
    fun `a stored body that cannot be read back fails loudly on load`() = TestDb().use { db ->
        val attemptId = db.attemptId()
        transaction(db.database) {
            execUpdate(
                "INSERT INTO coding_results (attempt_id, result_digest, patch_json, created_at) VALUES (?, ?, ?, ?)",
                attemptId, digest, "{\"files\":[],\"summary\":\"x\",\"unknown\":1}", utcNow(java.time.Clock.systemUTC()),
            )
        }
        assertFailsWith<IllegalStateException> { db.store().load(attemptId, digest) }
    }
}
