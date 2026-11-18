package net.kigawa.kivra.config

import java.time.Duration

/**
 * The automatic release of UNKNOWN self-development runs (CODING_ORPHANED and CODING_NEEDS_HUMAN, ADR 0009 追補 2026-11-16 and 2026-11-18),
 * read from the environment. The names keep `ORPHAN` from the first version; one switch and one grace cover both reasons.
 *
 * - `KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_ENABLED`: `true` turns it on. Unset, empty or `false` is off (the default in the code; the k8s
 *   manifest turns it on). Anything else fails the startup.
 * - `KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_GRACE_SECONDS`: how long a run stays UNKNOWN (since its last update) before it is released.
 *   Default 600, at least 60. A value that is not a whole number of seconds, or is below 60, fails the startup. The message never
 *   contains the value.
 */
data class SelfDevOrphanAutoReleaseSettings(val enabled: Boolean, val grace: Duration) {
    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_ENABLED"
        const val ENV_GRACE_SECONDS = "KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_GRACE_SECONDS"
        const val DEFAULT_GRACE_SECONDS = 600L
        const val MIN_GRACE_SECONDS = 60L

        fun fromEnv(env: Map<String, String>): SelfDevOrphanAutoReleaseSettings {
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
            return SelfDevOrphanAutoReleaseSettings(enabled, Duration.ofSeconds(seconds))
        }
    }
}
