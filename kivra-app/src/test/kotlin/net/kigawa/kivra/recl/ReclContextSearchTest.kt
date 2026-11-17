package net.kigawa.kivra.recl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.*
import java.time.Duration
import kotlin.test.*
import net.kigawa.kivra.recl.M5MemoryFixtures as F

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReclContextSearchTest {
    @Test
    fun `recall passes query and access context through memory service`() = runTest {
        val fake = FakeMemoryPort()
        fake.save(F.entry(), IdempotencyKey("seed"), F.ctx)
        val provider = object : MemoryProvider by fake {
            override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> {
                assertEquals("Gradle Build memory support", q.text)
                assertEquals(listOf(F.scope), q.scopes)
                assertEquals("trace", ctx.traceId)
                assertEquals("execution", ctx.executionId)
                return fake.search(q, ctx)
            }
        }
        val bundle = search(MemoryService(provider))
        assertEquals(1, bundle.sections.single().items.size)
        assertTrue(bundle.warnings.isEmpty())
        assertEquals(1, fake.calls["search"])
    }

    @Test
    fun `null service and null provider both warn`() = runTest {
        for (service in listOf(null, MemoryService(null))) {
            assertEquals(setOf(ContextWarning.MEMORY_UNAVAILABLE), search(service).warnings)
        }
    }

    @Test
    fun `search and health are both bounded by the service timeout`() = runTest {
        for (slowHealth in listOf(false, true)) {
            val fake = FakeMemoryPort()
            val provider = object : MemoryProvider by fake {
                override suspend fun health(): ProviderHealth = if (slowHealth) awaitCancellation() else ProviderHealth.UP
                override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> = awaitCancellation()
            }
            val start = testScheduler.currentTime
            val result = search(MemoryService(provider, readTimeout = Duration.ofMillis(50)))
            assertEquals(setOf(ContextWarning.MEMORY_UNAVAILABLE), result.warnings)
            assertEquals(50, testScheduler.currentTime - start)
        }
    }

    @Test
    fun `coroutine cancellation propagates`() = runTest {
        val provider = object : MemoryProvider by FakeMemoryPort() {
            override suspend fun health(): ProviderHealth = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { search(MemoryService(provider)) }
    }

    private suspend fun search(service: MemoryService?) =
        ReclContextSearch(service).searchForTask(F.task, F.scope, "trace", "execution")
}
