package net.kigawa.kivra.selfdev.review

import net.kigawa.kivra.selfdev.boolFlag
import net.kigawa.kivra.selfdev.boundedInt

/**
 * Settings of the AI review of self-development pull requests (comment only). Off unless [ENV_ENABLED] is exactly true; while off,
 * no other variable is read. A bad value is a startup failure whose message names the variable and never shows the value.
 */
data class PrReviewSettings(
    val enabled: Boolean,
    val model: String?,
    /** Reviews (model calls) per clock hour, across all pull requests. */
    val maxPerHour: Int,
    /** Reviews per pull request, across its heads. */
    val maxPerPr: Int,
    /** A diff longer than this is not sent to the model; the head is recorded as SKIPPED. */
    val maxDiffBytes: Int,
) {
    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_PR_REVIEW_ENABLED"
        const val ENV_MODEL = "KIVRA_SELFDEV_PR_REVIEW_MODEL"
        const val ENV_MAX_PER_HOUR = "KIVRA_SELFDEV_PR_REVIEW_MAX_PER_HOUR"
        const val ENV_MAX_PER_PR = "KIVRA_SELFDEV_PR_REVIEW_MAX_PER_PR"
        const val ENV_MAX_DIFF_BYTES = "KIVRA_SELFDEV_PR_REVIEW_MAX_DIFF_BYTES"

        const val DEFAULT_MAX_PER_HOUR = 5
        const val DEFAULT_MAX_PER_PR = 2
        const val DEFAULT_MAX_DIFF_BYTES = 60_000

        private val MODEL_SHAPE = Regex("[A-Za-z0-9._:-]{1,100}")

        val DISABLED = PrReviewSettings(false, null, 0, 0, DEFAULT_MAX_DIFF_BYTES)

        fun fromEnv(env: Map<String, String>): PrReviewSettings {
            if (!boolFlag(env, ENV_ENABLED, default = false)) return DISABLED
            val model = env[ENV_MODEL]?.trim()?.takeIf { it.isNotEmpty() }
            check(model != null) { "$ENV_MODEL must be set when $ENV_ENABLED is true" }
            check(MODEL_SHAPE.matches(model)) { "$ENV_MODEL must be a model name of letters, digits and . _ : -" }
            return PrReviewSettings(
                enabled = true,
                model = model,
                maxPerHour = boundedInt(env, ENV_MAX_PER_HOUR, DEFAULT_MAX_PER_HOUR, 0..100),
                maxPerPr = boundedInt(env, ENV_MAX_PER_PR, DEFAULT_MAX_PER_PR, 0..10),
                maxDiffBytes = boundedInt(env, ENV_MAX_DIFF_BYTES, DEFAULT_MAX_DIFF_BYTES, 1_000..200_000),
            )
        }
    }
}
