package net.kigawa.kivra.selfdev.llm

import net.kigawa.kivra.selfdev.boolFlag
import net.kigawa.kivra.selfdev.boundedInt

enum class LlmStopApprovalMode { SHADOW, ACTIVE }

/**
 * Settings of the LLM stop approval (ADR 0021 design 5). Off unless [ENV_ENABLED] is exactly true; while off, no other variable is
 * read. A bad value is a startup failure whose message names the variable and never shows the value. Not called from anywhere
 * yet (wired in L4).
 */
data class LlmStopApprovalSettings(
    val enabled: Boolean,
    val mode: LlmStopApprovalMode,
    val model: String?,
    val limits: StopJudgeLimits,
    val pricing: JudgePricing,
) {
    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_ENABLED"
        const val ENV_MODE = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MODE"
        const val ENV_MODEL = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MODEL"
        const val ENV_MAX_PER_HOUR = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MAX_PER_HOUR"
        const val ENV_MAX_CALLS_PER_HOUR = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MAX_CALLS_PER_HOUR"
        const val ENV_MAX_INPUT_TOKENS = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MAX_INPUT_TOKENS"
        const val ENV_MAX_OUTPUT_TOKENS = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MAX_OUTPUT_TOKENS"
        const val ENV_MAX_JPY_PER_MONTH = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MAX_JPY_PER_MONTH"
        const val ENV_INPUT_YEN_PER_MTOK = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_INPUT_YEN_PER_MTOK"
        const val ENV_OUTPUT_YEN_PER_MTOK = "KIVRA_SELFDEV_LLM_STOP_APPROVAL_OUTPUT_YEN_PER_MTOK"

        private val MODEL_SHAPE = Regex("[A-Za-z0-9._:-]{1,100}")

        val DISABLED = LlmStopApprovalSettings(false, LlmStopApprovalMode.SHADOW, null, StopJudgeLimits(), JudgePricing(1_000_000, 5_000_000))

        fun fromEnv(env: Map<String, String>): LlmStopApprovalSettings {
            if (!boolFlag(env, ENV_ENABLED, default = false)) return DISABLED
            val mode = when (env[ENV_MODE]?.trim()?.takeIf { it.isNotEmpty() }) {
                null, "shadow" -> LlmStopApprovalMode.SHADOW
                "active" -> LlmStopApprovalMode.ACTIVE
                else -> throw IllegalStateException("$ENV_MODE must be shadow or active")
            }
            val model = env[ENV_MODEL]?.trim()?.takeIf { it.isNotEmpty() }
            check(model != null) { "$ENV_MODEL must be set when $ENV_ENABLED is true" }
            check(MODEL_SHAPE.matches(model)) { "$ENV_MODEL must be a model name of letters, digits and . _ : -" }
            val limits = StopJudgeLimits(
                maxApprovalsPerHour = boundedInt(env, ENV_MAX_PER_HOUR, default = 3, range = 0..1000),
                maxCallsPerHour = boundedInt(env, ENV_MAX_CALLS_PER_HOUR, default = 20, range = 0..10_000),
                maxInputTokens = boundedInt(env, ENV_MAX_INPUT_TOKENS, default = 4000, range = 100..100_000),
                maxOutputTokens = boundedInt(env, ENV_MAX_OUTPUT_TOKENS, default = 100, range = 1..1000),
                maxMilliYenPerMonth = boundedInt(env, ENV_MAX_JPY_PER_MONTH, default = 500, range = 0..1_000_000) * 1000L,
            )
            val pricing = JudgePricing(
                inputMilliYenPerMtok = boundedInt(env, ENV_INPUT_YEN_PER_MTOK, default = 1000, range = 1..1_000_000) * 1000L,
                outputMilliYenPerMtok = boundedInt(env, ENV_OUTPUT_YEN_PER_MTOK, default = 5000, range = 1..1_000_000) * 1000L,
            )
            return LlmStopApprovalSettings(true, mode, model, limits, pricing)
        }
    }
}
