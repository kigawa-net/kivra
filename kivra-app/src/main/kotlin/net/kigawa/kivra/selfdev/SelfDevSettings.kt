package net.kigawa.kivra.selfdev

import java.io.File
import java.time.Duration
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RsaJwtSigner
import net.kigawa.kivra.modelgateway.FxConfig

/** A secret value. [toString] never shows it, so a settings dump cannot leak it. */
class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "Secret(***)"
}

/**
 * How the self-development worker authenticates to GitHub. Exactly one method is configured (fail-closed). [toString]
 * never shows a value.
 */
sealed class GitHubAuth {
    /** A personal access token. Kept for compatibility; a GitHub App is recommended. */
    class Pat(val token: Secret) : GitHubAuth() {
        override fun toString(): String = "GitHubAuth.Pat(***)"
    }

    /** A GitHub App installation: the App ID, the installation ID and the PEM private key. */
    class App(val appId: String, val installationId: Long, val privateKeyPem: Secret) : GitHubAuth() {
        override fun toString(): String = "GitHubAuth.App(***)"
    }
}

/**
 * Anthropic Workload Identity Federation settings (ADR 0008 part 7). The IDs are not secret, but they are held in [Secret] so a
 * settings dump cannot print them. [identityTokenFile] is a path to the projected ServiceAccount token; it is read at exchange time,
 * not at startup, because the pod may start before the file appears.
 */
class AnthropicWifSettings(
    val federationRuleId: Secret,
    val organizationId: Secret,
    val serviceAccountId: Secret,
    val workspaceId: Secret?,
    val identityTokenFile: Secret,
) {
    override fun toString(): String = "AnthropicWifSettings(***)"
}

/**
 * Self-development settings from the environment (ADR 0008 part 7, Issue #56). Fail-closed:
 *
 * - `KIVRA_SELFDEV_ENABLED` is off unless it is exactly `true`. While it is off, **no other self-development variable is read**:
 *   no secret, no registry file, no GitHub or model client. [enabled] is false and [config] is null.
 * - When it is `true`, the deployment must use `KIVRA_AUTH_MODE=oidc-jwt` and must not be `KIVRA_RUNTIME_ENV=development`.
 *   The required settings must all be present. Every failure message names the variable, never its value.
 * - GitHub authentication is either `KIVRA_SELFDEV_GITHUB_TOKEN` (a PAT) or the three `KIVRA_SELFDEV_GITHUB_APP_*` variables
 *   (a GitHub App). Both, or neither, is a startup failure.
 * - Anthropic authentication is either `KIVRA_SELFDEV_ANTHROPIC_API_KEY` (a static key) or the Workload Identity Federation
 *   variables (`KIVRA_SELFDEV_ANTHROPIC_FEDERATION_RULE_ID`, `_ORGANIZATION_ID`, `_SERVICE_ACCOUNT_ID`, `_IDENTITY_TOKEN_FILE`,
 *   and the optional `_WORKSPACE_ID`). Exactly one method: both, neither, or a partial federation setup is a startup failure.
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
    val githubAuth: GitHubAuth?,
    /** Present only when [enabled] and the static key is used. The name of the secret env var, passed to the credential binding. */
    val anthropicApiKeyEnvVar: String?,
    /** Present only when [enabled] and Workload Identity Federation is used. */
    val anthropicWif: AnthropicWifSettings?,
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
        const val ENV_GITHUB_APP_ID = "KIVRA_SELFDEV_GITHUB_APP_ID"
        const val ENV_GITHUB_APP_INSTALLATION_ID = "KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID"
        /** A path to the PEM file (preferred, e.g. a mounted Secret), or the PEM text itself. */
        const val ENV_GITHUB_APP_PRIVATE_KEY = "KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY"
        const val ENV_ANTHROPIC_API_KEY = "KIVRA_SELFDEV_ANTHROPIC_API_KEY"
        const val ENV_ANTHROPIC_FEDERATION_RULE_ID = "KIVRA_SELFDEV_ANTHROPIC_FEDERATION_RULE_ID"
        const val ENV_ANTHROPIC_ORGANIZATION_ID = "KIVRA_SELFDEV_ANTHROPIC_ORGANIZATION_ID"
        const val ENV_ANTHROPIC_SERVICE_ACCOUNT_ID = "KIVRA_SELFDEV_ANTHROPIC_SERVICE_ACCOUNT_ID"
        const val ENV_ANTHROPIC_IDENTITY_TOKEN_FILE = "KIVRA_SELFDEV_ANTHROPIC_IDENTITY_TOKEN_FILE"
        const val ENV_ANTHROPIC_WORKSPACE_ID = "KIVRA_SELFDEV_ANTHROPIC_WORKSPACE_ID"
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
            githubAuth = null,
            anthropicApiKeyEnvVar = null,
            anthropicWif = null,
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
            val auth = githubAuth(env)
            val wif = anthropicAuth(env)
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
                githubAuth = auth,
                anthropicApiKeyEnvVar = if (wif == null) ENV_ANTHROPIC_API_KEY else null,
                anthropicWif = wif,
                modelRegistryPath = registry,
                fx = fx,
            )
        }

        private fun githubAuth(env: Map<String, String>): GitHubAuth {
            val pat = env[ENV_GITHUB_TOKEN]?.trim()?.takeIf { it.isNotEmpty() }
            val appVars = listOf(ENV_GITHUB_APP_ID, ENV_GITHUB_APP_INSTALLATION_ID, ENV_GITHUB_APP_PRIVATE_KEY)
            val appSet = appVars.any { env[it]?.trim()?.isNotEmpty() == true }
            check(!(pat != null && appSet)) {
                "$ENV_GITHUB_TOKEN and ${appVars.joinToString(", ")} are mutually exclusive: configure one GitHub authentication method"
            }
            if (pat != null) return GitHubAuth.Pat(Secret(pat))
            check(appSet) {
                "$ENV_ENABLED=true requires either $ENV_GITHUB_TOKEN or all of ${appVars.joinToString(", ")}"
            }
            val appId = required(env, ENV_GITHUB_APP_ID)
            check(appId.all { it in '0'..'9' }) { "$ENV_GITHUB_APP_ID must be a positive integer" }
            val installationId = required(env, ENV_GITHUB_APP_INSTALLATION_ID).toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException("$ENV_GITHUB_APP_INSTALLATION_ID must be a positive integer")
            val pem = readPrivateKey(required(env, ENV_GITHUB_APP_PRIVATE_KEY))
            try {
                RsaJwtSigner(pem)
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("$ENV_GITHUB_APP_PRIVATE_KEY is not a usable RSA private key")
            }
            return GitHubAuth.App(appId, installationId, Secret(pem))
        }

        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * Returns the Workload Identity Federation settings, or null when the static key is used. Exactly one method must be
         * configured. Failure messages name variables only, never values.
         */
        private fun anthropicAuth(env: Map<String, String>): AnthropicWifSettings? {
            val key = env[ENV_ANTHROPIC_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            val wifVars = listOf(
                ENV_ANTHROPIC_FEDERATION_RULE_ID, ENV_ANTHROPIC_ORGANIZATION_ID, ENV_ANTHROPIC_SERVICE_ACCOUNT_ID,
                ENV_ANTHROPIC_IDENTITY_TOKEN_FILE, ENV_ANTHROPIC_WORKSPACE_ID,
            )
            val wifSet = wifVars.any { env[it]?.trim()?.isNotEmpty() == true }
            check(!(key != null && wifSet)) {
                "$ENV_ANTHROPIC_API_KEY and ${wifVars.joinToString(", ")} are mutually exclusive: configure one Anthropic authentication method"
            }
            if (key != null) return null
            check(wifSet) {
                "$ENV_ENABLED=true requires either $ENV_ANTHROPIC_API_KEY or all of ${wifVars.take(4).joinToString(", ")}"
            }
            val ruleId = required(env, ENV_ANTHROPIC_FEDERATION_RULE_ID)
            check(ruleId.startsWith("fdrl_")) { "$ENV_ANTHROPIC_FEDERATION_RULE_ID must be a federation rule id (fdrl_...)" }
            val orgId = required(env, ENV_ANTHROPIC_ORGANIZATION_ID)
            check(UUID_PATTERN.matches(orgId)) { "$ENV_ANTHROPIC_ORGANIZATION_ID must be a UUID" }
            val serviceAccountId = required(env, ENV_ANTHROPIC_SERVICE_ACCOUNT_ID)
            check(serviceAccountId.startsWith("svac_")) { "$ENV_ANTHROPIC_SERVICE_ACCOUNT_ID must be a service account id (svac_...)" }
            val identityFile = required(env, ENV_ANTHROPIC_IDENTITY_TOKEN_FILE)
            val workspaceId = env[ENV_ANTHROPIC_WORKSPACE_ID]?.trim()?.takeIf { it.isNotEmpty() }
            check(workspaceId == null || workspaceId.startsWith("wrkspc_")) { "$ENV_ANTHROPIC_WORKSPACE_ID must be a workspace id (wrkspc_...)" }
            return AnthropicWifSettings(
                federationRuleId = Secret(ruleId),
                organizationId = Secret(orgId),
                serviceAccountId = Secret(serviceAccountId),
                workspaceId = workspaceId?.let { Secret(it) },
                identityTokenFile = Secret(identityFile),
            )
        }

        /** A value that names an existing file is read as the file; anything else is taken as the PEM text. Failures never echo it. */
        private fun readPrivateKey(value: String): String {
            val file = File(value)
            if (!file.isFile) return value.replace("\\n", "\n")
            return try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                throw IllegalStateException("$ENV_GITHUB_APP_PRIVATE_KEY could not be read")
            }
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
