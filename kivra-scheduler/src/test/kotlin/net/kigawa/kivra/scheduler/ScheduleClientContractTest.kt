package net.kigawa.kivra.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleWriterLease

/**
 * Contract shared by every [ScheduleClient] implementation (the Fake and [AiSchedulerAdapter] against the fake
 * ai-scheduler). A new implementation is verified by subclassing this and providing [newClient].
 */
abstract class ScheduleClientContractTest {
    abstract fun newClient(): ScheduleClient

    private val writer = ScheduleWriterLease("contract", 1)
    private val spec = JobSpec(JobNaming.build("test", "tick"), "*/5 * * * *", null)

    @Test fun `upsert by deterministic name is idempotent`() = runTest {
        val c = newClient()
        val a = c.upsertJob(spec, IdempotencyKey("a"), writer)
        val b = c.upsertJob(spec, IdempotencyKey("b"), writer)
        assertEquals(a, b)
        assertEquals(spec.name, c.getJob(a).name)
    }

    @Test fun `triggerNow with the same key returns the same execution, a new key a new one`() = runTest {
        val c = newClient()
        val j = c.upsertJob(spec, IdempotencyKey("a"), writer)
        val e1 = c.triggerNow(j, IdempotencyKey("k1"))
        assertEquals(e1, c.triggerNow(j, IdempotencyKey("k1")))
        assertNotEquals(e1, c.triggerNow(j, IdempotencyKey("k2")))
    }

    @Test fun `pause and resume are visible through getJob`() = runTest {
        val c = newClient()
        val j = c.upsertJob(spec, IdempotencyKey("a"), writer)
        c.pauseJob(j)
        assertEquals(false, c.getJob(j).enabled)
        c.resumeJob(j)
        assertEquals(true, c.getJob(j).enabled)
    }

    @Test fun `execution lookup returns the execution that was started`() = runTest {
        val c = newClient()
        val j = c.upsertJob(spec, IdempotencyKey("a"), writer)
        val e = c.triggerNow(j, IdempotencyKey("k1"))
        assertEquals(e, c.getExecutionDetail(e).ref)
    }
}

class FakeScheduleClientContractTest : ScheduleClientContractTest() {
    override fun newClient(): ScheduleClient = FakeScheduleClient()
}

class AiSchedulerAdapterContractTest : ScheduleClientContractTest() {
    override fun newClient(): ScheduleClient {
        val cfg = AiSchedulerConfig(baseUrl = "http://ai-scheduler.internal:8080", allowInsecureHttp = true, devAllowInMemoryDedup = true)
        return AiSchedulerAdapter(
            aiSchedulerHttpClient(FakeAiSchedulerServer().engine(), cfg), cfg, backoff = {}, dedup = InMemoryTriggerDedupStore(),
        )
    }
}
