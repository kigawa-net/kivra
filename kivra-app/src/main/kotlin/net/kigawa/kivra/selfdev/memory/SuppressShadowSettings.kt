package net.kigawa.kivra.selfdev.memory

/**
 * The switch of the suppression shadow (ADR 0016 section 11, P3). It is read only while the memory is on: with `KIVRA_SELFDEV_MEMORY_ENABLED`
 * off, nothing beyond that switch is read.
 *
 * - Off unless the value is exactly `true` or `false`. Unset is off. Any other value (including an empty one) is a startup failure whose
 *   message names the variable, never the value (the same form as `boolFlag` in SelfDevOrderSettings).
 * - This switch only enables the shadow, which counts. There is no switch that makes the suppression act: that is a later change (P3 after
 *   the two-week shadow).
 */
class SuppressShadowSettings private constructor(val enabled: Boolean) {
    override fun toString(): String = "SuppressShadowSettings(enabled=$enabled)"

    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_MEMORY_SUPPRESS_SHADOW_ENABLED"

        fun disabled(): SuppressShadowSettings = SuppressShadowSettings(enabled = false)

        fun fromEnv(env: Map<String, String>): SuppressShadowSettings = when (env[ENV_ENABLED]?.trim()) {
            null -> disabled()
            "true" -> SuppressShadowSettings(enabled = true)
            "false" -> disabled()
            else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
        }

        /** The switch as Application.kt reads it at startup: read only when the memory's own switch is on. */
        fun fromStartupEnv(env: Map<String, String>, memory: SelfDevMemorySettings): SuppressShadowSettings =
            if (memory.enabled) fromEnv(env) else disabled()
    }
}
