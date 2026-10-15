package net.kigawa.kivra.db

import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostBasis
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.GatewayWarning
import net.kigawa.kivra.modelgateway.LedgerClaim
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.RequestLedger
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.Usage

internal val DIGEST_A = "a".repeat(64)
internal val DIGEST_B = "b".repeat(64)

internal fun sampleResponse(requestId: String, text: String = "hello"): ModelResponse = ModelResponse(
    requestId = requestId,
    attemptId = "$requestId-a1",
    selected = ModelRef("ext", "m1", "provider-m1"),
    message = Message(Role.ASSISTANT, text),
    structured = null,
    finishReason = FinishReason.STOP,
    usage = Usage(10, 5, 2),
    cost = Cost(BigDecimal("0.00015000"), "USD", CostBasis.ACTUAL, priceRevision = "rev-1", model = null, requestId = requestId),
    latencyMs = 42,
    routingDecisionId = "decision-1",
    warnings = listOf(GatewayWarning("W1", "a warning")),
    usageComplete = true,
)

internal fun rejectedCode(c: LedgerClaim): String? = (c as? LedgerClaim.Rejected)?.code

/** One ledger as seen by a process: [ledger] is this process's instance; [replica] opens another instance over the same store. */
class LedgerFixture(val ledger: RequestLedger, val replica: () -> RequestLedger)

/**
 * Scenarios every [RequestLedger] must pass (the contract of [RequestLedger]). Run over [net.kigawa.kivra.modelgateway.InMemoryRequestLedger]
 * and [net.kigawa.kivra.db.DbRequestLedger], so the durable store cannot drift from the reference semantics.
 */
abstract class RequestLedgerContract {
    /** A fresh ledger with [capacity] (default: the implementation's). Resources are released when [block] returns. */
    protected abstract fun withLedger(capacity: Int? = null, block: (LedgerFixture) -> Unit)

    @Test
    fun sameRequestIdWithAnotherDigestIsAlwaysRefused() = withLedger { f ->
        assertEquals(LedgerClaim.Claimed, f.ledger.begin("r", DIGEST_A))
        assertEquals("REQUEST_ID_REUSED", rejectedCode(f.ledger.begin("r", DIGEST_B)))
        f.ledger.markSending("r")
        f.ledger.complete("r", sampleResponse("r"))
        assertEquals("REQUEST_ID_REUSED", rejectedCode(f.ledger.begin("r", DIGEST_B)))
        assertIs<LedgerClaim.Completed>(f.ledger.begin("r", DIGEST_A))
    }

    @Test
    fun sentRequestIdIsSealedButUnsentRequestIdCanBeReclaimed() = withLedger { f ->
        f.ledger.begin("sent", DIGEST_A); f.ledger.markSending("sent"); f.ledger.markOutcomeUnknown("sent")
        assertEquals("DUPLICATE_REQUEST", rejectedCode(f.ledger.begin("sent", DIGEST_A)))
        f.ledger.begin("unsent", DIGEST_A); f.ledger.markSending("unsent"); f.ledger.releaseUnsent("unsent")
        assertEquals(LedgerClaim.Claimed, f.ledger.begin("unsent", DIGEST_A))
    }

    @Test
    fun inFlightRequestIdIsRejected() = withLedger { f ->
        f.ledger.begin("reserved", DIGEST_A)
        assertEquals("DUPLICATE_REQUEST", rejectedCode(f.ledger.begin("reserved", DIGEST_A)))
        f.ledger.markSending("reserved")
        assertEquals("DUPLICATE_REQUEST", rejectedCode(f.ledger.begin("reserved", DIGEST_A)))
    }

    @Test
    fun completedReplayReturnsTheStoredResponseAndStreamCompletionIsNotReplayable() = withLedger { f ->
        val resp = sampleResponse("r")
        f.ledger.begin("r", DIGEST_A); f.ledger.markSending("r"); f.ledger.complete("r", resp)
        assertEquals(LedgerClaim.Completed(resp), f.ledger.begin("r", DIGEST_A))
        f.ledger.begin("s", DIGEST_A); f.ledger.markSending("s"); f.ledger.complete("s", null)
        assertEquals(LedgerClaim.Completed(null), f.ledger.begin("s", DIGEST_A))
    }

    @Test
    fun completedRequestIdIsNeverRewritten() = withLedger { f ->
        val first = sampleResponse("r", text = "first")
        f.ledger.begin("r", DIGEST_A); f.ledger.markSending("r"); f.ledger.complete("r", first)
        f.ledger.complete("r", sampleResponse("r", text = "second"))
        f.ledger.complete("r", null)
        f.ledger.markOutcomeUnknown("r")
        f.ledger.releaseUnsent("r")
        assertEquals(LedgerClaim.Completed(first), f.ledger.begin("r", DIGEST_A))
    }

    @Test
    fun terminalStatesAreNeverMovedBackToSendable() = withLedger { f ->
        val resp = sampleResponse("r")
        f.ledger.begin("r", DIGEST_A); f.ledger.markSending("r"); f.ledger.complete("r", resp)
        f.ledger.releaseUnsent("r"); f.ledger.markOutcomeUnknown("r"); f.ledger.markSending("r")
        assertEquals(LedgerClaim.Completed(resp), f.ledger.begin("r", DIGEST_A))
    }

    @Test
    fun unknownOutcomeIsTerminalAndNeverResent() = withLedger { f ->
        f.ledger.begin("u", DIGEST_A); f.ledger.markSending("u"); f.ledger.markOutcomeUnknown("u")
        f.ledger.releaseUnsent("u"); f.ledger.markSending("u")
        assertEquals("DUPLICATE_REQUEST", rejectedCode(f.ledger.begin("u", DIGEST_A)))
    }

    @Test
    fun releaseIsRefusedAfterACompletion() = withLedger { f ->
        val resp = sampleResponse("s")
        f.ledger.begin("s", DIGEST_A); f.ledger.markSending("s"); f.ledger.complete("s", resp); f.ledger.releaseUnsent("s")
        assertEquals(LedgerClaim.Completed(resp), f.ledger.begin("s", DIGEST_A))
    }

    @Test
    fun capacityRefusesNewIdsWithoutEvictingExistingOnes() = withLedger(capacity = 1) { f ->
        val resp = sampleResponse("a")
        assertEquals(LedgerClaim.Claimed, f.ledger.begin("a", DIGEST_A)); f.ledger.markSending("a"); f.ledger.complete("a", resp)
        assertEquals("LEDGER_FULL", rejectedCode(f.ledger.begin("b", DIGEST_A)))
        assertEquals(LedgerClaim.Completed(resp), f.ledger.begin("a", DIGEST_A))
    }

    @Test
    fun concurrentBeginForOneRequestIdClaimsExactlyOnce() = withLedger { f ->
        repeat(3) { round ->
            val id = "race-$round"
            val claims = raceBegin(listOf(f.ledger, f.replica(), f.ledger, f.replica()), id, DIGEST_A, threads = 8)
            assertEquals(1, claims.count { it == LedgerClaim.Claimed }, "exactly one claim: $claims")
            assertEquals(7, claims.count { rejectedCode(it) == "DUPLICATE_REQUEST" }, "the rest are refused: $claims")
        }
    }

    @Test
    fun replicaSeesTheCompletedRequestIdAndItsStoredResponse() = withLedger { f ->
        val resp = sampleResponse("r")
        f.ledger.begin("r", DIGEST_A); f.ledger.markSending("r"); f.ledger.complete("r", resp)
        assertEquals(LedgerClaim.Completed(resp), f.replica().begin("r", DIGEST_A))
    }

    private fun raceBegin(ledgers: List<RequestLedger>, requestId: String, digest: String, threads: Int): List<LedgerClaim> {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { i ->
                pool.submit(Callable { start.await(); ledgers[i % ledgers.size].begin(requestId, digest) })
            }
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
