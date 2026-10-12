package net.kigawa.kivra.memory

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour every [MemoryProvider] must satisfy; run against the Fake and against ReclAdapter + a mock recl. */
abstract class MemoryProviderContract {
    interface Backend {
        /** A provider acting with [ctx]; all views of one backend share the same data. */
        fun view(ctx: AccessContext): MemoryProvider
    }

    abstract fun newBackend(): Backend

    private val ctxA = T.ctx(T.tenant)
    private val ctxB = T.ctx(T.otherTenant)

    @Test
    fun `save then get round trips the entry with provenance`() = runTest {
        val p = newBackend().view(ctxA)
        val id = p.save(T.entry(), T.key("k1"), ctxA)
        val got = p.get(id, ctxA)!!
        assertEquals(id, got.id)
        assertEquals(1, got.version)
        assertEquals("Gradle requires JDK 21", got.title)
        assertEquals(WriteProvenance("task-1", "exec-1", "agent:dev"), got.writtenBy)
        assertEquals(T.entry().sources, got.sources)
        assertEquals(MemoryStatus.ACTIVE, got.status)
    }

    @Test
    fun `save is idempotent on the key and rejects key reuse with different content`() = runTest {
        val p = newBackend().view(ctxA)
        assertTrue(MemoryCapability.IDEMPOTENT_SAVE in p.capabilities)
        val a = p.save(T.entry(), T.key("same"), ctxA)
        assertEquals(a, p.save(T.entry(), T.key("same"), ctxA))
        val ex = assertFailsWith<MemoryException> { p.save(T.entry(summary = "different content"), T.key("same"), ctxA) }
        assertEquals(MemoryErrorKind.REJECTED, ex.kind)
    }

    @Test
    fun `search finds relevant entries and omits bodies`() = runTest {
        val p = newBackend().view(ctxA)
        p.save(T.entry(), T.key("k1"), ctxA)
        p.save(T.entry(title = "Cooking", summary = "pasta recipe"), T.key("k2"), ctxA)
        val hits = p.search(MemoryQuery("jdk wrapper", listOf(T.tenant)), ctxA)
        assertEquals(1, hits.size)
        assertEquals("Gradle requires JDK 21", hits[0].entry.title)
        assertNull(hits[0].entry.body)
    }

    @Test
    fun `search never crosses tenants`() = runTest {
        val backend = newBackend()
        backend.view(ctxA).save(T.entry(), T.key("k1"), ctxA)
        val b = backend.view(ctxB)
        assertTrue(b.search(MemoryQuery("jdk", listOf(T.otherTenant)), ctxB).isEmpty())
        val ex = assertFailsWith<MemoryException> { b.search(MemoryQuery("jdk", listOf(T.tenant)), ctxB) }
        assertEquals(MemoryErrorKind.FORBIDDEN, ex.kind)
    }

    @Test
    fun `get of an entry in another tenant is null`() = runTest {
        val backend = newBackend()
        val id = backend.view(ctxA).save(T.entry(), T.key("k1"), ctxA)
        assertNull(backend.view(ctxB).get(id, ctxB))
    }

    @Test
    fun `search honours sensitivity cap, kinds and limit`() = runTest {
        val p = newBackend().view(ctxA)
        p.save(T.entry(summary = "jdk internal", sensitivity = Sensitivity.INTERNAL), T.key("k1"), ctxA)
        p.save(T.entry(title = "t2", summary = "jdk confidential", sensitivity = Sensitivity.CONFIDENTIAL), T.key("k2"), ctxA)
        p.save(T.entry(title = "t3", summary = "jdk procedure", kind = MemoryKind.PROCEDURAL), T.key("k3"), ctxA)
        val internalOnly = p.search(MemoryQuery("jdk", listOf(T.tenant), maxSensitivity = Sensitivity.INTERNAL), ctxA)
        assertTrue(internalOnly.none { it.entry.sensitivity > Sensitivity.INTERNAL })
        assertEquals(2, internalOnly.size)
        assertEquals(1, p.search(MemoryQuery("jdk", listOf(T.tenant), kinds = setOf(MemoryKind.PROCEDURAL)), ctxA).size)
        assertEquals(1, p.search(MemoryQuery("jdk", listOf(T.tenant), limit = 1), ctxA).size)
    }

    @Test
    fun `update uses optimistic locking`() = runTest {
        val p = newBackend().view(ctxA)
        val id = p.save(T.entry(), T.key("k1"), ctxA)
        val ex = assertFailsWith<MemoryException> { p.update(id, 7, MemoryPatch(summary = "x"), ctxA) }
        assertEquals(MemoryErrorKind.VERSION_CONFLICT, ex.kind)
        val updated = p.update(id, 1, MemoryPatch(summary = "Updated summary"), ctxA)
        assertEquals(2, updated.version)
        assertEquals("Updated summary", updated.summary)
        assertEquals(id, updated.id)
    }

    @Test
    fun `supersede replaces the old entry`() = runTest {
        val p = newBackend().view(ctxA)
        val old = p.save(T.entry(), T.key("k1"), ctxA)
        val new = p.supersede(old, 1, T.entry(summary = "Build with JDK 21 or 24 jdk"), T.key("k2"), ctxA)
        assertNotEquals(old, new)
        val hits = p.search(MemoryQuery("jdk", listOf(T.tenant)), ctxA)
        assertEquals(listOf(new), hits.map { it.entry.id })
        assertEquals(MemoryStatus.SUPERSEDED, p.get(old, ctxA)?.status)
    }

    @Test
    fun `soft delete hides the entry`() = runTest {
        val p = newBackend().view(ctxA)
        val id = p.save(T.entry(), T.key("k1"), ctxA)
        p.delete(id, DeleteMode.SOFT, ctxA)
        assertNull(p.get(id, ctxA))
        assertTrue(p.search(MemoryQuery("jdk", listOf(T.tenant)), ctxA).isEmpty())
    }

    @Test
    fun `pinned returns only pinned active entries in scope`() = runTest {
        val p = newBackend().view(ctxA)
        p.save(T.entry(pinned = true), T.key("k1"), ctxA)
        p.save(T.entry(title = "other", summary = "not pinned"), T.key("k2"), ctxA)
        val pinned = p.pinned(listOf(T.tenant), ctxA)
        assertEquals(1, pinned.size)
        assertTrue(pinned[0].pinned)
    }

    @Test
    fun `save outside the allowed scopes is forbidden`() = runTest {
        val p = newBackend().view(ctxA)
        val ex = assertFailsWith<MemoryException> { p.save(T.entry(scope = ScopeRef.project("t2", "x")), T.key("k1"), ctxA) }
        assertEquals(MemoryErrorKind.FORBIDDEN, ex.kind)
    }

    @Test
    fun `health is up`() = runTest {
        assertEquals(ProviderHealth.UP, newBackend().view(ctxA).health())
    }
}
