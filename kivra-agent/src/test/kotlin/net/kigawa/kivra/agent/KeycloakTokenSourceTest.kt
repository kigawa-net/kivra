package net.kigawa.kivra.agent

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class KeycloakTokenSourceTest {
    private val start: Instant = Instant.parse("2026-10-17T00:00:00Z")

    private fun tokenHttp(recorded: MutableList<Recorded>, reply: (Int) -> Canned): io.ktor.client.HttpClient =
        fakeKivraHttp(recorded) { rec -> reply(recorded.size) }

    private fun okToken(n: Int) = Canned(200, """{"access_token":"jwt-$n","expires_in":300,"token_type":"Bearer"}""")

    private fun secretFile(content: String = "client-secret-value-XYZ") =
        Files.createTempFile("kivra-agent-secret", ".txt").writeSecret(content)

    @Test
    fun cachesUntilJustBeforeExpiryThenFetchesAFreshToken() = runBlocking {
        val recorded = synchronizedList()
        val clock = MutableClock(start)
        val source = KeycloakTokenSource(tokenHttp(recorded) { n -> okToken(n) }, TOKEN_URL, "kivra-agent-test", secretFile(), clock)

        assertEquals("jwt-1", source.token().reveal())
        clock.now = start.plusSeconds(100)
        assertEquals("jwt-1", source.token().reveal(), "still cached well before expiry")
        assertEquals(1, recorded.size)

        // expires_in 300 with a 60 s skew: the cache ends at 240 s.
        clock.now = start.plusSeconds(241)
        assertEquals("jwt-2", source.token().reveal(), "a new token is fetched just before expiry")
        assertEquals(2, recorded.size)
    }

    @Test
    fun everyFetchIsANewTokenNotAReuseOfAnOldOne() = runBlocking {
        val recorded = synchronizedList()
        val source = KeycloakTokenSource(tokenHttp(recorded) { n -> okToken(n) }, TOKEN_URL, "kivra-agent-test", secretFile())
        val first = source.token().reveal()
        source.invalidate()
        val second = source.token().reveal()
        assertNotEquals(first, second)
        assertEquals(2, recorded.size)
    }

    @Test
    fun clientCredentialsAreSentOnlyInTheFormBody() = runBlocking {
        val recorded = synchronizedList()
        val source = KeycloakTokenSource(tokenHttp(recorded) { n -> okToken(n) }, TOKEN_URL, "kivra-agent-test", secretFile("S3CR3T"))
        source.token()
        val request = recorded.single()
        assertTrue(request.body.contains("grant_type=client_credentials"))
        assertTrue(request.body.contains("client_id=kivra-agent-test"))
        assertTrue(request.body.contains("client_secret=S3CR3T"))
        assertEquals(emptyList(), request.authorization, "no Authorization header on the token request")
    }

    @Test
    fun rejectedSecretIsTokenRejected() = runBlocking {
        val source = KeycloakTokenSource(tokenHttp(synchronizedList()) { Canned(401) }, TOKEN_URL, "kivra-agent-test", secretFile())
        assertEquals(AgentApiErrorKind.TOKEN_REJECTED, assertFailsWith<AgentApiException> { source.token() }.kind)
    }

    @Test
    fun serverErrorIsTokenUnavailableAndBadBodyIsMalformed() = runBlocking {
        val down = KeycloakTokenSource(tokenHttp(synchronizedList()) { Canned(503) }, TOKEN_URL, "kivra-agent-test", secretFile())
        assertEquals(AgentApiErrorKind.TOKEN_UNAVAILABLE, assertFailsWith<AgentApiException> { down.token() }.kind)

        val noExpiry = KeycloakTokenSource(
            tokenHttp(synchronizedList()) { Canned(200, """{"access_token":"x"}""") }, TOKEN_URL, "kivra-agent-test", secretFile(),
        )
        assertEquals(AgentApiErrorKind.MALFORMED_RESPONSE, assertFailsWith<AgentApiException> { noExpiry.token() }.kind)
    }

    @Test
    fun missingOrEmptySecretFileIsSecretUnreadable() = runBlocking {
        val missing = KeycloakTokenSource(
            tokenHttp(synchronizedList()) { n -> okToken(n) }, TOKEN_URL, "kivra-agent-test",
            Files.createTempDirectory("kivra-agent-none").resolve("absent"),
        )
        assertEquals(AgentApiErrorKind.SECRET_UNREADABLE, assertFailsWith<AgentApiException> { missing.token() }.kind)

        val empty = KeycloakTokenSource(tokenHttp(synchronizedList()) { n -> okToken(n) }, TOKEN_URL, "kivra-agent-test", secretFile("  \n"))
        assertEquals(AgentApiErrorKind.SECRET_UNREADABLE, assertFailsWith<AgentApiException> { empty.token() }.kind)
    }

    @Test
    fun secretIsReadAgainSoARotatedFileIsUsed() = runBlocking {
        val recorded = synchronizedList()
        val file = secretFile("old-secret")
        val source = KeycloakTokenSource(tokenHttp(recorded) { n -> okToken(n) }, TOKEN_URL, "kivra-agent-test", file)
        source.token()
        Files.writeString(file, "new-secret")
        source.invalidate()
        source.token()
        assertTrue(recorded[0].body.contains("old-secret"))
        assertTrue(recorded[1].body.contains("new-secret"))
    }

    @Test
    fun shortLivedTokenIsNotCached() = runBlocking {
        val recorded = synchronizedList()
        val clock = MutableClock(start)
        val shortLived = { n: Int -> Canned(200, """{"access_token":"jwt-$n","expires_in":30}""") }
        val source = KeycloakTokenSource(tokenHttp(recorded) { n -> shortLived(n) }, TOKEN_URL, "kivra-agent-test", secretFile(), clock, Duration.ofSeconds(60))
        source.token()
        source.token()
        assertEquals(2, recorded.size, "a token that expires inside the skew is fetched on every call")
    }
}
