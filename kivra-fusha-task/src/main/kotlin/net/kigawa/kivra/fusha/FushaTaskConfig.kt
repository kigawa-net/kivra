package net.kigawa.kivra.fusha

import java.time.Duration

/**
 * Connection settings for fusha-task. Everything not confirmed from the fusha-task source is a
 * setting rather than a constant (needs confirmation: per-environment URL, how the machine token is
 * obtained, see design #2 section 13).
 */
data class FushaTaskConfig(
    /** API root including the version path, e.g. `https://fusha.example/api/v1`. Needs confirmation per environment. */
    val baseUrl: String,
    /** Environment label used in TaskId (`fusha:{env}:{id}`), e.g. dev / staging / prod. */
    val environment: String,
    /**
     * Static bearer token. Needs confirmation: fusha-task verifies a Keycloak ID token (aud = its client_id)
     * and has no service-account path in code (design #2 section 2.3). Token acquisition/refresh is
     * pluggable through [TokenProvider]; this static value is only the simplest source.
     */
    val staticToken: String? = null,
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** Retries after the first attempt, for GET only. */
    val maxRetries: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(200),
    val maxBackoff: Duration = Duration.ofSeconds(5),
    /** Explicit dev-mode switch allowing plain http to a non-loopback host. Never enable in production. */
    val allowInsecureHttp: Boolean = false,
) {
    init {
        require(baseUrl.isNotBlank()) { "fusha baseUrl must not be blank" }
        require(environment.isNotBlank() && !environment.contains(':')) { "fusha environment must be non-blank and contain no ':'" }
        validateBaseUrl(baseUrl, allowInsecureHttp)
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(!requestTimeout.isZero && !requestTimeout.isNegative) { "requestTimeout must be positive" }
        require(!initialBackoff.isNegative && !maxBackoff.isNegative) { "backoff must not be negative" }
    }

    // Keep the token out of logs.
    override fun toString(): String =
        "FushaTaskConfig(baseUrl=$baseUrl, allowInsecureHttp=$allowInsecureHttp, environment=$environment, staticToken=${if (staticToken == null) "null" else "***"}, " +
            "requestTimeout=$requestTimeout, maxRetries=$maxRetries)"
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

/**
 * The bearer token is sent to this URL, so: https only (http only for loopback or explicit dev mode), no userinfo,
 * no query, no fragment. Messages never echo the URL (it may embed secrets when misconfigured).
 */
private fun validateBaseUrl(raw: String, allowInsecureHttp: Boolean) {
    val uri = try {
        java.net.URI(raw)
    } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("fusha baseUrl is not a valid URL")
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    require(scheme == "https" || scheme == "http") { "fusha baseUrl must use https (or http for loopback/dev mode)" }
    require(!host.isNullOrBlank()) { "fusha baseUrl must have a host" }
    require(uri.rawUserInfo == null) { "fusha baseUrl must not contain credentials (userinfo)" }
    require(uri.rawQuery == null && uri.rawFragment == null) { "fusha baseUrl must not contain a query or fragment" }
    if (scheme == "http") {
        require(allowInsecureHttp || host in LOOPBACK_HOSTS) {
            "fusha baseUrl over plain http is only allowed for loopback hosts or with allowInsecureHttp (dev mode)"
        }
    }
}

/** Supplies bearer tokens. Credentials are held only on the adapter side (issue #27). */
interface TokenProvider {
    /** @param forceRefresh true after a 401, to obtain a fresh token instead of a cached one. */
    suspend fun token(forceRefresh: Boolean): String
}

/** Always returns the same token. A 401 therefore cannot be recovered by refreshing. */
class StaticTokenProvider(private val value: String) : TokenProvider {
    override suspend fun token(forceRefresh: Boolean): String = value
    override fun toString(): String = "StaticTokenProvider(***)"
}
