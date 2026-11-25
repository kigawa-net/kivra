package net.kigawa.kivra.config

import java.time.Duration

/**
 * The automatic release of unpriced reservations (ADR 0003 追補 2026-11-15), read from the environment.
 *
 * - `KIVRA_BUDGET_UNPRICED_AUTO_RELEASE_ENABLED`: `true` turns it on. Unset, empty or `false` is off (the default in the code; the k8s manifest
 *   turns it on). Anything else fails the startup.
 * - `KIVRA_BUDGET_UNPRICED_AUTO_RELEASE_GRACE_SECONDS`: how long a cost stays unknown before it is released. Default 600, at least 60. A value
 *   that is not a whole number of seconds, or is below 60, fails the startup. The message never contains the value.
 */
data class UnpricedAutoReleaseSettings(val enabled: Boolean, val grace: Duration) {
    companion object {
        const val ENV_ENABLED = "KIVRA_BUDGET_UNPRICED_AUTO_RELEASE_ENABLED"
        const val ENV_GRACE_SECONDS = "KIVRA_BUDGET_UNPRICED_AUTO_RELEASE_GRACE_SECONDS"
        const val DEFAULT_GRACE_SECONDS = 600L
        const val MIN_GRACE_SECONDS = 60L

        fun fromEnv(env: Map<String, String>): UnpricedAutoReleaseSettings {
            val enabled = when (env[ENV_ENABLED]?.trim()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
            }
            val raw = env[ENV_GRACE_SECONDS]?.trim()?.takeIf { it.isNotEmpty() }
            val seconds = if (raw == null) DEFAULT_GRACE_SECONDS else {
                val value = raw.toLongOrNull() ?: throw IllegalStateException("$ENV_GRACE_SECONDS must be a whole number of seconds")
                check(value >= MIN_GRACE_SECONDS) { "$ENV_GRACE_SECONDS must be at least $MIN_GRACE_SECONDS" }
                value
            }
            return UnpricedAutoReleaseSettings(enabled, Duration.ofSeconds(seconds))
        }
    }
}
