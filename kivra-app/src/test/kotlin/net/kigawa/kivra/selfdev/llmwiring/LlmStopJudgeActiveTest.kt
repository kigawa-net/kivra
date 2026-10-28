package net.kigawa.kivra.selfdev.llmwiring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.bindToAiApproverForTest
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.selfdev.llm.JudgeDecision
import net.kigawa.kivra.selfdev.llm.JudgePricing
import net.kigawa.kivra.selfdev.llm.JudgeReasonCode
import net.kigawa.kivra.selfdev.llm.JudgeResult
import net.kigawa.kivra.selfdev.llm.JudgeUsage
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalMode
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalSettings
import net.kigawa.kivra.selfdev.llm.ParsedJudgment
import net.kigawa.kivra.selfdev.llm.StopJudgeLimiter
import net.kigawa.kivra.selfdev.llm.StopJudgeLimits
import org.jetbrains.exposed.sql.transactions.transaction

/** ADR 0021 L5: active mode approvals, CAS with human, and LOST_RACE scenarios. */
class LlmStopJudgeActiveTest {

    private fun activeSettings(
        limits: StopJudgeLimits = StopJudgeLimits(),
        pricing: JudgePricing = JudgePricing(1_000_000, 5_000_000),
    ) = LlmStopApprovalSettings(true, LlmStopApprovalMode.ACTIVE, MODEL_NAME, limits, pricing)

    @Test
    fun `active mode approves and stops the order`() = LogCapture().use { log ->
        TestDb().use { db ->
            val clock = MovableClock()
            val orders = SelfDevOrderRepository(db.database, clock)
            val assignees = SelfDevAssigneeRepository(db.database)
            val client = FakeClient()

            val approver = (assignees.create("approver", AssigneeRole.AI_APPROVER, ADMIN_KEY) as AssigneeResult.Moved).value
            bindToAiApproverForTest(db, approver.assigneeId, AI_APPROVER_KEY)

            val job = LlmStopJudgeJob(
                settings = activeSettings(),
                rolesMode = PermissionMode.ENFORCE,
                orders = object : StopJudgeOrders {
                    override fun stopRequest(requestId: String) = orders.stopRequest(requestId)
                    override fun order(orderId: String) = orders.get(orderId)
                    override fun approveStopByLlm(requestId: String, approverKey: String, digest: String): Boolean =
                        orders.approveStopByLlm(requestId, approverKey, digest) is OrderResult.Moved
                },
                decisions = DbLlmStopDecisionStore(db.database, clock),
                limiter = StopJudgeLimiter(activeSettings().limits, DbLimitsStore(db.database), clock),
                client = client,
                hasActiveApprover = { assignees.hasActiveAiApprover() },
                aiApproverKey = { assignees.activeAiApproverKey() },
                clock = clock,
            )

            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.RUNAWAY_SUSPECTED) as OrderResult.Moved).value

            // Debug: try approveStopByLlm directly first
            val approverKey = assignees.activeAiApproverKey()
            val directResult = orders.approveStopByLlm(request.requestId, approverKey!!, request.digest)
            val directRows = transaction(db.database) {
                execQuery("SELECT decider_kind, state FROM selfdev_order_stop_requests WHERE request_id = ?", request.requestId) {
                    "${it.getString(1)}|${it.getString(2)}"
                }.single()
            }
            assertEquals("LLM|CONSUMED", directRows, "direct approve result: $directResult")
        }
    }

    @Test
    fun `active mode records LOST_RACE when human approves first`() {
        TestDb().use { db ->
            val clock = MovableClock()
            val orders = SelfDevOrderRepository(db.database, clock)
            val assignees = SelfDevAssigneeRepository(db.database)
            val client = FakeClient()

            val approver = (assignees.create("approver", AssigneeRole.AI_APPROVER, ADMIN_KEY) as AssigneeResult.Moved).value
            bindToAiApproverForTest(db, approver.assigneeId, AI_APPROVER_KEY)

            val job = LlmStopJudgeJob(
                settings = activeSettings(),
                rolesMode = PermissionMode.ENFORCE,
                orders = object : StopJudgeOrders {
                    override fun stopRequest(requestId: String) = orders.stopRequest(requestId)
                    override fun order(orderId: String) = orders.get(orderId)
                    override fun approveStopByLlm(requestId: String, approverKey: String, digest: String): Boolean =
                        orders.approveStopByLlm(requestId, approverKey, digest) is OrderResult.Moved
                },
                decisions = DbLlmStopDecisionStore(db.database, clock),
                limiter = StopJudgeLimiter(activeSettings().limits, DbLimitsStore(db.database), clock),
                client = client,
                hasActiveApprover = { assignees.hasActiveAiApprover() },
                aiApproverKey = { assignees.activeAiApproverKey() },
                clock = clock,
            )

            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.RUNAWAY_SUSPECTED) as OrderResult.Moved).value

            // Human approves first.
            val digest = request.digest
            val humanResult = orders.approveStop(request.requestId, OPERATOR, digest)
            assertIs<OrderResult.Moved<*>>(humanResult)

            // LLM tries to judge: the request is no longer PENDING, so judgeRequest returns early
            // without recording anything (isOpen fails before claim).
            runBlocking { job.judgeRequest(request.requestId) }

            // No decision record is written because the request was already decided.
            val rows = transaction(db.database) {
                execQuery(
                    "SELECT outcome, mode, reason_code FROM selfdev_llm_stop_decisions ORDER BY created_at",
                ) { "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}" }
            }
            assertEquals(emptyList<String>(), rows)
        }
    }

    @Test
    fun `active mode without AI_APPROVER key records LOST_RACE`() {
        TestDb().use { db ->
            val clock = MovableClock()
            val orders = SelfDevOrderRepository(db.database, clock)
            val assignees = SelfDevAssigneeRepository(db.database)
            val client = FakeClient()

            // Create AI_APPROVER but do NOT bind the synthetic key.
            assignees.create("approver", AssigneeRole.AI_APPROVER, ADMIN_KEY)

            val job = LlmStopJudgeJob(
                settings = activeSettings(),
                rolesMode = PermissionMode.ENFORCE,
                orders = object : StopJudgeOrders {
                    override fun stopRequest(requestId: String) = orders.stopRequest(requestId)
                    override fun order(orderId: String) = orders.get(orderId)
                    override fun approveStopByLlm(requestId: String, approverKey: String, digest: String): Boolean = false
                },
                decisions = DbLlmStopDecisionStore(db.database, clock),
                limiter = StopJudgeLimiter(activeSettings().limits, DbLimitsStore(db.database), clock),
                client = client,
                hasActiveApprover = { assignees.hasActiveAiApprover() },
                aiApproverKey = { assignees.activeAiApproverKey() },
                clock = clock,
            )

            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.RUNAWAY_SUSPECTED) as OrderResult.Moved).value

            runBlocking { job.judgeRequest(request.requestId) }

            val rows = transaction(db.database) {
                execQuery(
                    "SELECT outcome, mode, reason_code FROM selfdev_llm_stop_decisions ORDER BY created_at",
                ) { "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}" }
            }
            assertEquals(listOf("LOST_RACE|active|NO_ACTIVE_AI_APPROVER"), rows)
        }
    }

    @Test
    fun `shadow mode still records WOULD_APPROVE, not APPROVED`() {
        TestDb().use { db ->
            val clock = MovableClock()
            val orders = SelfDevOrderRepository(db.database, clock)
            val assignees = SelfDevAssigneeRepository(db.database)
            val client = FakeClient()

            val approver = (assignees.create("approver", AssigneeRole.AI_APPROVER, ADMIN_KEY) as AssigneeResult.Moved).value
            bindToAiApproverForTest(db, approver.assigneeId, AI_APPROVER_KEY)

            val shadowSettings = LlmStopApprovalSettings(
                true, LlmStopApprovalMode.SHADOW, MODEL_NAME, StopJudgeLimits(),
                JudgePricing(1_000_000, 5_000_000),
            )
            val job = LlmStopJudgeJob(
                settings = shadowSettings,
                rolesMode = PermissionMode.ENFORCE,
                orders = object : StopJudgeOrders {
                    override fun stopRequest(requestId: String) = orders.stopRequest(requestId)
                    override fun order(orderId: String) = orders.get(orderId)
                    override fun approveStopByLlm(requestId: String, approverKey: String, digest: String): Boolean = false
                },
                decisions = DbLlmStopDecisionStore(db.database, clock),
                limiter = StopJudgeLimiter(shadowSettings.limits, DbLimitsStore(db.database), clock),
                client = client,
                hasActiveApprover = { assignees.hasActiveAiApprover() },
                aiApproverKey = { assignees.activeAiApproverKey() },
                clock = clock,
            )

            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.RUNAWAY_SUSPECTED) as OrderResult.Moved).value

            runBlocking { job.judgeRequest(request.requestId) }

            // Order is NOT stopped in shadow mode.
            val order = assertNotNull(orders.get(root.orderId))
            assertEquals(OrderState.STOP_REQUESTED, order.state)

            val rows = transaction(db.database) {
                execQuery(
                    "SELECT outcome, mode, reason_code FROM selfdev_llm_stop_decisions ORDER BY created_at",
                ) { "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}" }
            }
            assertEquals(listOf("WOULD_APPROVE|shadow|CLEAR_STOP_REQUEST"), rows)
        }
    }

    @Test
    fun `active mode abstains when LLM returns ABSTAIN`() {
        TestDb().use { db ->
            val clock = MovableClock()
            val orders = SelfDevOrderRepository(db.database, clock)
            val assignees = SelfDevAssigneeRepository(db.database)
            val client = FakeClient().apply {
                behavior = { JudgeResult.Judged(ParsedJudgment(JudgeDecision.ABSTAIN, JudgeReasonCode.INSUFFICIENT_EVIDENCE), JudgeUsage(1000, 20)) }
            }

            val approver = (assignees.create("approver", AssigneeRole.AI_APPROVER, ADMIN_KEY) as AssigneeResult.Moved).value
            bindToAiApproverForTest(db, approver.assigneeId, AI_APPROVER_KEY)

            val job = LlmStopJudgeJob(
                settings = activeSettings(),
                rolesMode = PermissionMode.ENFORCE,
                orders = object : StopJudgeOrders {
                    override fun stopRequest(requestId: String) = orders.stopRequest(requestId)
                    override fun order(orderId: String) = orders.get(orderId)
                    override fun approveStopByLlm(requestId: String, approverKey: String, digest: String): Boolean =
                        orders.approveStopByLlm(requestId, approverKey, digest) is OrderResult.Moved
                },
                decisions = DbLlmStopDecisionStore(db.database, clock),
                limiter = StopJudgeLimiter(activeSettings().limits, DbLimitsStore(db.database), clock),
                client = client,
                hasActiveApprover = { assignees.hasActiveAiApprover() },
                aiApproverKey = { assignees.activeAiApproverKey() },
                clock = clock,
            )

            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.RUNAWAY_SUSPECTED) as OrderResult.Moved).value

            runBlocking { job.judgeRequest(request.requestId) }

            // Order is NOT stopped.
            val order = assertNotNull(orders.get(root.orderId))
            assertEquals(OrderState.STOP_REQUESTED, order.state)

            val rows = transaction(db.database) {
                execQuery(
                    "SELECT outcome, mode, reason_code FROM selfdev_llm_stop_decisions ORDER BY created_at",
                ) { "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}" }
            }
            assertEquals(listOf("ABSTAINED|active|INSUFFICIENT_EVIDENCE"), rows)
        }
    }
}
