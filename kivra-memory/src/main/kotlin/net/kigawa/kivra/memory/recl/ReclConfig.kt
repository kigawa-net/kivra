package net.kigawa.kivra.memory.recl

import java.net.URI

/** Supplies the bearer token for recl. Implementations must not log or expose it. 要確認: recl's token model (recl#4) is unconfirmed. */
fun interface ReclCredentials {
    suspend fun bearerToken(): String
}

/**
 * Connection settings for the recl REST API. EVERYTHING here is 要確認. recl has merged only the design document
 * (docs/design.md) and the internal Core / InMemory Storage; the real REST / MCP server and the Fortis Adapter are NOT
 * provided yet, so the API contract below is Kivra's assumption, not a published recl API. Not wired into kivra-app
 * (there is no production API to connect to). A mock server passing the contract tests is NOT production verification.
 *
 * [baseUrl] must be https (no http, no userinfo, no query, no fragment): the bearer token must never travel in
 * clear text or to an unexpected host. Redirects are never followed.
 */
class ReclConfig(
    baseUrl: String,
    /** 要確認: API path prefix. */
    val apiPrefix: String = "/api/v1",
    val connectTimeoutMs: Long = 1_000,
    /** Search timeout (design #6 section 4.4: default 2s, to be tuned). */
    val requestTimeoutMs: Long = 2_000,
    /** Total attempts for GET requests only (get / pinned / health). POST / PATCH / DELETE are NEVER auto re-sent. */
    val maxReadAttempts: Int = 3,
    val maxResponseBytes: Int = 1024 * 1024,
    /** Set true only after confirming that recl dedupes `save` by Idempotency-Key (design #6 section 4.3-1). 要確認. */
    val idempotentSaveConfirmed: Boolean = false,
    /** Set true only after confirming a Redact / physical delete API exists (section 4.3-3). 要確認. */
    val redactSupported: Boolean = false,
    /**
     * Mutating operations (save / update / supersede / delete) are DISABLED by default and fail with UNSUPPORTED before
     * any request is sent. Set true only after the server-side idempotency, authorization, Receipt and contract tests
     * pass (recl#20 for the recl side, kivra#71 for the production capability gate). Read-only use does not need it.
     */
    val mutationsEnabled: Boolean = false,
) {
    val baseUrl: String

    init {
        val uri = try {
            URI(baseUrl)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid recl base url")
        }
        require(uri.scheme == "https") { "recl base url must use https" }
        require(!uri.host.isNullOrBlank()) { "recl base url needs a host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "recl base url must not contain credentials, query or fragment" }
        require(apiPrefix.isEmpty() || (apiPrefix.startsWith("/") && !apiPrefix.endsWith("/"))) { "invalid api prefix" }
        require(maxReadAttempts in 1..5 && connectTimeoutMs > 0 && requestTimeoutMs > 0 && maxResponseBytes > 0) { "invalid limits" }
        this.baseUrl = baseUrl.trimEnd('/')
    }

    override fun toString(): String = "ReclConfig(baseUrl=$baseUrl)"
}
