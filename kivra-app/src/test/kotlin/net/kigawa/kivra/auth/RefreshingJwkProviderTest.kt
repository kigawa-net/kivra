package net.kigawa.kivra.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.SigningKeyNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefreshingJwkProviderTest {
    private val keys: List<Jwk> = listOf(OidcTestTokens.jwkOf("kid-1", OidcTestTokens.signingKey))

    @Test
    fun `keys are cached between lookups`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", fetch = { fetches++; keys })
        assertEquals("kid-1", provider.get("kid-1").id)
        assertEquals("kid-1", provider.get("kid-1").id)
        assertEquals(1, fetches)
    }

    @Test
    fun `unknown kids do not cause a fetch per request`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", minRefreshIntervalMillis = 60_000, fetch = { fetches++; keys })
        provider.get("kid-1")
        repeat(20) { assertFailsWith<SigningKeyNotFoundException> { provider.get("forged-$it") } }
        assertEquals(1, fetches)
    }

    @Test
    fun `an unknown kid is re-fetched once the interval has passed (key rotation)`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", minRefreshIntervalMillis = 0, fetch = { fetches++; keys })
        provider.get("kid-1")
        assertFailsWith<SigningKeyNotFoundException> { provider.get("rotated-away") }
        assertEquals(2, fetches)
    }

    @Test
    fun `a failing fetch with nothing cached is a lookup failure and does not retry on every request`() {
        var fetches = 0
        val provider = RefreshingJwkProvider("unused", minRefreshIntervalMillis = 60_000, fetch = { fetches++; throw java.io.IOException("down") })
        repeat(5) { assertFailsWith<SigningKeyNotFoundException> { provider.get("kid-1") } }
        assertEquals(1, fetches)
    }
}
