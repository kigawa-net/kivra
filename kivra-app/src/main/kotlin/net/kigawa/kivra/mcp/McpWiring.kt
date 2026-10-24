package net.kigawa.kivra.mcp

import com.auth0.jwk.JwkProvider
import net.kigawa.kivra.auth.OidcJwtConfig
import net.kigawa.kivra.auth.RefreshingJwkProvider
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.legacyPermissionGuard

/**
 * Builds the `/mcp` routes' dependencies. Called only when [McpSettings.enabled] is true. The OIDC settings are the REST ones
 * (`KIVRA_OIDC_*`) with the MCP audience. [jwkProvider] is injectable for tests; production leaves it null.
 *
 * The write tool is registered only when [McpSettings.writeEnabled] (ADR 0013 section 5). [selfDevEnabled] is the self-development kill
 * switch: while it is off, the write tool is not listed, and a call is refused with a fixed reason. [maxActiveSelfDev] is the cap of
 * concurrent self-development runs, the same value the REST submission uses. [orders] is set only while the self-development orders are on
 * (ADR 0015); the order tools are then registered ([McpOrderTools]). [guard] is the REST routes' permission guard (ADR 0020 PR 3d). Without
 * it the roles are off: the guard of today's rules, with the AI principal of [orders] or [goals].
 */
fun buildMcpRouteDeps(
    settings: McpSettings,
    env: Map<String, String>,
    runs: DevelopmentRunRepository,
    targetRepo: RepoRef?,
    jwkProvider: JwkProvider? = null,
    selfDevEnabled: Boolean = false,
    maxActiveSelfDev: Int = 1,
    orders: McpOrderDeps? = null,
    goals: McpGoalDeps? = null,
    guard: PermissionGuard? = null,
): McpRouteDeps? {
    if (!settings.enabled) return null
    val oidc = OidcJwtConfig.fromEnv(env).copy(audience = checkNotNull(settings.audience))
    // The order tools are registered only while the orders are on ([orders] is null otherwise). The stop request also needs the write switch.
    // The Goal tools (ADR 0019 S1) need the autopilot ([goals] is null otherwise); the stop also needs the write switch.
    val tools = McpRunTools(runs, targetRepo).tools() +
        (if (settings.writeEnabled) McpWriteTools(runs, selfDevEnabled, maxActiveSelfDev).tools() else emptyList()) +
        (orders?.let { McpOrderTools(it).tools(settings.writeEnabled) } ?: emptyList()) +
        (goals?.let {
            McpGoalTools(it).tools(
                settings.goalReadEnabled,
                settings.writeEnabled && settings.goalStopEnabled,
                settings.writeEnabled && settings.goalSubmitEnabled,
                settings.goalTextEnabled,
            )
        } ?: emptyList())
    return McpRouteDeps(
        settings = settings,
        verifier = McpJwtVerifier(oidc, jwkProvider ?: RefreshingJwkProvider(oidc.jwksUrl), settings.agentRole),
        issuer = oidc.issuer,
        dispatcher = McpDispatcher(
            tools, McpRateLimiter(settings.writeRatePerMinute),
            guard ?: legacyPermissionGuard(goals?.aiPrincipal ?: orders?.aiPrincipal),
        ),
        rateLimiter = McpRateLimiter(settings.ratePerMinute),
    )
}

/** Registers `/mcp` when [deps] is non-null (that is, when enabled). Called from `appModule`. */
internal fun io.ktor.server.routing.Route.registerMcpRoutes(deps: McpRouteDeps?) {
    if (deps != null) configureMcpRoutes(deps)
}
