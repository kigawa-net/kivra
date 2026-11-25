package net.kigawa.kivra.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The chat summary's switch is opt-in (off by default, fail-closed on a bad value), and its bounds are validated only while it is on. */
class ChatSummarySettingsTest {

    @Test
    fun `off by default, and a broken bound is ignored while off`() {
        assertFalse(ChatSummarySettings.fromEnv(emptyMap()).enabled)
        val settings = ChatSummarySettings.fromEnv(mapOf(ChatSummarySettings.ENV_MAX_MESSAGES to "not-a-number"))
        assertFalse(settings.enabled)
        assertEquals(ChatSummaryLimits(), settings.limits)
    }

    @Test
    fun `on with the defaults when only the switch is set`() {
        val settings = ChatSummarySettings.fromEnv(mapOf(ChatSummarySettings.ENV_ENABLED to "true"))
        assertTrue(settings.enabled)
        assertEquals(ChatSummaryLimits.DEFAULT_MAX_MESSAGES, settings.limits.maxMessages)
        assertEquals(ChatSummaryLimits.DEFAULT_MAX_INPUT_CHARS, settings.limits.maxInputChars)
        assertEquals(ChatSummaryLimits.DEFAULT_MAX_OUTPUT_CHARS, settings.limits.maxOutputChars)
        assertEquals(60.seconds, settings.limits.timeout)
    }

    @Test
    fun `the switch must be exactly true or false`() {
        assertFailsWith<IllegalStateException> { ChatSummarySettings.fromEnv(mapOf(ChatSummarySettings.ENV_ENABLED to "yes")) }
        assertFailsWith<IllegalStateException> { ChatSummarySettings.fromEnv(mapOf(ChatSummarySettings.ENV_ENABLED to "1")) }
        assertFalse(ChatSummarySettings.fromEnv(mapOf(ChatSummarySettings.ENV_ENABLED to "false")).enabled)
    }

    @Test
    fun `the bounds are read from the environment and checked against their ranges`() {
        val settings = ChatSummarySettings.fromEnv(
            mapOf(
                ChatSummarySettings.ENV_ENABLED to "true",
                ChatSummarySettings.ENV_MAX_MESSAGES to "50",
                ChatSummarySettings.ENV_MAX_INPUT_CHARS to "20000",
                ChatSummarySettings.ENV_MAX_OUTPUT_CHARS to "1500",
                ChatSummarySettings.ENV_TIMEOUT_SECONDS to "30",
            ),
        )
        assertEquals(ChatSummaryLimits(50, 20_000, 1_500, 30.seconds), settings.limits)
    }

    @Test
    fun `an out-of-range or non-numeric bound fails the startup`() {
        val on = ChatSummarySettings.ENV_ENABLED to "true"
        listOf(
            ChatSummarySettings.ENV_MAX_MESSAGES to "1",
            ChatSummarySettings.ENV_MAX_MESSAGES to "501",
            ChatSummarySettings.ENV_MAX_INPUT_CHARS to "999",
            ChatSummarySettings.ENV_MAX_OUTPUT_CHARS to "abc",
            ChatSummarySettings.ENV_TIMEOUT_SECONDS to "4",
            ChatSummarySettings.ENV_TIMEOUT_SECONDS to "181",
        ).forEach { bound ->
            assertFailsWith<IllegalStateException>("$bound") { ChatSummarySettings.fromEnv(mapOf(on, bound)) }
        }
    }
}
