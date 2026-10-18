package net.kigawa.kivra.auth

import java.math.BigDecimal
import java.time.Duration

/**
 * `KIVRA_AGENT_API_ENABLED`: whether the agent API routes are registered at all (ADR 0010 addendum, PR 4a). Off by default.
 *
 * - Unset, or `false`: off. No other agent variable is read, and no agent component is built. Every `the agent API paths` path is 404.
 * - `true`: on. Fail-closed at startup (an error names the variable, never its value):
 *   - `KIVRA_AUTH_MODE` must be `oidc-jwt` (the header-based modes are never allowed for agents).
 *   - `KIVRA_OIDC_ISSUER`, `KIVRA_OIDC_AUDIENCE`, `KIVRA_AGENT_AZP` and `KIVRA_AGENT_REQUIRED_ROLE` (`<client>:<role>`) must be set.
 *   - The agent role must differ from the operator's `KIVRA_OIDC_REQUIRED_ROLE`, so an operator token can never satisfy the agent check.
 * - Any other value is a startup error.
 *
 * Enabling this is also the condition for the CLI runtime: `KIVRA_SELFDEV_RUNTIME=cli` is allowed only when this is `true` (checked by
 * `SelfDevSettings`). Nothing else allows it.
 */
class AgentApiSettings private constructor(
    val auth: AgentAuthConfig?,
    /**
     * The per-attempt reserve in USD (`KIVRA_AGENT_ATTEMPT_RESERVE_USD`, ADR 0010 addendum 4). It is booked for an attempt that ends without a
     * usage report, and one reserve must fit in the monthly budget before a claim. Read only when the agent API is on.
     */
    val attemptReserveUsd: BigDecimal = DEFAULT_RESERVE_USD,
    /**
     * The period of the coding reconcile worker (`KIVRA_CODING_RECONCILE_INTERVAL_SECONDS`, 10 to 3600, default 60). Read only when the agent
     * API is on. See [net.kigawa.kivra.coding.CodingReconcileWorker].
     */
    val reconcileInterval: Duration = DEFAULT_RECONCILE_INTERVAL,
    /**
     * How long a stored change set is kept after it was written (`KIVRA_CODING_RESULT_RETENTION_DAYS`, 1 to 365, default 30). Only sets that
     * nothing can read any more are deleted. Read only when the agent API is on.
     */
    val resultRetention: Duration = DEFAULT_RESULT_RETENTION,
) {
    val enabled: Boolean get() = auth != null

    companion object {
        const val ENV_ENABLED = "KIVRA_AGENT_API_ENABLED"
        const val ENV_RESERVE_USD = "KIVRA_AGENT_ATTEMPT_RESERVE_USD"
        const val ENV_RECONCILE_INTERVAL_SECONDS = "KIVRA_CODING_RECONCILE_INTERVAL_SECONDS"
        const val ENV_RESULT_RETENTION_DAYS = "KIVRA_CODING_RESULT_RETENTION_DAYS"
        val DEFAULT_RESERVE_USD: BigDecimal = BigDecimal("0.50")
        val DEFAULT_RECONCILE_INTERVAL: Duration = Duration.ofSeconds(60)
        val DEFAULT_RESULT_RETENTION: Duration = Duration.ofDays(30)
        private val MAX_RESERVE_USD = BigDecimal("100")

        private val DISABLED = AgentApiSettings(auth = null)

        fun fromEnv(env: Map<String, String>): AgentApiSettings {
            val raw = env[ENV_ENABLED]?.trim()?.takeIf { it.isNotEmpty() } ?: return DISABLED
            return when (raw) {
                "false" -> DISABLED
                "true" -> AgentApiSettings(
                    auth = authConfig(env),
                    attemptReserveUsd = reserveUsd(env),
                    reconcileInterval = reconcileInterval(env),
                    resultRetention = resultRetention(env),
                )
                else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
            }
        }

        private fun authConfig(env: Map<String, String>): AgentAuthConfig {
            check(resolveTaskApiAuthMode(env) == TaskApiAuthMode.OIDC_JWT) {
                "$ENV_ENABLED=true requires KIVRA_AUTH_MODE=oidc-jwt"
            }
            val issuer = required(env, "KIVRA_OIDC_ISSUER")
            val jwksUrl = env["KIVRA_OIDC_JWKS_URL"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${issuer.trimEnd('/')}/protocol/openid-connect/certs"
            val audience = required(env, "KIVRA_OIDC_AUDIENCE")
            val azp = required(env, "KIVRA_AGENT_AZP")
            require(azp.length <= MAX_AZP_LENGTH && azp.none { it.isISOControl() }) { "KIVRA_AGENT_AZP has an invalid form" }
            val role = required(env, "KIVRA_AGENT_REQUIRED_ROLE")
            val parts = role.split(":")
            require(parts.size == 2 && parts.all { it.isNotBlank() }) { "KIVRA_AGENT_REQUIRED_ROLE must be <client>:<role>" }
            val operatorRole = env["KIVRA_OIDC_REQUIRED_ROLE"]?.trim()
            require(operatorRole == null || operatorRole != role.trim()) {
                "KIVRA_AGENT_REQUIRED_ROLE must differ from KIVRA_OIDC_REQUIRED_ROLE"
            }
            return AgentAuthConfig(
                issuer = issuer,
                jwksUrl = jwksUrl,
                audience = audience,
                azp = azp,
                requiredClient = parts[0].trim(),
                requiredRole = parts[1].trim(),
            )
        }

        private fun reserveUsd(env: Map<String, String>): BigDecimal {
            val raw = env[ENV_RESERVE_USD]?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_RESERVE_USD
            val value = runCatching { BigDecimal(raw) }.getOrNull()
                ?: throw IllegalStateException("$ENV_RESERVE_USD must be a decimal number")
            require(value.signum() > 0 && value <= MAX_RESERVE_USD && value.stripTrailingZeros().scale() <= 6) {
                "$ENV_RESERVE_USD must be above 0 and at most 100, with at most 6 decimals"
            }
            return value
        }

        /** A whole number of seconds from 10 to 3600. Errors name the variable only. */
        private fun reconcileInterval(env: Map<String, String>): Duration {
            val seconds = wholeNumber(env, ENV_RECONCILE_INTERVAL_SECONDS, 10L..3600L) ?: return DEFAULT_RECONCILE_INTERVAL
            return Duration.ofSeconds(seconds)
        }

        /** Whole days from 1 to 365. Errors name the variable only. */
        private fun resultRetention(env: Map<String, String>): Duration {
            val days = wholeNumber(env, ENV_RESULT_RETENTION_DAYS, 1L..365L) ?: return DEFAULT_RESULT_RETENTION
            return Duration.ofDays(days)
        }

        private fun wholeNumber(env: Map<String, String>, name: String, range: LongRange): Long? {
            val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val value = raw.toLongOrNull()?.takeIf { it in range }
                ?: throw IllegalStateException("$name must be a whole number from ${range.first} to ${range.last}")
            return value
        }

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("$name is required for $ENV_ENABLED=true")

        private const val MAX_AZP_LENGTH = 128
    }
}
