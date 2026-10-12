package net.kigawa.kivra.fusha.poller

import net.kigawa.kivra.core.TaskId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StatusMappingAndHashTest {
    @Test
    fun `status writes are disabled by default`() {
        val default = StatusMapping()
        assertFalse(default.writeEnabled)
        assertFalse(default.writesAllowed())
    }

    @Test
    fun `enabling writes still requires a key and a complete one-to-one mapping`() {
        val mapped = StatusMapping(
            writeEnabled = true,
            statusFieldKey = "status",
            kivraToFusha = mapOf("READY" to "Ready", "RUNNING" to "InProgress"),
        )
        assertTrue(mapped.writesAllowed())
        assertEquals("READY", mapped.fushaToKivra["Ready"])

        assertIs<IllegalArgumentException>(runCatching { StatusMapping(writeEnabled = true) }.exceptionOrNull())
        assertIs<IllegalArgumentException>(
            runCatching { StatusMapping(writeEnabled = true, statusFieldKey = "status") }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            runCatching { StatusMapping(kivraToFusha = mapOf("A" to "X", "B" to "X")) }.exceptionOrNull(),
        )
    }

    @Test
    fun `content hash ignores fetchedAt and field order but sees every business field`() {
        val a = task(1, fields = mapOf("status" to "\"a\"", "priority" to "\"p\""), fetchedAt = Instant.parse("2026-01-01T00:00:00Z"))
        val b = task(1, fields = mapOf("priority" to "\"p\"", "status" to "\"a\""), fetchedAt = Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals(TaskContentHash.of(a), TaskContentHash.of(b))

        val changedField = task(1, fields = mapOf("status" to "\"b\"", "priority" to "\"p\""))
        assertNotEquals(TaskContentHash.of(a), TaskContentHash.of(changedField))

        val changedRelated = a.copy(relatedTaskIds = listOf(TaskId("fusha:dev:2")))
        assertNotEquals(TaskContentHash.of(a), TaskContentHash.of(changedRelated))
    }

    @Test
    fun `content hash normalizes unicode and separates adjacent fields`() {
        val composed = task(1, title = "café")
        val decomposed = task(1, title = "café")
        assertEquals(TaskContentHash.of(composed), TaskContentHash.of(decomposed))

        // Length prefixes keep ("ab","c") and ("a","bc") apart.
        val x = task(1, title = "ab", fields = mapOf("c" to "d"))
        val y = task(1, title = "a", fields = mapOf("bc" to "d"))
        assertNotEquals(TaskContentHash.of(x), TaskContentHash.of(y))
    }
}
