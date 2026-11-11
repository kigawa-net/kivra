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
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.AbandonResult
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.DEFAULT_CYCLE_BUDGET_JPY
import net.kigawa.kivra.db.ChildRefusal
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.EXTEND_DAYS_LIMIT
import net.kigawa.kivra.db.ExtendInvalid
import net.kigawa.kivra.db.ExtendResult
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.CurrentCycle
import net.kigawa.kivra.db.OrderCycle
import net.kigawa.kivra.db.OrderResult
// SelfDevGoalRepository removed in V40
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.StopEnd
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.stopCycleGoalOf
import org.slf4j.LoggerFactory

/*
 * The self-development orders' API (ADR 0015 stage 1, Issue #273). Authentication is the operator's, as for the autopilot routes
 * ([OperatorAuthenticator]: the OIDC token with the required role, and an agent token is refused).
 *
 * Two kinds of caller are told apart by [PermissionGuard] (ADR 0020, PR 3a): the AI and every other operator (a human). With the roles off
 * (the default) the guard applies today's rules: the AI principal (`KIVRA_SELFDEV_AI_PRINCIPAL`) may read and request a stop, and nothing
 * else (ADR 0015 section 4.6), and the root approver (`KIVRA_SELFDEV_ROOT_APPROVER`) is the only one who approves, rejects a stop or sees
 * the list of pending requests. With the roles on, the role table decides. For the root's stop
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
    val operations: SelfDevOperatorRepository,
    /** The projects a new root order may be assigned to (ADR 0022). */
    val projects: ProjectRepository,
    /** The permission entrance. The default is the roles off and no store: today's rules, from [aiPrincipal] and the root approver. */
    val guard: PermissionGuard = PermissionGuard(
        PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, orderSettings.rootApprover, null),
    ),
)

@Serializable
data class CreateOrderRequest(
    val title: String,
    val policy: String,
    /** The most cycles the order may run (ADR 0015 section 3.2). Absent means 1. Checked against 1..`KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`. */
    val maxCycles: Int? = null,
    /** The project the root order belongs to (ADR 0022). Required on creation; a child takes its parent's. Checked by the route. */
    val projectId: String? = null,
)

@Serializable
data class OrderVersionRequest(val version: Long)

/**
 * An operator's extension of the root order (ADR 0015 addendum 2026-11-10, section 2.6). At least one of [addCycles] (1 to the cap) and
 * [extendDays] (1 to 14) is given.
 */
@Serializable
data class ExtendOrderRequest(val version: Long, val addCycles: Int? = null, val extendDays: Int? = null)

/**
 * An operator's abandonment of the cycle in progress (ADR 0015 addendum 2026-11-11, section 3). [reason] is 1 to 500 characters; it goes to the
 * audit payload only (cut to fit the column), and is never logged or echoed.
 */
@Serializable
data class AbandonCycleRequest(val expectedVersion: Long, val reason: String)

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
    /** Optional project grouping (V42). Null means unassigned. */
    val projectId: String?,
)

@Serializable
data class OrderDetailResponse(
    val order: OrderSummaryResponse,
    val title: String,
    val policy: String,
    val pendingStopRequest: StopRequestResponse?,
    /** The cycle in progress read from its Task (added 2026-11-11), or null when the order has none (`currentGoalId` is null). */
    val currentCycle: CurrentCycleResponse? = null,
)

/** The state of the cycle in progress. [taskMissing] is true when no Task has the ID in `currentGoalId` (an old goal); then both state fields are null. */
@Serializable
data class CurrentCycleResponse(val taskId: String, val taskState: String?, val taskReason: String?, val taskMissing: Boolean)

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
    /** The project of the order the request is for (ADR 0022). Null when it has none. */
    val projectId: String? = null,
)

@Serializable
data class CycleResponse(val cycleNo: Int, val goalId: String, val goalState: String, val stateReason: String?, val createdAt: String)

@Serializable
data class CycleStartedResponse(val orderId: String, val goalId: String, val cycleNo: Int, val version: Long)

@Serializable
private data class OrderError(val error: String, val code: String? = null, val orderId: String? = null)

/** Who is calling: the owner key and its access (looked up once). */
private class OrderCaller(val principal: String, val access: GuardAccess) {
    val kind: OrderActor get() = if (access.isAi) OrderActor.AI else OrderActor.HUMAN
}

private val strictJson = Json { ignoreUnknownKeys = false }
private val ORDER_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val DIGEST_SHAPE = Regex("[0-9a-f]{64}")
private const val LIST_LIMIT = 50
private const val MAX_TITLE_CODE_POINTS = 200
private const val MAX_POLICY_CODE_POINTS = 2_000
private const val MAX_CHILD_KEY_CODE_POINTS = 128
/** The largest value of DECIMAL(12,0), the budget columns' type. */
private const val MAX_BUDGET_JPY = 999_999_999_999L

/** GET the orders of a project: the old route (its `projectId` query) or the alias (the path's project). */
private suspend fun listOrders(deps: SelfDevOrderRouteDeps, call: ApplicationCall) {
    val caller = deps.caller(call) ?: return
    if (!deps.requireEnabled(call)) return
    val pinned = call.pathProjectId()
    if (pinned != null && !deps.projects.projectExistsForPath(pinned)) return call.respond(HttpStatusCode.NotFound, OrderError(PROJECT_NOT_FOUND))
    if (!deps.permit(call, caller, Permission.READ, "order.list")) return
    // The alias answers 404 for a project the caller may not read, as for an unknown one (ADR 0022 PR-D3). READ is held by every project role.
    val scope = deps.guard.readScope(caller.access)
    if (pinned != null && !scope.sees(pinned)) return call.respond(HttpStatusCode.NotFound, OrderError(PROJECT_NOT_FOUND))
    val project = when (val s = listScopeOf(pinned, call.request.queryParameters["projectId"])) {
        is ListScope.Refused -> return call.respond(s.status, OrderError(s.error))
        is ListScope.Filtered -> scope.narrow(s.filter)
    }
    val rows = deps.orders.list(LIST_LIMIT, project)
    deps.guard.recordReadShadow(caller.access, rows.map { it.projectId }, "order.list")
    call.respond(rows.map { it.summary() })
}

/** POST a root order: the old route (the body's projectId) or the alias (the path's project; a body projectId must match). */
private suspend fun createOrder(deps: SelfDevOrderRouteDeps, call: ApplicationCall) {
    val pinned = call.pathProjectId()
    val caller = deps.caller(call) ?: return
    if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return
    if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.create")) return
    val request = decodeOr<CreateOrderRequest>(call) ?: return
    val title = validatedText(request.title, MAX_TITLE_CODE_POINTS)
    val policy = validatedPolicy(request.policy)
    if (title == null) return call.respond(HttpStatusCode.BadRequest, OrderError(TITLE_INVALID))
    if (policy == null) return call.respond(HttpStatusCode.BadRequest, OrderError(POLICY_INVALID))
    val maxCycles = request.maxCycles ?: 1
    if (maxCycles !in 1..deps.orderSettings.maxCyclesCap) {
        return call.respond(HttpStatusCode.BadRequest, OrderError(MAX_CYCLES_INVALID, MAX_CYCLES_INVALID_CODE))
    }
    val repo = deps.targetRepo ?: return call.respond(HttpStatusCode.ServiceUnavailable, OrderError(ORDERS_DISABLED))
    // The alias (ADR 0022 PR-E) takes the path's project; the body's projectId, when given, must be the same.
    val projectId = when (val scope = pinned?.let { deps.projects.scopeOfPath(it, request.projectId) } ?: deps.projects.scopeOf(request.projectId)) {
        is ProjectScope.Refused -> return call.respond(scope.status, OrderError(scope.error))
        is ProjectScope.Assigned -> scope.projectId
    }
    if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.create", projectId = projectId)) return
    val created = deps.orders.createRoot(caller.principal, title, policy, repo, maxCycles, projectId) as CreateRootResult.Created
    operatorLog.info("order operator action: action=ORDER_CREATED actor={} orderId={} result=ACTIVE", operatorTag(caller.principal), created.order.orderId)
    call.respond(HttpStatusCode.Created, created.order.summary())
}

/** GET the pending stop requests of a project: the old route or the alias (the path's project). */
private suspend fun listApprovals(deps: SelfDevOrderRouteDeps, call: ApplicationCall) {
    val caller = deps.caller(call) ?: return
    if (!deps.requireEnabled(call)) return
    val pinned = call.pathProjectId()
    if (pinned != null && !deps.projects.projectExistsForPath(pinned)) return call.respond(HttpStatusCode.NotFound, OrderError(PROJECT_NOT_FOUND))
    // The alias answers 404 for a project the caller may not read (ADR 0022 PR-D3). The approver's role in the project is still checked after it.
    val scope = deps.guard.readScope(caller.access)
    if (pinned != null && !scope.sees(pinned)) return call.respond(HttpStatusCode.NotFound, OrderError(PROJECT_NOT_FOUND))
    if (!deps.permit(call, caller, Permission.STOP_DECIDE, "order.stop.list", projectId = pinned)) return
    val state = call.request.queryParameters["state"]
    if (state != "PENDING") return call.respond(HttpStatusCode.BadRequest, OrderError(STATE_INVALID))
    // The rows are those of the projects where the caller's role holds STOP_DECIDE (ADR 0022 PR-D3). The old route has no project role
    // check of its own, so this keeps an OPERATOR member of a project from seeing its stop requests.
    val rowScope = deps.guard.readScope(caller.access, Permission.STOP_DECIDE)
    val project = when (val s = listScopeOf(pinned, call.request.queryParameters["projectId"])) {
        is ListScope.Refused -> return call.respond(s.status, OrderError(s.error))
        is ListScope.Filtered -> rowScope.narrow(s.filter)
    }
    val rows = deps.orders.pendingStopRequests(LIST_LIMIT, project)
    deps.guard.recordReadShadow(caller.access, rows.map { it.projectId }, "order.stop.list", permission = Permission.STOP_DECIDE)
    call.respond(rows.map { it.response(true) })
}

private const val MAX_ABANDON_REASON_CODE_POINTS = 500
private const val ORDERS_DISABLED = "self-development orders are disabled"
private const val INVALID_BODY = "invalid request body"
private const val TITLE_INVALID = "title must be 1-200 characters without control characters"
private const val POLICY_INVALID = "policy must be 1-2000 characters, and may contain line breaks but no other control characters"
private const val ORDER_NOT_FOUND = "order not found"
private const val REQUEST_NOT_FOUND = "stop request not found"
private const val FORBIDDEN = "forbidden"
private const val STALE = "the order changed; reload and try again"
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

    // The old list keeps its answer and announces the alias (ADR 0022 PR-E). The old creation is retired (PR-G): 410 with the alias in Link.
    // It still checks the caller and the write permission first, so the answers to a caller without them do not change.
    get("/api/selfdev/orders") {
        call.deprecatedBy("/api/projects/{projectId}/orders", call.request.queryParameters["projectId"])
        listOrders(deps, call)
    }
    post("/api/selfdev/orders") {
        val caller = deps.caller(call) ?: return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.create")) return@post
        call.retiredBy("/api/projects/{projectId}/orders")
        call.respond(HttpStatusCode.Gone, OrderError(ROUTE_RETIRED))
    }
    // The alias of the list and the creation under a project (ADR 0022 PR-E). The same handlers; the path's project is the project.
    get("/api/projects/{projectId}/orders") { listOrders(deps, call) }
    post("/api/projects/{projectId}/orders") { createOrder(deps, call) }

    get("/api/selfdev/orders/{id}") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "order.detail")) return@get
        val order = orderIdOf(call)?.let { deps.orders.get(it) }
        // An order of a project the caller may not read is the same 404 as a missing one (ADR 0022 PR-D3).
        if (order != null) deps.guard.recordReadShadow(caller.access, listOf(order.projectId), "order.detail")
        if (order == null || !deps.guard.readScope(caller.access).sees(order.projectId)) {
            return@get call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        }
        val pending = deps.orders.pendingStopRequestOf(order.orderId)
        // The title and the policy are the operators' text: returned here only, to every operator (the same as the goal detail).
        call.respond(
            OrderDetailResponse(
                order = order.summary(),
                title = order.title,
                policy = order.policy,
                pendingStopRequest = pending?.response(deps.guard.can(caller.access, Permission.STOP_DECIDE))?.copy(projectId = order.projectId),
                currentCycle = deps.orders.currentCycleOf(order)?.response(),
            ),
        )
    }

    get("/api/selfdev/orders/{id}/cycles") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "order.cycles")) return@get
        val order = orderIdOf(call)?.let { deps.orders.get(it) }
        // An order of a project the caller may not read is the same 404 as a missing one (ADR 0022 PR-D3).
        if (order != null) deps.guard.recordReadShadow(caller.access, listOf(order.projectId), "order.cycles")
        if (order == null || !deps.guard.readScope(caller.access).sees(order.projectId)) {
            return@get call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        }
        call.respond(deps.orders.cyclesOf(order.orderId).map { it.response() })
    }

    post("/api/selfdev/orders/{id}/cycles") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.cycle.start")) return@post
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
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.child.create")) return@post
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
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.pause")) return@post
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
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.resume")) return@post
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

    post("/api/selfdev/orders/{id}/extend") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        // The operator only, as for the resume: the AI principal is refused before the body is read (ADR 0015 section 4.6).
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.extend")) return@post
        val orderId = orderIdOf(call) ?: return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        val request = decodeOr<ExtendOrderRequest>(call) ?: return@post
        val cap = deps.orderSettings.maxCyclesCap
        if (request.version < 0 || (request.addCycles == null && request.extendDays == null)) {
            return@post call.respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
        }
        if (request.addCycles != null && request.addCycles !in 1..cap) {
            return@post call.respond(HttpStatusCode.BadRequest, OrderError(MAX_CYCLES_INVALID, MAX_CYCLES_INVALID_CODE))
        }
        // The days are an input check (EXTEND_DAYS_LIMIT), not a limit on the lifetime: any expiry the operator sets is allowed.
        if (request.extendDays != null && request.extendDays !in 1..EXTEND_DAYS_LIMIT) {
            return@post call.respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
        }
        when (val r = deps.orders.extendByOperator(orderId, request.version, caller.principal, request.addCycles, request.extendDays, cap)) {
            is ExtendResult.Moved -> {
                // The log holds the IDs and the fixed codes only: no title, no policy, and not the numbers of the extension.
                operatorLog.info("order operator action: action=EXTENDED actor={} orderId={} result={}", operatorTag(caller.principal), orderId, r.order.state.name)
                call.respond(r.order.summary())
            }
            ExtendResult.NotFound -> call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
            ExtendResult.StaleVersion -> call.respond(HttpStatusCode.Conflict, OrderError(STALE))
            is ExtendResult.Refused -> call.respond(HttpStatusCode.Conflict, OrderError(refusedText(r.reason), r.reason.name))
            is ExtendResult.Invalid -> when (r.reason) {
                ExtendInvalid.MAX_CYCLES_OVER_CAP -> call.respond(HttpStatusCode.BadRequest, OrderError(MAX_CYCLES_INVALID, MAX_CYCLES_INVALID_CODE))
            }
        }
    }

    post("/api/selfdev/orders/{id}/cycles/abandon") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        // The operator only, as for the extension: the AI principal is refused before the body is read (ADR 0015 section 4.6).
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "order.cycle.abandon")) return@post
        val orderId = orderIdOf(call) ?: return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        val request = decodeOr<AbandonCycleRequest>(call) ?: return@post
        val reason = request.reason.trim()
        if (request.expectedVersion < 0 || reason.isEmpty() || reason.codePointCount(0, reason.length) > MAX_ABANDON_REASON_CODE_POINTS ||
            reason.any { it.isISOControl() }
        ) {
            return@post call.respond(HttpStatusCode.BadRequest, OrderError(INVALID_BODY))
        }
        val order = deps.orders.get(orderId) ?: return@post call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
        // The checks that decide before anything is written: the version, the state and the cycle. The repository checks them again under the lock.
        if (order.version != request.expectedVersion) return@post call.respond(HttpStatusCode.Conflict, OrderError(STALE))
        when (order.state) {
            OrderState.ACTIVE, OrderState.PAUSED -> Unit
            OrderState.STOP_REQUESTED -> return@post call.respond(HttpStatusCode.Conflict, OrderError(refusedText(OrderConflict.STOP_ALREADY_PENDING), OrderConflict.STOP_ALREADY_PENDING.name))
            OrderState.STOPPED -> return@post call.respond(HttpStatusCode.Conflict, OrderError(refusedText(OrderConflict.TERMINAL), OrderConflict.TERMINAL.name))
        }
        val taskId = order.currentGoalId
            ?: return@post call.respond(HttpStatusCode.Conflict, OrderError(refusedText(OrderConflict.NO_CYCLE_IN_PROGRESS), OrderConflict.NO_CYCLE_IN_PROGRESS.name))
        // A Task that still runs is stopped first (the operator's stop, as POST /api/selfdev/goals/{id}/stop does); a missing or ended one is left.
        val task = deps.operations.task(taskId)
        if (task != null && !deps.orders.isCycleTaskEnded(task.state.name)) {
            when (val stopped = deps.operations.stopTask(taskId, task.version)) {
                is TaskTransition.Moved -> operatorLog.info("order operator action: action=CYCLE_TASK_STOPPED actor={} orderId={} result=STOPPED", operatorTag(caller.principal), orderId)
                TaskTransition.NotFound, TaskTransition.StaleVersion -> return@post call.respond(HttpStatusCode.Conflict, OrderError(STALE))
                is TaskTransition.Refused -> return@post call.respond(HttpStatusCode.Conflict, OrderError(refusedText(OrderConflict.CYCLE_IN_PROGRESS), stopped.reason.name))
            }
        }
        when (val r = deps.orders.abandonCycle(orderId, request.expectedVersion, caller.principal, reason)) {
            is AbandonResult.Moved -> {
                operatorLog.info("order operator action: action=CYCLE_ABANDONED actor={} orderId={} result={}", operatorTag(caller.principal), orderId, r.order.state.name)
                call.respond(r.order.summary())
            }
            AbandonResult.NotFound -> call.respond(HttpStatusCode.NotFound, OrderError(ORDER_NOT_FOUND))
            AbandonResult.StaleVersion -> call.respond(HttpStatusCode.Conflict, OrderError(STALE))
            is AbandonResult.Refused -> call.respond(HttpStatusCode.Conflict, OrderError(refusedText(r.reason), r.reason.name))
        }
    }

    post("/api/selfdev/orders/{id}/stop-requests") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val orderId = orderIdOf(call)
        if (!deps.permit(call, caller, Permission.STOP_REQUEST, "order.stop.request", projectId = deps.projectOfOrder(orderId))) return@post
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
                call.respond(HttpStatusCode.Created, r.value.response(deps.guard.can(caller.access, Permission.STOP_DECIDE)))
            }
            else -> call.respondOutcome(r)
        }
    }

    // The old route keeps its answer and announces the alias (ADR 0022 PR-E): the pending stop requests of a project.
    get("/api/selfdev/stop-requests") {
        call.deprecatedBy("/api/projects/{projectId}/approvals", call.request.queryParameters["projectId"])
        listApprovals(deps, call)
    }
    get("/api/projects/{projectId}/approvals") { listApprovals(deps, call) }

    post("/api/selfdev/stop-requests/{id}/approve") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val requestId = requestIdOf(call)
        if (!deps.permit(call, caller, Permission.STOP_DECIDE, "order.stop.approve", projectId = deps.projectOfStopRequest(requestId))) return@post
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
        val requestId = requestIdOf(call)
        if (!deps.permit(call, caller, Permission.STOP_DECIDE, "order.stop.reject", projectId = deps.projectOfStopRequest(requestId))) return@post
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
        // The AI may not cancel: it may only request (ADR 0015 section 4.6). Anyone else needs the stop-request permission, and is then
        // the requester, or holds the permission to decide (below).
        val requestId = requestIdOf(call)
        val project = deps.projectOfStopRequest(requestId)
        if (!deps.permit(call, caller, Permission.STOP_REQUEST, "order.stop.cancel", humanOnly = true, projectId = project)) return@post
        if (!call.readEmptyBody()) return@post
        if (requestId == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(REQUEST_NOT_FOUND))
        val request = deps.orders.stopRequest(requestId)
        if (request == null) return@post call.respond(HttpStatusCode.NotFound, OrderError(REQUEST_NOT_FOUND))
        if (request.requestedBy != caller.principal &&
            !deps.permit(call, caller, Permission.STOP_DECIDE, "order.stop.cancel.other", projectId = project)
        ) {
            return@post
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
        // The authenticator accepts operators only, so the caller is an operator by today's rule (legacyAdmin).
        is OperatorAuth.Authenticated -> OrderCaller(auth.principal, guard.resolve(auth.principal, legacyAdmin = true))
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

/** The permission check. Responds 403 with the fixed body and returns false when refused. The check's order in each route is the old one. */
private suspend fun SelfDevOrderRouteDeps.permit(
    call: ApplicationCall,
    caller: OrderCaller,
    permission: Permission,
    route: String,
    humanOnly: Boolean = false,
    /** The request's project (ADR 0022 PR-D). Null: the plain check. */
    projectId: String? = null,
): Boolean {
    // Without a project this is the plain check; with one, the project check of ADR 0022 PR-D is added to it (the same answer when off).
    val result = if (projectId == null) {
        guard.check(caller.access, permission, route, humanOnly = humanOnly)
    } else {
        guard.checkInProject(caller.access, projectId, permission, route, humanOnly = humanOnly)
    }
    if (result is GuardResult.Allow) return true
    call.respond(HttpStatusCode.Forbidden, OrderError(FORBIDDEN))
    return false
}

/** The project of an order, for the project check (ADR 0022 PR-D). Read only while the project check is on; otherwise null, nothing read. */
private fun SelfDevOrderRouteDeps.projectOfOrder(orderId: String?): String? =
    if (orderId == null || !guard.projectScopeOn) null else orders.get(orderId)?.projectId

/** The project of a stop request's order, for the project check (ADR 0022 PR-D). As [projectOfOrder]. */
private fun SelfDevOrderRouteDeps.projectOfStopRequest(requestId: String?): String? =
    if (requestId == null || !guard.projectScopeOn) null else orders.projectIdOfStopRequest(requestId)

/**
 * After a stop is approved, the cycle in progress of each stopped order is stopped too: its root Task moves to STOPPED (ADR 0015 section 4.5,
 * V49). The order is already STOPPED, and the dispatcher will not submit a Run for its tasks, so a refusal here is logged as a code and
 * left for the operator.
 */
private fun SelfDevOrderRouteDeps.stopCurrentGoal(order: SelfDevOrder) {
    when (stopCycleGoalOf(order, operations)) {
        true -> operatorLog.info("order operator action: action=GOAL_STOPPED_WITH_ORDER orderId={} result=STOPPED", order.orderId)
        false -> operatorLog.warn("order operator action: action=GOAL_STOPPED_WITH_ORDER orderId={} result=REFUSED", order.orderId)
        null -> Unit
    }
}

private fun orderIdOf(call: ApplicationCall): String? = call.pathParam("id")?.takeIf { ORDER_ID_SHAPE.matches(it) }

private fun requestIdOf(call: ApplicationCall): String? = call.pathParam("id")?.takeIf { ORDER_ID_SHAPE.matches(it) }

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
    OrderConflict.NO_PROJECT -> "the order has no project; it must be assigned to a project before a cycle can start"
    OrderConflict.NOT_ACTIVE -> "the order is not active"
    OrderConflict.RESUME_NOT_ALLOWED -> "this order cannot be resumed; a new order is needed"
    OrderConflict.NOT_ROOT -> "only a root order can be extended"
    OrderConflict.NO_CYCLE_IN_PROGRESS -> "the order has no cycle in progress"
    OrderConflict.CYCLE_ALREADY_ENDED -> "the cycle's task has already ended; the evaluation closes it with its real outcome"
    OrderConflict.EXTENSION_DOES_NOT_LIFT -> "the extension would not lift the cycle limit and the expiry that paused the order"
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
    projectId = projectId,
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
    projectId = projectId,
)

private fun CurrentCycle.response() = CurrentCycleResponse(taskId = taskId, taskState = taskState, taskReason = taskReason, taskMissing = taskMissing)

private fun OrderCycle.response() = CycleResponse(
    cycleNo = cycleNo,
    goalId = goalId,
    goalState = goalState,
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
