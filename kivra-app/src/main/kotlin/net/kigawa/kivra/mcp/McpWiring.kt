package net.kigawa.kivra.mcp

import com.auth0.jwk.JwkProvider
import net.kigawa.kivra.auth.OidcJwtConfig
import net.kigawa.kivra.auth.RefreshingJwkProvider
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
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
 * it the roles are off: the guard of today's rules, with the AI principal of [orders] or [goals]. [tokenAuth] is the entrance for
 * an assignee's API token (ADR 0020 addendum, T2); null (the default) leaves it off.
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
    /** The tasks of the cycle tree ([McpTaskTools], ADR 0019). Set only while the autopilot is on; registered with the Goal read flag. */
    tasks: SelfDevOperatorRepository? = null,
    guard: PermissionGuard? = null,
    tokenAuth: McpTokenAuth? = null,
    /** The projects a write tool may assign a run to (ADR 0022). Required while the MCP write switch is on. */
    projects: ProjectRepository? = null,
): McpRouteDeps? {
    if (!settings.enabled) return null
    val oidc = OidcJwtConfig.fromEnv(env).copy(audience = checkNotNull(settings.audience))
    // The order tools are registered only while the orders are on ([orders] is null otherwise).
    // Goal tools temporarily removed pending Task model migration (V40).
    val tools = McpRunTools(runs, targetRepo).tools() +
        (if (settings.writeEnabled) {
            McpWriteTools(runs, selfDevEnabled, maxActiveSelfDev, requireNotNull(projects) { "projects is required when the MCP writes are on" }).tools()
        } else {
            emptyList()
        }) +
        (orders?.let { McpOrderTools(it).tools(settings.writeEnabled) } ?: emptyList()) +
        (if (settings.goalReadEnabled) tasks?.let { McpTaskTools(it, settings.goalTextEnabled).tools() } ?: emptyList() else emptyList())
    return McpRouteDeps(
        settings = settings,
        verifier = McpJwtVerifier(oidc, jwkProvider ?: RefreshingJwkProvider(oidc.jwksUrl), settings.agentRole),
        issuer = oidc.issuer,
        dispatcher = McpDispatcher(
            tools, McpRateLimiter(settings.writeRatePerMinute),
            guard ?: legacyPermissionGuard(orders?.aiPrincipal),
        ),
        rateLimiter = McpRateLimiter(settings.ratePerMinute),
        tokenAuth = tokenAuth,
    )
}

/** Registers `/mcp` when [deps] is non-null (that is, when enabled). Called from `appModule`. */
internal fun io.ktor.server.routing.Route.registerMcpRoutes(deps: McpRouteDeps?) {
    if (deps != null) configureMcpRoutes(deps)
}
