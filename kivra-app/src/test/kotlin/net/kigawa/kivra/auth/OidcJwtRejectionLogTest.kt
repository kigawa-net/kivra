package net.kigawa.kivra.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.auth.OidcTestTokens.token
import org.slf4j.LoggerFactory

/**
 * A rejected token must leave a trace that says why (expired, wrong audience, missing role, ...), at the default production
 * level, because otherwise a 401 cannot be diagnosed. The trace holds a fixed reason or an exception class name only: never
 * the token, a claim value or an exception message. The same reason is logged at most once per minute.
 */
class OidcJwtRejectionLogTest {
    private val logger = LoggerFactory.getLogger(OidcJwtVerifier::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var previousLevel: Level

    @BeforeTest
    fun attach() {
        previousLevel = logger.level ?: Level.INFO
        logger.level = Level.INFO
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }

    private fun verify(token: String): String? = runBlocking {
        OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())
            .principal(headersOf(HttpHeaders.Authorization, "Bearer $token"))
    }

    private fun warnings(): List<String> = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun `an expired token is logged at WARN with the exception class only`() {
        val expired = token(expiresInSeconds = -3600)
        assertNull(verify(expired))
        val lines = warnings()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("reason=TokenExpiredException"), lines.single())
    }

    @Test
    fun `a missing required role is logged with a fixed reason`() {
        assertNull(verify(token(roles = listOf("viewer"))))
        assertTrue(warnings().single().contains("reason=RequiredRoleMissing"))
    }

    @Test
    fun `the log never contains the token, a claim value or the exception message`() {
        val expired = token(expiresInSeconds = -3600, email = "alice-secret@example.test")
        assertNull(verify(expired))
        val all = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        assertFalse(all.contains(expired), "token must not be logged")
        assertFalse(all.contains(expired.substringBefore('.')), "token header must not be logged")
        assertFalse(all.contains("alice-secret"), "claim value must not be logged")
        assertFalse(all.contains("expired at"), "exception message must not be logged")
        assertTrue(appender.list.all { it.throwableProxy == null }, "no stack trace")
    }

    @Test
    fun `the same reason is logged once per minute, a different reason is logged separately`() {
        val verifier = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())
        fun run(token: String) = runBlocking {
            verifier.principal(headersOf(HttpHeaders.Authorization, "Bearer $token"))
        }
        repeat(20) { assertNull(run(token(expiresInSeconds = -3600))) }
        assertEquals(1, warnings().size, "flood of the same reason is collapsed")

        assertNull(run(token(roles = listOf("viewer"))))
        assertEquals(2, warnings().size, "another reason is logged")
    }

    @Test
    fun `concurrent requests with the same rejection reason yield only one warning`() = runBlocking {
        val verifier = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())
        val expired = token(expiresInSeconds = -3600)
        val headers = headersOf(HttpHeaders.Authorization, "Bearer $expired")
        coroutineScope {
            (1..64).map {
                async(Dispatchers.Default) { assertNull(verifier.principal(headers)) }
            }.awaitAll()
        }
        assertEquals(1, warnings().size, "throttle must be atomic under concurrent JWT failures")
    }

    // One verifier for the whole test, like production (one instance is created at startup and shared by every request).
    private val shared = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())

    private fun principalOf(vararg headers: Pair<String, String>): String? = runBlocking {
        shared.principal(headersOf(*headers.map { it.first to listOf(it.second) }.toTypedArray()))
    }

    @Test
    fun `a request without any credential is logged once with a fixed reason, never the header value`() {
        assertNull(principalOf())
        assertEquals(1, warnings().size)
        assertTrue(warnings().single().contains("reason=NoAuthorizationAndNoForwardedToken"), warnings().single())
        // a flood of unauthenticated requests collapses into that one line
        repeat(50) { principalOf() }
        assertEquals(1, warnings().size, "scanning must not fill the log")
    }

    @Test
    fun `missing-token reasons are named without leaking the header value`() {
        assertNull(principalOf("Authorization" to "Basic dXNlcjpzZWNyZXQtcGFzc3dvcmQ="))
        assertNull(principalOf("X-Forwarded-Access-Token" to "not-a-jwt-secret-value"))
        val all = warnings().joinToString("\n")
        assertTrue(all.contains("reason=AuthorizationNotBearer"), all)
        assertTrue(all.contains("reason=TokenNotJwtShaped"), all)
        assertFalse(all.contains("dXNlcjpzZWNyZXQ"), "Authorization value must not be logged")
        assertFalse(all.contains("not-a-jwt-secret-value"), "forwarded token value must not be logged")
    }
}
