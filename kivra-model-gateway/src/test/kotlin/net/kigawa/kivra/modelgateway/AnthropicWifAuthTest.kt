package net.kigawa.kivra.modelgateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val JWT_A = "eyJhbGciOiJSUzI1NiJ9.PAYLOAD_A_MUST_NOT_LEAK.SIG_A"
private const val JWT_B = "eyJhbGciOiJSUzI1NiJ9.PAYLOAD_B_MUST_NOT_LEAK.SIG_B"
private const val JWT_C = "eyJhbGciOiJSUzI1NiJ9.PAYLOAD_C_MUST_NOT_LEAK.SIG_C"
private const val TOKEN_1 = "sk-ant-oat01-TOKEN_ONE_MUST_NOT_LEAK"
private const val TOKEN_2 = "sk-ant-oat01-TOKEN_TWO_MUST_NOT_LEAK"
private const val TOKEN_URL = "https://api.anthropic.com/v1/oauth/token"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private const val MESSAGE_OK = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
private val t0: Instant = Instant.parse("2026-10-16T00:00:00Z")

private class FakeClock(var at: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = at
}

private fun tokenJson(token: String, expiresIn: Long = 3600) =
    """{"access_token":"$token","token_type":"Bearer","expires_in":$expiresIn,"scope":"workspace:inference"}"""

private fun bodyOf(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
private fun assertionOf(r: HttpRequestData) = bodyOf(r)["assertion"]!!.jsonPrimitive.content

private fun wifTarget(endpoint: String = "https://api.anthropic.com") = ResolvedTarget(
    provider("anthropic").copy(endpoint = endpoint),
    model("claude-test", "anthropic"),
)

private fun config(file: Path, workspace: String? = null) = AnthropicWifConfig(
    federationRuleId = "fdrl_test", organizationId = "11111111-2222-3333-4444-555555555555",
    serviceAccountId = "svac_test", workspaceId = workspace, identityTokenFile = file,
)

private fun newFile(content: String): Path =
    Files.createTempFile("wif-jwt", ".token").also { it.toFile().deleteOnExit(); Files.writeString(it, content) }

/** A scripted token endpoint: [exchange] answers each exchange call; the messages endpoint answers 200 unless overridden. */
private class Harness(
    val file: Path,
    val clock: FakeClock = FakeClock(t0),
    val pauses: MutableList<Long> = mutableListOf(),
    val exchange: MockRequestHandleScope.(HttpRequestData, Int) -> HttpResponseData,
) {
    val exchanges = mutableListOf<HttpRequestData>()
    val messages = mutableListOf<HttpRequestData>()
    val engine = MockEngine { req ->
        when (req.url.encodedPath) {
            "/v1/oauth/token" -> { exchanges.add(req); exchange(req, exchanges.size) }
            "/v1/messages" -> { messages.add(req); respond(MESSAGE_OK, HttpStatusCode.OK, jsonHeaders) }
            else -> error("unexpected url path")
        }
    }
    val auth = AnthropicWifAuth(
        HttpClient(engine), config(file), clock,
        pause = { pauses.add(it) },
    )
    val provider = AnthropicProvider(HttpClient(engine), auth, "anthropic")
}

class AnthropicWifAuthTest {
    private fun MockRequestHandleScope.tokenOk(token: String, expiresIn: Long = 3600): HttpResponseData =
        respond(tokenJson(token, expiresIn), HttpStatusCode.OK, jsonHeaders)

    @Test fun exchangeRequestHasJwtBearerGrantAndIds() = runTest {
        val h = Harness(newFile(JWT_A)) { _, _ -> tokenOk(TOKEN_1) }
        h.auth.authorize(wifTarget())
        val r = h.exchanges.single()
        assertEquals(TOKEN_URL, r.url.toString())
        assertEquals("application/json", r.body.contentType?.toString()?.substringBefore(";"))
        val b = bodyOf(r)
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", b["grant_type"]!!.jsonPrimitive.content)
        assertEquals(JWT_A, b["assertion"]!!.jsonPrimitive.content)
        assertEquals("fdrl_test", b["federation_rule_id"]!!.jsonPrimitive.content)
        assertEquals("11111111-2222-3333-4444-555555555555", b["organization_id"]!!.jsonPrimitive.content)
        assertEquals("svac_test", b["service_account_id"]!!.jsonPrimitive.content)
        assertNull(b["workspace_id"], "workspace_id is optional and omitted when unset")
    }

    @Test fun workspaceIdIsSentWhenConfigured() = runTest {
        val file = newFile(JWT_A)
        val exchanges = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req -> exchanges.add(req); tokenOk(TOKEN_1) }
        AnthropicWifAuth(HttpClient(engine), config(file, workspace = "wrkspc_test"), FakeClock(t0)).authorize(wifTarget())
        assertEquals("wrkspc_test", bodyOf(exchanges.single())["workspace_id"]!!.jsonPrimitive.content)
    }

    @Test fun messagesUseBearerAndNeverXApiKey() = runTest {
        val h = Harness(newFile(JWT_A)) { _, _ -> tokenOk(TOKEN_1) }
        h.provider.invoke(request(), wifTarget(), CallContext("r", "a", "d"))
        val m = h.messages.single()
        assertEquals("Bearer $TOKEN_1", m.headers[HttpHeaders.Authorization])
        assertNull(m.headers["x-api-key"])
        assertTrue(m.headers["anthropic-version"] != null)
    }

    @Test fun cachedTokenIsNotExchangedBeforeRecommendedWindow() = runTest {
        val clock = FakeClock(t0)
        val h = Harness(newFile(JWT_A), clock) { _, _ -> tokenOk(TOKEN_1, expiresIn = 3600) }
        h.auth.authorize(wifTarget())
        clock.at = t0.plusSeconds(3600 - 121)
        assertEquals("Bearer $TOKEN_1", h.auth.authorize(wifTarget()).headerValue)
        assertEquals(1, h.exchanges.size)
    }

    @Test fun refreshesInRecommendedWindowAndSwitchesToNewToken() = runTest {
        val clock = FakeClock(t0)
        val file = newFile(JWT_A)
        val h = Harness(file, clock) { req, n -> if (n == 1) tokenOk(TOKEN_1, 3600) else tokenOk(TOKEN_2, 3600) }
        h.auth.authorize(wifTarget())
        clock.at = t0.plusSeconds(3600 - 100)
        Files.writeString(file, JWT_B)
        assertEquals("Bearer $TOKEN_2", h.auth.authorize(wifTarget()).headerValue)
        assertEquals(JWT_B, assertionOf(h.exchanges[1]))
    }

    @Test fun failedRecommendedRefreshKeepsCachedToken() = runTest {
        val clock = FakeClock(t0)
        val file = newFile(JWT_A)
        val h = Harness(file, clock) { _, n -> if (n == 1) tokenOk(TOKEN_1, 3600) else respond("""{"error":"down"}""", HttpStatusCode.InternalServerError) }
        h.auth.authorize(wifTarget())
        clock.at = t0.plusSeconds(3600 - 100)
        Files.writeString(file, JWT_B)
        assertEquals("Bearer $TOKEN_1", h.auth.authorize(wifTarget()).headerValue)
        assertEquals(2, h.exchanges.size)
    }

    @Test fun failedRequiredRefreshIsAnError() = runTest {
        val clock = FakeClock(t0)
        val file = newFile(JWT_A)
        val h = Harness(file, clock) { _, n -> if (n == 1) tokenOk(TOKEN_1, 3600) else respond("""{"error":"down"}""", HttpStatusCode.InternalServerError) }
        h.auth.authorize(wifTarget())
        clock.at = t0.plusSeconds(3600 - 20)
        Files.writeString(file, JWT_B)
        val e = assertFailsWith<GatewayException> { h.auth.authorize(wifTarget()) }
        assertEquals(ErrorKind.TRANSIENT, e.error.kind)
        assertEquals(2, h.exchanges.size, "one send for the refresh; the retries wait for the file to rotate instead of re-sending")
    }

    @Test fun concurrentCallersShareOneExchange() = runTest {
        val h = Harness(newFile(JWT_A)) { _, _ -> tokenOk(TOKEN_1) }
        val results = coroutineScope { (1..10).map { async { h.auth.authorize(wifTarget()).headerValue } }.awaitAll() }
        assertEquals(1, h.exchanges.size)
        assertTrue(results.all { it == "Bearer $TOKEN_1" })
    }

    @Test fun identityFileIsReReadForEveryExchange() = runTest {
        val clock = FakeClock(t0)
        val file = newFile(JWT_A)
        val h = Harness(file, clock) { _, n -> tokenOk(if (n == 1) TOKEN_1 else TOKEN_2, 3600) }
        h.auth.authorize(wifTarget())
        Files.writeString(file, JWT_C)
        clock.at = t0.plusSeconds(3600 - 10)
        h.auth.authorize(wifTarget())
        assertEquals(listOf(JWT_A, JWT_C), h.exchanges.map { assertionOf(it) })
    }

    @Test fun sameJwtIsNeverSentTwice() = runTest {
        val h = Harness(newFile(JWT_A)) { _, _ -> respond("""{"error":"invalid"}""", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<GatewayException> { h.auth.authorize(wifTarget()) }
        assertEquals(ErrorKind.AUTH, e.error.kind)
        assertEquals("Anthropic token exchange failed (HTTP 401)", e.error.message)
        assertEquals(1, h.exchanges.size, "the spent jti must not be re-sent")
        assertEquals(AnthropicWifAuth.MAX_ATTEMPTS - 1, h.pauses.size)
    }

    @Test fun retryWaitsWithBackoffAndUsesRotatedJwt() = runTest {
        val file = newFile(JWT_A)
        val h = Harness(file) { _, n ->
            if (n == 1) { Files.writeString(file, JWT_B); respond("""{"error":"x"}""", HttpStatusCode.InternalServerError) }
            else tokenOk(TOKEN_1)
        }
        assertEquals("Bearer $TOKEN_1", h.auth.authorize(wifTarget()).headerValue)
        assertEquals(listOf(JWT_A, JWT_B), h.exchanges.map { assertionOf(it) })
        assertEquals(listOf(AnthropicWifAuth.BACKOFF_BASE_MS), h.pauses)
    }

    @Test fun unreadableIdentityFileIsFixedError() = runTest {
        val missing = Files.createTempDirectory("wif-missing").resolve("nope.token")
        val h = Harness(missing) { _, _ -> tokenOk(TOKEN_1) }
        val e = assertFailsWith<GatewayException> { h.auth.authorize(wifTarget()) }
        assertEquals(AnthropicWifAuth.IDENTITY_UNREADABLE, e.error.message)
        assertEquals(0, h.exchanges.size)
        assertFalse(e.message!!.contains(missing.toString()))
    }

    @Test fun unauthorizedDropsCachedTokenWithoutRetry() = runTest {
        val file = newFile(JWT_A)
        val exchanges = mutableListOf<HttpRequestData>()
        val messages = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/v1/oauth/token") {
                exchanges.add(req)
                tokenOk(if (exchanges.size == 1) TOKEN_1 else TOKEN_2)
            } else {
                messages.add(req)
                if (req.headers[HttpHeaders.Authorization] == "Bearer $TOKEN_1") {
                    respond("""{"type":"error","error":{"type":"authentication_error","message":"bad"}}""", HttpStatusCode.Unauthorized, jsonHeaders)
                } else respond(MESSAGE_OK, HttpStatusCode.OK, jsonHeaders)
            }
        }
        val provider = AnthropicProvider(HttpClient(engine), AnthropicWifAuth(HttpClient(engine), config(file), FakeClock(t0)), "anthropic")
        assertFailsWith<GatewayException> { provider.invoke(request(), wifTarget(), CallContext("r", "a", "d")) }
        assertEquals(1, messages.size, "a 401 is not retried in place")
        Files.writeString(file, JWT_B)
        provider.invoke(request(), wifTarget(), CallContext("r", "a", "d"))
        assertEquals(2, exchanges.size, "the rejected token was dropped, so the next call re-exchanges")
        assertEquals("Bearer $TOKEN_2", messages.last().headers[HttpHeaders.Authorization])
    }

    @Test fun endpointOtherThanBoundHostReceivesNothing() = runTest {
        val h = Harness(newFile(JWT_A)) { _, _ -> tokenOk(TOKEN_1) }
        val e = assertFailsWith<GatewayException> { h.auth.authorize(wifTarget("https://evil.example.test")) }
        assertEquals(ErrorKind.AUTH, e.error.kind)
        assertEquals(0, h.exchanges.size)
        assertFalse(h.auth.isConfigured(wifTarget("http://api.anthropic.com")))
        assertFalse(h.auth.isConfigured(wifTarget("https://api.anthropic.com.evil.test")))
        assertTrue(h.auth.isConfigured(wifTarget()))
    }

    @Test fun secretsNeverAppearInErrorsOrToString() = runTest {
        val file = newFile(JWT_A)
        val h = Harness(file) { _, _ -> respond("""{"error":"$JWT_A $TOKEN_1"}""", HttpStatusCode.BadRequest, jsonHeaders) }
        val e = assertFailsWith<GatewayException> { h.auth.authorize(wifTarget()) }
        val texts = listOf(e.message.orEmpty(), e.error.message, e.cause?.message.orEmpty())
        texts.forEach { t -> assertFalse(t.contains(JWT_A)); assertFalse(t.contains(TOKEN_1)) }
        val authz = AnthropicAuthorization("Authorization", "Bearer $TOKEN_1", listOf(TOKEN_1))
        assertFalse(authz.toString().contains(TOKEN_1))
        assertFalse(h.auth.toString().contains(JWT_A))
        assertFalse(config(file, "wrkspc_x").toString().contains("fdrl_test"))
    }

    @Test fun messagesErrorBodyWithAccessTokenIsRedacted() = runTest {
        val h = Harness(newFile(JWT_A)) { _, _ -> tokenOk(TOKEN_1) }
        val provider = AnthropicProvider(HttpClient(MockEngine { req ->
            if (req.url.encodedPath == "/v1/oauth/token") tokenOk(TOKEN_1)
            else respond("""{"type":"error","error":{"type":"authentication_error","message":"bad $TOKEN_1"}}""", HttpStatusCode.Unauthorized, jsonHeaders)
        }), h.auth, "anthropic")
        val e = assertFailsWith<GatewayException> { provider.invoke(request(), wifTarget(), CallContext("r", "a", "d")) }
        assertFalse(e.error.message.contains(TOKEN_1))
        assertFalse(e.message.orEmpty().contains(TOKEN_1))
        assertEquals(ErrorKind.AUTH, e.error.kind)
    }

    @Test fun streamAlsoUsesBearer() = runTest {
        val sse = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/v1/oauth/token") tokenOk(TOKEN_1)
            else { assertEquals("Bearer $TOKEN_1", req.headers[HttpHeaders.Authorization]); assertNull(req.headers["x-api-key"]); respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
        }
        val p = AnthropicProvider(HttpClient(engine), AnthropicWifAuth(HttpClient(engine), config(newFile(JWT_A)), FakeClock(t0)), "anthropic")
        assertTrue(p.stream(request(), wifTarget(), CallContext("r", "a", "d")).toList().isNotEmpty())
    }
}
