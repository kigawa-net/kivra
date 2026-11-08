package net.kigawa.kivra.selfdev.incident

import net.kigawa.kivra.selfdev.boolFlag
import net.kigawa.kivra.selfdev.boundedInt

/**
 * Settings of the incident issue detector (issue #225). Off unless [ENV_ENABLED] is exactly true; while off, no other variable is read
 * and nothing is built. A bad value is a startup failure whose message names the variable and never shows the value.
 */
data class IncidentIssueSettings(
    val enabled: Boolean,
    /** Issues created per clock hour, across all causes. Zero stops the filing. */
    val maxPerHour: Int,
    /** How many self-development Runs with the same reason code must fail in the window before a RUN_FAILED incident is filed. */
    val minFailedRuns: Int,
    /** The window in hours. A cause is filed at most once per window (and its failures are counted within it). */
    val windowHours: Int,
) {
    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_AUTO_ISSUE_ENABLED"
        const val ENV_MAX_PER_HOUR = "KIVRA_SELFDEV_AUTO_ISSUE_MAX_PER_HOUR"
        const val ENV_MIN_FAILED_RUNS = "KIVRA_SELFDEV_AUTO_ISSUE_MIN_FAILED_RUNS"
        const val ENV_WINDOW_HOURS = "KIVRA_SELFDEV_AUTO_ISSUE_WINDOW_HOURS"

        const val DEFAULT_MAX_PER_HOUR = 2
        const val DEFAULT_MIN_FAILED_RUNS = 3
        const val DEFAULT_WINDOW_HOURS = 24

        val DISABLED = IncidentIssueSettings(false, 0, DEFAULT_MIN_FAILED_RUNS, DEFAULT_WINDOW_HOURS)

        fun fromEnv(env: Map<String, String>): IncidentIssueSettings {
            if (!boolFlag(env, ENV_ENABLED, default = false)) return DISABLED
            return IncidentIssueSettings(
                enabled = true,
                maxPerHour = boundedInt(env, ENV_MAX_PER_HOUR, DEFAULT_MAX_PER_HOUR, 0..20),
                minFailedRuns = boundedInt(env, ENV_MIN_FAILED_RUNS, DEFAULT_MIN_FAILED_RUNS, 1..100),
                windowHours = boundedInt(env, ENV_WINDOW_HOURS, DEFAULT_WINDOW_HOURS, 1..168),
            )
        }
    }
}
