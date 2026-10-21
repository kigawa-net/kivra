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
 * - `KIVRA_MCP_WRITE_ENABLED`: `true` to register the write tools: `kivra_selfdev_start` (ADR 0013 section 5) and, while the orders are on,
 *   `kivra_order_request_stop` (ADR 0015 section 6). Off by default. It needs
 *   `KIVRA_MCP_ENABLED=true`: with the endpoint off, `true` refuses the start. The write and Goal switches are the only MCP variables read
 *   while the endpoint is off, and only to refuse `true`.
 * - `KIVRA_MCP_WRITE_RATE_PER_MINUTE`: write calls per principal per minute, 1 to 6000, default 5. Over the limit, 429. Read only when
 *   the write tool is enabled.
 * - `KIVRA_MCP_GOAL_READ_ENABLED`: `true` to register `kivra_goal_list` and `kivra_goal_get` (ADR 0019 S1). Off by default. Registered only
 *   while the autopilot is on, and needs `KIVRA_MCP_ENABLED=true`.
 * - `KIVRA_MCP_GOAL_STOP_ENABLED`: `true` to register `kivra_goal_stop` (ADR 0019 S1), an operator-only stop. Off by default. Needs
 *   `KIVRA_MCP_ENABLED=true` and `KIVRA_MCP_WRITE_ENABLED=true`, and the autopilot must be on (checked at startup). The AI principal is refused.
 * - `KIVRA_MCP_GOAL_SUBMIT_ENABLED`: `true` to register `kivra_goal_submit` (ADR 0019 S2), the operator's Goal submission. Off by default. Needs
 *   `KIVRA_MCP_ENABLED=true` and `KIVRA_MCP_WRITE_ENABLED=true`, and the autopilot must be on (checked at startup). The AI principal is refused.
 * - `KIVRA_MCP_GOAL_SUBMIT_DAILY_LIMIT`: goals one operator may submit over MCP per UTC day, 1 to 100, default 10. Read only when the submit
 *   tool is enabled. A replay does not count.
 * - `KIVRA_MCP_GOAL_TEXT_ENABLED`: `true` to register `kivra_goal_get_titles` (ADR 0019 amendment 2026-10-21), which returns the titles of a
 *   goal and of its latest proposed plan's tasks, to the operator only. Off by default. Needs `KIVRA_MCP_ENABLED=true` and
 *   `KIVRA_MCP_GOAL_READ_ENABLED=true`, and the autopilot must be on. The bodies and task instructions are never returned.
 */
class McpSettings private constructor(
    val enabled: Boolean,
    val audience: String?,
    val allowedHosts: Set<String>,
    val allowedOrigins: Set<String>,
    val ratePerMinute: Int,
    val agentRole: RequiredRole?,
    /** Whether the write tool is registered (`KIVRA_MCP_WRITE_ENABLED=true`). Never true when [enabled] is false. */
    val writeEnabled: Boolean = false,
    /** Write calls per principal per minute. Only meaningful when [writeEnabled]. */
    val writeRatePerMinute: Int = DEFAULT_WRITE_RATE_PER_MINUTE,
    /** Whether the Goal read tools are registered (`KIVRA_MCP_GOAL_READ_ENABLED=true`). Never true when [enabled] is false. */
    val goalReadEnabled: Boolean = false,
    /** Whether the Goal stop tool is registered (`KIVRA_MCP_GOAL_STOP_ENABLED=true`). Never true unless [writeEnabled]. */
    val goalStopEnabled: Boolean = false,
    /** Whether the Goal submit tool is registered (`KIVRA_MCP_GOAL_SUBMIT_ENABLED=true`). Never true unless [writeEnabled]. */
    val goalSubmitEnabled: Boolean = false,
    /** Goals one operator may submit over MCP per UTC day. Only meaningful when [goalSubmitEnabled]. */
    val goalSubmitDailyLimit: Int = DEFAULT_GOAL_SUBMIT_DAILY_LIMIT,
    /** Whether the Goal title tool is registered (`KIVRA_MCP_GOAL_TEXT_ENABLED=true`). Never true unless [goalReadEnabled]. */
    val goalTextEnabled: Boolean = false,
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
        const val ENV_WRITE_ENABLED = "KIVRA_MCP_WRITE_ENABLED"
        const val ENV_WRITE_RATE_PER_MINUTE = "KIVRA_MCP_WRITE_RATE_PER_MINUTE"
        const val ENV_GOAL_READ_ENABLED = "KIVRA_MCP_GOAL_READ_ENABLED"
        const val ENV_GOAL_STOP_ENABLED = "KIVRA_MCP_GOAL_STOP_ENABLED"
        const val ENV_GOAL_SUBMIT_ENABLED = "KIVRA_MCP_GOAL_SUBMIT_ENABLED"
        const val ENV_GOAL_SUBMIT_DAILY_LIMIT = "KIVRA_MCP_GOAL_SUBMIT_DAILY_LIMIT"
        const val ENV_GOAL_TEXT_ENABLED = "KIVRA_MCP_GOAL_TEXT_ENABLED"

        const val DEFAULT_RATE_PER_MINUTE = 60
        const val DEFAULT_WRITE_RATE_PER_MINUTE = 5
        private const val MAX_RATE_PER_MINUTE = 6_000
        private const val MAX_LIST_ENTRIES = 10
        const val DEFAULT_GOAL_SUBMIT_DAILY_LIMIT = 10
        private const val MAX_GOAL_SUBMIT_DAILY_LIMIT = 100

        /** The settings with the endpoint off. Nothing else is read. */
        val DISABLED = McpSettings(false, null, emptySet(), emptySet(), DEFAULT_RATE_PER_MINUTE, null)

        fun fromEnv(env: Map<String, String>): McpSettings {
            val writeFlag = switch(env, ENV_WRITE_ENABLED)
            val goalReadFlag = switch(env, ENV_GOAL_READ_ENABLED)
            val goalStopFlag = switch(env, ENV_GOAL_STOP_ENABLED)
            val goalSubmitFlag = switch(env, ENV_GOAL_SUBMIT_ENABLED)
            val goalTextFlag = switch(env, ENV_GOAL_TEXT_ENABLED)
            when (val flag = env[ENV_ENABLED]?.trim()) {
                null, "", "false" -> {
                    check(!writeFlag) { "$ENV_WRITE_ENABLED=true requires $ENV_ENABLED=true" }
                    check(!goalReadFlag && !goalStopFlag && !goalSubmitFlag && !goalTextFlag) {
                        "$ENV_GOAL_READ_ENABLED, $ENV_GOAL_STOP_ENABLED, $ENV_GOAL_SUBMIT_ENABLED and $ENV_GOAL_TEXT_ENABLED require $ENV_ENABLED=true"
                    }
                    return DISABLED
                }
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
            val writeRate = if (writeFlag) writeRate(env) else DEFAULT_WRITE_RATE_PER_MINUTE
            check(!goalStopFlag || writeFlag) { "$ENV_GOAL_STOP_ENABLED=true requires $ENV_WRITE_ENABLED=true" }
            check(!goalSubmitFlag || writeFlag) { "$ENV_GOAL_SUBMIT_ENABLED=true requires $ENV_WRITE_ENABLED=true" }
            check(!goalTextFlag || goalReadFlag) { "$ENV_GOAL_TEXT_ENABLED=true requires $ENV_GOAL_READ_ENABLED=true" }
            val submitLimit = if (goalSubmitFlag) goalSubmitDailyLimit(env) else DEFAULT_GOAL_SUBMIT_DAILY_LIMIT
            return McpSettings(
                true, audience, hosts, origins, rate, agentRole, writeFlag, writeRate, goalReadFlag, goalStopFlag,
                goalSubmitFlag, submitLimit, goalTextFlag,
            )
        }

        /** The `true`/`false` switch. Unset and empty are `false`. Any other value is refused by name only. */
        private fun switch(env: Map<String, String>, name: String): Boolean = when (env[name]?.trim()) {
            null, "", "false" -> false
            "true" -> true
            else -> throw IllegalStateException("$name must be true or false")
        }

        private fun writeRate(env: Map<String, String>): Int {
            val raw = env[ENV_WRITE_RATE_PER_MINUTE]?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_WRITE_RATE_PER_MINUTE
            val value = raw.toIntOrNull() ?: throw IllegalStateException("$ENV_WRITE_RATE_PER_MINUTE must be a whole number")
            require(value in 1..MAX_RATE_PER_MINUTE) { "$ENV_WRITE_RATE_PER_MINUTE must be 1 to $MAX_RATE_PER_MINUTE" }
            return value
        }

        private fun goalSubmitDailyLimit(env: Map<String, String>): Int {
            val raw = env[ENV_GOAL_SUBMIT_DAILY_LIMIT]?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_GOAL_SUBMIT_DAILY_LIMIT
            val value = raw.toIntOrNull() ?: throw IllegalStateException("$ENV_GOAL_SUBMIT_DAILY_LIMIT must be a whole number")
            require(value in 1..MAX_GOAL_SUBMIT_DAILY_LIMIT) { "$ENV_GOAL_SUBMIT_DAILY_LIMIT must be 1 to $MAX_GOAL_SUBMIT_DAILY_LIMIT" }
            return value
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
