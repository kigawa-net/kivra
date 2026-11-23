package net.kigawa.kivra.chat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.kigawa.kivra.selfdev.boolFlag
import net.kigawa.kivra.selfdev.boundedInt

/**
 * The bounds of one chat summary (ADR 0023 追補 8). [maxMessages] and [maxInputChars] cap what is summarized (the most recent part only, when
 * the history is longer); [maxOutputChars] caps the summary; [timeout] is the deadline of the summarizer's model call.
 */
data class ChatSummaryLimits(
    val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(maxMessages >= 1) { "maxMessages must be positive" }
        require(maxInputChars >= 1) { "maxInputChars must be positive" }
        require(maxOutputChars >= 1) { "maxOutputChars must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_MESSAGES = 200
        const val DEFAULT_MAX_INPUT_CHARS = 60_000
        const val DEFAULT_MAX_OUTPUT_CHARS = 2_000
        val DEFAULT_TIMEOUT: Duration = 60.seconds
    }
}

/**
 * The chat summary's environment settings (ADR 0023 追補 8). Off by default, so a deployment opts in. Fail-closed like the other settings:
 * [ENV_ENABLED] must be `true` or `false`, and a bad bound fails the startup. The bounds are read only while the summary is on.
 */
data class ChatSummarySettings(val enabled: Boolean, val limits: ChatSummaryLimits) {
    companion object {
        const val ENV_ENABLED = "KIVRA_CHAT_SUMMARY_ENABLED"
        const val ENV_MAX_MESSAGES = "KIVRA_CHAT_SUMMARY_MAX_MESSAGES"
        const val ENV_MAX_INPUT_CHARS = "KIVRA_CHAT_SUMMARY_MAX_INPUT_CHARS"
        const val ENV_MAX_OUTPUT_CHARS = "KIVRA_CHAT_SUMMARY_MAX_OUTPUT_CHARS"
        const val ENV_TIMEOUT_SECONDS = "KIVRA_CHAT_SUMMARY_TIMEOUT_SECONDS"

        const val MAX_MESSAGES_LIMIT = 500
        const val MAX_INPUT_CHARS_LIMIT = 200_000
        const val MAX_OUTPUT_CHARS_LIMIT = 4_000
        const val TIMEOUT_SECONDS_MAX = 180

        fun disabled(): ChatSummarySettings = ChatSummarySettings(enabled = false, limits = ChatSummaryLimits())

        fun fromEnv(env: Map<String, String>): ChatSummarySettings {
            if (!boolFlag(env, ENV_ENABLED, default = false)) return disabled()
            val limits = ChatSummaryLimits(
                maxMessages = boundedInt(env, ENV_MAX_MESSAGES, ChatSummaryLimits.DEFAULT_MAX_MESSAGES, 2..MAX_MESSAGES_LIMIT),
                maxInputChars = boundedInt(env, ENV_MAX_INPUT_CHARS, ChatSummaryLimits.DEFAULT_MAX_INPUT_CHARS, 1_000..MAX_INPUT_CHARS_LIMIT),
                maxOutputChars = boundedInt(env, ENV_MAX_OUTPUT_CHARS, ChatSummaryLimits.DEFAULT_MAX_OUTPUT_CHARS, 200..MAX_OUTPUT_CHARS_LIMIT),
                timeout = boundedInt(
                    env, ENV_TIMEOUT_SECONDS, ChatSummaryLimits.DEFAULT_TIMEOUT.inWholeSeconds.toInt(), 5..TIMEOUT_SECONDS_MAX,
                ).seconds,
            )
            return ChatSummarySettings(enabled = true, limits = limits)
        }
    }
}
