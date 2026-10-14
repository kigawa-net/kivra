package net.kigawa.kivra.acceptance

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kigawa.kivra.agent.OpOutcome
import net.kigawa.kivra.core.OperationStatus

/**
 * Scenario 4 (issue #70): the provider accepts a mutating POST, then the answer is lost. Until a trusted observer
 * establishes the real state, the operation is held (RECONCILIATION_REQUIRED) and is never sent again automatically.
 */
class ResponseLostScenarioTest {

    @Test
    fun `POST accepted and response dropped - one resource, held for reconciliation, no automatic second POST`() =
        deterministicScenario("response-lost-drop") { responseLost(dropResponse = true) }

    @Test
    fun `POST accepted and answered 429 - still Unknown, not RETRYABLE, no second POST`() =
        deterministicScenario("response-lost-429") { responseLost(dropResponse = false) }

    private fun AcceptanceRun.responseLost(dropResponse: Boolean) {
        val runId = newRun()
        val ws = seedWorkspace(runId)
        if (dropResponse) {
            proxy.persistEffectThenDropResponse(HttpMethod.Post, "/runtimes")
        } else {
            proxy.persistEffectThenStatus(HttpStatusCode.TooManyRequests, HttpMethod.Post, "/runtimes")
        }
        val l1 = acquire(runId, "worker-1")
        val first = perform(executor(), l1, runtimeCreateOp(runId, ws))
        trace.record("first attempt: ${first::class.simpleName}")
        assertEquals(OpOutcome.ReconciliationRequired::class, first::class)

        // A later worker must not replay the operation either: the lookup finds the resource, but no trusted evidence confirms it.
        expireLease(runId)
        val l2 = acquire(runId, "worker-2")
        val second = perform(executor(), l2, runtimeCreateOp(runId, ws))
        trace.record("second worker: ${second::class.simpleName}")
        assertEquals(OpOutcome.ReconciliationRequired::class, second::class)

        expect(runId) {
            operationStatus(OperationStatus.RECONCILIATION_REQUIRED)
            reconciliationRequired(true)
            externalRuntimeCount(1)
            externalSideEffectCount(1)
            providerPostCount(1)
            automaticReplayCount(0)
        }
    }
}
