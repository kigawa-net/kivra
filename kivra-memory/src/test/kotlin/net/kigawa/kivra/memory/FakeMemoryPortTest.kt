package net.kigawa.kivra.memory

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeMemoryPortContractTest : MemoryProviderContract() {
    override fun newBackend() = object : Backend {
        private val fake = FakeMemoryPort()
        override fun view(ctx: AccessContext): MemoryProvider = fake
    }
}

class FakeMemoryPortTest {
    private val ctx = T.ctx()

    @Test
    fun `redact erases content`() = runTest {
        val fake = FakeMemoryPort()
        val id = fake.save(T.entry(), T.key("k"), ctx)
        fake.delete(id, DeleteMode.REDACT, ctx)
        val raw = fake.rawEntry(id)!!
        assertEquals("[redacted]", raw.summary)
        assertNull(raw.body)
        assertTrue(raw.sources.isEmpty())
    }

    @Test
    fun `failure injection throws on every operation but health`() = runTest {
        val fake = FakeMemoryPort()
        fake.failure = MemoryException(MemoryErrorKind.UNAVAILABLE, "down")
        assertFailsWith<MemoryException> { fake.search(MemoryQuery("x y", listOf(T.tenant)), ctx) }
        assertFailsWith<MemoryException> { fake.save(T.entry(), T.key("k"), ctx) }
        assertEquals(ProviderHealth.UP, fake.health())
    }
}
