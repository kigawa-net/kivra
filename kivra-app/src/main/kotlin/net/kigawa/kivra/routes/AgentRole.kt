package net.kigawa.kivra.routes

import net.kigawa.kivra.auth.RequiredRole

/**
 * Parse the required agent role from environment. Used by the autopilot operator API.
 */
fun requiredAgentRole(env: Map<String, String>): RequiredRole {
    val raw = env[AGENT_ROLE_ENV]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("$AUTOPILOT_ENV_ENABLED=true requires $AGENT_ROLE_ENV")
    return RequiredRole.parse(raw, AGENT_ROLE_ENV)
}

private const val AGENT_ROLE_ENV = "KIVRA_AGENT_REQUIRED_ROLE"
private const val AUTOPILOT_ENV_ENABLED = "KIVRA_SELFDEV_AUTOPILOT_ENABLED"
