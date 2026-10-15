package net.kigawa.kivra.acceptance

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import net.kigawa.kivra.agent.OpOutcome
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.db.JournalEntry

/**
 * Scenario 5 (issue #70): worker A is parked just before its POST, a newer generation B takes over, and A's POST arrives late.
 * B must not send again, and A's late write must not be able to confirm the operation.
 *
 * Two interleavings are checked: the late POST lands after B has looked (B sees nothing), and before B looks (B sees the
 * resource, but without trusted evidence it still does not adopt it).
 */
class OldWorkerDelayedPostScenarioTest {

    @Test
    fun `late POST arrives after the new owner looked - no second POST, the old writer is fenced`() =
        deterministicScenario("old-worker-late-post-after-takeover") { oldWorkerLatePost(latePostBeforeTakeover = false) }

    @Test
    fun `late POST arrives before the new owner looks - resource is not adopted without evidence`() =
        deterministicScenario("old-worker-late-post-before-takeover") { oldWorkerLatePost(latePostBeforeTakeover = true) }

    private fun AcceptanceRun.oldWorkerLatePost(latePostBeforeTakeover: Boolean) {
        val runId = newRun()
        val ws = seedWorkspace(runId)
        val gate = "A-before-post"
        proxy.delayBefore(gate, HttpMethodPost, "/runtimes")

        val lA = acquire(runId, "worker-A")
        val pool = Executors.newSingleThreadExecutor()
        val aFuture = pool.submit<OpOutcome> { perform(executor(), lA, runtimeCreateOp(runId, ws)) }
        try {
            barriers.awaitReached(gate)
            trace.record("A: parked before the POST reached the provider")

            if (latePostBeforeTakeover) {
                // A's lease expires first, then the late POST lands, then B looks. A's CONFIRMED is fenced.
                expireLease(runId)
                barriers.release(gate)
                assertIs<LeaseLostException>(futureFailureCause(aFuture))
                trace.record("A: late POST landed after expiry, CONFIRMED rejected by fencing")
            }

            if (!latePostBeforeTakeover) expireLease(runId)
            val lB = acquire(runId, "worker-B")
            trace.record("B: took over, generation newer than A")
            val outB = perform(executor(), lB, runtimeCreateOp(runId, ws))
            trace.record("B: outcome=${outB::class.simpleName}")
            assertEquals(OpOutcome.ReconciliationRequired::class, outB::class)

            // Fencing of the old writer: its stage write is refused, the new owner's is accepted.
            val staleWrite = runCatching { db.outbox.appendFenced(lA, 600, "STAGE_UPDATED", "{}") }.exceptionOrNull()
            assertIs<LeaseLostException>(staleWrite)
            trace.record("A: stale outbox write refused by fencing")

            if (!latePostBeforeTakeover) {
                barriers.release(gate)
                val failure = assertFailsWith<ExecutionException> { aFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertIs<LeaseLostException>(failure.cause)
                trace.record("A: late POST landed after takeover, CONFIRMED rejected by fencing")
            }

            // B is stopped by the held operation: a further attempt by B neither dispatches nor sends.
            val again = perform(executor(), lB, runtimeCreateOp(runId, ws))
            trace.record("B: second attempt=${again::class.simpleName}")
            assertEquals(OpOutcome.ReconciliationRequired::class, again::class)
        } finally {
            barriers.release(gate)
            pool.shutdownNow()
        }

        expect(runId) {
            operationStatus(OperationStatus.RECONCILIATION_REQUIRED)
            externalRuntimeCount(1)
            externalSideEffectCount(1)
            providerPostCount(1)
            automaticReplayCount(0)
        }
        assertEquals(false, journalIsConfirmed(runId), "the old writer never confirmed the operation")
    }

    private fun AcceptanceRun.journalIsConfirmed(runId: net.kigawa.kivra.core.RunId): Boolean =
        evidence.journalOf(runId).any { it.status == OperationStatus.CONFIRMED }

    /** The exception that ended the old writer's call (unwrapped from the executor's ExecutionException). */
    private fun futureFailureCause(f: java.util.concurrent.Future<*>): Throwable {
        val failure = assertFailsWith<ExecutionException> { f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        return checkNotNull(failure.cause) { "old writer failed without a cause" }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val HttpMethodPost = io.ktor.http.HttpMethod.Post
    }
}
