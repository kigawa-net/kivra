package net.kigawa.kivra.selfdev.llm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.modelgateway.AnthropicAuth
import net.kigawa.kivra.modelgateway.AnthropicAuthorization
import net.kigawa.kivra.modelgateway.ErrorKind
import net.kigawa.kivra.modelgateway.GatewayError
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.ResolvedTarget
import org.slf4j.LoggerFactory

class AnthropicStopJudgeClientTest {
    private val apiKey = "sk-ant-SECRET-CREDENTIAL-VALUE"
    private val freeText = "FREE-TEXT-MUST-NOT-LEAK"
    private val modelOutputText = "MODEL-OUTPUT-MUST-NOT-LEAK"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class FakeClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = at
    }

    private inner class FakeAuth(private val fail: Boolean = false) : AnthropicAuth {
        val authorizeCalls = AtomicInteger()
        val unauthorized = AtomicInteger()
        override fun isConfigured(target: ResolvedTarget): Boolean = true
        override suspend fun authorize(target: ResolvedTarget): AnthropicAuthorization {
            authorizeCalls.incrementAndGet()
            if (fail) throw GatewayException(GatewayError(ErrorKind.AUTH, "no credential $apiKey"))
            return AnthropicAuthorization("x-api-key", apiKey, listOf(apiKey))
        }
        override fun onUnauthorized(authorization: AnthropicAuthorization) {
            unauthorized.incrementAndGet()
        }
    }

    private val loggerRoot = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var previousLevel: Level

    @BeforeTest
    fun attach() {
        previousLevel = loggerRoot.level ?: Level.INFO
        loggerRoot.level = Level.ALL
        appender.start()
        loggerRoot.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        loggerRoot.detachAppender(appender)
        appender.stop()
        loggerRoot.level = previousLevel
    }

    private val input = StopJudgeInputBuilder.build(
        StopRequestFacts(true, "RUNNING", 1000, 10, 1, 0, "OPERATOR_REQUEST", 60, RequesterKind.HUMAN),
        StopRequestFreeText(freeText, freeText, freeText),
    )

    private fun ok(text: String, inTokens: Int = 120, outTokens: Int = 20) =
        """{"content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}],"usage":{"input_tokens":$inTokens,"output_tokens":$outTokens}}"""

    private val approveJson = """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}"""

    private class Harness(val client: AnthropicStopJudgeClient, val calls: AtomicInteger, val requests: MutableList<HttpRequestData>, val pauses: MutableList<Long>)

    private fun harness(
        auth: AnthropicAuth,
        clock: Clock = FakeClock(Instant.parse("2026-10-27T00:00:00Z")),
        config: StopJudgeClientConfig = StopJudgeClientConfig(model = "test-model", maxAttempts = 3, attemptTimeoutMillis = 2000),
        breaker: JudgeCircuitBreaker = JudgeCircuitBreaker(clock, failureThreshold = 3, openMillis = 60_000),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Harness {
        val calls = AtomicInteger()
        val requests = mutableListOf<HttpRequestData>()
        val pauses = mutableListOf<Long>()
        val engine = MockEngine { req ->
            calls.incrementAndGet()
            requests += req
            handler(req)
        }
        return Harness(AnthropicStopJudgeClient(HttpClient(engine), auth, config, breaker) { pauses += it }, calls, requests, pauses)
    }

    private fun logs(): String = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") + " " + it.argumentArray?.joinToString() }

    private fun assertNoLeak(vararg extra: String) {
        val text = logs()
        for (s in listOf(apiKey, freeText, modelOutputText, "decision") + extra) assertFalse(text.contains(s), "log contains: $s")
    }

    @Test
    fun `success returns the parsed judgement and the token counts, with the credential in the header`() = runBlocking {
        val h = harness(FakeAuth()) { respond(ok(approveJson, 321, 17), HttpStatusCode.OK, jsonHeaders) }
        val r = h.client.judge(input)
        val judged = assertIs<JudgeResult.Judged>(r)
        assertEquals(ParsedJudgment(JudgeDecision.APPROVE, JudgeReasonCode.CLEAR_STOP_REQUEST), judged.judgment)
        assertEquals(JudgeUsage(321, 17), judged.usage)
        val req = h.requests.single()
        assertEquals(apiKey, req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
        val body = Json.parseToJsonElement(String(req.body.toByteArray())).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("100", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(input.systemPrompt, body["system"]!!.jsonPrimitive.content)
        assertEquals(input.userMessage, body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertNoLeak()
    }

    private fun io.ktor.http.content.OutgoingContent.toByteArray(): ByteArray = (this as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()

    @Test
    fun `an output the parser refuses is a judgement of ABSTAIN with the usage kept`() = runBlocking {
        val h = harness(FakeAuth()) { respond(ok("Sure! $modelOutputText approve"), HttpStatusCode.OK, jsonHeaders) }
        val judged = assertIs<JudgeResult.Judged>(h.client.judge(input))
        assertEquals(ParsedJudgment.INVALID, judged.judgment)
        assertEquals(JudgeUsage(120, 20), judged.usage)
        assertNoLeak()
    }

    @Test
    fun `a 4xx is an error and is not retried`() = runBlocking {
        val h = harness(FakeAuth()) { respond("""{"error":{"message":"$freeText $modelOutputText"}}""", HttpStatusCode.BadRequest, jsonHeaders) }
        assertEquals(JudgeErrorCode.CLIENT_ERROR, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(1, h.calls.get())
        assertNoLeak()
    }

    @Test
    fun `401 and 403 are auth errors, not retried, and 401 drops the cached credential`() = runBlocking {
        val auth = FakeAuth()
        val h = harness(auth) { respond("{}", HttpStatusCode.Unauthorized, jsonHeaders) }
        assertEquals(JudgeErrorCode.AUTH, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(1, h.calls.get())
        assertEquals(1, auth.unauthorized.get())
        val h2 = harness(FakeAuth()) { respond("{}", HttpStatusCode.Forbidden, jsonHeaders) }
        assertEquals(JudgeErrorCode.AUTH, assertIs<JudgeResult.Error>(h2.client.judge(input)).code)
        assertEquals(1, h2.calls.get())
        assertNoLeak()
    }

    @Test
    fun `a failure to get the credential is an auth error, no request is sent, nothing leaks`() = runBlocking {
        val auth = FakeAuth(fail = true)
        val h = harness(auth) { respond(ok(approveJson), HttpStatusCode.OK, jsonHeaders) }
        assertEquals(JudgeErrorCode.AUTH, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(0, h.calls.get())
        assertEquals(1, auth.authorizeCalls.get())
        assertNoLeak()
    }

    @Test
    fun `5xx is retried up to the limit with backoff, then an error`() = runBlocking {
        val h = harness(FakeAuth()) { respond("boom $modelOutputText", HttpStatusCode.BadGateway) }
        assertEquals(JudgeErrorCode.UPSTREAM, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(3, h.calls.get())
        assertEquals(listOf(300L, 600L), h.pauses)
        assertNoLeak()
    }

    @Test
    fun `a 5xx then a success returns the success`() = runBlocking {
        val n = AtomicInteger()
        val h = harness(FakeAuth()) {
            if (n.incrementAndGet() == 1) respond("x", HttpStatusCode.InternalServerError) else respond(ok(approveJson), HttpStatusCode.OK, jsonHeaders)
        }
        assertIs<JudgeResult.Judged>(h.client.judge(input))
        assertEquals(2, h.calls.get())
    }

    @Test
    fun `429 is retried and reported as rate limited`() = runBlocking {
        val h = harness(FakeAuth()) { respond("slow down", HttpStatusCode.TooManyRequests) }
        assertEquals(JudgeErrorCode.RATE_LIMITED, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(3, h.calls.get())
    }

    @Test
    fun `a timeout is retried and then an error`() = runBlocking {
        val h = harness(FakeAuth(), config = StopJudgeClientConfig(model = "m", maxAttempts = 2, attemptTimeoutMillis = 50)) {
            delay(5000)
            respond(ok(approveJson), HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals(JudgeErrorCode.TIMEOUT, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        // Both attempts timed out (the mock engine may queue the second behind the first handler, so count the backoff, not the calls).
        assertEquals(1, h.pauses.size)
        assertTrue(h.calls.get() >= 1)
    }

    @Test
    fun `a transport failure is retried and its message is not logged`() = runBlocking {
        val h = harness(FakeAuth()) { throw IOException("connect failed $apiKey $freeText") }
        assertEquals(JudgeErrorCode.TRANSPORT, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(3, h.calls.get())
        assertNoLeak()
    }

    @Test
    fun `a malformed body is a bad response and is not retried`() = runBlocking {
        for (body in listOf("not json $modelOutputText", "[]", """{"content":[]}""", """{"content":[],"usage":{"input_tokens":"x","output_tokens":1}}""", "")) {
            val h = harness(FakeAuth()) { respond(body, HttpStatusCode.OK, jsonHeaders) }
            assertEquals(JudgeErrorCode.BAD_RESPONSE, assertIs<JudgeResult.Error>(h.client.judge(input)).code, body)
            assertEquals(1, h.calls.get())
        }
        assertNoLeak()
    }

    @Test
    fun `the circuit opens after consecutive failures, rejects without a call, and closes after a good trial`() = runBlocking {
        val clock = FakeClock(Instant.parse("2026-10-27T00:00:00Z"))
        val healthy = java.util.concurrent.atomic.AtomicBoolean(false)
        val h = harness(FakeAuth(), clock) {
            if (healthy.get()) respond(ok(approveJson), HttpStatusCode.OK, jsonHeaders) else respond("x", HttpStatusCode.ServiceUnavailable)
        }
        repeat(3) { assertEquals(JudgeErrorCode.UPSTREAM, assertIs<JudgeResult.Error>(h.client.judge(input)).code) }
        val callsWhenOpen = h.calls.get()
        assertEquals(JudgeErrorCode.CIRCUIT_OPEN, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(callsWhenOpen, h.calls.get())
        clock.at = clock.at.plusSeconds(59)
        assertEquals(JudgeErrorCode.CIRCUIT_OPEN, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        // After the open time, a trial that fails opens it again at once.
        clock.at = clock.at.plusSeconds(2)
        assertEquals(JudgeErrorCode.UPSTREAM, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        assertEquals(JudgeErrorCode.CIRCUIT_OPEN, assertIs<JudgeResult.Error>(h.client.judge(input)).code)
        // A good trial closes it.
        clock.at = clock.at.plusSeconds(61)
        healthy.set(true)
        assertIs<JudgeResult.Judged>(h.client.judge(input))
        assertIs<JudgeResult.Judged>(h.client.judge(input))
        assertNoLeak()
    }

    @Test
    fun `results and the config print no secret`() = runBlocking {
        val h = harness(FakeAuth()) { respond(ok(approveJson), HttpStatusCode.OK, jsonHeaders) }
        val r = h.client.judge(input)
        assertFalse(r.toString().contains(apiKey) || r.toString().contains(freeText))
        assertFalse(StopJudgeClientConfig(model = "m").toString().contains(apiKey))
    }

    @Test
    fun `the model is required`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { StopJudgeClientConfig(model = " ") }
    }

    @Test
    fun `only the net kigawa kivra logger was captured`() {
        assertTrue(loggerRoot.name == "net.kigawa.kivra")
    }
}
