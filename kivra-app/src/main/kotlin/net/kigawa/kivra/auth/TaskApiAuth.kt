package net.kigawa.kivra.auth

import com.auth0.jwk.JwkProvider
import io.ktor.http.Headers

/**
 * How the Task API (`/api/tasks`, `/api/runs`) identifies its caller. Set by `KIVRA_AUTH_MODE`.
 *
 * - [OIDC_JWT] (`oidc-jwt`, production): the caller is identified by a Keycloak access token (JWT) that kivra verifies
 *   itself (signature via JWKS, iss, aud, exp, nbf, required client role). The token comes from `Authorization: Bearer`
 *   or, when that is absent, `X-Forwarded-Access-Token` (oauth2-proxy `--pass-access-token=true`). A forged token fails
 *   the signature check, so this does not depend on network isolation. See [OidcJwtVerifier].
 * - [OAUTH2_PROXY]: the caller is identified by the `X-Auth-Request-Email` / `X-Auth-Request-User` headers. These headers
 *   are trusted, not verified, so anyone who can reach kivra directly can forge them. Flannel (the cluster CNI) does not
 *   enforce NetworkPolicy, so that isolation cannot be relied on. **Only for `KIVRA_RUNTIME_ENV=development`.**
 * - [NONE]: no authentication, every caller is one shared principal. Only for `KIVRA_RUNTIME_ENV=development`.
 */
enum class TaskApiAuthMode { OIDC_JWT, OAUTH2_PROXY, NONE }

/** Principal used in [TaskApiAuthMode.NONE]. It is never a real identity. */
const val DEVELOPMENT_ANONYMOUS_PRINCIPAL = "development-anonymous"

const val MAX_PRINCIPAL_LENGTH = 256

/**
 * Resolves `KIVRA_AUTH_MODE`. Returns null when unset: the Task API routes are then not registered at all (fail-closed).
 * Throws [IllegalArgumentException] (startup failure) for an unknown value, or for `oauth2-proxy` / `none` outside
 * `KIVRA_RUNTIME_ENV=development`.
 */
fun resolveTaskApiAuthMode(env: Map<String, String>): TaskApiAuthMode? {
    val raw = env["KIVRA_AUTH_MODE"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (raw) {
        "oidc-jwt" -> TaskApiAuthMode.OIDC_JWT
        "oauth2-proxy" -> {
            requireDevelopment(env, raw)
            TaskApiAuthMode.OAUTH2_PROXY
        }
        "none" -> {
            requireDevelopment(env, raw)
            TaskApiAuthMode.NONE
        }
        else -> throw IllegalArgumentException("KIVRA_AUTH_MODE must be oidc-jwt, oauth2-proxy or none")
    }
}

private fun requireDevelopment(env: Map<String, String>, mode: String) {
    require(env["KIVRA_RUNTIME_ENV"] == "development") {
        "KIVRA_AUTH_MODE=$mode is allowed only with KIVRA_RUNTIME_ENV=development (use oidc-jwt elsewhere)"
    }
}

/**
 * Maps a request's headers to the caller's principal. Null means unauthenticated (-> 401). Implementations must not put
 * the reason for a failure into the response.
 */
fun interface TaskApiIdentity {
    suspend fun principal(headers: Headers): String?
}

/**
 * Builds the identity for the configured mode. Null when `KIVRA_AUTH_MODE` is unset. For `oidc-jwt` the required
 * settings are validated here (startup failure), but the JWKS is not fetched until the first request.
 * [jwkProvider] is injectable for tests; production leaves it null.
 */
fun resolveTaskApiIdentity(env: Map<String, String>, jwkProvider: JwkProvider? = null): TaskApiIdentity? {
    val mode = resolveTaskApiAuthMode(env) ?: return null
    return when (mode) {
        TaskApiAuthMode.OIDC_JWT -> {
            val config = OidcJwtConfig.fromEnv(env)
            OidcJwtVerifier(config, jwkProvider ?: RefreshingJwkProvider(config.jwksUrl)).asIdentity()
        }
        TaskApiAuthMode.OAUTH2_PROXY, TaskApiAuthMode.NONE -> TaskApiIdentity { headers ->
            principalFromHeaders(mode, headers["X-Auth-Request-Email"], headers["X-Auth-Request-User"])
        }
    }
}

/**
 * Principal for a request in the header-based modes. In OAUTH2_PROXY mode it is the email when present, otherwise the
 * user. Null (-> 401) when the value is missing, empty, longer than [MAX_PRINCIPAL_LENGTH] or contains control characters.
 */
fun principalFromHeaders(mode: TaskApiAuthMode, email: String?, user: String?): String? {
    if (mode == TaskApiAuthMode.NONE) return DEVELOPMENT_ANONYMOUS_PRINCIPAL
    return validPrincipal((email?.takeIf { it.isNotBlank() } ?: user))
}

/** Trims [raw] and returns it when it is a usable principal (non-empty, at most [MAX_PRINCIPAL_LENGTH], no control characters). */
internal fun validPrincipal(raw: String?): String? {
    val candidate = raw?.trim() ?: return null
    if (candidate.isEmpty() || candidate.length > MAX_PRINCIPAL_LENGTH) return null
    if (candidate.any { it.isISOControl() }) return null
    return candidate
}
