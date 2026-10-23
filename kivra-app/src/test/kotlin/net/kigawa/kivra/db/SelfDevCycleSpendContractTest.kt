package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.coding.DbCodingCostLedger
import net.kigawa.kivra.developer.LlmDeveloper
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.selfdev.plannerRequestId

/**
 * The contract of the budget key forms that the cycle cost reader matches (ADR 0015 addendum 2026-10-23, section B and C.5/C.6, U-36:
 * until a key-to-goal table exists, a change of a key format is caught here). Each key is built by the production code. A change in one
 * of the three forms makes one of these tests fail, because the reader would otherwise under-count that path silently.
 */
class SelfDevCycleSpendContractTest {

    /** The ULID alphabet (Crockford base32, no I, L, O, U). */
    private val ulid = "[0-9A-HJKMNP-TV-Z]{26}"

    @Test
    fun `the CLI key is coding of the attempt id and the lease generation`() {
        val attemptId = Ulid.generate()
        val key = DbCodingCostLedger.reservationKey(attemptId, 7)

        assertEquals("coding:$attemptId:7", key)
        assertTrue(Regex("coding:$ulid:[0-9]+").matches(key))
        assertEquals("coding:", DbBudgetStore.CODING_KEY_PREFIX)
    }

    @Test
    fun `the API Developer key starts with developer, the run id and a dash`() {
        val runId = Ulid.generate()
        val requestId = LlmDeveloper.requestIdOf(runId, 2)
        val key = ModelGateway.ledgerKey(requestId, 1)

        assertEquals("developer-$runId-2", requestId)
        assertEquals("developer-$runId-2/developer-$runId-2-a1", key)
        assertTrue(key.startsWith(SelfDevCycleSpendReader.DEVELOPER_PREFIX + runId + "-"))
        assertTrue(Regex("developer-$ulid-[0-9]+/developer-$ulid-[0-9]+-a[0-9]+").matches(key))
        assertEquals("developer-", SelfDevCycleSpendReader.DEVELOPER_PREFIX)
    }

    @Test
    fun `the Planner key starts with planner, the goal id and a dash`() {
        val goalId = Ulid.generate()
        val requestId = plannerRequestId(goalId, 3L, 2)
        val key = ModelGateway.ledgerKey(requestId, 2)

        assertEquals("planner-$goalId-3-2", requestId)
        assertEquals("planner-$goalId-3-2/planner-$goalId-3-2-a2", key)
        assertTrue(key.startsWith(SelfDevCycleSpendReader.PLANNER_PREFIX + goalId + "-"))
        assertTrue(Regex("planner-$ulid-[0-9]+-[0-9]+/planner-$ulid-[0-9]+-[0-9]+-a[0-9]+").matches(key))
        assertEquals("planner-", SelfDevCycleSpendReader.PLANNER_PREFIX)
    }
}
