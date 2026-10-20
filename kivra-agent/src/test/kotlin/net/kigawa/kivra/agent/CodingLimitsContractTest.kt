package net.kigawa.kivra.agent

import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.workdir.DiffLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract between kivra (kivra-app) and this agent. kivra-app does not depend on kivra-agent, so each side pins the same fixed values:
 * the server's defaults are in kivra-app `coding/CodingLimitsContractTest`. Changing one side alone makes one of the two tests fail.
 */
class CodingLimitsContractTest {
    @Test
    fun theDefaultMaxTurnsIsTheServerDefault() {
        // kivra-app CodingRuntimeConfig.maxTurns = 30 (the value a run uses, passed to the CLI as --max-turns)
        assertEquals(30, CliCommand.DEFAULT_MAX_TURNS)
    }

    @Test
    fun theClaimedMaxTurnsReachesTheCliArgv() {
        val argv = CliCommand.argv(30)
        assertEquals("30", argv[CliCommand.MAX_TURNS_INDEX])
        assertEquals("--max-turns", argv[CliCommand.MAX_TURNS_INDEX - 1])
    }

    @Test
    fun theDiffDefaultsArePatchPolicyDefaults() {
        // kivra-app PatchPolicyConfig defaults: maxFiles 20, maxDeletes 5, maxFileBytes 200 KiB, maxTotalBytes 1 MiB
        val d = DiffLimits()
        assertEquals(20, d.maxChangedFiles)
        assertEquals(5, d.maxDeletes)
        assertEquals(200L * 1024, d.maxWriteFileBytes)
        assertEquals(1024L * 1024, d.maxTotalWriteBytes)
    }

    @Test
    fun aClaimedFileLimitIsUsedWhenItIsSmallerThanTheDefault() {
        val limits = claim(maxChangedFiles = 5, maxDeletes = 2, maxFileBytes = 1000, maxTotalBytes = 5000).toDiffLimits()
        assertEquals(5, limits.maxChangedFiles)
        assertEquals(2, limits.maxDeletes)
        assertEquals(1000L, limits.maxWriteFileBytes)
        assertEquals(5000L, limits.maxTotalWriteBytes)
    }

    @Test
    fun aClaimedFileLimitAboveTheDefaultIsCappedByTheDefault() {
        val limits = claim(maxChangedFiles = 100, maxDeletes = 50, maxFileBytes = 10L * 1024 * 1024, maxTotalBytes = 100L * 1024 * 1024)
            .toDiffLimits()
        assertEquals(DiffLimits().maxChangedFiles, limits.maxChangedFiles)
        assertEquals(DiffLimits().maxDeletes, limits.maxDeletes)
        assertEquals(DiffLimits().maxWriteFileBytes, limits.maxWriteFileBytes)
        assertEquals(DiffLimits().maxTotalWriteBytes, limits.maxTotalWriteBytes)
    }

    private fun claim(maxChangedFiles: Int, maxDeletes: Int, maxFileBytes: Long, maxTotalBytes: Long) = AgentLimits(
        maxRuntimeSeconds = 1800,
        maxTurns = 30,
        maxOutputBytes = 8L * 1024 * 1024,
        maxChangedFiles = maxChangedFiles,
        maxFileBytes = maxFileBytes,
        maxTotalBytes = maxTotalBytes,
        maxDeletes = maxDeletes,
    )
}
