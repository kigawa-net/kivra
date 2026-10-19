package net.kigawa.kivra.selfdev.memory

/**
 * The self-development memory's switch (ADR 0016 section 10). Fail-closed, like [net.kigawa.kivra.selfdev.AutopilotSettings]:
 *
 * - `KIVRA_SELFDEV_MEMORY_ENABLED` is off unless it is exactly `true`. Empty, unset and `false` mean off. Any other value is a
 *   startup failure whose message names the variable, never the value.
 * - Off means no record is written or read (the repository checks [enabled]). In P0 nothing outside the tests calls the repository,
 *   so `true` changes no behaviour of the application yet.
 */
class SelfDevMemorySettings private constructor(val enabled: Boolean) {
    override fun toString(): String = "SelfDevMemorySettings(enabled=$enabled)"

    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_MEMORY_ENABLED"

        fun disabled(): SelfDevMemorySettings = SelfDevMemorySettings(enabled = false)

        fun fromEnv(env: Map<String, String>): SelfDevMemorySettings = when (env[ENV_ENABLED]?.trim()) {
            null, "", "false" -> disabled()
            "true" -> SelfDevMemorySettings(enabled = true)
            else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
        }
    }
}
