package net.kigawa.kivra.mcp

import java.net.URI
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.auth.TaskApiAuthMode
import net.kigawa.kivra.auth.resolveTaskApiAuthMode

/**
 * Settings of the MCP endpoint (`/mcp`, ADR 0013). Off by default. With `KIVRA_MCP_ENABLED` unset, `false` or empty, nothing beyond
 * that switch is read, and no MCP route is registered. The error messages name a variable, never its value.
 *
 * - `KIVRA_MCP_ENABLED`: `true` to serve `/mcp`. Requires `KIVRA_AUTH_MODE=oidc-jwt` (the Keycloak tokens are verified by kivra).
 * - `KIVRA_MCP_AUDIENCE`: required, `https://<host>/mcp`. The `aud` an MCP access token must carry. Its origin is the public origin of
 *   the server, used for the protected resource metadata URL. It is not taken from a request header.
 * - `KIVRA_MCP_ALLOWED_HOSTS`: required, comma-separated `Host` values (for example `kivra-dev.kigawa.net`). A request with another
 *   `Host` gets 403.
 * - `KIVRA_MCP_ALLOWED_ORIGINS`: optional, comma-separated `Origin` values. A request that carries an `Origin` header must match one of
 *   them. A request without `Origin` (a non-browser client) is allowed.
 * - `KIVRA_MCP_RATE_PER_MINUTE`: requests per principal per minute, 1 to 6000, default 60. Over the limit, 429.
 * - `KIVRA_AGENT_REQUIRED_ROLE`: optional, `<client>:<role>` of the agent token. When it is set, a token that carries it is refused
 *   (403). The audience check already rejects agent tokens, so this is a second layer.
 */
class McpSettings private constructor(
    val enabled: Boolean,
    val audience: String?,
    val allowedHosts: Set<String>,
    val allowedOrigins: Set<String>,
    val ratePerMinute: Int,
    val agentRole: RequiredRole?,
) {
    /** `https://<host>[:port]`, the origin of [audience]. Null when disabled. */
    val publicOrigin: String? get() = audience?.let { URI(it).let { u -> "${u.scheme}://${u.rawAuthority}" } }

    /** The protected resource metadata URL (RFC 9728), named in the `WWW-Authenticate` challenge. */
    val resourceMetadataUrl: String? get() = publicOrigin?.let { "$it/.well-known/oauth-protected-resource" }

    companion object {
        const val ENV_ENABLED = "KIVRA_MCP_ENABLED"
        const val ENV_AUDIENCE = "KIVRA_MCP_AUDIENCE"
        const val ENV_ALLOWED_HOSTS = "KIVRA_MCP_ALLOWED_HOSTS"
        const val ENV_ALLOWED_ORIGINS = "KIVRA_MCP_ALLOWED_ORIGINS"
        const val ENV_RATE_PER_MINUTE = "KIVRA_MCP_RATE_PER_MINUTE"
        const val ENV_AGENT_ROLE = "KIVRA_AGENT_REQUIRED_ROLE"

        const val DEFAULT_RATE_PER_MINUTE = 60
        private const val MAX_RATE_PER_MINUTE = 6_000
        private const val MAX_LIST_ENTRIES = 10

        /** The settings with the endpoint off. Nothing else is read. */
        val DISABLED = McpSettings(false, null, emptySet(), emptySet(), DEFAULT_RATE_PER_MINUTE, null)

        fun fromEnv(env: Map<String, String>): McpSettings {
            when (val flag = env[ENV_ENABLED]?.trim()) {
                null, "", "false" -> return DISABLED
                "true" -> Unit
                else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
            }
            check(resolveTaskApiAuthMode(env) == TaskApiAuthMode.OIDC_JWT) {
                "$ENV_ENABLED=true requires KIVRA_AUTH_MODE=oidc-jwt"
            }
            val audience = audience(env)
            val hosts = hostList(env, ENV_ALLOWED_HOSTS, required = true)
            val origins = hostList(env, ENV_ALLOWED_ORIGINS, required = false)
            val rate = rate(env)
            val agentRole = env[ENV_AGENT_ROLE]?.trim()?.takeIf { it.isNotEmpty() }?.let { RequiredRole.parse(it, ENV_AGENT_ROLE) }
            return McpSettings(true, audience, hosts, origins, rate, agentRole)
        }

        private fun audience(env: Map<String, String>): String {
            val raw = env[ENV_AUDIENCE]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("$ENV_ENABLED=true requires $ENV_AUDIENCE")
            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                throw IllegalStateException("$ENV_AUDIENCE must be an https URL ending in /mcp")
            }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawFragment == null && uri.rawQuery == null && uri.rawPath == "/mcp") {
                "$ENV_AUDIENCE must be an https URL ending in /mcp"
            }
            return raw
        }

        private fun hostList(env: Map<String, String>, name: String, required: Boolean): Set<String> {
            val raw = env[name]?.takeIf { it.isNotBlank() }
            if (raw == null) {
                require(!required) { "$ENV_ENABLED=true requires $name" }
                return emptySet()
            }
            val entries = raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            require(entries.isNotEmpty() && entries.size <= MAX_LIST_ENTRIES) { "$name must list 1 to $MAX_LIST_ENTRIES entries" }
            val shape = if (name == ENV_ALLOWED_ORIGINS) ORIGIN_SHAPE else HOST_SHAPE
            require(entries.all { it.matches(shape) }) {
                "$name has an entry of the wrong shape"
            }
            return entries.toSet()
        }

        private fun rate(env: Map<String, String>): Int {
            val raw = env[ENV_RATE_PER_MINUTE]?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_RATE_PER_MINUTE
            val value = raw.toIntOrNull() ?: throw IllegalStateException("$ENV_RATE_PER_MINUTE must be a whole number")
            require(value in 1..MAX_RATE_PER_MINUTE) { "$ENV_RATE_PER_MINUTE must be 1 to $MAX_RATE_PER_MINUTE" }
            return value
        }

        private val HOST_SHAPE = Regex("[a-z0-9.\\-]+(:[0-9]{1,5})?")
        private val ORIGIN_SHAPE = Regex("https?://[a-z0-9.\\-]+(:[0-9]{1,5})?")
    }
}
