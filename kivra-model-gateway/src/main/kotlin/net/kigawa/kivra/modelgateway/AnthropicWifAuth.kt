package net.kigawa.kivra.modelgateway

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Workload Identity Federation settings. The IDs are not secret, but [toString] hides them anyway. [identityTokenFile] is the
 * projected ServiceAccount token; it is read again at every exchange because the kubelet rotates it.
 */
class AnthropicWifConfig(
    val federationRuleId: String,
    val organizationId: String,
    val serviceAccountId: String,
    val workspaceId: String?,
    val identityTokenFile: Path,
) {
    override fun toString(): String = "AnthropicWifConfig(***)"
}

/**
 * Workload Identity Federation: exchanges the projected ServiceAccount JWT for an Anthropic access token
 * (POST /v1/oauth/token) and sends it as `Authorization: Bearer`. Never sends `x-api-key`.
 *
 * - The access token is cached. It is refreshed [RECOMMENDED_REFRESH_SECONDS] before expiry (a failure keeps the cached
 *   token) and is mandatory from [REQUIRED_REFRESH_SECONDS] before expiry (a failure is an error).
 * - Concurrent callers share one exchange: the mutex is held across it.
 * - A JWT `jti` is single-use. The identity token file is re-read for every attempt, and the same JWT content is never sent
 *   twice: when the file has not rotated yet the attempt waits (bounded backoff) instead of re-sending.
 * - Only the host [allowedHost] (default api.anthropic.com over HTTPS) may receive the access token.
 * - Every failure is a [GatewayException] with a fixed message. The JWT, the access token, the response body and the file
 *   content never appear in messages, causes, logs or [toString].
 */
class AnthropicWifAuth(
    client: HttpClient,
    private val config: AnthropicWifConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val tokenUrl: String = "https://api.anthropic.com/v1/oauth/token",
    private val allowedHost: String = "api.anthropic.com",
) : AnthropicAuth {
    // Never follow redirects: the assertion must not be forwarded to another host.
    private val client: HttpClient = client.config { followRedirects = false }
    private val lock = Mutex()
    private val cached = AtomicReference<CachedToken?>(null)

    /** Digest of the last JWT sent. Guarded by [lock]. The digest is kept instead of the JWT itself. */
    private var lastSentDigest: ByteArray? = null

    private class CachedToken(val token: String, val expiresAt: Instant) {
        override fun toString(): String = "CachedToken(***)"
    }

    override fun isConfigured(target: ResolvedTarget): Boolean =
        BoundCredentialSource.endpointAllowed(target.provider.endpoint, allowedHost)

    override suspend fun authorize(target: ResolvedTarget): AnthropicAuthorization {
        if (!isConfigured(target)) {
            throw GatewayException(GatewayError(ErrorKind.AUTH, "credential is not released for this provider endpoint", code = "NO_CREDENTIAL", sendState = SendState.NOT_SENT))
        }
        val token = lock.withLock {
            val now = clock.instant()
            val c = cached.get()
            when {
                c != null && now.isBefore(c.expiresAt.minusSeconds(RECOMMENDED_REFRESH_SECONDS)) -> c
                c != null && now.isBefore(c.expiresAt.minusSeconds(REQUIRED_REFRESH_SECONDS)) ->
                    try { exchange(now, attempts = 1) } catch (_: GatewayException) { c }
                else -> exchange(now, attempts = MAX_ATTEMPTS)
            }
        }
        return AnthropicAuthorization("Authorization", "Bearer ${token.token}", listOf(token.token))
    }

    override fun onUnauthorized(authorization: AnthropicAuthorization) {
        val c = cached.get() ?: return
        if (authorization.secrets.contains(c.token)) cached.compareAndSet(c, null)
    }

    /** Runs up to [attempts] exchanges. Only called while [lock] is held. */
    private suspend fun exchange(now: Instant, attempts: Int): CachedToken {
        var last: GatewayException? = null
        // The failure of the last real exchange is reported in preference to "not rotated" waits.
        var lastSend: GatewayException? = null
        for (attempt in 0 until attempts) {
            if (attempt > 0) pause(BACKOFF_BASE_MS shl (attempt - 1))
            val assertion = try {
                readAssertion()
            } catch (e: GatewayException) {
                last = e
                continue
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(assertion.toByteArray(Charsets.UTF_8))
            if (lastSentDigest?.contentEquals(digest) == true) {
                // Same JWT as the last exchange: its jti is spent. Wait for the kubelet to rotate the file.
                last = GatewayException(GatewayError(ErrorKind.AUTH, ROTATION_PENDING, code = "NO_CREDENTIAL", sendState = SendState.NOT_SENT))
                continue
            }
            // Recorded before sending: the jti is consumed by the attempt whatever its outcome.
            lastSentDigest = digest
            try {
                val fresh = send(assertion, now)
                cached.set(fresh)
                return fresh
            } catch (e: GatewayException) {
                last = e
                lastSend = e
            }
        }
        throw lastSend ?: checkNotNull(last)
    }

    private fun readAssertion(): String {
        val raw = try {
            Files.readString(config.identityTokenFile, Charsets.UTF_8)
        } catch (_: Exception) {
            throw GatewayException(GatewayError(ErrorKind.AUTH, IDENTITY_UNREADABLE, code = "NO_CREDENTIAL", sendState = SendState.NOT_SENT))
        }
        return raw.trim().takeIf { it.isNotEmpty() }
            ?: throw GatewayException(GatewayError(ErrorKind.AUTH, IDENTITY_UNREADABLE, code = "NO_CREDENTIAL", sendState = SendState.NOT_SENT))
    }

    private suspend fun send(assertion: String, now: Instant): CachedToken {
        val body = buildJsonObject {
            put("grant_type", GRANT_TYPE)
            put("assertion", assertion)
            put("federation_rule_id", config.federationRuleId)
            put("organization_id", config.organizationId)
            put("service_account_id", config.serviceAccountId)
            config.workspaceId?.let { put("workspace_id", it) }
        }.toString()
        val resp = try {
            client.post(tokenUrl) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw GatewayException(GatewayError(ErrorKind.TRANSIENT, EXCHANGE_TRANSPORT))
        }
        val text = try { resp.bodyAsText() } catch (e: CancellationException) { throw e } catch (_: Exception) {
            throw GatewayException(GatewayError(ErrorKind.TRANSIENT, EXCHANGE_TRANSPORT))
        }
        val status = resp.status.value
        if (!resp.status.isSuccess()) {
            val kind = if (status in 400..499) ErrorKind.AUTH else ErrorKind.TRANSIENT
            throw GatewayException(GatewayError(kind, "Anthropic token exchange failed (HTTP $status)", providerStatus = status))
        }
        return parse(text, now)
    }

    /** The failure message is fixed: the response body is never copied, and the parse error is not chained. */
    private fun parse(text: String, now: Instant): CachedToken {
        val malformed = GatewayException(GatewayError(ErrorKind.TRANSIENT, EXCHANGE_MALFORMED))
        return try {
            val obj = Json.parseToJsonElement(text).jsonObject
            val token = obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw malformed
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: throw malformed
            CachedToken(token, now.plusSeconds(expiresIn))
        } catch (e: GatewayException) {
            throw e
        } catch (_: Exception) {
            throw malformed
        }
    }

    companion object {
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val RECOMMENDED_REFRESH_SECONDS = 120L
        const val REQUIRED_REFRESH_SECONDS = 30L
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_BASE_MS = 500L
        const val IDENTITY_UNREADABLE = "Anthropic identity token file could not be read"
        const val ROTATION_PENDING = "Anthropic identity token has not rotated since the last exchange"
        const val EXCHANGE_TRANSPORT = "Anthropic token exchange failed (transport)"
        const val EXCHANGE_MALFORMED = "Anthropic token exchange response is malformed"
    }
}
