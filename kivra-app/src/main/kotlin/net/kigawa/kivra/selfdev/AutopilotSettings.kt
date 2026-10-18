package net.kigawa.kivra.selfdev

/** How a task of an autopilot Plan is approved before it is submitted (ADR 0011 section 6.1, U-1). */
enum class AutopilotApproval {
    /** Every task waits for the operator. The default. */
    REQUIRED,
    /** Tasks that the stage rules allow are submitted without asking. Denylisted tasks still wait (see [AutopilotConfig.requiresApproval]). */
    AUTO,
}

/**
 * The settings of an enabled autopilot. Present only when [AutopilotSettings.enabled].
 *
 * [requiresApproval] is the rule the dispatcher uses for a task's `requires_approval` flag: a task that touches a denylisted path
 * (ADR 0011 section 4) always waits for the operator, even when [approval] is [AutopilotApproval.AUTO].
 */
data class AutopilotConfig(
    val approval: AutopilotApproval,
    val maxPendingPrs: Int,
    val maxConsecutiveFailures: Int,
    val maxRunsPerDay: Int,
    val budgetSharePercent: Int,
    /** How often the Planner worker looks for goals to plan (seconds). Never a secret. */
    val planIntervalSeconds: Long = AutopilotSettings.DEFAULT_PLAN_INTERVAL_SECONDS,
) {
    /** True when the task waits for the operator. A denylisted task waits under every [approval], including AUTO. */
    fun requiresApproval(denylisted: Boolean): Boolean = denylisted || approval == AutopilotApproval.REQUIRED
}

/**
 * The autopilot's environment settings (ADR 0011, Issue #233). Fail-closed, like [SelfDevSettings]:
 *
 * - `KIVRA_SELFDEV_AUTOPILOT_ENABLED` is off unless it is exactly `true`. While it is off, **no other autopilot variable is read**,
 *   and nothing is wired: [enabled] is false and [config] is null. A value other than `true` or `false` is a startup failure.
 * - When it is `true`, self-development must itself be enabled (`KIVRA_SELFDEV_ENABLED=true`) with `KIVRA_SELFDEV_RUNTIME=cli`
 *   (ADR 0010). Any other combination is a startup failure. The autopilot submits Runs through the same path as the operator,
 *   so it may not run on the `api` runtime, whose model cost is not on the budget ledger in this form.
 * - `KIVRA_SELFDEV_AUTOPILOT_APPROVAL` is `required` (default) or `auto`. A denylisted task is always approved by the operator,
 *   under `auto` too ([AutopilotConfig.requiresApproval]).
 * - Numbers are bounded; an out-of-range value is a startup failure. Every failure message names the variable, never its value.
 *   None of these values is a secret.
 */
class AutopilotSettings private constructor(
    val enabled: Boolean,
    /** Present only when [enabled]. */
    val config: AutopilotConfig?,
) {
    override fun toString(): String = "AutopilotSettings(enabled=$enabled)"

    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_AUTOPILOT_ENABLED"
        const val ENV_APPROVAL = "KIVRA_SELFDEV_AUTOPILOT_APPROVAL"
        const val ENV_MAX_PENDING_PRS = "KIVRA_SELFDEV_AUTOPILOT_MAX_PENDING_PRS"
        const val ENV_MAX_CONSECUTIVE_FAILURES = "KIVRA_SELFDEV_AUTOPILOT_MAX_CONSECUTIVE_FAILURES"
        const val ENV_MAX_RUNS_PER_DAY = "KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY"
        const val ENV_BUDGET_SHARE_PERCENT = "KIVRA_SELFDEV_AUTOPILOT_BUDGET_SHARE_PERCENT"
        const val ENV_PLAN_INTERVAL_SECONDS = "KIVRA_SELFDEV_AUTOPILOT_PLAN_INTERVAL_SECONDS"

        const val DEFAULT_APPROVAL = "required"
        const val DEFAULT_MAX_PENDING_PRS = 3
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3
        const val DEFAULT_MAX_RUNS_PER_DAY = 5
        const val DEFAULT_BUDGET_SHARE_PERCENT = 50
        const val DEFAULT_PLAN_INTERVAL_SECONDS = 30L

        const val MIN_MAX_PENDING_PRS = 1
        const val MAX_MAX_PENDING_PRS = 20
        const val MIN_MAX_CONSECUTIVE_FAILURES = 1
        const val MAX_MAX_CONSECUTIVE_FAILURES = 20
        const val MIN_MAX_RUNS_PER_DAY = 1
        const val MAX_MAX_RUNS_PER_DAY = 100
        const val MIN_BUDGET_SHARE_PERCENT = 1
        const val MAX_BUDGET_SHARE_PERCENT = 100
        const val MIN_PLAN_INTERVAL_SECONDS = 5L
        const val MAX_PLAN_INTERVAL_SECONDS = 3600L

        /** The settings when the autopilot is off. Reads nothing but [ENV_ENABLED]. */
        fun disabled(): AutopilotSettings = AutopilotSettings(enabled = false, config = null)

        /**
         * Reads the autopilot settings. [selfDev] is the already-parsed self-development settings; the autopilot needs them enabled
         * on the cli runtime, and reads no variable of its own when it is off.
         */
        fun fromEnv(env: Map<String, String>, selfDev: SelfDevSettings): AutopilotSettings {
            when (env[ENV_ENABLED]?.trim()) {
                null, "", "false" -> return disabled()
                "true" -> Unit
                else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
            }
            check(selfDev.enabled) { "$ENV_ENABLED=true requires ${SelfDevSettings.ENV_ENABLED}=true" }
            check(selfDev.config?.runtime == SelfDevRuntime.CLI) {
                "$ENV_ENABLED=true requires ${SelfDevSettings.ENV_RUNTIME}=cli"
            }
            val approval = when (val raw = env[ENV_APPROVAL]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_APPROVAL) {
                "required" -> AutopilotApproval.REQUIRED
                "auto" -> AutopilotApproval.AUTO
                else -> throw IllegalStateException("$ENV_APPROVAL must be required or auto")
            }
            return AutopilotSettings(
                enabled = true,
                config = AutopilotConfig(
                    approval = approval,
                    maxPendingPrs = bounded(env, ENV_MAX_PENDING_PRS, DEFAULT_MAX_PENDING_PRS, MIN_MAX_PENDING_PRS, MAX_MAX_PENDING_PRS),
                    maxConsecutiveFailures = bounded(
                        env, ENV_MAX_CONSECUTIVE_FAILURES, DEFAULT_MAX_CONSECUTIVE_FAILURES,
                        MIN_MAX_CONSECUTIVE_FAILURES, MAX_MAX_CONSECUTIVE_FAILURES,
                    ),
                    maxRunsPerDay = bounded(env, ENV_MAX_RUNS_PER_DAY, DEFAULT_MAX_RUNS_PER_DAY, MIN_MAX_RUNS_PER_DAY, MAX_MAX_RUNS_PER_DAY),
                    budgetSharePercent = bounded(
                        env, ENV_BUDGET_SHARE_PERCENT, DEFAULT_BUDGET_SHARE_PERCENT,
                        MIN_BUDGET_SHARE_PERCENT, MAX_BUDGET_SHARE_PERCENT,
                    ),
                    planIntervalSeconds = boundedLong(
                        env, ENV_PLAN_INTERVAL_SECONDS, DEFAULT_PLAN_INTERVAL_SECONDS,
                        MIN_PLAN_INTERVAL_SECONDS, MAX_PLAN_INTERVAL_SECONDS,
                    ),
                ),
            )
        }

        /** A long in [min, max]; unset or blank means [default]. The message names the variable only. */
        private fun boundedLong(env: Map<String, String>, name: String, default: Long, min: Long, max: Long): Long {
            val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val value = raw.toLongOrNull()
            check(value != null && value in min..max) { "$name must be an integer from $min to $max" }
            return value
        }

        /** An integer in [min, max]; unset or blank means [default]. The message names the variable only. */
        private fun bounded(env: Map<String, String>, name: String, default: Int, min: Int, max: Int): Int {
            val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val value = raw.toIntOrNull()
            check(value != null && value in min..max) { "$name must be an integer from $min to $max" }
            return value
        }
    }
}
