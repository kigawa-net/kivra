package net.kigawa.kivra.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwk.UrlJwkProvider
import java.net.URI
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Read-only JWKS cache for [OidcJwtVerifier].
 *
 * - The keys are cached for [cacheTtlMillis] and not fetched on every verification.
 * - A key id that is not cached triggers a re-fetch, but at most once per [minRefreshIntervalMillis]. This stops forged
 *   tokens with random kids from causing a fetch per request.
 * - If a re-fetch fails, the previous keys are kept. With no keys at all, [SigningKeyNotFoundException] is thrown, which
 *   the verifier turns into a 401.
 */
class RefreshingJwkProvider(
    private val jwksUrl: String,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val minRefreshIntervalMillis: Long = DEFAULT_MIN_REFRESH_INTERVAL_MILLIS,
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val fetch: () -> List<Jwk> = { fetchJwks(jwksUrl, connectTimeoutMillis) },
) : JwkProvider {

    private data class CachedKeys(val keys: List<Jwk>, val fetchedAt: Long)

    private val logger = LoggerFactory.getLogger(RefreshingJwkProvider::class.java)

    @Volatile
    private var cached: CachedKeys? = null

    @Volatile
    private var lastAttemptAt: Long = 0L

    private val refreshLock = Any()

    override fun get(keyId: String?): Jwk {
        val now = System.currentTimeMillis()
        val current = cached
        if (current == null || now - current.fetchedAt >= cacheTtlMillis) refreshByTtl()
        val snapshot = cached ?: throw SigningKeyNotFoundException("JWKS is unavailable", null)
        findByKid(snapshot.keys, keyId)?.let { return it }

        if (refreshQuietly()) findByKid(cached?.keys.orEmpty(), keyId)?.let { return it }
        throw SigningKeyNotFoundException("no matching key in JWKS", null)
    }

    /** Matches on kid. A token without kid matches only when the JWKS has exactly one key. */
    private fun findByKid(keys: List<Jwk>, keyId: String?): Jwk? = when {
        keyId != null -> keys.firstOrNull { it.id == keyId }
        keys.size == 1 -> keys.first()
        else -> null
    }

    private fun refreshByTtl() {
        synchronized(refreshLock) {
            val now = System.currentTimeMillis()
            val current = cached
            if (current != null && now - current.fetchedAt < cacheTtlMillis) return
            // Nothing cached yet and the last attempt failed recently: do not fetch on every request.
            if (current == null && now - lastAttemptAt < minRefreshIntervalMillis) return
            lastAttemptAt = now
            try {
                cached = CachedKeys(fetch(), now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("JWKS fetch failed; keeping the previous keys: {}", e.javaClass.simpleName)
            }
        }
    }

    /** Re-fetches regardless of the TTL, but no more often than [minRefreshIntervalMillis]. True when it fetched. */
    private fun refreshQuietly(): Boolean {
        synchronized(refreshLock) {
            val now = System.currentTimeMillis()
            if (now - lastAttemptAt < minRefreshIntervalMillis) return false
            lastAttemptAt = now
            return try {
                cached = CachedKeys(fetch(), now)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("JWKS re-fetch failed: {}", e.javaClass.simpleName)
                false
            }
        }
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS = 10 * 60 * 1000L
        const val DEFAULT_MIN_REFRESH_INTERVAL_MILLIS = 30 * 1000L
        const val DEFAULT_TIMEOUT_MILLIS = 5_000

        /** Plain GET of the JWKS with the system trust store. */
        internal fun fetchJwks(jwksUrl: String, timeoutMillis: Int): List<Jwk> =
            UrlJwkProvider(URI(jwksUrl).toURL(), timeoutMillis, timeoutMillis, null, emptyMap()).getAll()
    }
}
