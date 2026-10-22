package net.kigawa.kivra.selfdev

import net.kigawa.kivra.auth.AgentApiSettings
import net.kigawa.kivra.modelgateway.FxConfig
import org.slf4j.LoggerFactory

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
    /** Whether the Planner is shown the repository's file list (Issue #293). On by default; `KIVRA_SELFDEV_PLANNER_REPO_TREE=false` turns it off. */
    val plannerRepoTree: Boolean = true,
    /** The shadow judgement of the automatic merge (ADR 0014 S1). Judges and records only. Off unless `KIVRA_SELFDEV_AUTOMERGE_SHADOW_ENABLED=true`. */
    val autoMergeShadow: Boolean = false,
    /** The check names the shadow judgement requires on the PR head and on the base branch (ADR 0014 U-3). */
    val autoMergeRequiredChecks: Set<String> = AutopilotSettings.DEFAULT_AUTOMERGE_REQUIRED_CHECKS,
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
 * - The `cli` runtime's cost is on the budget ledger only with the agent API on, the FX rate and its as-of set, and a model registry
 *   configured (ADR 0011 section 5.3, #230). Enabling the autopilot without them is a startup failure that names the missing variables.
 *   The FX rate's age is not checked here: an expired rate stops the USD calls at run time, and [net.kigawa.kivra.fx.FxExpiryWatcher] logs it.
 * - When enabled, the limits are logged once (numbers only). A pending pull request limit above the concurrent Run limit is logged as
 *   a WARN: a PULL_REQUEST Run counts as an active Run, so the concurrent limit is the one that holds first.
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

        /** The Planner's repository file list (Issue #293). Read only when the autopilot is on. Unset means on; other values are refused. */
        const val ENV_PLANNER_REPO_TREE = "KIVRA_SELFDEV_PLANNER_REPO_TREE"

        /** The shadow judgement of the automatic merge (ADR 0014 S1). Read only when the autopilot is on. Unset or false means off. */
        const val ENV_AUTOMERGE_SHADOW = "KIVRA_SELFDEV_AUTOMERGE_SHADOW_ENABLED"

        /** The required check names of the shadow judgement, comma separated. Read only when the shadow switch is on. */
        const val ENV_AUTOMERGE_REQUIRED_CHECKS = "KIVRA_SELFDEV_AUTOMERGE_REQUIRED_CHECKS"
        const val DEFAULT_AUTOMERGE_REQUIRED_CHECKS_TEXT = "test,build,boot"
        val DEFAULT_AUTOMERGE_REQUIRED_CHECKS: Set<String> = setOf("test", "build", "boot")
        const val MAX_AUTOMERGE_REQUIRED_CHECKS = 10
        private val CHECK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9 ._()/-]{0,99}")

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

        private val logger = LoggerFactory.getLogger(AutopilotSettings::class.java)

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
            val missing = missingCostLedgerSettings(env)
            check(missing.isEmpty()) {
                "$ENV_ENABLED=true requires the CLI cost ledger (ADR 0011 section 5.3, #230); missing: ${missing.joinToString(", ")}"
            }
            val approval = when (val raw = env[ENV_APPROVAL]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_APPROVAL) {
                "required" -> AutopilotApproval.REQUIRED
                "auto" -> AutopilotApproval.AUTO
                else -> throw IllegalStateException("$ENV_APPROVAL must be required or auto")
            }
            val shadow = autoMergeShadow(env)
            val config = AutopilotConfig(
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
                plannerRepoTree = plannerRepoTree(env),
                autoMergeShadow = shadow,
                autoMergeRequiredChecks = if (shadow) requiredChecks(env) else DEFAULT_AUTOMERGE_REQUIRED_CHECKS,
            )
            logLimits(config, selfDev.maxActiveRuns)
            return AutopilotSettings(enabled = true, config = config)
        }

        /**
         * The CLI cost ledger's settings that are not set, by variable name. The agent API switch is named with the value it must have.
         * Presence only: a malformed FX value is refused by [FxConfig.fromEnv] when the ledger is built, and an expired rate is not a
         * startup refusal (the FX expiry watcher logs it).
         */
        internal fun missingCostLedgerSettings(env: Map<String, String>): List<String> = buildList {
            if (env[AgentApiSettings.ENV_ENABLED]?.trim() != "true") add("${AgentApiSettings.ENV_ENABLED}=true")
            if (env[FxConfig.ENV_USD_JPY].isNullOrBlank()) add(FxConfig.ENV_USD_JPY)
            if (env[FxConfig.ENV_AS_OF].isNullOrBlank()) add(FxConfig.ENV_AS_OF)
            if (env[SelfDevSettings.ENV_MODEL_REGISTRY_PATH].isNullOrBlank()) add(SelfDevSettings.ENV_MODEL_REGISTRY_PATH)
        }

        /** The effective limits, once, as numbers. A WARN when the pending PR limit can never be reached (see the class comment). */
        private fun logLimits(config: AutopilotConfig, maxActiveRuns: Int) {
            logger.info(
                "autopilot limits: maxActiveRuns={} maxPendingPrs={} maxConsecutiveFailures={} maxRunsPerDay={} budgetSharePercent={}",
                maxActiveRuns, config.maxPendingPrs, config.maxConsecutiveFailures, config.maxRunsPerDay, config.budgetSharePercent,
            )
            if (config.maxPendingPrs > maxActiveRuns) {
                logger.warn(
                    "autopilot limits: maxPendingPrs={} is above maxActiveRuns={}, so the pull request limit is capped by the " +
                        "concurrent Run limit (a PULL_REQUEST Run counts as active)",
                    config.maxPendingPrs, maxActiveRuns,
                )
            }
        }

        /** `true` (or unset) or `false`. Any other value is a startup failure; the message names the variable only. */
        private fun plannerRepoTree(env: Map<String, String>): Boolean = when (env[ENV_PLANNER_REPO_TREE]?.trim()) {
            null, "", "true" -> true
            "false" -> false
            else -> throw IllegalStateException("$ENV_PLANNER_REPO_TREE must be true or false")
        }

        /** `true`, `false`, or unset (off). Any other value is a startup failure; the message names the variable only. */
        private fun autoMergeShadow(env: Map<String, String>): Boolean = when (env[ENV_AUTOMERGE_SHADOW]?.trim()) {
            null, "", "false" -> false
            "true" -> true
            else -> throw IllegalStateException("$ENV_AUTOMERGE_SHADOW must be true or false")
        }

        /**
         * The comma-separated required check names: 1 to [MAX_AUTOMERGE_REQUIRED_CHECKS] distinct names, each made of letters, digits, spaces
         * and `._()/-`. Unset means the default. The message names the variable only.
         */
        private fun requiredChecks(env: Map<String, String>): Set<String> {
            val raw = env[ENV_AUTOMERGE_REQUIRED_CHECKS]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AUTOMERGE_REQUIRED_CHECKS_TEXT
            val names = raw.split(",").map { it.trim() }
            check(
                names.size in 1..MAX_AUTOMERGE_REQUIRED_CHECKS &&
                    names.all { CHECK_NAME.matches(it) } &&
                    names.toSet().size == names.size,
            ) { "$ENV_AUTOMERGE_REQUIRED_CHECKS must be 1 to $MAX_AUTOMERGE_REQUIRED_CHECKS distinct check names separated by commas" }
            return names.toSet()
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
