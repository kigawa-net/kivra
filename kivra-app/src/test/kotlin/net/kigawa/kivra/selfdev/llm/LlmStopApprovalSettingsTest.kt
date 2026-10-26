package net.kigawa.kivra.selfdev.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmStopApprovalSettingsTest {
    private val on = mapOf(LlmStopApprovalSettings.ENV_ENABLED to "true", LlmStopApprovalSettings.ENV_MODEL to "model-x")

    @Test
    fun `unset means off and no other variable is read`() {
        val s = LlmStopApprovalSettings.fromEnv(emptyMap())
        assertFalse(s.enabled)
        assertNull(s.model)
        // Bad values of other variables are ignored while off.
        assertFalse(LlmStopApprovalSettings.fromEnv(mapOf(LlmStopApprovalSettings.ENV_MODE to "bogus", LlmStopApprovalSettings.ENV_MAX_PER_HOUR to "x")).enabled)
        assertFalse(LlmStopApprovalSettings.fromEnv(mapOf(LlmStopApprovalSettings.ENV_ENABLED to "false")).enabled)
    }

    @Test
    fun `when on, the defaults are the ADR proposal and the mode is shadow`() {
        val s = LlmStopApprovalSettings.fromEnv(on)
        assertTrue(s.enabled)
        assertEquals(LlmStopApprovalMode.SHADOW, s.mode)
        assertEquals("model-x", s.model)
        assertEquals(StopJudgeLimits(3, 20, 4000, 100, 500_000), s.limits)
    }

    @Test
    fun `values are read`() {
        val s = LlmStopApprovalSettings.fromEnv(
            on + mapOf(
                LlmStopApprovalSettings.ENV_MODE to "active",
                LlmStopApprovalSettings.ENV_MAX_PER_HOUR to "5",
                LlmStopApprovalSettings.ENV_MAX_CALLS_PER_HOUR to "7",
                LlmStopApprovalSettings.ENV_MAX_INPUT_TOKENS to "2000",
                LlmStopApprovalSettings.ENV_MAX_OUTPUT_TOKENS to "50",
                LlmStopApprovalSettings.ENV_MAX_JPY_PER_MONTH to "123",
            ),
        )
        assertEquals(LlmStopApprovalMode.ACTIVE, s.mode)
        assertEquals(StopJudgeLimits(5, 7, 2000, 50, 123_000), s.limits)
    }

    @Test
    fun `a bad value fails with the variable name and never the value`() {
        val cases = listOf(
            LlmStopApprovalSettings.ENV_ENABLED to "yes-SECRETVALUE",
            LlmStopApprovalSettings.ENV_MODE to "turbo-SECRETVALUE",
            LlmStopApprovalSettings.ENV_MAX_PER_HOUR to "abc-SECRETVALUE",
            LlmStopApprovalSettings.ENV_MAX_PER_HOUR to "1001",
            LlmStopApprovalSettings.ENV_MAX_PER_HOUR to "-1",
            LlmStopApprovalSettings.ENV_MAX_CALLS_PER_HOUR to "10001",
            LlmStopApprovalSettings.ENV_MAX_INPUT_TOKENS to "99",
            LlmStopApprovalSettings.ENV_MAX_OUTPUT_TOKENS to "0",
            LlmStopApprovalSettings.ENV_MAX_JPY_PER_MONTH to "1000001",
            LlmStopApprovalSettings.ENV_MODEL to "bad model SECRETVALUE!",
            LlmStopApprovalSettings.ENV_INPUT_YEN_PER_MTOK to "0",
        )
        for ((name, value) in cases) {
            val e = assertFailsWith<IllegalStateException>("$name=$value") { LlmStopApprovalSettings.fromEnv(on + (name to value)) }
            assertTrue(e.message!!.contains(name), "message must name $name")
            assertFalse(e.message!!.contains(value) && value.contains("SECRETVALUE"), "message must not show the value")
            assertFalse(e.message!!.contains("SECRETVALUE"))
        }
    }

    @Test
    fun `a missing model fails when on`() {
        val e = assertFailsWith<IllegalStateException> { LlmStopApprovalSettings.fromEnv(mapOf(LlmStopApprovalSettings.ENV_ENABLED to "true")) }
        assertTrue(e.message!!.contains(LlmStopApprovalSettings.ENV_MODEL))
        assertFailsWith<IllegalStateException> { LlmStopApprovalSettings.fromEnv(on + (LlmStopApprovalSettings.ENV_MODEL to "  ")) }
    }
}
