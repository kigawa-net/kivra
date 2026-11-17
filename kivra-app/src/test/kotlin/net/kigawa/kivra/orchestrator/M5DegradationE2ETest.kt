package net.kigawa.kivra.orchestrator

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.agent.AgentProfile
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.db.DbRunAdmission
import net.kigawa.kivra.db.LeaderLeaseRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.fusha.FakeTaskProvider
import net.kigawa.kivra.fusha.poller.TaskDiscovered
import net.kigawa.kivra.fusha.poller.TaskEventSink
import net.kigawa.kivra.fusha.poller.TaskPoller
import net.kigawa.kivra.fusha.poller.TaskPollerConfig
import net.kigawa.kivra.memory.*
import net.kigawa.kivra.recl.ReclContextSearch
import net.kigawa.kivra.recl.M5MemoryFixtures as F
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.*

/** Public task receipt -> bounded recall -> real DB admission. No REST server or agent required. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class M5DegradationE2ETest {
    private val policy = AdmissionPolicy(
        "status", setOf("ready"), setOf("cancelled"), "dependencies", "target", setOf("A"), "repository",
    )

    @Test
    fun `A null provider does not prevent admission and logs MEMORY_UNAVAILABLE`() = runTest {
        verifyAdmission(null, degraded = true)
    }

    @Test
    fun `B DOWN opens circuit and subsequent admission skips provider`() = runTest {
        var healthCalls = 0
        val fake = FakeMemoryPort().apply { healthStatus = ProviderHealth.DOWN }
        val provider = object : MemoryProvider by fake {
            override suspend fun health(): ProviderHealth { healthCalls++; return fake.health() }
        }
        val audits = mutableListOf<MemoryAuditEvent>()
        val service = MemoryService(provider, breakerThreshold = 1, audit = { audits += it })
        assertTrue(service.recall(MemoryQuery("Gradle", listOf(F.scope)), F.ctx).degraded)
        assertEquals(1, healthCalls)
        fake.healthStatus = ProviderHealth.UP
        verifyAdmission(service, degraded = true)
        assertEquals(1, healthCalls) // circuit_open: neither health nor search is called
        assertNull(fake.calls["search"])
        assertTrue(audits.any { it.detail["reason"] == "circuit_open" })
    }

    @Test
    fun `C timed out provider continues to admission after timeout`() = runTest {
        val provider = object : MemoryProvider by FakeMemoryPort() {
            override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> = awaitCancellation()
        }
        val start = testScheduler.currentTime
        verifyAdmission(MemoryService(provider, readTimeout = Duration.ofMillis(50)), degraded = true)
        assertEquals(50, testScheduler.currentTime - start)
    }

    @Test
    fun `D healthy provider logs bundle metadata and admits without exposing memory text`() = runTest {
        val fake = FakeMemoryPort()
        fake.save(F.entry(), IdempotencyKey("seed"), F.ctx)
        verifyAdmission(MemoryService(fake), degraded = false, items = 1)
        assertEquals(1, fake.calls["search"])
        assertEquals(1, fake.calls["save"]) // fixture only: the orchestrator never writes
    }

    @Test
    fun `stopped provider connection failure does not prevent admission`() = runTest {
        val fake = FakeMemoryPort().apply { failure = MemoryException(MemoryErrorKind.UNAVAILABLE, "stopped") }
        verifyAdmission(MemoryService(fake), degraded = true)
        assertEquals(1, fake.calls["search"])
    }

    private suspend fun verifyAdmission(service: MemoryService?, degraded: Boolean, items: Int = 0) {
        TestDb().use { db ->
            // Establish poller health separately; deliver the task through the public event sink below.
            val poller = TaskPoller(FakeTaskProvider(), TaskEventSink { },
                TaskPollerConfig(environment = "test", filter = TaskFilter(assigneeId = "bot")))
            poller.pollOnce()
            assertTrue(poller.admissionAllowed())
            val orchestrator = Orchestrator(
                poller = poller,
                admission = DbRunAdmission(db.database, db.runs, db.audit, policy),
                profile = AgentProfile("test", "1", templateId = "test"),
                leaderLeaseRepo = LeaderLeaseRepository(db.database), runRepo = db.runs,
                config = OrchestratorConfig(), policy = policy,
                outboxPublisher = null, agentManager = null, reviewerPipeline = null, plannerPipeline = null,
                reclContextSearch = ReclContextSearch(service), memoryScope = F.scope,
                reclSummaryWriter = null, orchestratorMetrics = null, killSwitch = null,
            )
            val loggers = listOf(ReclContextSearch::class.java, Orchestrator::class.java)
                .map { LoggerFactory.getLogger(it) as Logger }
            val levels = loggers.map { it.level }
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            loggers.forEach { it.level = Level.INFO; it.addAppender(appender) }
            try {
                orchestrator.publish(listOf(TaskDiscovered(F.task, "m5-task")))
                assertEquals(1L, db.runs.countActiveRuns()) // proves execution continued beyond recall
                val events = appender.list
                assertEquals(degraded, events.any {
                    it.level == Level.WARN && it.formattedMessage.contains("MEMORY_UNAVAILABLE")
                })
                val bundleLog = events.single { it.formattedMessage.contains("ContextBundle bundleId=") }.formattedMessage
                assertTrue(bundleLog.contains("items=$items"))
                assertEquals(degraded, bundleLog.contains("MEMORY_UNAVAILABLE"))
                assertFalse(events.any { it.formattedMessage.contains("PRIVATE_MEMORY_CONTENT") })
            } finally {
                loggers.forEachIndexed { index, logger -> logger.detachAppender(appender); logger.level = levels[index] }
                appender.stop()
            }
        }
    }
}
