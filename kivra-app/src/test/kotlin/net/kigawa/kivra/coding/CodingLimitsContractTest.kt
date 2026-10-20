package net.kigawa.kivra.coding

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kigawa.kivra.core.patch.PatchPolicyConfig

/**
 * Contract between kivra-app and kivra-agent. kivra-app does not depend on kivra-agent, so both sides pin the same fixed values:
 * the agent's side is in kivra-agent `CodingLimitsContractTest`. Changing one side alone makes one of the two tests fail.
 */
class CodingLimitsContractTest {
    @Test
    fun theServerSendsTheMaxTurnsThatTheAgentPassesToTheCli() {
        // kivra-agent CliCommand.DEFAULT_MAX_TURNS = 30
        assertEquals(30, CodingRuntimeConfig().maxTurns)
        assertEquals(30, CodingLimits().maxTurns)
    }

    @Test
    fun theServerRuntimeAndOutputLimitsMatchTheAgent() {
        // kivra-agent CliOutputLimits.MAX_TOTAL_BYTES = 8 MiB; the claim's maxRuntime is 30 minutes
        assertEquals(Duration.ofMinutes(30), CodingRuntimeConfig().maxRuntime)
        assertEquals(8L * 1024 * 1024, CodingRuntimeConfig().maxOutputBytes)
        assertEquals(8L * 1024 * 1024, CodingLimits().maxOutputBytes)
    }

    @Test
    fun theServerFileLimitsAreThePatchPolicyDefaults() {
        // kivra-agent DiffLimits defaults: maxFiles 20, maxDeletes 5, maxFileBytes 200 KiB, maxTotalBytes 1 MiB
        val limits = CodingLimits()
        assertEquals(20, limits.maxChangedFiles)
        assertEquals(5, limits.maxDeletes)
        assertEquals(200L * 1024, limits.maxFileBytes)
        assertEquals(1024L * 1024, limits.maxTotalBytes)
        assertEquals(PatchPolicyConfig().maxFiles, limits.maxChangedFiles)
    }
}
