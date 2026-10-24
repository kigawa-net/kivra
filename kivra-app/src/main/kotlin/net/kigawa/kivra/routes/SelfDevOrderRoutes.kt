package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.DEFAULT_CYCLE_BUDGET_JPY
import net.kigawa.kivra.db.ChildRefusal
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.OrderCycle
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.StopEnd
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.stopCycleGoalOf
import org.slf4j.LoggerFactory

/*
 * The self-development orders' API (ADR 0015 stage 1, Issue #273). Authentication is the operator's, as for the autopilot routes
 * ([OperatorAuthenticator]: the OIDC token with the required role, and an agent token is refused).
 *
 * Two kinds of caller are told apart by their owner key: the AI principal (`KIVRA_SELFDEV_AI_PRINCIPAL`) and every other operator (a human).
 * The AI principal may read and request a stop, and nothing else (ADR 0015 section 4.6). The root approver
 * (`KIVRA_SELFDEV_ROOT_APPROVER`) is the only one who approves, rejects a stop or sees the list of pending requests. For the root's stop
 * the approver is never the requester (the repository and the schema check it); a child's stop is the parent's decision, so the root
 * approver may decide a child's stop it requested itself (ADR 0015 section 4.2 (a)).
 *
 * Everything answers 503 while the feature is off. Errors use fixed text, and a refusal names its fixed code (an enum name); no request
 * value is echoed. A title, a policy, and a request's text are returned only by the detail view, and never logged.
 */
class SelfDevOrderRouteDeps(
    val operators: OperatorAuthenticator,
    val autopilot: AutopilotSettings,
    val orderSettings: OrderSettings,
    /** The AI principal's owner key (`KIVRA_SELFDEV_AI_PRINCIPAL`), or null when it is not configured. Read whatever the order switch says. */
    val aiPrincipal: String?,
    /** `owner/repo` of the target. Stored with the order at creation (fixed). */
    val targetRepo: String?,
    val orders: SelfDevOrderRepository,
    val goals: SelfDevGoalRepository,
    val operations: SelfDevOperatorRepository,
)

@Serializable
data class CreateOrderRequest(
    val title: String,
    val policy: String,
    /** The most cycles the order may run (ADR 0015 section 3.2). Absent means 1. Checked against 1..`KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`. */
    val maxCycles: Int? = null,
)

@Serializable
data class OrderVersionRequest(val version: Long)

/** A child order (ADR 0015 section 2.7). The same title and policy rules as the root; the budget is whole yen. */
@Serializable
data class CreateChildRequest(val title: String, val policy: String, val orderBudgetJpy: Long, val idempotencyKey: String)

@Serializable
data class RequestStopRequest(val version: Long, val reasonCode: String)

@Serializable
data class ApproveStopRequest(val digest: String)

@Serializable
data class OrderSummaryResponse(
    val orderId: String,
    val parentOrderId: String?,
    val depth: Int,
    val state: String,
    val stateReason: String?,
    val cyclesStarted: Int,
    val maxCycles: Int,
    val currentGoalId: String?,
    val consecutiveFailedCycles: Int,
    val expiresAt: String,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
    val orderBudgetJpy: Long,
    val allocatedJpy: Long,
    val childCount: Int,
    val spentJpy: Long,
    val cycleBudgetJpy: Long,
    /** orderBudgetJpy - spentJpy. */
    val remainingJpy: Long,
)

@Serializable
data class OrderDetailResponse(
    val order: OrderSummaryResponse,
    val title: String,
    val policy: String,
    val pendingStopRequest: StopRequestResponse?,
)

@Serializable
data class StopRequestResponse(
    val requestId: String,
    val orderId: String,
    val requesterKind: String,
    val reasonCode: String,
    val state: String,
    val orderVersion: Long,
    /** Shown to the root approver only: the value the approval must echo. */
    val digest: String?,
    val expiresAt: String,
    val createdAt: String,
    /** True when the request stops the root; false for a child, which its direct parent (the root approver) decides. */
    val targetIsRoot: Boolean,
)

@Serializable
data class CycleResponse(val cycleNo: Int, val goalId: String, val goalState: String, val stateReason: String?, val createdAt: String)

@Serializable
data class CycleStartedResponse(val orderId: String, val goalId: String, val cycleNo: Int, val version: Long)

@Serializable
private data class OrderError(val error: String, val code: String? = null, val orderId: String? = null)

/** Who is calling: the owner key and whether it is the AI principal. */
private data class OrderCaller(val principal: String, val kind: OrderActor)

private val strictJson = Json { ignoreUnknownKeys = false }
private val ORDER_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val DIGEST_SHAPE = Regex("[0-9a-f]{64}")
private const val LIST_LIMIT = 50
private const val MAX_TITLE_CODE_POINTS = 200
private const val MAX_POLICY_CODE_POINTS = 2_000
private const val MAX_CHILD_KEY_CODE_POINTS = 128
/** The largest value of DECIMAL(12,0), the budget columns' type. */
private const val MAX_BUDGET_JPY = 999_999_999_999L

private const val ORDERS_DISABLED = "self-development orders are disabled"
private const val INVALID_BODY = "invalid request body"
private const val TITLE_INVALID = "title must be 1-200 characters without control characters"
private const val POLICY_INVALID = "policy must be 1-2000 characters, and may contain line breaks but no other control characters"
private const val ORDER_NOT_FOUND = "order not found"
private const val REQUEST_NOT_FOUND = "stop request not found"
private const val FORBIDDEN = "forbidden"
private const val STALE = "the order changed; reload and try again"
private const val ROOT_EXISTS = "the root order already exists"
private const val REASON_INVALID = "reasonCode is not one of the fixed codes"
private const val DIGEST_INVALID = "digest must be 64 lowercase hex characters"
private const val STATE_INVALID = "state must be PENDING"
private const val CHILDREN_DISABLED = "child orders are disabled"
private const val CHILD_FIELDS_INVALID = "title and policy as for an order, and idempotencyKey 1-128 characters without control characters"
private const val MAX_CYCLES_INVALID = "maxCycles must be a whole number from 1 to the configured cap"
private const val MAX_CYCLES_INVALID_CODE = "MAX_CYCLES_INVALID"
private const val BUDGET_INVALID = "orderBudgetJpy must be a whole number of yen from 1 to 999999999999"
private const val BUDGET_BELOW_CYCLE = "orderBudgetJpy must be at least the cycle budget of 1000 yen"
private const val PARENT_NOT_ACTIVE = "the parent order is not active; a child needs an active parent"
private const val KEY_CONFLICT = "idempotency key was already used with a different request"

private val operatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.order.operator")

internal fun Route.configureSelfDevOrderRoutes(deps: SelfDevOrderRouteDeps) {
    get("/api/selfdev/orders") {
        if (deps.caller(call) == null) return@get
        if (!deps.requireEnabled(call)) return@get
        call.respond(deps.orders.list(LIST_LIMIT).map { it.summary() })
    }

    post("/api/selfdev/orders") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (caller.kind == OrderActor.AI) return@post call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
        val request = decodeOr<CreateOrderRequest>(call) ?: return@post
        val title = validatedText(request.title, MAX_TITLE_CODE_POINTS)
        val policy = validatedPolicy(request.policy)
        if (title == null) return@post call.respond(HttpStatusCode.BadRequest, OrderError(TITLE_INVALID))
        if (policy == null) return@post call.respond(HttpStatusCode.BadRequest, OrderError(POLICY_INVALID))
        val maxCycles = request.maxCycles ?: 1
        if (maxCycles !in 1..deps.orderSettings.maxCyclesCap) {
            return@post call.respond(HttpStatusCode.BadRequest, OrderError(MAX_CYCLES_INVALID, MAX_CYCLES_INVALID_CODE))
        }
        val repo = deps.targetRepo ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, OrderError(ORDERS_DISABLED))
        when (val created = deps.orders.createRoot(caller.principal, title, policy, repo, maxCycles)) {
            is CreateRootResult.Created -> {
                operatorLog.info("order operator action: action=ORDER_CREATED actor={} orderId={} result=ACTIVE", operatorTag(caller.principal), created.order.orderId)
                call.respond(HttpStatusCode.Created, created.order.summary())
            }
            CreateRootResult.RootExists -> call.respond(HttpStatusCode.Conflict, OrderError(ROOT_EXISTS))
        }
    }

    get("/api/selfdev/orders/{id}") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        val order = orderIdOf(call)?.let { deps.orders.get(it) }
        if (order == null) return@get call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        val pending = deps.orders.pendingStopRequestOf(order.orderId)
        // The title and the policy are the operators' text: returned here only, to every operator (the same as the goal detail).
        call.respond(
            OrderDetailResponse(
                order = order.summary(),
                title = order.title,
                policy = order.policy,
                pendingStopRequest = pending?.response(caller.isRootApprover(deps)),
            ),
        )
    }

    get("/api/selfdev/orders/{id}/cycles") {
        if (deps.caller(call) == null) return@get
        if (!deps.requireEnabled(call)) return@get
        val order = orderIdOf(call)?.let { deps.orders.get(it) }
        if (order == null) return@get call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        call.respond(deps.orders.cyclesOf(order.orderId).map { it.response() })
    }

    post("/api/selfdev/orders/{id}/cycles") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!caller.isHuman(call)) return@post
        val orderId = orderIdOf(call)
        val version = call.readVersion() ?: return@post
        if (orderId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        when (val r = deps.orders.startCycle(orderId, version, caller.principal)) {
            is OrderResult.Moved -> {
                operatorLog.info("order operator action: action=CYCLE_STARTED actor={} orderId={} cycleNo={}", operatorTag(caller.principal), orderId, r.value.cycleNo)
                call.respond(
                    HttpStatusCode.Created,
                    CycleStartedResponse(orderId, r.value.goalId, r.value.cycleNo, r.value.order.version),
                )
            }
            else -> call.respondOutcome(r)
        }
    }

    post("/api/selfdev/orders/{id}/children") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        // Only the operator creates a child (ADR 0015 U-19). The AI principal is refused before the body is read.
        if (!caller.isHuman(call)) return@post
        if (deps.orderSettings.maxDepth < 1) return@post call.respond(HttpStatusCode.Conflict, OrderError(CHILDREN_DISABLED, "CHILDREN_DISABLED"))
        val request = decodeOr<CreateChildRequest>(call) ?: return@post
        val title = validatedText(request.title, MAX_TITLE_CODE_POINTS)
        val policy = validatedPolicy(request.policy)
        val key = validatedText(request.idempotencyKey, MAX_CHILD_KEY_CODE_POINTS)
        if (title == null || policy == null || key == null) return@post call.respond(HttpStatusCode.BadRequest, OrderError(CHILD_FIELDS_INVALID))
        if (request.orderBudgetJpy !in 1..MAX_BUDGET_JPY) return@post call.respond(HttpStatusCode.BadRequest, OrderError(BUDGET_INVALID))
        // A child's cycle budget is the default (ADR 0015 section 3.2). A child whose budget is below it could never start a cycle.
        if (request.orderBudgetJpy < DEFAULT_CYCLE_BUDGET_JPY) return@post call.respond(HttpStatusCode.BadRequest, OrderError(BUDGET_BELOW_CYCLE))
        val parentId = orderIdOf(call) ?: return@post call.respond(HttpStatusCode.Conflict, OrderError(PARENT_NOT_ACTIVE, "PARENT_NOT_ACTIVE"))
        val limits = ChildLimits(
            maxDepth = deps.orderSettings.maxDepth,
            maxChildren = deps.orderSettings.maxChildren,
            maxTree = deps.orderSettings.maxTree,
        )
        when (val r = deps.orders.createChild(parentId, title, policy, request.orderBudgetJpy, key, caller.principal, limits)) {
            is ChildCreateResult.Created -> {
                operatorLog.info(
                    "order operator action: action=CHILD_CREATED actor={} orderId={} parentOrderId={} depth={} result=ACTIVE",
                    operatorTag(caller.principal), r.order.orderId, parentId, r.order.depth,
                )
                call.respond(HttpStatusCode.Created, r.order.summary())
            }
            is ChildCreateResult.Replayed -> call.respond(HttpStatusCode.OK, r.order.summary())
            is ChildCreateResult.Refused -> call.respond(HttpStatusCode.Conflict, OrderError(refusedChildText(r.reason), r.reason.name))
            ChildCreateResult.KeyConflict -> call.respond(HttpStatusCode.Conflict, OrderError(KEY_CONFLICT, "KEY_CONFLICT"))
        }
    }

    post("/api/selfdev/orders/{id}/pause") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!caller.isHuman(call)) return@post
        val orderId = orderIdOf(call)
        val version = call.readVersion() ?: return@post
        if (orderId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        when (val r = deps.orders.pauseByOperator(orderId, version, caller.principal)) {
            is OrderResult.Moved -> {
                operatorLog.info("order operator action: action=PAUSE actor={} orderId={} result=PAUSED", operatorTag(caller.principal), orderId)
                call.respond(r.value.summary())
            }
            else -> call.respondOutcome(r)
        }
    }

    post("/api/selfdev/orders/{id}/resume") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!caller.isHuman(call)) return@post
        val orderId = orderIdOf(call)
        val version = call.readVersion() ?: return@post
        if (orderId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        when (val r = deps.orders.resumeByOperator(orderId, version, caller.principal)) {
            is OrderResult.Moved -> {
                operatorLog.info("order operator action: action=RESUME actor={} orderId={} result=ACTIVE", operatorTag(caller.principal), orderId)
                call.respond(r.value.summary())
            }
            else -> call.respondOutcome(r)
        }
    }

    post("/api/selfdev/orders/{id}/stop-requests") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val orderId = orderIdOf(call)
        val request = decodeOr<RequestStopRequest>(call) ?: return@post
        val reason = StopRequestReason.REQUESTABLE.firstOrNull { it.name == request.reasonCode }
            ?: return@post call.respond(HttpStatusCode.BadRequest, OrderError(REASON_INVALID))
        if (request.version < 0) return@post call.respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
        if (orderId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        when (val r = deps.orders.requestStop(orderId, request.version, caller.principal, caller.kind, reason)) {
            is OrderResult.Moved -> {
                operatorLog.info(
                    "order operator action: action=STOP_REQUESTED actor={} orderId={} reason={} result=STOP_REQUESTED",
                    operatorTag(caller.principal), orderId, reason.name,
                )
                call.respond(HttpStatusCode.Created, r.value.response(caller.isRootApprover(deps)))
            }
            else -> call.respondOutcome(r)
        }
    }

    get("/api/selfdev/stop-requests") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!caller.isRootApprover(deps)) return@get call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
        val state = call.request.queryParameters["state"]
        if (state != "PENDING") return@get call.respond(HttpStatusCode.BadRequest, OrderError(STATE_INVALID))
        call.respond(deps.orders.pendingStopRequests(LIST_LIMIT).map { it.response(true) })
    }

    post("/api/selfdev/stop-requests/{id}/approve") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!caller.isRootApprover(deps)) return@post call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
        val requestId = requestIdOf(call)
        val body = decodeOr<ApproveStopRequest>(call) ?: return@post
        if (!DIGEST_SHAPE.matches(body.digest)) return@post call.respond(HttpStatusCode.BadRequest, OrderError(DIGEST_INVALID))
        if (requestId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(REQUEST_NOT_FOUND))
        when (val r = deps.orders.approveStop(requestId, caller.principal, body.digest)) {
            is OrderResult.Moved -> {
                operatorLog.info("order operator action: action=STOP_APPROVED actor={} requestId={} result=STOPPED", operatorTag(caller.principal), requestId)
                // Every order of the stopped part: its cycle in progress, if any, is stopped too.
                deps.orders.subtreeOf(r.value.orderId).forEach { deps.stopCurrentGoal(it) }
                call.respond(r.value.summary())
            }
            else -> call.respondOutcome(r)
        }
    }

    post("/api/selfdev/stop-requests/{id}/reject") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!caller.isRootApprover(deps)) return@post call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
        val requestId = requestIdOf(call)
        if (!call.readEmptyBody()) return@post
        if (requestId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(REQUEST_NOT_FOUND))
        when (val r = deps.orders.endStopRequest(requestId, StopEnd.REJECTED, caller.principal, OrderActor.HUMAN)) {
            is OrderResult.Moved -> {
                operatorLog.info("order operator action: action=STOP_REJECTED actor={} requestId={} result=PAUSED", operatorTag(caller.principal), requestId)
                call.respond(r.value.summary())
            }
            else -> call.respondOutcome(r)
        }
    }

    post("/api/selfdev/stop-requests/{id}/cancel") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        // The AI principal may not cancel: it may only request (ADR 0015 section 4.6).
        if (!caller.isHuman(call)) return@post
        val requestId = requestIdOf(call)
        if (!call.readEmptyBody()) return@post
        if (requestId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(REQUEST_NOT_FOUND))
        val request = deps.orders.stopRequest(requestId)
        if (request == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(REQUEST_NOT_FOUND))
        if (request.requestedBy != caller.principal && !caller.isRootApprover(deps)) {
            return@post call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
        }
        when (val r = deps.orders.endStopRequest(requestId, StopEnd.CANCELLED, caller.principal, OrderActor.HUMAN)) {
            is OrderResult.Moved -> {
                operatorLog.info("order operator action: action=STOP_CANCELLED actor={} requestId={} result=PAUSED", operatorTag(caller.principal), requestId)
                call.respond(r.value.summary())
            }
            else -> call.respondOutcome(r)
        }
    }
}

/** Authenticates the caller. Responds 401 (no valid operator token) or 403 (an agent token), and returns the caller otherwise. */
private suspend fun SelfDevOrderRouteDeps.caller(call: ApplicationCall): OrderCaller? =
    when (val auth = operators.authenticate(call.request.headers)) {
        is OperatorAuth.Authenticated -> OrderCaller(
            auth.principal,
            if (auth.principal == aiPrincipal) OrderActor.AI else OrderActor.HUMAN,
        )
        OperatorAuth.Unauthenticated -> {
            call.respond(HttpStatusCode.Unauthorized, OrderError("unauthorized"))
            null
        }
        OperatorAuth.AgentRefused -> {
            call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
            null
        }
    }

/** 503 while the feature is off. Returns false after responding. */
private suspend fun SelfDevOrderRouteDeps.requireEnabled(call: ApplicationCall): Boolean {
    if (orderSettings.enabled && autopilot.enabled && autopilot.config != null) return true
    call.respond(HttpStatusCode.ServiceUnavailable, OrderError(ORDERS_DISABLED))
    return false
}

/** Only a human operator may change an order. The AI principal gets 403, and the response has nothing else in it. */
private suspend fun OrderCaller.isHuman(call: ApplicationCall): Boolean {
    if (kind == OrderActor.HUMAN) return true
    call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
    return false
}

private fun OrderCaller.isRootApprover(deps: SelfDevOrderRouteDeps): Boolean =
    kind == OrderActor.HUMAN && principal == deps.orderSettings.rootApprover

/**
 * After the root order's stop is approved, the goal of the cycle in progress is stopped too, through the existing goal stop (ADR 0015
 * section 4.5). The order is already STOPPED, and the dispatcher will not start a Run for it, so a failure here is logged as a code and
 * left for the operator. Its tasks are not changed by it.
 */
private fun SelfDevOrderRouteDeps.stopCurrentGoal(order: SelfDevOrder) {
    when (stopCycleGoalOf(order, goals, operations) ?: return) {
        true -> operatorLog.info("order operator action: action=GOAL_STOPPED_WITH_ORDER orderId={} result=STOPPED", order.orderId)
        false -> operatorLog.warn("order operator action: action=GOAL_STOPPED_WITH_ORDER orderId={} result=NOT_STOPPED", order.orderId)
    }
}

private fun orderIdOf(call: ApplicationCall): String? = call.parameters["id"]?.takeIf { ORDER_ID_SHAPE.matches(it) }

private fun requestIdOf(call: ApplicationCall): String? = call.parameters["id"]?.takeIf { ORDER_ID_SHAPE.matches(it) }

/** Decodes a strict JSON body or responds 400. */
private suspend inline fun <reified T> decodeOr(call: ApplicationCall): T? {
    val text = call.receiveText()
    return try {
        strictJson.decodeFromString<T>(text)
    } catch (_: Exception) {
        call.respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
        null
    }
}

/** `{"version": n}`, strictly. Responds 400 and returns null when it is malformed or negative. */
private suspend fun ApplicationCall.readVersion(): Long? {
    val parsed = try {
        strictJson.decodeFromString(OrderVersionRequest.serializer(), receiveText())
    } catch (_: Exception) {
        null
    }
    if (parsed == null || parsed.version < 0) {
        respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
        return null
    }
    return parsed.version
}

/** Accepts an empty body or `{}`. Responds 400 and returns false otherwise. */
private suspend fun ApplicationCall.readEmptyBody(): Boolean {
    val text = receiveText()
    if (text.isBlank()) return true
    val ok = try {
        strictJson.decodeFromString(EmptyOrderBody.serializer(), text)
        true
    } catch (_: Exception) {
        false
    }
    if (!ok) respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
    return ok
}

@Serializable
private class EmptyOrderBody

/** A refusal or a miss: the fixed code of the refusal, and a fixed text. Never a request value. */
private suspend fun ApplicationCall.respondOutcome(outcome: OrderResult<*>) {
    when (outcome) {
        OrderResult.NotFound -> respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        OrderResult.StaleVersion -> respond(HttpStatusCode.Conflict, OrderError(STALE))
        is OrderResult.Refused -> when (outcome.reason) {
            OrderConflict.SAME_PRINCIPAL -> respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
            else -> respond(HttpStatusCode.Conflict, OrderError(refusedText(outcome.reason), outcome.reason.name, outcome.orderId))
        }
        is OrderResult.Moved<*> -> respond(HttpStatusCode.OK)
    }
}

internal fun refusedText(reason: OrderConflict): String = when (reason) {
    OrderConflict.STOP_ALREADY_PENDING -> "a stop request is already pending for this tree"
    OrderConflict.STOP_NOT_PENDING -> "the stop request is no longer pending"
    OrderConflict.STOP_EXPIRED -> "the stop request has expired; the order is paused"
    OrderConflict.DIGEST_MISMATCH -> "the digest does not match the stop request"
    OrderConflict.ORDER_CHANGED -> "the order changed since the stop was requested"
    OrderConflict.CYCLE_IN_PROGRESS -> "a cycle is already in progress"
    OrderConflict.CYCLE_LIMIT_REACHED -> "the cycle limit is reached; a new order is needed"
    OrderConflict.ORDER_EXPIRED -> "the order has expired"
    OrderConflict.AUTOPILOT_PAUSED -> "the autopilot is paused"
    OrderConflict.ORDER_BUDGET -> "an order on the path to the root has less budget left than one cycle needs"
    OrderConflict.ANCESTOR_NOT_ACTIVE -> "an order on the path to the root is not active; it must be resumed first"
    OrderConflict.ORDER_UNPRICED -> "an order on the path to the root has a cycle with an unconfirmed UNPRICED cost; an operator must acknowledge it"
    OrderConflict.NOT_ACTIVE -> "the order is not active"
    OrderConflict.RESUME_NOT_ALLOWED -> "this order cannot be resumed; a new order is needed"
    OrderConflict.ACTOR_NOT_ALLOWED -> "this caller may not make this change"
    OrderConflict.REASON_NOT_ALLOWED, OrderConflict.NO_EDGE, OrderConflict.TERMINAL -> "the state does not allow this operation"
    OrderConflict.SAME_PRINCIPAL -> FORBIDDEN
}

internal fun refusedChildText(reason: ChildRefusal): String = when (reason) {
    ChildRefusal.PARENT_NOT_ACTIVE -> PARENT_NOT_ACTIVE
    ChildRefusal.DEPTH_LIMIT -> "the tree is at its depth limit"
    ChildRefusal.CHILD_LIMIT -> "the parent is at its child limit"
    ChildRefusal.TREE_LIMIT -> "the tree is at its size limit"
    ChildRefusal.BUDGET_EXCEEDS_PARENT -> "orderBudgetJpy is more than the parent's unallocated budget"
}

private fun java.time.LocalDateTime.utc(): String = toInstant(ZoneOffset.UTC).toString()

private fun SelfDevOrder.summary() = OrderSummaryResponse(
    orderId = orderId,
    parentOrderId = parentOrderId,
    depth = depth,
    state = state.name,
    stateReason = stateReason?.name,
    cyclesStarted = cyclesStarted,
    maxCycles = maxCycles,
    currentGoalId = currentGoalId,
    consecutiveFailedCycles = consecutiveFailedCycles,
    expiresAt = expiresAt.utc(),
    version = version,
    createdAt = createdAt.utc(),
    updatedAt = updatedAt.utc(),
    orderBudgetJpy = orderBudgetJpy,
    allocatedJpy = allocatedJpy,
    childCount = childCount,
    spentJpy = spentJpy,
    cycleBudgetJpy = cycleBudgetJpy,
    remainingJpy = orderBudgetJpy - spentJpy,
)

private fun SelfDevOrderStopRequest.response(approverView: Boolean) = StopRequestResponse(
    requestId = requestId,
    orderId = orderId,
    requesterKind = requesterKind.name,
    reasonCode = reasonCode.name,
    state = state.name,
    orderVersion = orderVersion,
    digest = if (approverView) digest else null,
    expiresAt = expiresAt.utc(),
    createdAt = createdAt.utc(),
    targetIsRoot = targetIsRoot,
)

private fun OrderCycle.response() = CycleResponse(
    cycleNo = cycleNo,
    goalId = goalId,
    goalState = goalState.name,
    stateReason = stateReason,
    createdAt = createdAt.utc(),
)

/** Validates a policy: trimmed, 1 to 2000 code points, line breaks and tabs allowed, other control characters refused. Null when invalid. */
internal fun validatedPolicy(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.codePointCount(0, value.length) > MAX_POLICY_CODE_POINTS) return null
    if (value.any { (it.isISOControl() && it != '\n' && it != '\t') || isBidiControl(it) }) return null
    return value
}
