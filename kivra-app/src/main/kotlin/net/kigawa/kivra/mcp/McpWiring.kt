package net.kigawa.kivra.mcp

import com.auth0.jwk.JwkProvider
import net.kigawa.kivra.auth.OidcJwtConfig
import net.kigawa.kivra.auth.RefreshingJwkProvider
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.github.RepoRef

/**
 * Builds the `/mcp` routes' dependencies. Called only when [McpSettings.enabled] is true. The OIDC settings are the REST ones
 * (`KIVRA_OIDC_*`) with the MCP audience. [jwkProvider] is injectable for tests; production leaves it null.
 *
 * The write tool is registered only when [McpSettings.writeEnabled] (ADR 0013 section 5). [selfDevEnabled] is the self-development kill
 * switch: while it is off, the write tool is not listed, and a call is refused with a fixed reason. [maxActiveSelfDev] is the cap of
 * concurrent self-development runs, the same value the REST submission uses.
 */
fun buildMcpRouteDeps(
    settings: McpSettings,
    env: Map<String, String>,
    runs: DevelopmentRunRepository,
    targetRepo: RepoRef?,
    jwkProvider: JwkProvider? = null,
    selfDevEnabled: Boolean = false,
    maxActiveSelfDev: Int = 1,
): McpRouteDeps? {
    if (!settings.enabled) return null
    val oidc = OidcJwtConfig.fromEnv(env).copy(audience = checkNotNull(settings.audience))
    val tools = McpRunTools(runs, targetRepo).tools() +
        if (settings.writeEnabled) McpWriteTools(runs, selfDevEnabled, maxActiveSelfDev).tools() else emptyList()
    return McpRouteDeps(
        settings = settings,
        verifier = McpJwtVerifier(oidc, jwkProvider ?: RefreshingJwkProvider(oidc.jwksUrl), settings.agentRole),
        issuer = oidc.issuer,
        dispatcher = McpDispatcher(tools, McpRateLimiter(settings.writeRatePerMinute)),
        rateLimiter = McpRateLimiter(settings.ratePerMinute),
    )
}

/** Registers `/mcp` when [deps] is non-null (that is, when enabled). Called from `appModule`. */
internal fun io.ktor.server.routing.Route.registerMcpRoutes(deps: McpRouteDeps?) {
    if (deps != null) configureMcpRoutes(deps)
}
