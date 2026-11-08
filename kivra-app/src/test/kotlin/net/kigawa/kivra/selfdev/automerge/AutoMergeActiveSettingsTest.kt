package net.kigawa.kivra.selfdev.automerge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0014 active addendum: the switch is off unless exactly `true`, and `true` needs the shadow judgement and the PR AI review. */
class AutoMergeActiveSettingsTest {
    private val env = AutoMergeActiveSettings.ENV_ACTIVE

    @Test
    fun `off when unset, empty or false, and the other variables are not read`() {
        assertFalse(AutoMergeActiveSettings.fromEnv(emptyMap(), shadowOn = false, reviewOn = false).enabled)
        assertFalse(AutoMergeActiveSettings.fromEnv(mapOf(env to ""), shadowOn = false, reviewOn = false).enabled)
        assertFalse(AutoMergeActiveSettings.fromEnv(mapOf(env to "false"), shadowOn = true, reviewOn = true).enabled)
        assertEquals(AutoMergeActiveSettings.DISABLED, AutoMergeActiveSettings.fromEnv(emptyMap(), shadowOn = true, reviewOn = true))
    }

    @Test
    fun `any value other than true or false fails at startup, and the message names the variable only`() {
        val error = assertFailsWith<IllegalStateException> {
            AutoMergeActiveSettings.fromEnv(mapOf(env to "yes please"), shadowOn = true, reviewOn = true)
        }
        assertTrue(error.message!!.contains(env))
        assertFalse(error.message!!.contains("yes please"))
    }

    @Test
    fun `true without the shadow judgement fails at startup`() {
        assertFailsWith<IllegalStateException> {
            AutoMergeActiveSettings.fromEnv(mapOf(env to "true"), shadowOn = false, reviewOn = true)
        }
    }

    @Test
    fun `true without the PR AI review fails at startup`() {
        assertFailsWith<IllegalStateException> {
            AutoMergeActiveSettings.fromEnv(mapOf(env to "true"), shadowOn = true, reviewOn = false)
        }
    }

    @Test
    fun `true with the shadow judgement and the PR AI review is on`() {
        assertTrue(AutoMergeActiveSettings.fromEnv(mapOf(env to "true"), shadowOn = true, reviewOn = true).enabled)
        assertTrue(AutoMergeActiveSettings.fromEnv(mapOf(env to " true "), shadowOn = true, reviewOn = true).enabled)
    }
}
