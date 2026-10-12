package net.kigawa.kivra.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun `scope grammar and containment`() {
        assertTrue(ScopeRef.project("t1", "p").isWithin(ScopeRef.tenant("t1")))
        assertFalse(ScopeRef.tenant("t1").isWithin(ScopeRef.project("t1", "p")))
        assertFalse(ScopeRef.tenant("t10").isWithin(ScopeRef.tenant("t1"))) // not a plain string prefix
        assertTrue(ScopeRef.exec("t1", "p", "e1").isWithin(ScopeRef.project("t1", "p")))
        for (bad in listOf("", "project:x", "tenant:", "tenant:a/exec:b", "tenant:a/user:b/exec:c", "tenant:a/project:b/exec:c/x:d", "tenant:a b", "tenant:a//user:b", "tenant:../x")) {
            assertFailsWith<IllegalArgumentException>(bad) { ScopeRef.parse(bad) }
        }
    }

    @Test
    fun `a write without provenance cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { WriteProvenance("", "exec", "me") }
        assertFailsWith<IllegalArgumentException> { WriteProvenance("task", " ", "me") }
        assertFailsWith<IllegalArgumentException> { T.entry().copy(sources = emptyList()) }
    }

    @Test
    fun `entry invariants`() {
        assertFailsWith<IllegalArgumentException> { T.entry().copy(provenance = MemoryProvenance.AGENT_INFERRED, confidence = 0.9) }
        T.entry().copy(provenance = MemoryProvenance.AGENT_INFERRED, confidence = 0.6)
        assertFailsWith<IllegalArgumentException> { T.entry(kind = MemoryKind.PREFERENCE) } // preference is never agent-derived
        assertFailsWith<IllegalArgumentException> { T.entry(sensitivity = Sensitivity.RESTRICTED) } // restricted keeps no body
        T.entry(sensitivity = Sensitivity.RESTRICTED, body = null)
        assertFailsWith<IllegalArgumentException> { T.entry().copy(importance = 1.5) }
        assertFailsWith<IllegalArgumentException> { T.entry().copy(validFrom = T.at, validUntil = T.at.minusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { T.entry().copy(scope = emptyList()) }
    }

    @Test
    fun `source refs refuse credentials`() {
        assertFailsWith<IllegalArgumentException> { SourceRef(SourceType.URL, "http://example.com/x", retrievedAt = T.at) }
        assertFailsWith<IllegalArgumentException> { SourceRef(SourceType.URL, "https://user:pw@example.com/x", retrievedAt = T.at) }
        assertFailsWith<IllegalArgumentException> { SourceRef(SourceType.URL, "https://example.com/x?token=ghp_abcdefghijklmnopqrstuvwxyz0123", retrievedAt = T.at) }
    }

    @Test
    fun `citation ids parse and round trip`() {
        val c = CitationId.of(MemoryId("mem-1"), 3)
        assertEquals("M:mem-1@v3", c.value)
        assertEquals(MemoryId("mem-1"), c.memoryId)
        assertEquals(3, c.version)
        assertFailsWith<IllegalArgumentException> { CitationId("mem-1@v3") }
    }

    @Test
    fun `query rejects restricted and oversized input`() {
        assertFailsWith<IllegalArgumentException> { MemoryQuery("x", listOf(T.tenant), maxSensitivity = Sensitivity.RESTRICTED) }
        assertFailsWith<IllegalArgumentException> { MemoryQuery("x", listOf(T.tenant), limit = 51) }
        assertFailsWith<IllegalArgumentException> { MemoryQuery(" ", listOf(T.tenant)) }
    }

    @Test
    fun `idempotency key is deterministic and content-free`() {
        val a = IdempotencyKeys.forSave(T.entry())
        assertEquals(a, IdempotencyKeys.forSave(T.entry()))
        assertTrue(a.value != IdempotencyKeys.forSave(T.entry(summary = "other")).value)
        assertTrue(a.value != IdempotencyKeys.forSave(T.entry(exec = "exec-2")).value)
        assertFalse(a.value.contains("JDK"))
    }
}
