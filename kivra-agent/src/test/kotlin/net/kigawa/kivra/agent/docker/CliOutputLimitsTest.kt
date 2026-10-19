package net.kigawa.kivra.agent.docker

import kotlin.test.Test
import kotlin.test.assertEquals

class CliOutputLimitsTest {
    @Test
    fun theTotalLimitIsEightMiBAndTheRunnerDefaultIsTheSameValue() {
        assertEquals(8L * 1024 * 1024, CliOutputLimits.MAX_TOTAL_BYTES)
        assertEquals(CliOutputLimits.MAX_TOTAL_BYTES, DockerRunner.DEFAULT_OUTPUT_LIMIT_BYTES.toLong())
    }

    @Test
    fun theLineLimitStaysOneMiB() {
        assertEquals(1024 * 1024, CliOutputLimits.MAX_LINE_BYTES)
    }
}
