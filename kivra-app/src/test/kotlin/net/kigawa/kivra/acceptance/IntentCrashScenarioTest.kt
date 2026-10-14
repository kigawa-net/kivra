package net.kigawa.kivra.acceptance

import kotlin.test.Test
import kotlin.test.assertEquals
import net.kigawa.kivra.agent.OpOutcome
import net.kigawa.kivra.core.OperationStatus
import kotlin.coroutines.cancellation.CancellationException

/**
 * Scenario 3 (issue #70): a worker crashes around the Journal's dispatch point. A restarted worker must never send the same
 * request again on the strength of "the Journal says it was never sent" when the Journal cannot prove that.
 */
class IntentCrashScenarioTest {

    @Test
    fun `crash after PREPARED but before dispatch - the restarted worker sends exactly once`() = deterministicScenario("intent-crash-prepared") {
        val runId = newRun()
        val ws = seedWorkspace(runId)
        val op = runtimeCreateOp(runId, ws)
        val l1 = acquire(runId, "worker-1")
        prepareOnly(l1, op)
        trace.record("crash: PREPARED committed, nothing dispatched")

        expireLease(runId)
        val l2 = acquire(runId, "worker-2")
        val outcome = perform(executor(), l2, runtimeCreateOp(runId, ws))
        trace.record("restart outcome: ${outcome::class.simpleName}")

        expect(runId) {
            operationStatus(OperationStatus.CONFIRMED)
            externalRuntimeCount(1)
            providerPostCount(1)
            automaticReplayCount(0)
        }
    }

    @Test
    fun `crash after IN_FLIGHT commit and before the POST - no POST on restart, the row is not treated as unsent`() =
        deterministicScenario("intent-crash-in-flight-before-send") {
            val runId = newRun()
            val ws = seedWorkspace(runId)
            val l1 = acquire(runId, "worker-1")
            val crashed = try {
                perform(executor(), l1, runtimeCreateOp(runId, ws, Crash.BEFORE_SEND))
                false
            } catch (e: CancellationException) {
                true
            }
            assertEquals(true, crashed, "the simulated crash must interrupt the worker")
            trace.record("crash: IN_FLIGHT committed, POST never sent")

            expireLease(runId)
            val l2 = acquire(runId, "worker-2")
            val outcome = perform(executor(), l2, runtimeCreateOp(runId, ws))
            trace.record("restart outcome: ${outcome::class.simpleName}")
            assertEquals(OpOutcome.ReconciliationRequired::class, outcome::class)

            expect(runId) {
                operationStatus(OperationStatus.RECONCILIATION_REQUIRED)
                reconciliationRequired(true)
                externalRuntimeCount(0)
                providerPostCount(0)
                automaticReplayCount(0)
            }
        }

    @Test
    fun `crash after the provider answered but before CONFIRMED - no second POST`() = deterministicScenario("intent-crash-after-send") {
        val runId = newRun()
        val ws = seedWorkspace(runId)
        val l1 = acquire(runId, "worker-1")
        val crashed = try {
            perform(executor(), l1, runtimeCreateOp(runId, ws, Crash.AFTER_SEND))
            false
        } catch (e: CancellationException) {
            true
        }
        assertEquals(true, crashed, "the simulated crash must interrupt the worker")
        trace.record("crash: POST answered, CONFIRMED never written")

        expireLease(runId)
        val l2 = acquire(runId, "worker-2")
        val outcome = perform(executor(), l2, runtimeCreateOp(runId, ws))
        trace.record("restart outcome: ${outcome::class.simpleName}")

        expect(runId) {
            operationStatus(OperationStatus.RECONCILIATION_REQUIRED)
            externalRuntimeCount(1)
            providerPostCount(1)
            automaticReplayCount(0)
        }
    }
}
