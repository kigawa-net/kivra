package net.kigawa.kivra.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwk.Jwk
import com.auth0.jwk.SigningKeyNotFoundException
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.auth.OidcTestTokens.KID
import net.kigawa.kivra.auth.OidcTestTokens.token
import org.slf4j.LoggerFactory

class RefreshingJwkProviderTest {
    private val keys: List<Jwk> = listOf(OidcTestTokens.jwkOf(KID, OidcTestTokens.signingKey))

    /** Monotonic test clock in milliseconds. */
    private class Clock(var now: Long = 1_000_000L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    /** In-memory JWKS endpoint: counts fetches, can be made to fail, and can hold a fetch open for the concurrency test. */
    private class Upstream(var keys: List<Jwk>) {
        val fetches = AtomicInteger()
        @Volatile var fail = false
        @Volatile var onFetch: (Int) -> Unit = {}

        fun fetch(): List<Jwk> {
            val n = fetches.incrementAndGet()
            onFetch(n)
            if (fail) throw IOException("connect failed: https://idp.example.test/certs?secret=abc")
            return keys
        }
    }

    private val ttl = RefreshingJwkProvider.DEFAULT_CACHE_TTL_MILLIS
    private val grace = RefreshingJwkProvider.DEFAULT_STALE_GRACE_MILLIS
    private val backoff = RefreshingJwkProvider.DEFAULT_MIN_REFRESH_INTERVAL_MILLIS

    private fun provider(clock: Clock, upstream: Upstream, minRefreshIntervalMillis: Long = backoff) =
        RefreshingJwkProvider(
            jwksUrl = "unused",
            cacheTtlMillis = ttl,
            minRefreshIntervalMillis = minRefreshIntervalMillis,
            staleGraceMillis = grace,
            clock = { clock.now },
            fetch = { upstream.fetch() },
        )

    private fun verifier(provider: RefreshingJwkProvider) = OidcJwtVerifier(OidcTestTokens.config, provider)

    private fun principal(verifier: OidcJwtVerifier, token: String): String? = runBlocking {
        verifier.principal(headersOf(HttpHeaders.Authorization, "Bearer $token"))
    }

    @Test
    fun `keys are cached between lookups`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", fetch = { fetches++; keys })
        assertEquals(KID, provider.get(KID).id)
        assertEquals(KID, provider.get(KID).id)
        assertEquals(1, fetches)
    }

    @Test
    fun `unknown kids do not cause a fetch per request`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", minRefreshIntervalMillis = 60_000, fetch = { fetches++; keys })
        provider.get(KID)
        repeat(20) { assertFailsWith<SigningKeyNotFoundException> { provider.get("forged-$it") } }
        assertEquals(1, fetches)
    }

    @Test
    fun `an unknown kid is re-fetched once the interval has passed (key rotation)`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", minRefreshIntervalMillis = 0, fetch = { fetches++; keys })
        provider.get(KID)
        assertFailsWith<SigningKeyNotFoundException> { provider.get("rotated-away") }
        assertEquals(2, fetches)
    }

    @Test
    fun `a failing fetch with nothing cached is a lookup failure and does not retry on every request`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", minRefreshIntervalMillis = 60_000, fetch = { fetches++; throw IOException("down") })
        repeat(5) { assertFailsWith<SigningKeyNotFoundException> { provider.get(KID) } }
        assertEquals(1, fetches)
    }

    @Test
    fun `TTL expiry during an outage uses the cached keys within the grace and backs off between retries`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        val verifier = verifier(provider)
        assertEquals(KID, provider.get(KID).id)
        assertEquals(1, upstream.fetches.get())

        upstream.fail = true
        clock.advance(ttl) // TTL expired, the refresh fails, the stale keys are used
        assertNotNull(principal(verifier, OidcTestTokens.token()))
        assertEquals(2, upstream.fetches.get())

        clock.advance(1_000) // inside the backoff: no network, still served from the stale keys
        assertNotNull(principal(verifier, OidcTestTokens.token()))
        assertEquals(KID, provider.get(KID).id)
        assertEquals(2, upstream.fetches.get())

        clock.advance(backoff) // the backoff has passed: one more attempt
        assertNotNull(principal(verifier, OidcTestTokens.token()))
        assertEquals(3, upstream.fetches.get())
    }

    @Test
    fun `a stale key does not verify a token signed with another key`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        val verifier = verifier(provider)
        assertNotNull(principal(verifier, OidcTestTokens.token()))

        upstream.fail = true
        clock.advance(ttl)
        assertNull(principal(verifier, OidcTestTokens.token(key = OidcTestTokens.otherKey)))
    }

    @Test
    fun `after the grace period every lookup fails closed`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        val verifier = verifier(provider)
        assertNotNull(principal(verifier, OidcTestTokens.token()))

        upstream.fail = true
        clock.advance(ttl + grace) // the stale window is over
        val lookup = assertFailsWith<JwksLookupException> { provider.get(KID) }
        assertEquals(RefreshingJwkProvider.REASON_GRACE_EXPIRED, lookup.reasonCode)
        assertNull(principal(verifier, OidcTestTokens.token()))
    }

    @Test
    fun `an unknown kid is rejected while the re-fetch fails, and is not re-fetched within the backoff`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        val verifier = verifier(provider)
        assertNotNull(principal(verifier, OidcTestTokens.token()))

        upstream.fail = true
        clock.advance(backoff + 1) // past the interval, so the unknown kid triggers an attempt
        assertNull(principal(verifier, OidcTestTokens.token(kid = "rotated-kid")))
        assertEquals(2, upstream.fetches.get())

        repeat(20) {
            clock.advance(100)
            assertNull(principal(verifier, OidcTestTokens.token(kid = "forged-$it")))
        }
        assertEquals(2, upstream.fetches.get(), "no network call within the backoff")
        // the known kid still works from the cache, within the grace
        assertNotNull(principal(verifier, OidcTestTokens.token()))
    }

    @Test
    fun `concurrent requests during TTL expiry share one fetch`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        provider.get(KID)
        clock.advance(ttl)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        upstream.onFetch = { n ->
            if (n == 2) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test timed out" }
            }
        }
        runConcurrently(entered, release, 20) { provider.get(KID).id }.forEach { assertEquals(KID, it) }
        assertEquals(2, upstream.fetches.get())
    }

    @Test
    fun `concurrent requests with the same unknown kid share one fetch`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        provider.get(KID)
        clock.advance(backoff + 1)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        upstream.onFetch = { n ->
            if (n == 2) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test timed out" }
            }
        }
        val results = runConcurrently(entered, release, 20) {
            runCatching { provider.get("rotated-kid") }.exceptionOrNull()
        }
        results.forEach { assertTrue(it is SigningKeyNotFoundException) }
        assertEquals(2, upstream.fetches.get())
    }

    @Test
    fun `recovery returns to normal and the backoff does not carry over`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        provider.get(KID)

        upstream.fail = true
        clock.advance(ttl)
        assertEquals(KID, provider.get(KID).id) // stale, failed attempt (fetch 2)
        assertEquals(2, upstream.fetches.get())

        upstream.fail = false
        clock.advance(backoff)
        assertEquals(KID, provider.get(KID).id) // recovered (fetch 3)
        assertEquals(3, upstream.fetches.get())

        // a fresh TTL cycle starts at once: the earlier failure does not delay the next refresh
        upstream.fail = true
        clock.advance(ttl)
        assertEquals(KID, provider.get(KID).id)
        assertEquals(4, upstream.fetches.get())
    }

    @Test
    fun `rotation replaces the key set and the old kid stops working`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        val verifier = verifier(provider)
        assertNotNull(principal(verifier, OidcTestTokens.token()))

        val rotated = listOf(OidcTestTokens.jwkOf("rotated-kid", OidcTestTokens.otherKey))
        upstream.keys = rotated
        clock.advance(ttl) // TTL refresh picks up the new key set
        assertEquals("rotated-kid", provider.get("rotated-kid").id)
        assertNotNull(principal(verifier, OidcTestTokens.token(key = OidcTestTokens.otherKey, kid = "rotated-kid")))

        val fetchesBefore = upstream.fetches.get()
        assertNull(principal(verifier, OidcTestTokens.token()), "old kid is gone from the new set")
        assertEquals(fetchesBefore, upstream.fetches.get(), "the old kid does not cause a fetch within the backoff")
    }

    @Test
    fun `rejections log a fixed reason and never the token, claims or the fetch error`() {
        val clock = Clock()
        val upstream = Upstream(keys)
        val provider = provider(clock, upstream)
        val verifier = verifier(provider)
        assertNotNull(principal(verifier, OidcTestTokens.token()))

        upstream.fail = true
        clock.advance(ttl + grace)
        val secretToken = OidcTestTokens.token(email = "alice-secret@example.test")
        val logs = captureLogs {
            assertNull(principal(verifier, secretToken))
        }
        val all = logs.joinToString("\n")
        assertTrue(all.contains("reason=JwksGraceExpired"), all)
        assertFalse(all.contains(secretToken), "token must not be logged")
        assertFalse(all.contains("alice-secret"), "claim value must not be logged")
        assertFalse(all.contains("idp.example.test"), "URL must not be logged")
        assertFalse(all.contains("secret=abc"), "fetch error message must not be logged")
        assertFalse(all.contains("connect failed"), "exception message must not be logged")
    }

    /**
     * Starts [threads] calls to [block] on a pool. Waits until the first is inside a held fetch ([entered]), then releases
     * it ([release]). Every wait is bounded, so a bug fails the test instead of hanging it.
     */
    private fun <T> runConcurrently(
        entered: CountDownLatch,
        release: CountDownLatch,
        threads: Int,
        block: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures = (1..threads).map {
                pool.submit(Callable<T> {
                    check(start.await(5, TimeUnit.SECONDS)) { "start timed out" }
                    block()
                })
            }
            start.countDown()
            check(entered.await(5, TimeUnit.SECONDS)) { "the first fetch never started" }
            release.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun captureLogs(block: () -> Unit): List<String> {
        val loggers = listOf(
            LoggerFactory.getLogger(RefreshingJwkProvider::class.java) as Logger,
            LoggerFactory.getLogger(OidcJwtVerifier::class.java) as Logger,
        )
        val appender = ListAppender<ILoggingEvent>()
        val previous = loggers.map { it.level }
        appender.start()
        loggers.forEach {
            it.level = Level.INFO
            it.addAppender(appender)
        }
        try {
            block()
        } finally {
            loggers.forEach { it.detachAppender(appender) }
            loggers.forEachIndexed { i, l -> l.level = previous[i] }
            appender.stop()
        }
        return appender.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
    }
}
