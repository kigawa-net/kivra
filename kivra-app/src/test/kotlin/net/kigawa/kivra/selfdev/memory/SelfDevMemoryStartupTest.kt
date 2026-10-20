package net.kigawa.kivra.selfdev.memory

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.db.MemoryRetentionResult
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotApproval
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.captureKivraLogs
import net.kigawa.kivra.selfdev.e2eConfig
import net.kigawa.kivra.selfdev.e2eSettings

/**
 * ADR 0016 P1 startup and the retention step: the switch is read only while the autopilot is on, a bad value fails the startup without
 * echoing it, the retention step removes a fact after its period and not before, and a failing step is logged and contained.
 */
class SelfDevMemoryStartupTest {

    /** Fails if the memory switch is read. */
    private class Trap(private val base: Map<String, String>) : Map<String, String> by base {
        override fun get(key: String): String? {
            if (key == SelfDevMemorySettings.ENV_ENABLED) throw AssertionError("the memory switch was read while the autopilot is off")
            return base[key]
        }
    }

    @Test
    fun `with the autopilot off, the memory switch is not read at all, even when it holds a bad value`() {
        val settings = SelfDevMemorySettings.fromStartupEnv(Trap(mapOf(SelfDevMemorySettings.ENV_ENABLED to "garbage")), AutopilotSettings.disabled())
        assertFalse(settings.enabled)
    }

    @Test
    fun `with the autopilot on, a bad value fails the startup, and the message names the variable but not the value`() {
        val autopilot = e2eSettings(e2eConfig(AutopilotApproval.REQUIRED))
        assertTrue(autopilot.enabled)
        val e = assertFailsWith<IllegalStateException> {
            SelfDevMemorySettings.fromStartupEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "yes-please"), autopilot)
        }
        assertTrue(e.message!!.contains(SelfDevMemorySettings.ENV_ENABLED))
        assertFalse(e.message!!.contains("yes-please"))
    }

    @Test
    fun `with the autopilot on, the switch is on only for exactly true, and a missing value is off`() {
        val autopilot = e2eSettings(e2eConfig(AutopilotApproval.REQUIRED))
        assertTrue(SelfDevMemorySettings.fromStartupEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "true"), autopilot).enabled)
        assertFalse(SelfDevMemorySettings.fromStartupEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "false"), autopilot).enabled)
        assertFalse(SelfDevMemorySettings.fromStartupEnv(emptyMap(), autopilot).enabled)
    }

    @Test
    fun `the retention step repeats while it removes rows, and stops when a step removes nothing`() {
        var calls = 0
        val worker = SelfDevMemoryRetentionWorker({
            calls++
            if (calls == 1) MemoryRetentionResult(tombstoned = 2, tombstonesPurged = 0, factsPurged = 0, eventsPurged = 0)
            else MemoryRetentionResult(0, 0, 0, 0)
        }, SelfDevMemoryRetentionWorker.DEFAULT_INTERVAL)
        assertEquals(2, worker.tick())
        assertEquals(2, calls)
    }

    @Test
    fun `a failing retention step is logged with its exception class only, and the next tick still runs`() {
        var calls = 0
        val worker = SelfDevMemoryRetentionWorker({
            calls++
            error("LEAK-RETENTION-MESSAGE")
        }, SelfDevMemoryRetentionWorker.DEFAULT_INTERVAL)
        val (touched, logs) = captureKivraLogs { worker.tick() }
        assertEquals(0, touched)
        assertEquals(1, calls)
        assertTrue(logs.any { it.contains("IllegalStateException") })
        assertTrue(logs.none { it.contains("LEAK") })
        worker.tick()
        assertEquals(2, calls)
    }

    @Test
    fun `the retention job removes a fact after its 365 days, and keeps it before`() = TestDb().use { db ->
        val goalId = (SelfDevGoalRepository(db.database).submit("operator-1", "key-retention", "題名", null, "kigawa-net/kivra") as SubmitResult.Created).goal.goalId
        val written = SelfDevMemoryRepository(db.database, enabled = true, clock = clockAt("2026-01-01T00:00:00Z"))
        written.save(MemoryCandidate(kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = goalId, code = "PLAN_PROPOSED", countValue = 1))
        assertEquals(1, db.count("selfdev_memory_records"))

        // The job runs on the day before the cut: the fact is kept.
        SelfDevMemoryRetentionWorker(
            { SelfDevMemoryRepository(db.database, enabled = true, clock = clockAt("2026-12-31T00:00:00Z")).purgeRetention() },
            SelfDevMemoryRetentionWorker.DEFAULT_INTERVAL,
        ).tick()
        assertEquals(1, db.count("selfdev_memory_records"))

        // A day after the cut: the fact is removed.
        SelfDevMemoryRetentionWorker(
            { SelfDevMemoryRepository(db.database, enabled = true, clock = clockAt("2027-01-02T00:00:00Z")).purgeRetention() },
            SelfDevMemoryRetentionWorker.DEFAULT_INTERVAL,
        ).tick()
        assertEquals(0, db.count("selfdev_memory_records"))
    }

    @Test
    fun `the retention interval must be positive`() {
        assertFailsWith<IllegalArgumentException> { SelfDevMemoryRetentionWorker({ MemoryRetentionResult(0, 0, 0, 0) }, Duration.ZERO) }
    }

    private fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
}
