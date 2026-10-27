package net.kigawa.kivra.selfdev.llmwiring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.StopEnd
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.selfdev.llm.JudgeDecision
import net.kigawa.kivra.selfdev.llm.JudgeInput
import net.kigawa.kivra.selfdev.llm.JudgePricing
import net.kigawa.kivra.selfdev.llm.JudgeReasonCode
import net.kigawa.kivra.selfdev.llm.JudgeResult
import net.kigawa.kivra.selfdev.llm.JudgeUsage
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalMode
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalSettings
import net.kigawa.kivra.selfdev.llm.ParsedJudgment
import net.kigawa.kivra.selfdev.llm.StopJudgeClient
import net.kigawa.kivra.selfdev.llm.StopJudgeLimiter
import net.kigawa.kivra.selfdev.llm.StopJudgeLimits
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

internal const val OPERATOR = "op-owner-key-SECRET"
internal const val AI_REQUESTER = "ai-owner-key-SECRET"
internal const val TITLE_MARKER = "TITLE-MARKER-XYZ"
internal const val POLICY_MARKER = "POLICY-MARKER-XYZ"
internal const val MODEL_NAME = "test-model-1"
internal const val ADMIN_KEY = "6:oidc:root"
internal val START: Instant = Instant.parse("2026-10-27T10:00:00Z")

internal class MovableClock(var now: Instant = START) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

internal val APPROVE = JudgeResult.Judged(ParsedJudgment(JudgeDecision.APPROVE, JudgeReasonCode.CLEAR_STOP_REQUEST), JudgeUsage(1000, 20))
internal val ABSTAIN = JudgeResult.Judged(ParsedJudgment(JudgeDecision.ABSTAIN, JudgeReasonCode.INSUFFICIENT_EVIDENCE), JudgeUsage(1000, 20))

internal class FakeClient(var behavior: suspend (JudgeInput) -> JudgeResult = { APPROVE }) : StopJudgeClient {
    val calls = AtomicInteger()
    val inputs = CopyOnWriteArrayList<JudgeInput>()
    override suspend fun judge(input: JudgeInput): JudgeResult {
        calls.incrementAndGet()
        inputs.add(input)
        return behavior(input)
    }
}

internal fun settings(
    limits: StopJudgeLimits = StopJudgeLimits(),
    enabled: Boolean = true,
    mode: LlmStopApprovalMode = LlmStopApprovalMode.SHADOW,
    pricing: JudgePricing = JudgePricing(1_000_000, 5_000_000),
) = LlmStopApprovalSettings(enabled, mode, MODEL_NAME, limits, pricing)

internal class DecisionRow(
    val requestId: String, val orderId: String, val outcome: String, val reason: String, val model: String, val hash: String,
    val mode: String, val tokensIn: Int, val tokensOut: Int, val cost: Long,
)

/** A migrated DB, the real repositories, a fake model client and the job under test. */
internal class JudgeFixture(
    limits: StopJudgeLimits = StopJudgeLimits(),
    settingsOverride: LlmStopApprovalSettings? = null,
    rolesMode: PermissionMode = PermissionMode.ENFORCE,
    withApprover: Boolean = true,
    callTimeout: Duration = Duration.ofSeconds(5),
) : AutoCloseable {
    val db = TestDb()
    val clock = MovableClock()
    val orders = SelfDevOrderRepository(db.database, clock)
    val assignees = SelfDevAssigneeRepository(db.database)
    val decisions = DbLlmStopDecisionStore(db.database, clock)
    val client = FakeClient()
    val approverId: String? = if (withApprover) (assignees.create("approver", AssigneeRole.AI_APPROVER, ADMIN_KEY) as AssigneeResult.Moved).value.assigneeId else null
    val settings: LlmStopApprovalSettings = settingsOverride ?: settings(limits)
    val job = LlmStopJudgeJob(
        settings = settings,
        rolesMode = rolesMode,
        orders = object : StopJudgeOrders {
            override fun stopRequest(requestId: String): SelfDevOrderStopRequest? = orders.stopRequest(requestId)
            override fun order(orderId: String): SelfDevOrder? = orders.get(orderId)
        },
        decisions = decisions,
        limiter = StopJudgeLimiter(settings.limits, DbLimitsStore(db.database), clock),
        client = client,
        hasActiveApprover = { assignees.hasActiveAiApprover() },
        clock = clock,
        callTimeout = callTimeout,
    )

    private var root: SelfDevOrder? = null

    fun order(): SelfDevOrder = orders.get(checkNotNull(root).orderId)!!

    /** Makes a PENDING stop request by the AI principal (creates the root on the first call; ends and resumes the previous one). */
    fun newRequest(reason: StopRequestReason = StopRequestReason.RUNAWAY_SUSPECTED): SelfDevOrderStopRequest {
        // One second per request, so the rows of the decision record have distinct creation times (a ULID of one millisecond is not ordered).
        clock.now = clock.now.plusSeconds(1)
        if (root == null) {
            root = (orders.createRoot(OPERATOR, TITLE_MARKER, POLICY_MARKER, "kigawa-net/kivra") as CreateRootResult.Created).order
        } else {
            val pending = orders.pendingStopRequestOf(root!!.orderId)
            if (pending != null) assertIs<OrderResult.Moved<*>>(orders.endStopRequest(pending.requestId, StopEnd.CANCELLED, OPERATOR, OrderActor.HUMAN))
            val paused = order()
            assertIs<OrderResult.Moved<*>>(orders.resumeByOperator(paused.orderId, paused.version, OPERATOR))
        }
        val o = order()
        val moved = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(orders.requestStop(o.orderId, o.version, AI_REQUESTER, OrderActor.AI, reason))
        return moved.value
    }

    fun rows(): List<DecisionRow> = transaction(db.database) {
        execQuery(
            "SELECT request_id, order_id, outcome, reason_code, model, input_hash, mode, tokens_in, tokens_out, cost_millijpy FROM selfdev_llm_stop_decisions ORDER BY created_at, decision_id",
        ) { DecisionRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getInt(8), it.getInt(9), it.getLong(10)) }
    }

    fun claims(): Int = transaction(db.database) { execQuery("SELECT COUNT(*) FROM selfdev_llm_stop_claims") { it.getInt(1) }.single() }

    fun eventCount(): Int = transaction(db.database) { execQuery("SELECT COUNT(*) FROM selfdev_order_events") { it.getInt(1) }.single() }

    fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

    override fun close() = db.close()
}

/** Captures the `net.kigawa.kivra` loggers only (the MariaDB driver logs input values at TRACE, so the root logger is never opened to ALL). */
internal class LogCapture : AutoCloseable {
    private val logger = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }
    private val previous: Level? = logger.level

    init {
        logger.level = Level.ALL
        logger.addAppender(appender)
    }

    fun text(): String = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") + " " + it.mdcPropertyMap }

    fun messages(): List<String> = appender.list.map { it.formattedMessage }

    override fun close() {
        logger.detachAppender(appender)
        logger.level = previous
        appender.stop()
    }
}
