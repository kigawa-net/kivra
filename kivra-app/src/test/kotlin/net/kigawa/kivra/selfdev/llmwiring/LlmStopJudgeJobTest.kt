package net.kigawa.kivra.selfdev.llmwiring

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.StopEnd
import net.kigawa.kivra.db.StopRequestState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.selfdev.llm.JudgeErrorCode
import net.kigawa.kivra.selfdev.llm.JudgePricing
import net.kigawa.kivra.selfdev.llm.JudgeResult
import net.kigawa.kivra.selfdev.llm.ParsedJudgment
import net.kigawa.kivra.selfdev.llm.JudgeUsage
import net.kigawa.kivra.selfdev.llm.LimitDecision
import net.kigawa.kivra.selfdev.llm.StopJudgeLimiter
import net.kigawa.kivra.selfdev.llm.StopJudgeLimits

/** The shadow judgement job (ADR 0021 L4) on H2 (MariaDB with `KIVRA_TEST_DB=mariadb`), with the real repositories and a fake model client. */
class LlmStopJudgeJobTest {

    @Test
    fun `a shadow APPROVE is recorded as WOULD_APPROVE and nothing about the order or the request changes`() = JudgeFixture().use { f ->
        val request = f.newRequest()
        val versionBefore = f.order().version
        val eventsBefore = f.eventCount()
        runBlocking { f.job.judgeRequest(request.requestId) }

        val row = f.rows().single()
        assertEquals("WOULD_APPROVE", row.outcome)
        assertEquals("CLEAR_STOP_REQUEST", row.reason)
        assertEquals("shadow", row.mode)
        assertEquals(MODEL_NAME, row.model)
        assertEquals(64, row.hash.length)
        assertEquals(1000, row.tokensIn)
        assertEquals(20, row.tokensOut)
        // 1000 in at 1_000_000 milli-yen per Mtok and 20 out at 5_000_000: 1000 + 100 milli-yen.
        assertEquals(1100L, row.cost)
        assertEquals(request.requestId, row.requestId)
        assertEquals(request.orderId, row.orderId)
        // Nothing was approved: the request is PENDING, the order is STOP_REQUESTED at the same version, and no event was written.
        val after = f.orders.stopRequest(request.requestId)!!
        assertEquals(StopRequestState.PENDING, after.state)
        assertNull(after.decidedBy)
        assertEquals(OrderState.STOP_REQUESTED, f.order().state)
        assertEquals(versionBefore, f.order().version)
        assertEquals(eventsBefore, f.eventCount())
        assertEquals(0, f.rows().count { it.outcome == "APPROVED" })
    }

    @Test
    fun `an ABSTAIN is recorded with its fixed reason code`() = JudgeFixture().use { f ->
        f.client.behavior = { ABSTAIN }
        val request = f.newRequest()
        runBlocking { f.job.judgeRequest(request.requestId) }
        val row = f.rows().single()
        assertEquals("ABSTAINED", row.outcome)
        assertEquals("INSUFFICIENT_EVIDENCE", row.reason)
    }

    @Test
    fun `the input has the fixed facts and the free text of the order, and the request reason is only a code`() = JudgeFixture().use { f ->
        val request = f.newRequest()
        runBlocking { f.job.judgeRequest(request.requestId) }
        val message = f.client.inputs.single().userMessage
        assertTrue(message.contains("stop_reason_code: RUNAWAY_SUSPECTED"))
        assertTrue(message.contains("requester_kind: AI"))
        assertTrue(message.contains("order_kind: ROOT"))
        assertTrue(message.contains("order_state_code: STOP_REQUESTED"))
        assertTrue(message.contains(TITLE_MARKER) && message.contains(POLICY_MARKER))
        assertFalse(message.contains(AI_REQUESTER) || message.contains(OPERATOR), "no owner key goes to the model")
    }

    @Test
    fun `the same request is judged once, however often it is swept`() = JudgeFixture().use { f ->
        val request = f.newRequest()
        runBlocking {
            f.job.judgeRequest(request.requestId)
            f.job.judgeRequest(request.requestId)
            f.job.sweep()
            f.job.sweep()
        }
        assertEquals(1, f.rows().size)
        assertEquals(1, f.client.calls.get())
        assertEquals(1, f.claims())
    }

    @Test
    fun `two jobs running at once write one row and call the model once`() = JudgeFixture().use { f ->
        val request = f.newRequest()
        val start = CountDownLatch(1)
        f.client.behavior = { delay(150); APPROVE }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                pool.submit {
                    start.await()
                    runBlocking { f.job.judgeRequest(request.requestId) }
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, f.rows().size)
        assertEquals(1, f.client.calls.get())
    }

    @Test
    fun `the sweep picks up a pending request that was never nudged, and leaves decided ones`() = JudgeFixture().use { f ->
        val pending = f.newRequest()
        runBlocking { f.job.sweep() }
        assertEquals(listOf(pending.requestId), f.rows().map { it.requestId })
        val second = f.newRequest()
        f.orders.endStopRequest(second.requestId, StopEnd.REJECTED, "someone-else", OrderActor.HUMAN)
        runBlocking { f.job.sweep() }
        assertEquals(1, f.rows().size, "a rejected request is not judged")
    }

    // ---- prerequisites (read again on every judgement; nothing cached) ----------------------------------------------------------

    @Test
    fun `with the flag off, the roles mode not enforce or no AI_APPROVER, SKIPPED is recorded and the model is not called`() {
        val cases = listOf(
            Triple(JudgeFixture(settingsOverride = settings(enabled = false)), "FLAG_OFF", 0),
            Triple(JudgeFixture(rolesMode = PermissionMode.SHADOW), "ROLES_NOT_ENFORCE", 1),
            Triple(JudgeFixture(rolesMode = PermissionMode.OFF), "ROLES_NOT_ENFORCE", 2),
            Triple(JudgeFixture(withApprover = false), "NO_ACTIVE_AI_APPROVER", 3),
        )
        for ((f, code, _) in cases) f.use {
            val request = f.newRequest()
            runBlocking { f.job.judgeRequest(request.requestId) }
            val row = f.rows().single()
            assertEquals("SKIPPED", row.outcome)
            assertEquals(code, row.reason)
            assertEquals(0, f.client.calls.get())
            assertEquals(StopRequestState.PENDING, f.orders.stopRequest(request.requestId)!!.state)
        }
    }

    @Test
    fun `disabling the AI_APPROVER takes effect from the next judgement, and enabling it again brings the judgement back`() = JudgeFixture().use { f ->
        val first = f.newRequest()
        runBlocking { f.job.judgeRequest(first.requestId) }
        assertEquals("WOULD_APPROVE", f.rows().last().outcome)

        val approver = f.assignees.find(checkNotNull(f.approverId))!!
        f.assignees.setState(approver.assigneeId, AssigneeState.DISABLED, approver.version, ADMIN_KEY)
        val second = f.newRequest()
        runBlocking { f.job.judgeRequest(second.requestId) }
        assertEquals("SKIPPED", f.rows().last().outcome)
        assertEquals("NO_ACTIVE_AI_APPROVER", f.rows().last().reason)
        assertEquals(1, f.client.calls.get())

        val disabled = f.assignees.find(approver.assigneeId)!!
        f.assignees.setState(disabled.assigneeId, AssigneeState.ACTIVE, disabled.version, ADMIN_KEY)
        val third = f.newRequest()
        runBlocking { f.job.judgeRequest(third.requestId) }
        assertEquals("WOULD_APPROVE", f.rows().last().outcome)
        assertEquals(2, f.client.calls.get())
    }

    // ---- limits ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the hourly call limit refuses the next call without asking the model`() = JudgeFixture(StopJudgeLimits(maxCallsPerHour = 1, maxApprovalsPerHour = 10)).use { f ->
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        assertEquals(1, f.client.calls.get())
        val last = f.rows().last()
        assertEquals("ABSTAINED", last.outcome)
        assertEquals("LIMIT_CALLS", last.reason)
        // The next hour: allowed again.
        f.clock.now = f.clock.now.plusSeconds(3600)
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        assertEquals(2, f.client.calls.get())
    }

    @Test
    fun `the hourly approval limit stops the model from being asked once it is used up`() = JudgeFixture(StopJudgeLimits(maxApprovalsPerHour = 1)).use { f ->
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        assertEquals("WOULD_APPROVE", f.rows().last().outcome)
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        assertEquals(1, f.client.calls.get(), "an APPROVE could not be used, so the model is not asked")
        assertEquals("LIMIT_APPROVALS", f.rows().last().reason)
        assertEquals("ABSTAINED", f.rows().last().outcome)
    }

    @Test
    fun `an approval limit of zero is refused before any call`() = JudgeFixture(StopJudgeLimits(maxApprovalsPerHour = 0)).use { f ->
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        assertEquals(0, f.client.calls.get())
        assertEquals("LIMIT_APPROVALS", f.rows().single().reason)
    }

    @Test
    fun `the monthly cost limit closes the month once a call passes it, and the model is not called again`() =
        JudgeFixture(
            settingsOverride = settings(StopJudgeLimits(maxMilliYenPerMonth = 1000, maxApprovalsPerHour = 100, maxCallsPerHour = 100), pricing = JudgePricing(400_000, 0)),
        ).use { f ->
            f.client.behavior = { ABSTAIN }
            repeat(5) { runBlocking { f.job.judgeRequest(f.newRequest().requestId) } }
            // 400 + 400 = 800 (under 1000); the third call costs 400 more, passes the month, and closes it; the fourth and fifth are refused.
            assertEquals(3, f.client.calls.get())
            assertEquals(listOf("ABSTAINED", "ABSTAINED", "ABSTAINED", "ABSTAINED", "ABSTAINED"), f.rows().map { it.outcome })
            assertEquals(listOf("LIMIT_COST", "LIMIT_COST"), f.rows().map { it.reason }.takeLast(2))
            assertTrue(f.rows().takeLast(2).all { it.tokensIn == 0 })
        }

    @Test
    fun `an input over the token limit is refused before any call`() = JudgeFixture(StopJudgeLimits(maxInputTokens = 1)).use { f ->
        runBlocking { f.job.judgeRequest(f.newRequest().requestId) }
        assertEquals(0, f.client.calls.get())
        assertEquals("LIMIT_TOKENS", f.rows().single().reason)
    }

    @Test
    fun `limiters of several Pods on one DB never pass the call limit`() = JudgeFixture(StopJudgeLimits(maxCallsPerHour = 3)).use { f ->
        val pool = Executors.newFixedThreadPool(8)
        try {
            val latch = CountDownLatch(1)
            val results = (1..16).map {
                // Each task has its own store object and limiter, as each Pod has.
                val limiter = StopJudgeLimiter(f.settings.limits, DbLimitsStore(f.db.database), f.clock)
                pool.submit<Boolean> { latch.await(); limiter.reserveCall(10) is LimitDecision.Allowed }
            }
            latch.countDown()
            assertEquals(3, results.count { it.get(20, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- API failure --------------------------------------------------------------------------------------------------------------

    @Test
    fun `a failed call, a timeout, an exception and an invalid output leave the request PENDING and record ERROR or ABSTAINED`() = JudgeFixture(callTimeout = Duration.ofMillis(300)).use { f ->
        f.client.behavior = { JudgeResult.Error(JudgeErrorCode.UPSTREAM) }
        val a = f.newRequest()
        runBlocking { f.job.judgeRequest(a.requestId) }
        assertEquals("ERROR" to "UPSTREAM", f.rows().last().outcome to f.rows().last().reason)

        f.client.behavior = { delay(5_000); APPROVE }
        val b = f.newRequest()
        runBlocking { f.job.judgeRequest(b.requestId) }
        assertEquals("ERROR" to "CALL_TIMEOUT", f.rows().last().outcome to f.rows().last().reason)

        f.client.behavior = { throw IllegalStateException("boom") }
        val c = f.newRequest()
        runBlocking { f.job.judgeRequest(c.requestId) }
        assertEquals("ERROR" to "JUDGE_INTERNAL_ERROR", f.rows().last().outcome to f.rows().last().reason)

        f.client.behavior = { JudgeResult.Judged(ParsedJudgment.INVALID, JudgeUsage(100, 5)) }
        val d = f.newRequest()
        runBlocking { f.job.judgeRequest(d.requestId) }
        assertEquals("ABSTAINED" to "INVALID_OUTPUT", f.rows().last().outcome to f.rows().last().reason)

        assertEquals(StopRequestState.PENDING, f.orders.stopRequest(d.requestId)!!.state)
        assertEquals(OrderState.STOP_REQUESTED, f.order().state)
        assertEquals(4, f.rows().size)
    }

    @Test
    fun `creating a stop request is neither failed nor delayed by the judgement`() = JudgeFixture().use { f ->
        val gate = CompletableDeferred<Unit>()
        f.client.behavior = { gate.await(); APPROVE }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            f.orders.setStopRequestListener { id -> f.job.nudge(id) }
            f.job.start(scope)
            val started = System.nanoTime()
            val request = f.newRequest()
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMillis < 3_000, "the request returned in $tookMillis ms while the model call is stuck")
            assertEquals(StopRequestState.PENDING, f.orders.stopRequest(request.requestId)!!.state)
            // The nudge reached the job (the model is being asked, stuck on the gate); let it finish and the row appears.
            waitFor { f.client.calls.get() == 1 }
            gate.complete(Unit)
            waitFor { f.rows().size == 1 }
            assertEquals("WOULD_APPROVE", f.rows().single().outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a listener that throws does not fail the request`() = JudgeFixture().use { f ->
        f.orders.setStopRequestListener { error("listener down") }
        val request = f.newRequest()
        assertEquals(StopRequestState.PENDING, f.orders.stopRequest(request.requestId)!!.state)
    }

    // ---- re-check of the request ----------------------------------------------------------------------------------------------------

    @Test
    fun `a request that is already rejected, cancelled or expired is not judged and nothing is recorded`() = JudgeFixture().use { f ->
        val rejected = f.newRequest()
        f.orders.endStopRequest(rejected.requestId, StopEnd.REJECTED, "approver-human", OrderActor.HUMAN)
        runBlocking { f.job.judgeRequest(rejected.requestId) }

        val cancelled = f.newRequest()
        f.orders.endStopRequest(cancelled.requestId, StopEnd.CANCELLED, OPERATOR, OrderActor.HUMAN)
        runBlocking { f.job.judgeRequest(cancelled.requestId) }

        val expiring = f.newRequest()
        f.clock.now = f.clock.now.plus(Duration.ofHours(25))
        runBlocking { f.job.judgeRequest(expiring.requestId) }

        assertEquals(0, f.client.calls.get())
        assertEquals(0, f.rows().size)
        assertEquals(0, f.claims())
        runBlocking { f.job.judgeRequest("01ZZZZZZZZZZZZZZZZZZZZZZZZ") }
        assertEquals(0, f.rows().size)
    }

    @Test
    fun `an order that moved since the request is SKIPPED as REQUEST_CHANGED`() = JudgeFixture().use { f ->
        val request = f.newRequest()
        f.exec("UPDATE selfdev_orders SET version = version + 1 WHERE order_id = ?", request.orderId)
        runBlocking { f.job.judgeRequest(request.requestId) }
        assertEquals("SKIPPED" to "REQUEST_CHANGED", f.rows().single().outcome to f.rows().single().reason)
        assertEquals(0, f.client.calls.get())
    }

    // ---- no leak ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `logs and records hold no owner key, no free text, no model output and no exception message`() = LogCapture().use { log ->
        val outputMarker = "MODEL-OUTPUT-MARKER"
        JudgeFixture().use { f ->
            val a = f.newRequest()
            runBlocking { f.job.judgeRequest(a.requestId) }
            f.client.behavior = { throw IllegalStateException("EXC-MESSAGE-MARKER $TITLE_MARKER $outputMarker") }
            val b = f.newRequest()
            runBlocking { f.job.judgeRequest(b.requestId) }
            f.client.behavior = { JudgeResult.Error(JudgeErrorCode.TRANSPORT) }
            runBlocking { f.job.judgeRequest(f.newRequest().requestId) }

            val everything = log.text() + f.rows().joinToString("\n") { listOf(it.requestId, it.orderId, it.outcome, it.reason, it.model, it.hash, it.mode).joinToString(" ") }
            for (marker in listOf(OPERATOR, AI_REQUESTER, TITLE_MARKER, POLICY_MARKER, outputMarker, "EXC-MESSAGE-MARKER", "approver")) {
                assertFalse(everything.contains(marker), "must not contain $marker")
            }
            assertTrue(log.messages().any { it.contains("outcome=WOULD_APPROVE") }, "the fixed codes are logged")
        }
    }

    private fun waitFor(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }
}
