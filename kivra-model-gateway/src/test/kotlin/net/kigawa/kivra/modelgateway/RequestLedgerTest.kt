package net.kigawa.kivra.modelgateway

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RequestLedgerTest {
    private val m = model("m", "ext")
    private fun digest(r: ModelRequest) = RequestDigest.of(r)

    @Test fun digestIsStableAndIgnoresRequestIdOnly() {
        assertEquals(digest(request(id = "a")), digest(request(id = "b")))
        assertEquals(digest(request()), digest(request()))
    }

    @Test fun everyOutcomeRelevantFieldChangesTheDigest() {
        val base = request()
        val variants = listOf(
            base.copy(principal = "other"),
            base.copy(dataClass = DataClass.RESTRICTED),
            base.copy(purpose = Purpose.REVIEW),
            base.copy(budgetScopes = listOf("org", "task")),
            base.copy(taskId = "t"),
            base.copy(messages = listOf(Message(Role.SYSTEM, "hello"))),
            base.copy(messages = listOf(Message(Role.USER, "hello!"))),
            base.copy(tools = listOf(ToolSpec("t", "d", buildJsonObject { put("k", JsonPrimitive(1)) }))),
            base.copy(tools = listOf(ToolSpec("t", "d", buildJsonObject { put("k", JsonPrimitive("1")) }))),
            base.copy(responseFormat = ResponseFormat.JsonSchema("s", emptySchema)),
            base.copy(generation = base.generation.copy(maxOutputTokens = 7)),
            base.copy(requirements = Requirements(locality = Locality.LOCAL_ONLY)),
            base.copy(preference = RoutingPreference(quality = 0.9)),
            base.copy(timeout = 61.seconds),
        )
        val digests = variants.map { digest(it) }
        assertTrue(digests.all { it != digest(base) })
        assertEquals(variants.size, digests.toSet().size, "distinct requests must not share a digest")
    }

    @Test fun digestDistinguishesRequestsWithCollidingHashCodes() {
        val a = request(text = "Aa"); val b = request(text = "BB")
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(digest(a) != digest(b))
    }

    @Test fun encodingIsUnambiguousAcrossListBoundaries() {
        val x = request().copy(budgetScopes = listOf("ab", "c"))
        val y = request().copy(budgetScopes = listOf("a", "bc"))
        assertTrue(digest(x) != digest(y))
    }

    @Test fun loneSurrogatesAreNotCollapsedByUtf8Replacement() {
        assertTrue(digest(request(text = "\uD800")) != digest(request(text = "�")))
    }

    @Test fun ledgerRefusesSameRequestIdWithDifferentDigest() {
        val l = InMemoryRequestLedger()
        assertEquals(LedgerClaim.Claimed, l.begin("r", "d1"))
        assertEquals("REQUEST_ID_REUSED", (l.begin("r", "d2") as LedgerClaim.Rejected).code)
    }

    @Test fun sentRequestIdIsSealedButNeverSentRequestIdCanBeReclaimed() {
        val l = InMemoryRequestLedger()
        l.begin("sent", "d"); l.markSending("sent"); l.markOutcomeUnknown("sent")
        assertEquals("DUPLICATE_REQUEST", (l.begin("sent", "d") as LedgerClaim.Rejected).code)
        l.begin("unsent", "d"); l.markSending("unsent"); l.releaseUnsent("unsent")
        assertEquals(LedgerClaim.Claimed, l.begin("unsent", "d"))
    }

    @Test fun inFlightRequestIdIsRejected() {
        val l = InMemoryRequestLedger()
        l.begin("r", "d"); l.markSending("r")
        assertEquals("DUPLICATE_REQUEST", (l.begin("r", "d") as LedgerClaim.Rejected).code)
    }

    @Test fun completedReplayIsReturnedAndStreamCompletionIsNotReplayable() {
        val l = InMemoryRequestLedger()
        val resp = okResponse(m.ref)
        l.begin("r", "d"); l.markSending("r"); l.complete("r", resp)
        assertEquals(LedgerClaim.Completed(resp), l.begin("r", "d"))
        l.begin("s", "d"); l.markSending("s"); l.complete("s", null)
        assertEquals(LedgerClaim.Completed(null), l.begin("s", "d"))
    }

    @Test fun terminalStatesAreNeverMovedBackToSendable() {
        val l = InMemoryRequestLedger()
        val resp = okResponse(m.ref)
        l.begin("r", "d"); l.markSending("r"); l.complete("r", resp); l.releaseUnsent("r"); l.markOutcomeUnknown("r")
        assertEquals(LedgerClaim.Completed(resp), l.begin("r", "d"))
    }

    @Test fun capacityRefusesNewIdsWithoutEvictingExistingOnes() {
        val l = InMemoryRequestLedger(capacity = 1)
        val resp = okResponse(m.ref)
        assertEquals(LedgerClaim.Claimed, l.begin("a", "d")); l.markSending("a"); l.complete("a", resp)
        assertEquals("LEDGER_FULL", (l.begin("b", "d") as LedgerClaim.Rejected).code)
        assertEquals(LedgerClaim.Completed(resp), l.begin("a", "d"))
    }
}
