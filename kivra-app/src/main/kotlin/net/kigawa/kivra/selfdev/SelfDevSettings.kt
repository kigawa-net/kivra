package net.kigawa.kivra.selfdev

import java.time.Duration
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.FxConfig

/** A secret value. [toString] never shows it, so a settings dump cannot leak it. */
class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "Secret(***)"
}

/**
 * Self-development settings from the environment (ADR 0008 part 7, Issue #56). Fail-closed:
 *
 * - `KIVRA_SELFDEV_ENABLED` is off unless it is exactly `true`. While it is off, **no other self-development variable is read**:
 *   no secret, no registry file, no GitHub or model client. [enabled] is false and [config] is null.
 * - When it is `true`, the deployment must use `KIVRA_AUTH_MODE=oidc-jwt` and must not be `KIVRA_RUNTIME_ENV=development`.
 *   The required settings must all be present. Every failure message names the variable, never its value.
 *
 * [toString] never shows a secret or a path to a secret.
 */
class SelfDevSettings private constructor(
    val enabled: Boolean,
    /** Present only when [enabled]. */
    val config: SelfDevConfig?,
    val advanceInterval: Duration,
    val maxActiveRuns: Int,
    /** Present only when [enabled]. */
    val githubToken: Secret?,
    /** Present only when [enabled]. The name of the secret env var, passed to the credential binding. */
    val anthropicApiKeyEnvVar: String?,
    /** Present only when [enabled]. */
    val modelRegistryPath: String?,
    val fx: FxConfig?,
) {
    override fun toString(): String = "SelfDevSettings(enabled=$enabled)"

    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_ENABLED"
        const val ENV_TARGET_REPO = "KIVRA_SELFDEV_TARGET_REPO"
        const val ENV_BASE_BRANCH = "KIVRA_SELFDEV_BASE_BRANCH"
        const val ENV_GITHUB_TOKEN = "KIVRA_SELFDEV_GITHUB_TOKEN"
        const val ENV_ANTHROPIC_API_KEY = "KIVRA_SELFDEV_ANTHROPIC_API_KEY"
        const val ENV_MODEL_REGISTRY_PATH = "KIVRA_SELFDEV_MODEL_REGISTRY_PATH"
        const val ENV_CONTEXT_PREFIXES = "KIVRA_SELFDEV_CONTEXT_PREFIXES"
        const val ENV_MAX_CONTEXT_FILE_BYTES = "KIVRA_SELFDEV_MAX_CONTEXT_FILE_BYTES"
        const val ENV_PR_REVIEW_TIMEOUT_DAYS = "KIVRA_SELFDEV_PR_REVIEW_TIMEOUT_DAYS"
        const val ENV_ADVANCE_INTERVAL_SECONDS = "KIVRA_SELFDEV_ADVANCE_INTERVAL_SECONDS"
        const val ENV_MAX_ACTIVE_RUNS = "KIVRA_SELFDEV_MAX_ACTIVE_RUNS"

        const val DEFAULT_TARGET_REPO = "kigawa-net/kivra"
        const val DEFAULT_BASE_BRANCH = "main"
        const val DEFAULT_CONTEXT_PREFIXES = "README.md,docs/"
        const val DEFAULT_MAX_CONTEXT_FILE_BYTES = 102_400L
        const val DEFAULT_PR_REVIEW_TIMEOUT_DAYS = 7L
        const val DEFAULT_ADVANCE_INTERVAL_SECONDS = 30L
        const val MIN_ADVANCE_INTERVAL_SECONDS = 10L
        const val DEFAULT_MAX_ACTIVE_RUNS = 1

        /** The settings when self-development is off. Reads nothing but [ENV_ENABLED]. */
        fun disabled(): SelfDevSettings = SelfDevSettings(
            enabled = false,
            config = null,
            advanceInterval = Duration.ofSeconds(DEFAULT_ADVANCE_INTERVAL_SECONDS),
            maxActiveRuns = DEFAULT_MAX_ACTIVE_RUNS,
            githubToken = null,
            anthropicApiKeyEnvVar = null,
            modelRegistryPath = null,
            fx = null,
        )

        fun fromEnv(env: Map<String, String>): SelfDevSettings {
            when (val flag = env[ENV_ENABLED]?.trim()) {
                null, "", "false" -> return disabled()
                "true" -> Unit
                else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
            }
            check(env["KIVRA_AUTH_MODE"]?.trim() == "oidc-jwt") {
                "$ENV_ENABLED=true requires KIVRA_AUTH_MODE=oidc-jwt"
            }
            check(env["KIVRA_RUNTIME_ENV"]?.trim() != "development") {
                "$ENV_ENABLED=true is not allowed with KIVRA_RUNTIME_ENV=development"
            }

            val repo = parseRepo(env[ENV_TARGET_REPO]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TARGET_REPO)
            val baseBranch = env[ENV_BASE_BRANCH]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_BRANCH
            val token = required(env, ENV_GITHUB_TOKEN)
            required(env, ENV_ANTHROPIC_API_KEY)
            val registry = required(env, ENV_MODEL_REGISTRY_PATH)
            val fx = FxConfig.fromEnv(env)
            check(fx.usdJpyRate != null && fx.fxAsOf != null) {
                "$ENV_ENABLED=true requires ${FxConfig.ENV_USD_JPY} and ${FxConfig.ENV_AS_OF}"
            }
            val prefixes = (env[ENV_CONTEXT_PREFIXES]?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTEXT_PREFIXES)
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val contextBytes = number(env, ENV_MAX_CONTEXT_FILE_BYTES, DEFAULT_MAX_CONTEXT_FILE_BYTES)
            val timeoutDays = number(env, ENV_PR_REVIEW_TIMEOUT_DAYS, DEFAULT_PR_REVIEW_TIMEOUT_DAYS)
            val interval = number(env, ENV_ADVANCE_INTERVAL_SECONDS, DEFAULT_ADVANCE_INTERVAL_SECONDS)
            check(interval >= MIN_ADVANCE_INTERVAL_SECONDS) { "$ENV_ADVANCE_INTERVAL_SECONDS must be at least $MIN_ADVANCE_INTERVAL_SECONDS" }
            val maxActive = number(env, ENV_MAX_ACTIVE_RUNS, DEFAULT_MAX_ACTIVE_RUNS.toLong())
            check(maxActive >= 1) { "$ENV_MAX_ACTIVE_RUNS must be at least 1" }

            val config = SelfDevConfig(
                enabled = true,
                repo = repo,
                baseBranch = baseBranch,
                prReviewTimeout = Duration.ofDays(timeoutDays),
                contextPathPrefixes = prefixes,
                maxContextFileBytes = contextBytes,
            )
            return SelfDevSettings(
                enabled = true,
                config = config,
                advanceInterval = Duration.ofSeconds(interval),
                maxActiveRuns = maxActive.toInt(),
                githubToken = Secret(token),
                anthropicApiKeyEnvVar = ENV_ANTHROPIC_API_KEY,
                modelRegistryPath = registry,
                fx = fx,
            )
        }

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("$name is required when $ENV_ENABLED=true")

        private fun number(env: Map<String, String>, name: String, default: Long): Long {
            val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            return raw.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalStateException("$name must be a positive integer")
        }

        private fun parseRepo(raw: String): RepoRef {
            val parts = raw.split("/")
            check(parts.size == 2) { "$ENV_TARGET_REPO must be owner/repo" }
            return try {
                RepoRef(parts[0], parts[1])
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("$ENV_TARGET_REPO is not a valid repository name")
            }
        }
    }
}
