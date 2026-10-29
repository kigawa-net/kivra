package net.kigawa.kivra.routes

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable as KSerializable
import net.kigawa.kivra.auth.OidcSessionSettings
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.db.DbSessionStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

/** The server-side session data stored in the database and referenced by the cookie. */
@Serializable
data class LoginSession(val principal: String)

private val sessionHttpClient = HttpClient(CIO)

/** OIDC token response from the authorization server. */
@KSerializable
internal data class OidcTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("id_token") val idToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

/**
 * Configure OIDC login/logout routes for session persistence.
 *
 * Flow:
 * 1. GET /api/auth/login -> redirect to Keycloak authorization endpoint
 * 2. GET /api/auth/callback?code=... -> exchange code for tokens, create session, set cookie
 * 3. POST /api/auth/logout -> delete session, clear cookie, redirect to Keycloak logout
 *
 * The session cookie carries only the session ID. The principal is stored in the database.
 * Every API request with a valid session cookie is authenticated as the session's principal.
 */
internal fun Route.configureOidcSessionRoutes(
    settings: OidcSessionSettings,
    sessionStore: DbSessionStore,
) {
    val authorizationEndpoint = "${settings.issuer.trimEnd('/')}/protocol/openid-connect/auth"
    val tokenEndpoint = "${settings.issuer.trimEnd('/')}/protocol/openid-connect/token"
    val logoutEndpoint = "${settings.issuer.trimEnd('/')}/protocol/openid-connect/logout"

    get("/api/auth/login") {
        val state = UUID.randomUUID().toString()
        val codeChallenge = generateCodeChallenge()

        val authUrl = buildString {
            append(authorizationEndpoint)
            append("?client_id=").append(URLEncoder.encode(settings.clientId, StandardCharsets.UTF_8.name()))
            append("&redirect_uri=").append(URLEncoder.encode(settings.redirectUri, StandardCharsets.UTF_8.name()))
            append("&response_type=code")
            append("&scope=openid")
            append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8.name()))
            append("&code_challenge=").append(URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8.name()))
            append("&code_challenge_method=S256")
        }

        call.respondRedirect(authUrl, permanent = false)
    }

    get("/api/auth/callback") {
        val code = call.request.queryParameters["code"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing code parameter")

        // Exchange authorization code for tokens
        val tokens = exchangeCodeForTokens(
            tokenEndpoint = tokenEndpoint,
            clientId = settings.clientId,
            clientSecret = settings.clientSecret,
            code = code,
            redirectUri = settings.redirectUri,
        ) ?: return@get call.respond(HttpStatusCode.InternalServerError, "Token exchange failed")

        // Extract subject from ID token
        val subject = extractSubjectFromIdToken(tokens.idToken)
            ?: return@get call.respond(HttpStatusCode.InternalServerError, "Invalid ID token")

        // Build principal
        val principal = oidcOwnerKey(settings.issuer, subject)
            ?: return@get call.respond(HttpStatusCode.InternalServerError, "Invalid subject")

        // Create session in database
        val sessionId = sessionStore.create(principal, settings.sessionMaxAge)

        // Set session cookie with proper attributes (HttpOnly, Secure, SameSite=Lax, Max-Age)
        val maxAge = settings.sessionMaxAge.seconds
        val secureAttr = if (settings.cookieSecure) "Secure; " else ""
        call.response.header(
            HttpHeaders.SetCookie,
            "${settings.sessionCookieName}=$sessionId; Path=/; Max-Age=$maxAge; HttpOnly; $secureAttr SameSite=Lax"
        )

        // Redirect to UI
        call.respondRedirect("/ui", permanent = false)
    }

    post("/api/auth/logout") {
        val sessionId = call.request.cookies[settings.sessionCookieName]
        if (sessionId != null) {
            sessionStore.delete(sessionId)
        }
        val secureAttr = if (settings.cookieSecure) "Secure; " else ""
        call.response.header(HttpHeaders.SetCookie, "${settings.sessionCookieName}=; Path=/; Max-Age=0; HttpOnly; $secureAttr SameSite=Lax")

        // Redirect to Keycloak logout
        val host = call.request.headers[HttpHeaders.Host] ?: "localhost"
        val scheme = "https"
        val redirect = "$scheme://$host/ui"
        val encodedRedirect = URLEncoder.encode(redirect, StandardCharsets.UTF_8.name())
        val logoutUrl = "$logoutEndpoint?post_logout_redirect_uri=$encodedRedirect"
        call.respondRedirect(logoutUrl, permanent = false)
    }

    get("/api/auth/me") {
        val sessionId = call.request.cookies[settings.sessionCookieName]
            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Not authenticated")

        val session = sessionStore.get(sessionId)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Session not found")

        call.respond(mapOf<String, String>("principal" to session.principal))
    }
}

private val secureRandom = SecureRandom()

private fun generateCodeChallenge(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.toBase64Url()
}

private fun ByteArray.toBase64Url(): String {
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}

private suspend fun exchangeCodeForTokens(
    tokenEndpoint: String,
    clientId: String,
    clientSecret: String,
    code: String,
    redirectUri: String,
): OidcTokenResponse? {
    return try {
        sessionHttpClient.submitForm(
            url = tokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", code)
                append("redirect_uri", redirectUri)
            },
        ).body<OidcTokenResponse>()
    } catch (e: Exception) {
        null
    }
}

private fun extractSubjectFromIdToken(idToken: String): String? {
    return try {
        val decoded = JWT.decode(idToken)
        decoded.subject
    } catch (e: Exception) {
        null
    }
}
