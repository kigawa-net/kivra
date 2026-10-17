package net.kigawa.kivra.auth

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
 * Enabling this does not allow the CLI runtime: `KIVRA_SELFDEV_RUNTIME=cli` stays refused by `SelfDevSettings` until the agent itself is
 * complete.
 */
class AgentApiSettings private constructor(val auth: AgentAuthConfig?) {
    val enabled: Boolean get() = auth != null

    companion object {
        const val ENV_ENABLED = "KIVRA_AGENT_API_ENABLED"

        private val DISABLED = AgentApiSettings(auth = null)

        fun fromEnv(env: Map<String, String>): AgentApiSettings {
            val raw = env[ENV_ENABLED]?.trim()?.takeIf { it.isNotEmpty() } ?: return DISABLED
            return when (raw) {
                "false" -> DISABLED
                "true" -> AgentApiSettings(authConfig(env))
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

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("$name is required for $ENV_ENABLED=true")

        private const val MAX_AZP_LENGTH = 128
    }
}
