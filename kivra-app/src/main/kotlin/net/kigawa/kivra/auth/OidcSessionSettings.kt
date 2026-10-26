package net.kigawa.kivra.auth

import java.time.Duration

/**
 * Settings for OIDC login session persistence.
 *
 * - `KIVRA_OIDC_CLIENT_ID`: the OIDC client ID for the authorization code flow.
 * - `KIVRA_OIDC_CLIENT_SECRET`: the OIDC client secret.
 * - `KIVRA_OIDC_REDIRECT_URI`: the redirect URI after login, e.g. `https://kivra.example.com/api/auth/callback`.
 * - `KIVRA_SESSION_MAX_AGE`: optional session max age in seconds (default 7 days).
 * - `KIVRA_SESSION_COOKIE_NAME`: optional cookie name (default `kivra-session`).
 */
data class OidcSessionSettings(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val issuer: String,
    val sessionMaxAge: Duration,
    val sessionCookieName: String,
) {
    companion object {
        const val ENV_CLIENT_ID = "KIVRA_OIDC_CLIENT_ID"
        const val ENV_CLIENT_SECRET = "KIVRA_OIDC_CLIENT_SECRET"
        const val ENV_REDIRECT_URI = "KIVRA_OIDC_REDIRECT_URI"
        const val ENV_SESSION_MAX_AGE = "KIVRA_SESSION_MAX_AGE"
        const val ENV_SESSION_COOKIE_NAME = "KIVRA_SESSION_COOKIE_NAME"
        const val DEFAULT_SESSION_MAX_AGE_SECONDS = 7 * 24 * 3600L
        const val DEFAULT_COOKIE_NAME = "kivra-session"

        /**
         * Returns the session settings if all required environment variables are present.
         * Null when OIDC session is not configured (login session persistence is off).
         */
        fun fromEnv(env: Map<String, String>): OidcSessionSettings? {
            val clientId = env[ENV_CLIENT_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val clientSecret = env[ENV_CLIENT_SECRET]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val redirectUri = env[ENV_REDIRECT_URI]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val issuer = env["KIVRA_OIDC_ISSUER"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val maxAgeSeconds = env[ENV_SESSION_MAX_AGE]?.trim()?.toLongOrNull()
                ?: DEFAULT_SESSION_MAX_AGE_SECONDS
            require(maxAgeSeconds > 0) { "$ENV_SESSION_MAX_AGE must be positive" }
            val cookieName = env[ENV_SESSION_COOKIE_NAME]?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_COOKIE_NAME
            return OidcSessionSettings(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                issuer = issuer,
                sessionMaxAge = Duration.ofSeconds(maxAgeSeconds),
                sessionCookieName = cookieName,
            )
        }
    }
}
