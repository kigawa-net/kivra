package net.kigawa.kivra.agent

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** The `events` call (ADR 0012 §2.1): each status maps to one [EventsReply], and the request is the body the caller built. */
class KivraAgentClientEventsTest {
    private val body = """{"generation":3,"events":[{"seq":1,"kind":"TURN"}]}"""

    private fun client(recorded: MutableList<Recorded>, canned: Canned) =
        KivraAgentClient(fakeKivraHttp(recorded) { canned }, KIVRA_BASE, FixedTokens())

    @Test
    fun aSuccessIsAcceptedAndSendsTheBodyToTheEventsPath() = runBlocking {
        val recorded = synchronizedList()
        val reply = client(recorded, Canned(200, """{"attemptId":"att-1","lastAcceptedSeq":1}""")).postEvents("att-1", body)

        assertEquals(EventsReply.Accepted, reply)
        val request = recorded.single()
        assertEquals("POST", request.method)
        assertEquals("/api/agent/v1/attempts/att-1/events", request.path)
        assertEquals(body, request.body)
        assertEquals(listOf("Bearer tok-1"), request.authorization)
    }

    @Test
    fun aBadRequestIsRejected() = runBlocking {
        assertEquals(EventsReply.Rejected, client(synchronizedList(), Canned(400, "{}")).postEvents("att-1", body))
    }

    @Test
    fun aConflictCarriesTheLastAcceptedSeq() = runBlocking {
        val reply = client(synchronizedList(), Canned(409, """{"error":"SEQ_GAP","lastAcceptedSeq":4}""")).postEvents("att-1", body)
        assertEquals(EventsReply.Conflict(lastAcceptedSeq = 4), reply)
    }

    @Test
    fun aConflictWithoutASeqMeansStale() = runBlocking {
        val reply = client(synchronizedList(), Canned(409, """{"error":"STALE_GENERATION"}""")).postEvents("att-1", body)
        assertEquals(EventsReply.Conflict(lastAcceptedSeq = null), reply)
        val garbled = client(synchronizedList(), Canned(409, "not json")).postEvents("att-1", body)
        assertEquals(EventsReply.Conflict(lastAcceptedSeq = null), garbled)
    }

    @Test
    fun aRateLimitCarriesRetryAfter() = runBlocking {
        val reply = client(synchronizedList(), Canned(429, "{}", retryAfter = "7")).postEvents("att-1", body)
        assertEquals(EventsReply.RateLimited(retryAfterSeconds = 7), reply)
        val missing = client(synchronizedList(), Canned(429, "{}")).postEvents("att-1", body)
        assertEquals(EventsReply.RateLimited(retryAfterSeconds = null), missing)
    }

    @Test
    fun aServerErrorIsUnavailableAndOtherClientErrorsAreRefused() = runBlocking {
        assertEquals(EventsReply.Unavailable, client(synchronizedList(), Canned(500, "{}")).postEvents("att-1", body))
        assertEquals(EventsReply.Unavailable, client(synchronizedList(), Canned(503, "{}")).postEvents("att-1", body))
        assertEquals(EventsReply.Refused, client(synchronizedList(), Canned(403, "{}")).postEvents("att-1", body))
        assertEquals(EventsReply.Refused, client(synchronizedList(), Canned(404, "{}")).postEvents("att-1", body))
        assertEquals(EventsReply.Refused, client(synchronizedList(), Canned(401, "{}")).postEvents("att-1", body))
    }

    @Test
    fun aNetworkFailureIsUnavailableWithoutItsMessage() = runBlocking {
        val kivra = KivraAgentClient(
            io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { throw IOException("connect to https://kivra.example failed") }) {
                agentClientConfig()
            },
            KIVRA_BASE,
            FixedTokens(),
        )
        assertEquals(EventsReply.Unavailable, kivra.postEvents("att-1", body))
    }

    @Test
    fun theAttemptIdIsCheckedBeforeAnyRequest() = runBlocking {
        val recorded = synchronizedList()
        val failed = runCatching { client(recorded, Canned(200)).postEvents("../x", body) }
        assertIs<Throwable>(failed.exceptionOrNull())
        assertEquals(0, recorded.size)
    }
}
