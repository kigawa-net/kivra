package net.kigawa.kivra.selfdev.autofix

import net.kigawa.kivra.selfdev.boolFlag

/**
 * Settings of the incident fix (issue #226, the second stage of self-healing). Off unless [ENV_ENABLED] is exactly true. While off, nothing
 * is built, no task is submitted, and no row is written. It needs the autopilot (its Planner and dispatcher run the task); the startup check
 * is in the wiring. There is no other switch: the limits are fixed in code (see [IncidentFixWatch]).
 */
data class IncidentFixSettings(val enabled: Boolean) {
    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_AUTO_FIX_ENABLED"

        val DISABLED = IncidentFixSettings(false)

        fun fromEnv(env: Map<String, String>): IncidentFixSettings =
            IncidentFixSettings(boolFlag(env, ENV_ENABLED, default = false))
    }
}
