package net.kigawa.kivra.agent

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.kivra.agent.claude.ReportedUsage

class KivraAgentClientTest {
    private val FIXTURE_TASK = """"task":{"origin":"title","value":"do the thing","trust":"UNTRUSTED","truncated":false}"""
    private fun client(recorded: MutableList<Recorded>, tokens: FixedTokens = FixedTokens(), handler: (Recorded) -> Canned) =
        KivraAgentClient(fakeKivraHttp(recorded, handler = handler), KIVRA_BASE, tokens)

    @Test
    fun claimParsesAnAttemptAndSendsOnlyBearer() = runBlocking {
        val recorded = synchronizedList()
        val tokens = FixedTokens()
        val result = client(recorded, tokens) { Canned(200, CLAIM_BODY) }.claim()

        val attempt = assertIs<ClaimResult.Assigned>(result).attempt
        assertEquals("att-1", attempt.attemptId)
        assertEquals("run-1", attempt.runId)
        assertEquals(3L, attempt.generation)
        assertEquals(TEST_SHA, attempt.baseSha)
        assertEquals("UNTRUSTED", attempt.task.trust)
        assertEquals(1800L, attempt.limits.maxRuntimeSeconds)
        assertEquals(listOf("src/**"), attempt.allowedPathPatterns)
        assertEquals(listOf(".github/**"), attempt.deniedPathPatterns)

        val request = recorded.single()
        assertEquals("POST", request.method)
        assertEquals("/api/agent/v1/claim", request.path)
        assertEquals(listOf("Bearer tok-1"), request.authorization)
        assertEquals("{}", request.body)
        assertTrue(request.contentType!!.startsWith("application/json"))
    }

    @Test
    fun noContentMeansNoAssignment() = runBlocking {
        assertIs<ClaimResult.NoAssignment>(client(synchronizedList()) { Canned(204) }.claim())
    }

    @Test
    fun claimWithMissingRequiredFieldIsMalformed() = runBlocking {
        val broken = CLAIM_BODY.replace("\"generation\":3,", "")
        val e = assertFailsWith<AgentApiException> { client(synchronizedList()) { Canned(200, broken) }.claim() }
        assertEquals(AgentApiErrorKind.MALFORMED_RESPONSE, e.kind)
    }

    @Test
    fun claimWithWrongTypeOrTrustIsMalformed() = runBlocking {
        val notTrusted = CLAIM_BODY.replace("\"UNTRUSTED\"", "\"TRUSTED\"")
        val badSha = CLAIM_BODY.replace(TEST_SHA, "not-a-sha")
        val badGeneration = CLAIM_BODY.replace("\"generation\":3", "\"generation\":\"three\"")
        for (body in listOf(notTrusted, badSha, badGeneration, "not json")) {
            val e = assertFailsWith<AgentApiException> { client(synchronizedList()) { Canned(200, body) }.claim() }
            assertEquals(AgentApiErrorKind.MALFORMED_RESPONSE, e.kind)
        }
    }

    @Test
    fun statusCodesMapToFixedKinds() = runBlocking {
        val expected = mapOf(
            401 to AgentApiErrorKind.UNAUTHORIZED,
            403 to AgentApiErrorKind.FORBIDDEN,
            404 to AgentApiErrorKind.NOT_FOUND,
            409 to AgentApiErrorKind.CONFLICT,
            413 to AgentApiErrorKind.REJECTED,
            422 to AgentApiErrorKind.REJECTED,
            418 to AgentApiErrorKind.CLIENT_ERROR,
            500 to AgentApiErrorKind.SERVER,
            503 to AgentApiErrorKind.SERVER,
        )
        for ((status, kind) in expected) {
            val e = assertFailsWith<AgentApiException> { client(synchronizedList()) { Canned(status, "secret body") }.claim() }
            assertEquals(kind, e.kind, "status $status")
        }
    }

    @Test
    fun unauthorizedInvalidatesTheToken() = runBlocking {
        val tokens = FixedTokens()
        assertFailsWith<AgentApiException> { client(synchronizedList(), tokens) { Canned(401) }.claim() }
        assertEquals(1, tokens.invalidations)
    }

    @Test
    fun redirectIsReturnedNotFollowed() = runBlocking {
        val recorded = synchronizedList()
        val e = assertFailsWith<AgentApiException> {
            client(recorded) { Canned(302, location = "https://evil.example/steal") }.claim()
        }
        assertEquals(AgentApiErrorKind.REDIRECT, e.kind)
        assertEquals(1, recorded.size, "the redirect must not be followed")
    }

    @Test
    fun errorMessageNeverCarriesBodyOrCause() = runBlocking {
        val e = assertFailsWith<AgentApiException> {
            client(synchronizedList()) { Canned(500, "internal detail with a secret") }.claim()
        }
        assertEquals("SERVER", e.message)
        assertNull(e.cause)
        assertTrue(!e.toString().contains("secret"))
    }

    @Test
    fun networkFailureIsClassifiedWithoutItsMessage() = runBlocking {
        val kivra = KivraAgentClient(
            io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { throw IOException("connect to https://kivra.example failed token=abc") }) {
                agentClientConfig()
            },
            KIVRA_BASE,
            FixedTokens(),
        )
        val e = assertFailsWith<AgentApiException> { kivra.claim() }
        assertEquals(AgentApiErrorKind.NETWORK, e.kind)
        assertNull(e.cause)
        assertEquals("NETWORK", e.message)
    }

    @Test
    fun heartbeatSendsGenerationAndOptionalProgress() = runBlocking {
        val recorded = synchronizedList()
        val kivra = client(recorded) { Canned(200, STATE_RUNNING) }
        assertEquals("RUNNING", kivra.heartbeat("att-1", 3, null))
        assertEquals("RUNNING", kivra.heartbeat("att-1", 3, ProgressPhase.TESTING))
        assertEquals("/api/agent/v1/attempts/att-1/heartbeat", recorded[0].path)
        assertEquals("""{"generation":3}""", recorded[0].body)
        assertEquals("""{"generation":3,"progress":"TESTING"}""", recorded[1].body)
    }

    @Test
    fun failSendsOnlyAFixedCode() = runBlocking {
        val recorded = synchronizedList()
        client(recorded) { Canned(200, """{"attemptId":"att-1","state":"FAILED"}""") }
            .fail("att-1", 3, AgentErrorCode.NEEDS_HUMAN)
        assertEquals("""{"generation":3,"errorCode":"NEEDS_HUMAN"}""", recorded.single().body)
    }

    @Test
    fun statusParsesCancelRequested() = runBlocking {
        val status = client(synchronizedList()) { Canned(200, STATUS_CANCEL) }.status("att-1")
        assertEquals(AttemptStatus("RUNNING", true), status)
    }

    @Test
    fun attemptIdIsCheckedBeforeItEntersAPath() = runBlocking {
        assertFailsWith<IllegalArgumentException> { client(synchronizedList()) { Canned(200) }.status("../admin") }
        Unit
    }

    @Test
    fun resultBodyCarriesTheChangesAndARefusalIsAReply() = runBlocking {
        val recorded = synchronizedList()
        val result = net.kigawa.kivra.agent.workdir.AgentResult(
            summary = "done",
            changes = listOf(
                net.kigawa.kivra.agent.workdir.AgentChange("kivra-agent/a.kt", net.kigawa.kivra.agent.workdir.AgentChangeKind.WRITE, "x\n"),
                net.kigawa.kivra.agent.workdir.AgentChange("docs/b.md", net.kigawa.kivra.agent.workdir.AgentChangeKind.DELETE, null),
            ),
            exitStatus = 0,
            sessionId = null,
        )
        val c = client(recorded) { Canned(422, "{}") }
        assertEquals(ResultReply.Rejected, c.submitResult("att-1", 3, result))
        val body = recorded.single().body
        assertTrue(body.startsWith("""{"generation":3,"result":{"summary":"done","exitStatus":0,"sessionId":null,"changes":["""), body)
        assertTrue(body.contains(""""path":"kivra-agent/a.kt","kind":"WRITE","fileKind":"REGULAR","content":"x\n""""), body)
        assertTrue(body.contains(""""path":"docs/b.md","kind":"DELETE","fileKind":"REGULAR"}"""), body)
        assertEquals(listOf("Bearer tok-1"), recorded.single().authorization)
    }

    @Test
    fun resultOnAStaleLeaseIsAConflictForTheLoopToDiscard() = runBlocking {
        val recorded = synchronizedList()
        val c = client(recorded) { Canned(409, "{}") }
        val error = assertFailsWith<AgentApiException> {
            c.submitResult("att-1", 3, net.kigawa.kivra.agent.workdir.AgentResult("done", emptyList(), 0, null))
        }
        assertEquals(AgentApiErrorKind.CONFLICT, error.kind)
    }

    private val usage = ReportedUsage(
        totalCostUsd = "0.004268",
        model = "claude-haiku-5-5",
        numTurns = 3,
        inputTokens = 120,
        outputTokens = 45,
        cacheCreationTokens = 10,
        cacheReadTokens = 300,
    )
    private val usageJson = """"usage":{"totalCostUsd":"0.004268","model":"claude-haiku-5-5","numTurns":3,""" +
        """"inputTokens":120,"outputTokens":45,"cacheCreationTokens":10,"cacheReadTokens":300}"""

    @Test
    fun failWithUsageSendsTheUsageObjectNextToTheCode() = runBlocking {
        val recorded = synchronizedList()
        client(recorded) { Canned(200, """{"attemptId":"att-1","state":"FAILED"}""") }
            .fail("att-1", 3, AgentErrorCode.CLI_NONZERO, usage)
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO",$usageJson}""", recorded.single().body)
    }

    @Test
    fun resultWithUsageSendsTheUsageAtTheTopLevel() = runBlocking {
        val recorded = synchronizedList()
        val c = client(recorded) { Canned(200, """{"attemptId":"att-1","state":"RESULT_RECEIVED"}""") }
        val accepted = c.submitResult("att-1", 3, net.kigawa.kivra.agent.workdir.AgentResult("done", emptyList(), 0, null), usage)
        assertEquals(ResultReply.Accepted("RESULT_RECEIVED"), accepted)
        assertEquals(
            """{"generation":3,"result":{"summary":"done","exitStatus":0,"sessionId":null,"changes":[]},$usageJson}""",
            recorded.single().body,
        )
    }

    @Test
    fun withoutUsageTheBodiesAreExactlyTheOldOnes() = runBlocking {
        val recorded = synchronizedList()
        val c = client(recorded) { Canned(200, """{"attemptId":"att-1","state":"RESULT_RECEIVED"}""") }
        c.fail("att-1", 3, AgentErrorCode.NEEDS_HUMAN, null)
        c.submitResult("att-1", 3, net.kigawa.kivra.agent.workdir.AgentResult("done", emptyList(), 0, null), null)
        assertEquals("""{"generation":3,"errorCode":"NEEDS_HUMAN"}""", recorded[0].body)
        assertEquals(
            """{"generation":3,"result":{"summary":"done","exitStatus":0,"sessionId":null,"changes":[]}}""",
            recorded[1].body,
        )
        assertFalse(recorded.any { it.body.contains("usage") })
    }

    /** The claim fixture with its `task` object replaced by [task]. */
    private fun claimWithTask(task: String): String = CLAIM_BODY.replace(FIXTURE_TASK, """"task":$task""")

    @Test
    fun claimWithoutBodyFromAnOlderKivraHasEmptyBodyAndNoTruncation() = runBlocking {
        val attempt = assertIs<ClaimResult.Assigned>(client(synchronizedList()) { Canned(200, CLAIM_BODY) }.claim()).attempt
        assertEquals("", attempt.task.body)
        assertFalse(attempt.task.bodyTruncated)
        assertEquals("do the thing", attempt.task.value)
    }

    @Test
    fun claimWithBodyKeepsJapaneseNewlinesAndQuotesAsTheyAre() = runBlocking {
        val body = "1行目 日本語\n2行目 \"引用\" </script> \\ 末尾"
        val json = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(body))
        val attempt = assertIs<ClaimResult.Assigned>(
            client(synchronizedList()) { Canned(200, claimWithTask("""{"origin":"title","value":"t","trust":"UNTRUSTED","truncated":false,"body":$json,"bodyTruncated":true}""")) }.claim(),
        ).attempt
        assertEquals(body, attempt.task.body)
        assertTrue(attempt.task.bodyTruncated)
    }

    @Test
    fun claimBodyLongerThanTheLimitIsCutToCodePointsAndMarked() = runBlocking {
        val long = "😀".repeat(8_001)
        val json = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(long))
        val attempt = assertIs<ClaimResult.Assigned>(
            client(synchronizedList()) { Canned(200, claimWithTask("""{"origin":"title","value":"t","trust":"UNTRUSTED","truncated":false,"body":$json}""")) }.claim(),
        ).attempt
        assertEquals(8_000, attempt.task.body.codePointCount(0, attempt.task.body.length))
        assertEquals("😀".repeat(8_000), attempt.task.body)
        assertTrue(attempt.task.bodyTruncated)
        assertFalse(attempt.task.truncated, "the title flag is not changed by the body")
    }

    @Test
    fun claimBodyOfExactlyTheLimitIsNotCut() = runBlocking {
        val exact = "あ".repeat(8_000)
        val json = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(exact))
        val attempt = assertIs<ClaimResult.Assigned>(
            client(synchronizedList()) { Canned(200, claimWithTask("""{"origin":"title","value":"t","trust":"UNTRUSTED","truncated":false,"body":$json}""")) }.claim(),
        ).attempt
        assertEquals(exact, attempt.task.body)
        assertFalse(attempt.task.bodyTruncated)
    }

    @Test
    fun claimIgnoresUnknownTaskFieldsAndRejectsAWrongBodyType() = runBlocking {
        val ok = assertIs<ClaimResult.Assigned>(
            client(synchronizedList()) { Canned(200, claimWithTask("""{"origin":"title","value":"t","trust":"UNTRUSTED","truncated":false,"futureTaskField":[1,2],"body":"b"}""")) }.claim(),
        ).attempt
        assertEquals("b", ok.task.body)
        for (bad in listOf("""{"origin":"title","value":"t","trust":"UNTRUSTED","truncated":false,"body":5}""",
            """{"origin":"title","value":"t","trust":"UNTRUSTED","truncated":false,"bodyTruncated":"yes"}""")) {
            val e = assertFailsWith<AgentApiException> { client(synchronizedList()) { Canned(200, claimWithTask(bad)) }.claim() }
            assertEquals(AgentApiErrorKind.MALFORMED_RESPONSE, e.kind)
        }
    }
}
