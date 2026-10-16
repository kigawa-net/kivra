package net.kigawa.kivra.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.github.githubHttpClient

private const val APP_ID = "123456"
private const val INSTALLATION_ID = 987654L
private const val ACCESS_TOKEN = "ghs_TEST_ACCESS_TOKEN_MUST_NOT_LEAK"
private val NOW: Instant = Instant.parse("2026-10-16T00:00:00Z")

private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

private fun pem(label: String, der: ByteArray): String =
    "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

/** Reads one DER TLV at [offset]; returns (contentStart, contentEnd). */
private fun tlv(der: ByteArray, offset: Int): Pair<Int, Int> {
    var p = offset + 1
    val first = der[p++].toInt() and 0xFF
    val len = if (first < 0x80) first else {
        var l = 0
        repeat(first and 0x7F) { l = (l shl 8) or (der[p++].toInt() and 0xFF) }
        l
    }
    return p to p + len
}

/** PKCS#1 RSAPrivateKey is the OCTET STRING inside the PKCS#8 PrivateKeyInfo. */
private fun pkcs1Of(pkcs8: ByteArray): ByteArray {
    val (outerStart, _) = tlv(pkcs8, 0)
    var start = tlv(pkcs8, outerStart).second // skip version
    start = tlv(pkcs8, start).second // skip AlgorithmIdentifier
    val (octetStart, octetEnd) = tlv(pkcs8, start)
    return pkcs8.copyOfRange(octetStart, octetEnd)
}

private class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
}

private fun decodePart(part: String): String = String(Base64.getUrlDecoder().decode(part))

class GitHubAppTokenAuthTest {
    private val keys = newKeyPair()

    @Test
    fun `JWT carries iss, iat 60 seconds back, exp at most 9 minutes ahead, and a valid RS256 signature`() {
        val jwt = RsaJwtSigner(pem("PRIVATE KEY", keys.private.encoded)).sign(APP_ID, NOW)
        val parts = jwt.split(".")
        assertEquals(3, parts.size)
        assertEquals("""{"alg":"RS256","typ":"JWT"}""", decodePart(parts[0]))
        val claims = Json.parseToJsonElement(decodePart(parts[1])).jsonObject
        assertEquals(APP_ID, claims["iss"]!!.jsonPrimitive.content)
        val iat = claims["iat"]!!.jsonPrimitive.content.toLong()
        val exp = claims["exp"]!!.jsonPrimitive.content.toLong()
        assertEquals(NOW.epochSecond - 60, iat)
        assertTrue(exp - iat <= 10 * 60, "exp must be within 10 minutes of iat")
        assertTrue(exp > NOW.epochSecond)
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keys.public as RSAPublicKey)
            update("${parts[0]}.${parts[1]}".toByteArray())
        }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])))
    }

    @Test
    fun `PKCS#1 and PKCS#8 keys sign the same way and a PKCS#1 key verifies with the public key`() {
        val pkcs8 = keys.private.encoded
        val fromPkcs1 = RsaJwtSigner(pem("RSA PRIVATE KEY", pkcs1Of(pkcs8))).sign(APP_ID, NOW)
        val fromPkcs8 = RsaJwtSigner(pem("PRIVATE KEY", pkcs8)).sign(APP_ID, NOW)
        // RSA PKCS#1 v1.5 signatures are deterministic, so the two must match exactly.
        assertEquals(fromPkcs8, fromPkcs1)
    }

    @Test
    fun `encrypted, unknown and broken keys are refused with a fixed message that holds no key material`() {
        val body = Base64.getMimeEncoder().encodeToString(keys.private.encoded)
        for (bad in listOf(
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\n$body\n-----END ENCRYPTED PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----\n$body\n-----END EC PRIVATE KEY-----",
            "not a key at all",
            "-----BEGIN PRIVATE KEY-----\n!!!notbase64!!!\n-----END PRIVATE KEY-----",
        )) {
            val e = assertFailsWith<IllegalArgumentException> { RsaJwtSigner(bad) }
            assertFalse(body in e.message.orEmpty(), "key material in message")
        }
    }

    @Test
    fun `the token request has the right URL, App JWT, headers and fixed body`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            requests += req
            respond(
                """{"token":"$ACCESS_TOKEN","expires_at":"2026-10-16T01:00:00Z"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val minter = GitHubAppTokenMinter(githubHttpClient(engine))
        val token = minter.mint("jwt.part.sig", INSTALLATION_ID)
        assertEquals(ACCESS_TOKEN, token.token)
        assertEquals(Instant.parse("2026-10-16T01:00:00Z"), token.expiresAt)

        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://api.github.com/app/installations/$INSTALLATION_ID/access_tokens", req.url.toString())
        assertEquals("Bearer jwt.part.sig", req.headers[HttpHeaders.Authorization])
        val body = String(req.body.toByteArray())
        assertEquals("""{"repositories":["kivra"],"permissions":{"contents":"write","pull_requests":"write"}}""", body)
    }

    @Test
    fun `minter failures carry a fixed message and never the body or the token`() = runTest {
        val leakyBody = """{"token":"$ACCESS_TOKEN","message":"Bad credentials for $APP_ID"}"""
        val rejected = GitHubAppTokenMinter(githubHttpClient(MockEngine { respond(leakyBody, HttpStatusCode.Unauthorized) }))
        val e1 = assertFailsWith<GitHubTokenUnavailableException> { rejected.mint("j", INSTALLATION_ID) }
        assertEquals("GitHub App installation token request failed (HTTP 401)", e1.message)

        val malformed = GitHubAppTokenMinter(githubHttpClient(MockEngine { respond("""{"token":"$ACCESS_TOKEN"}""", HttpStatusCode.Created) }))
        val e2 = assertFailsWith<GitHubTokenUnavailableException> { malformed.mint("j", INSTALLATION_ID) }
        for (e in listOf(e1, e2)) {
            assertFalse(ACCESS_TOKEN in e.message.orEmpty())
            assertFalse(APP_ID in e.message.orEmpty())
        }
    }

    @Test
    fun `the cached token is reused until five minutes before expiry, then refreshed`() = runTest {
        val clock = MutableClock(NOW)
        var mints = 0
        val minter = InstallationTokenMinter { _, _ ->
            mints++
            InstallationToken("tok-$mints", clock.instant().plus(Duration.ofHours(1)))
        }
        val provider = InstallationTokenProvider(APP_ID, INSTALLATION_ID, JwtSigner { _, _ -> "jwt" }, minter, clock)

        assertEquals("tok-1", provider.token())
        clock.now = NOW.plus(Duration.ofMinutes(54)).plusSeconds(59)
        assertEquals("tok-1", provider.token(), "not refreshed before the margin")
        assertEquals(1, mints)
        clock.now = NOW.plus(Duration.ofMinutes(55))
        assertEquals("tok-2", provider.token(), "refreshed exactly five minutes before expiry")
        assertEquals(2, mints)
    }

    @Test
    fun `invalidate forces the next call to mint again`() = runTest {
        var mints = 0
        val clock = MutableClock(NOW)
        val provider = InstallationTokenProvider(
            APP_ID, INSTALLATION_ID, JwtSigner { _, _ -> "jwt" },
            InstallationTokenMinter { _, _ -> mints++; InstallationToken("t$mints", NOW.plus(Duration.ofHours(1))) },
            clock,
        )
        assertEquals("t1", provider.token())
        provider.invalidate()
        assertEquals("t2", provider.token())
        assertEquals(2, mints)
    }

    @Test
    fun `concurrent callers share one refresh`() = runTest {
        var mints = 0
        val provider = InstallationTokenProvider(
            APP_ID, INSTALLATION_ID, JwtSigner { _, _ -> "jwt" },
            InstallationTokenMinter { _, _ ->
                mints++
                delay(100)
                InstallationToken("shared", NOW.plus(Duration.ofHours(1)))
            },
            MutableClock(NOW),
        )
        val results = List(20) { async { provider.token() } }.awaitAll()
        assertEquals(List(20) { "shared" }, results)
        assertEquals(1, mints)
    }

    @Test
    fun `a failed refresh is not cached`() = runTest {
        var calls = 0
        val provider = InstallationTokenProvider(
            APP_ID, INSTALLATION_ID, JwtSigner { _, _ -> "jwt" },
            InstallationTokenMinter { _, _ ->
                calls++
                if (calls == 1) throw GitHubTokenUnavailableException("fixed")
                InstallationToken("ok", NOW.plus(Duration.ofHours(1)))
            },
            MutableClock(NOW),
        )
        assertFailsWith<GitHubTokenUnavailableException> { provider.token() }
        assertEquals("ok", provider.token())
    }

    @Test
    fun `provider toString never shows the token`() {
        assertFalse(ACCESS_TOKEN in InstallationToken(ACCESS_TOKEN, NOW).toString())
        assertFalse(ACCESS_TOKEN in StaticTokenProvider(ACCESS_TOKEN).toString())
    }

    @Test
    fun `adapter re-mints once on 401 and never loops`() = runTest {
        var mints = 0
        val provider = InstallationTokenProvider(
            APP_ID, INSTALLATION_ID, JwtSigner { _, _ -> "jwt" },
            InstallationTokenMinter { _, _ -> mints++; InstallationToken("tok$mints", NOW.plus(Duration.ofHours(1))) },
            MutableClock(NOW),
        )
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += req.headers[HttpHeaders.Authorization].orEmpty()
            respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = HttpGitHubAdapter(githubHttpClient(engine), provider, backoff = {})
        val r: GitHubResult<PullRequest> = adapter.getPullRequest(RepoRef("kigawa-net", "kivra"), 7)
        assertEquals(GitHubFailureKind.AUTH, assertIs<GitHubResult.Failure>(r).kind)
        assertEquals(listOf("Bearer tok1", "Bearer tok2"), seen)
        assertEquals(2, mints)
    }
}
