package net.kigawa.kivra.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwk.UrlJwkProvider
import java.net.URI
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * A lookup failure whose [reasonCode] is a fixed name ([REASON_UNAVAILABLE] or [REASON_GRACE_EXPIRED]). It is a
 * [SigningKeyNotFoundException], so the verifier rejects it (401) like any other missing key. The verifier logs
 * [reasonCode] instead of the class name. The message is never a URL or a server response.
 */
class JwksLookupException(val reasonCode: String) : SigningKeyNotFoundException(reasonCode, null)

/**
 * JWKS cache for [OidcJwtVerifier]. Every fetch goes through [fetch], with a monotonic [clock] in milliseconds (injectable
 * for tests).
 *
 * - Fresh: the keys are cached for [cacheTtlMillis] and are not fetched on every verification.
 * - Stale grace: when a refresh fails after the TTL, the last successful keys stay usable until [staleGraceMillis] after the
 *   TTL has run out (the stale window is measured from the last successful fetch). After that every lookup fails with
 *   [REASON_GRACE_EXPIRED] (fail closed).
 * - Unknown kid: a key id that is not in the cache triggers a re-fetch. A key id that is not in the cache is never
 *   accepted while the re-fetch fails.
 * - Backoff: every attempt (TTL path or unknown-kid path) is at most one per [minRefreshIntervalMillis]. A failed attempt
 *   therefore does not cause a network call on the next request. A successful one replaces the whole key set (rotation).
 * - Single flight: a fetch runs under [refreshLock]. Concurrent callers wait for it and then read the result (a waiter
 *   that finds its kid after the wait does not fetch). The fetch is blocking, so callers must run on an IO dispatcher.
 * - With no keys at all, [REASON_UNAVAILABLE] is thrown.
 */
class RefreshingJwkProvider(
    private val jwksUrl: String,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val minRefreshIntervalMillis: Long = DEFAULT_MIN_REFRESH_INTERVAL_MILLIS,
    private val staleGraceMillis: Long = DEFAULT_STALE_GRACE_MILLIS,
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val fetch: () -> List<Jwk> = { fetchJwks(jwksUrl, connectTimeoutMillis) },
) : JwkProvider {

    private data class CachedKeys(val keys: List<Jwk>, val fetchedAt: Long)

    private val logger = LoggerFactory.getLogger(RefreshingJwkProvider::class.java)

    @Volatile
    private var cached: CachedKeys? = null

    /** Time of the last fetch attempt (success or failure). Guarded by [refreshLock]. Null before the first attempt. */
    private var lastAttemptAt: Long? = null

    private val refreshLock = Any()

    override fun get(keyId: String?): Jwk {
        val current = cached
        if (current == null || clock() - current.fetchedAt >= cacheTtlMillis) refreshForTtl()
        val snapshot = usableKeys()
        findByKid(snapshot.keys, keyId)?.let { return it }

        refreshForUnknownKid(keyId)
        findByKid(usableKeys().keys, keyId)?.let { return it }
        throw SigningKeyNotFoundException("no matching key in JWKS", null)
    }

    /** The cached keys, if they may still be used now. Throws [JwksLookupException] otherwise. */
    private fun usableKeys(): CachedKeys {
        val snapshot = cached ?: throw JwksLookupException(REASON_UNAVAILABLE)
        if (clock() - snapshot.fetchedAt >= cacheTtlMillis + staleGraceMillis) throw JwksLookupException(REASON_GRACE_EXPIRED)
        return snapshot
    }

    /** Matches on kid. A token without kid matches only when the JWKS has exactly one key. */
    private fun findByKid(keys: List<Jwk>, keyId: String?): Jwk? = when {
        keyId != null -> keys.firstOrNull { it.id == keyId }
        keys.size == 1 -> keys.first()
        else -> null
    }

    /** TTL path: fetches when the cache is missing or expired. Another thread may already have refreshed it. */
    private fun refreshForTtl() {
        synchronized(refreshLock) {
            val now = clock()
            val current = cached
            if (current != null && now - current.fetchedAt < cacheTtlMillis) return
            tryFetch(now)
        }
    }

    /** Unknown-kid path: fetches unless the kid is already cached (another thread fetched it while this one waited). */
    private fun refreshForUnknownKid(keyId: String?) {
        synchronized(refreshLock) {
            val now = clock()
            val current = cached
            if (current != null && findByKid(current.keys, keyId) != null) return
            tryFetch(now)
        }
    }

    /**
     * One fetch attempt, unless the last attempt was less than [minRefreshIntervalMillis] ago. On success the whole key set
     * is replaced. Call only under [refreshLock].
     */
    private fun tryFetch(now: Long) {
        val last = lastAttemptAt
        if (last != null && now - last < minRefreshIntervalMillis) return
        lastAttemptAt = now
        try {
            cached = CachedKeys(fetch(), now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: the message may contain the URL.
            logger.warn("JWKS fetch failed; keeping the previous keys if still usable: {}", e.javaClass.simpleName)
        }
    }

    companion object {
        const val REASON_UNAVAILABLE = "JwksUnavailable"
        const val REASON_GRACE_EXPIRED = "JwksGraceExpired"

        const val DEFAULT_CACHE_TTL_MILLIS = 10 * 60 * 1000L
        const val DEFAULT_MIN_REFRESH_INTERVAL_MILLIS = 30 * 1000L

        /** How long the last successful keys stay usable after the TTL, while refreshes fail. */
        const val DEFAULT_STALE_GRACE_MILLIS = 10 * 60 * 1000L
        const val DEFAULT_TIMEOUT_MILLIS = 5_000

        /** Plain GET of the JWKS with the system trust store. */
        internal fun fetchJwks(jwksUrl: String, timeoutMillis: Int): List<Jwk> =
            UrlJwkProvider(URI(jwksUrl).toURL(), timeoutMillis, timeoutMillis, null, emptyMap()).getAll()
    }
}
