package net.kigawa.kivra.mcp

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.EXTEND_DAYS_LIMIT
import net.kigawa.kivra.db.ExtendResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectFilter
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.OrderCycle
import net.kigawa.kivra.routes.GuardResult
import net.kigawa.kivra.routes.MAX_POLICY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TITLE_CODE_POINTS
import net.kigawa.kivra.routes.PROJECT_ID_SHAPE
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.ProjectScope
import net.kigawa.kivra.routes.RootOrderCheck
import net.kigawa.kivra.routes.VIA_MCP
import net.kigawa.kivra.routes.checkRootOrderFields
import net.kigawa.kivra.routes.narrow
import net.kigawa.kivra.routes.operatorTag
import net.kigawa.kivra.routes.refusedText
import net.kigawa.kivra.routes.scopeOf
import net.kigawa.kivra.selfdev.OrderSettings
import org.slf4j.LoggerFactory

/** Scope of the order's stop request tool (ADR 0013 section 4, ADR 0015 section 6). Not implied by the read scope. */
const val SCOPE_SELFDEV_STOP = "kivra.selfdev.stop"

/**
 * Scope of the order's extension and resume tools (ADR 0015 amendment 2026-11-18). Not implied by the read scope or the stop scope. It is
 * granted only to a token whose holder is an operator, and the permission check (ORDER_MANAGE) refuses the AI principal whatever the scope says.
 */
const val SCOPE_SELFDEV_ORDER_MANAGE = "kivra.selfdev.order.manage"

/**
 * Scope of the root order creation tool, `kivra_order_create` (ADR 0015 amendment 2026-11-19, section 9). Not implied by the order manage
 * scope, the read scope or the stop scope. The permission check (ORDER_MANAGE) refuses the AI principal whatever the scope says.
 */
const val SCOPE_SELFDEV_ORDER_CREATE = "kivra.selfdev.order.create"

private val ORDER_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val ORDER_LIST_LIMIT = 50

/** The order's operator log, the same name as the REST routes' (SelfDevOrderRoutes): fixed codes and IDs only. */
private val orderOperatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.order.operator")

/**
 * The order dependencies of `/mcp`. Built only while the orders are on (`KIVRA_SELFDEV_ORDERS_ENABLED`, with the autopilot), so with the orders
 * off no order tool is registered at all. [aiPrincipal] is the AI principal of the REST routes (`KIVRA_SELFDEV_AI_PRINCIPAL`). It only builds the default
 * guard of [buildMcpRouteDeps] (the roles off); the actor of a stop request comes from the dispatcher's guard (`McpCaller.isAi`).
 */
class McpOrderDeps(
    val orders: SelfDevOrderRepository,
    val aiPrincipal: String?,
    /** `KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`, the same value the REST extension checks against. */
    val maxCyclesCap: Int = OrderSettings.DEFAULT_MAX_CYCLES_CAP,
    /** `owner/repo` of the target, stored with a new root order (as the REST route's `targetRepo`). Null: the creation is refused. */
    val targetRepo: String? = null,
    /** The projects a new root order may belong to (ADR 0022). `project_id` must name one of them. */
    val projects: ProjectRepository,
)

/**
 * The order tools of ADR 0015 section 6 on `/mcp`. Reading the orders and requesting a stop are the base (ADR 0015 U-9). Amendment 2026-11-18
 * adds the operator's decisions that a Claude without an operator JWT needs: extending a root order (`kivra_order_extend`) and resuming a
 * paused one (`kivra_order_resume`). The task's approve and skip are in [McpTaskWriteTools] (amendment 2026-11-19), not here. Amendment
 * 2026-11-19 (section 9) adds the creation of a root order (`kivra_order_create`). The order's approval (of a stop request), rejection,
 * child creation, cycle start, pause and cancellation are still not defined here, so they are not in `tools/list` and a call to them is
 * an unknown tool.
 *
 * - `kivra_order_list` and `kivra_order_get` need `kivra.runs.read`. They return fixed codes, IDs, dates and counts only: never a title, a
 *   policy, a goal's title or body, or a stop request's digest (the REST detail view returns the title and policy, and this tool does not).
 * - `kivra_order_request_stop` needs `kivra.selfdev.stop`. It is listed and answered only while `KIVRA_MCP_WRITE_ENABLED=true` ([tools]).
 *   It calls the same repository request as `POST /api/selfdev/orders/{id}/stop-requests`, so the rules (the state machine, the version check,
 *   one pending request per tree) are the repository's and are not repeated here. Its input is strict: the order id, the version and one
 *   of the fixed reason codes. No free text is accepted.
 * - `kivra_order_extend` and `kivra_order_resume` need `kivra.selfdev.order.manage` and the write switch. They call the same repository
 *   functions as `POST /api/selfdev/orders/{id}/extend` and `.../resume`, with the same limits (the cap of the cycles, the days). The permission
 *   (ORDER_MANAGE) is the dispatcher's, so the AI principal is refused before the handler runs.
 * - `kivra_order_create` needs `kivra.selfdev.order.create` and the write switch (amendment 2026-11-19, section 9). It creates a root order as
 *   `POST /api/projects/{projectId}/orders` does: the same field checks ([checkRootOrderFields]), the same repository call, and the same project
 *   check ([PermissionGuard.checkInProject]) on top of the dispatcher's ORDER_MANAGE check. It returns the summary, with no title or policy.
 *
 * [guard] is the permission guard of the routes ([buildMcpRouteDeps]); the handler uses it only for the project check, never to compare the
 * caller with the AI principal.
 */
class McpOrderTools(private val deps: McpOrderDeps, private val guard: PermissionGuard) {

    /** The order tools for the current settings. The write tools are registered only when the MCP write switch is on. */
    fun tools(writeEnabled: Boolean): List<McpTool> =
        listOf(orderList(), orderGet()) + if (writeEnabled) listOf(stopTool(), extendTool(), resumeTool(), createTool()) else emptyList()

    private fun createTool() = McpTool(
        name = "kivra_order_create",
        description = "Create a root self-development order (an operator's decision), as POST /api/projects/{projectId}/orders does. Give a title, " +
            "a policy (the request's text), the project_id, and max_cycles (1 to the configured cap; 1 when absent). The order starts ACTIVE " +
            "with no cycle yet. Nothing is deduplicated: check kivra_order_list before a retry, or the same request creates a second order.",
        inputSchema = objectSchema(
            "title" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_TITLE_CODE_POINTS)
            },
            "policy" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_POLICY_CODE_POINTS)
                put("description", "The request's text. Untrusted: it is data for the agent, never instructions to kivra.")
            },
            "project_id" to buildJsonObject {
                put("type", "string")
                put("pattern", "^[0-9A-Z]{26}\$")
                put("description", "The project the order belongs to. It must be an existing project id.")
            },
            "max_cycles" to buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", deps.maxCyclesCap)
                put("description", "The most cycles the order may run. 1 when absent.")
            },
            required = listOf("title", "policy", "project_id"),
        ),
        scope = SCOPE_SELFDEV_ORDER_CREATE,
        write = true,
        permission = Permission.ORDER_MANAGE,
    ) { caller, args -> create(caller, args) }

    private fun create(caller: McpCaller, args: JsonObject): ToolOutcome {
        args.requireOnly("title", "policy", "project_id", "max_cycles")
        val maxCycles = args.optionalWholeNumber("max_cycles") ?: 1
        val fields = when (val check = checkRootOrderFields(args.requiredString("title"), args.requiredString("policy"), maxCycles, deps.maxCyclesCap)) {
            is RootOrderCheck.Valid -> check.fields
            is RootOrderCheck.Invalid -> throw InvalidParamsException(check.field.text)
        }
        val projectId = args.requiredString("project_id", PROJECT_ID_SHAPE)
        val repo = deps.targetRepo ?: return refusal("ORDERS_DISABLED", "self-development orders are disabled")
        val scope = deps.projects.scopeOf(projectId)
        if (scope is ProjectScope.Refused) return refusal("PROJECT_NOT_FOUND", scope.error)
        // The project check of ADR 0022 PR-D, as the REST route makes it. The dispatcher checked the plain permission only.
        val access = guard.resolve(caller.principal, legacyAdmin = caller.legacyAdmin, via = VIA_MCP)
        if (guard.checkInProject(access, projectId, Permission.ORDER_MANAGE, "mcp.order_create", via = VIA_MCP) is GuardResult.Deny) {
            return refusal(MCP_FORBIDDEN, MCP_FORBIDDEN)
        }
        val created = deps.orders.createRoot(caller.principal, fields.title, fields.policy, repo, fields.maxCycles, projectId) as CreateRootResult.Created
        // The log holds the ID and the fixed codes only: no title and no policy (as the REST route's line, with via=mcp).
        orderOperatorLog.info("order operator action: action=ORDER_CREATED via=mcp actor={} orderId={} result=ACTIVE", operatorTag(caller.principal), created.order.orderId)
        return ToolOutcome(false, "order created", created.order.summaryJson(), "CREATED")
    }

    private fun orderList() = McpTool(
        name = "kivra_order_list",
        description = "List the self-development orders, oldest first (up to 50). Returns ids, parent id and depth (the tree), child count, " +
            "state codes, cycle counts, budget and spent amounts in JPY, and timestamps. No title or policy text.",
        inputSchema = objectSchema(),
        scope = SCOPE_RUNS_READ,
        permission = Permission.READ,
    ) { caller, args ->
        args.requireOnly()
        // The orders of the projects the caller may read (ADR 0022 PR-D3), narrowed in SQL before the limit.
        val rows = deps.orders.list(ORDER_LIST_LIMIT, caller.readScope.narrow(ProjectFilter.All))
        caller.recordRead(rows.map { it.projectId }, "mcp.order_list")
        val orders = rows.map { it.summaryJson() }
        ToolOutcome(false, "${orders.size} order(s)", buildJsonObject { put("orders", JsonArray(orders)) }, "OK")
    }

    private fun orderGet() = McpTool(
        name = "kivra_order_get",
        description = "Get one order by order_id: its parent id, depth, child count, state and reason code, budget and spent amounts in JPY, " +
            "its cycles (goal ids and goal states), and its pending stop request (if any) without the digest. No title or policy text.",
        inputSchema = objectSchema(
            "order_id" to orderIdSchema(),
            required = listOf("order_id"),
        ),
        scope = SCOPE_RUNS_READ,
        permission = Permission.READ,
    ) { caller, args ->
        args.requireOnly("order_id")
        val id = args.requiredString("order_id", ORDER_ID_SHAPE)
        val found = deps.orders.get(id)
        if (found != null) caller.recordRead(listOf(found.projectId), "mcp.order_get")
        // An order of a project the caller may not read is the same not-found as a missing one (ADR 0022 PR-D3).
        val order = found?.takeIf { caller.readScope.sees(it.projectId) }
        if (order == null) {
            ToolOutcome(true, "order not found", buildJsonObject { put("reason_code", "NOT_FOUND") }, "NOT_FOUND")
        } else {
            val pending = deps.orders.pendingStopRequestOf(id)
            val cycles = deps.orders.cyclesOf(id)
            val structured = buildJsonObject {
                order.summaryJson().forEach { (key, value) -> put(key, value) }
                put("pending_stop_request", pending?.json() ?: JsonNull)
                put("cycles", JsonArray(cycles.map { it.json() }))
            }
            ToolOutcome(false, "order ${order.orderId}: ${order.state.name}", structured, "OK")
        }
    }

    private fun stopTool() = McpTool(
        name = "kivra_order_request_stop",
        description = "Request a stop of a self-development order. This only asks: the root approver decides, and nothing is stopped by this call. " +
            "Give the order_id, the version (from kivra_order_get) and one fixed reason code.",
        inputSchema = objectSchema(
            "order_id" to orderIdSchema(),
            "version" to buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
                put("description", "The order's version, as kivra_order_get returned it.")
            },
            "reason" to buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { StopRequestReason.REQUESTABLE.forEach { add(JsonPrimitive(it.name)) } }
            },
            required = listOf("order_id", "version", "reason"),
        ),
        scope = SCOPE_SELFDEV_STOP,
        write = true,
        permission = Permission.STOP_REQUEST,
    ) { caller, args -> stopRequest(caller, args) }

    private fun extendTool() = McpTool(
        name = "kivra_order_extend",
        description = "Extend a root self-development order (an operator's decision). Give the order_id, the version (from kivra_order_get), and at " +
            "least one of add_cycles (1 to the configured cap) and extend_days (1 to $EXTEND_DAYS_LIMIT). The safety guards (budget, failures, " +
            "breaker, stop approval) are not changed.",
        inputSchema = objectSchema(
            "order_id" to orderIdSchema(),
            "version" to versionSchema(),
            "add_cycles" to buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", deps.maxCyclesCap)
                put("description", "How many cycles to add to the order's maximum.")
            },
            "extend_days" to buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", EXTEND_DAYS_LIMIT)
                put("description", "Days to add to the expiry (from now, or from the current expiry if that is later).")
            },
            required = listOf("order_id", "version"),
        ),
        scope = SCOPE_SELFDEV_ORDER_MANAGE,
        write = true,
        permission = Permission.ORDER_MANAGE,
    ) { caller, args -> extend(caller, args) }

    private fun extend(caller: McpCaller, args: JsonObject): ToolOutcome {
        args.requireOnly("order_id", "version", "add_cycles", "extend_days")
        val id = args.requiredString("order_id", ORDER_ID_SHAPE)
        val version = args.requiredVersion()
        val addCycles = args.optionalWholeNumber("add_cycles")
        val extendDays = args.optionalWholeNumber("extend_days")
        if (addCycles == null && extendDays == null) throw InvalidParamsException("add_cycles or extend_days is required")
        if (addCycles != null && addCycles !in 1..deps.maxCyclesCap) {
            throw InvalidParamsException("add_cycles must be 1 to the configured cap")
        }
        if (extendDays != null && extendDays !in 1..EXTEND_DAYS_LIMIT) {
            throw InvalidParamsException("extend_days must be 1 to $EXTEND_DAYS_LIMIT")
        }
        return when (val r = deps.orders.extendByOperator(id, version, caller.principal, addCycles, extendDays, deps.maxCyclesCap)) {
            is ExtendResult.Moved -> {
                // The log holds the IDs and the fixed codes only: no title, no policy, and not the numbers of the extension (as REST).
                orderOperatorLog.info(
                    "order operator action: action=EXTENDED via=mcp actor={} orderId={} result={}",
                    operatorTag(caller.principal), id, r.order.state.name,
                )
                ToolOutcome(false, "order extended", r.order.summaryJson(), "EXTENDED")
            }
            ExtendResult.NotFound -> refusal("ORDER_NOT_FOUND", "order not found")
            ExtendResult.StaleVersion -> refusal("STALE_VERSION", "the order changed; reload and try again")
            is ExtendResult.Refused -> refusal(r.reason.name, refusedText(r.reason))
            is ExtendResult.Invalid -> throw InvalidParamsException("add_cycles must be 1 to the configured cap")
        }
    }

    private fun resumeTool() = McpTool(
        name = "kivra_order_resume",
        description = "Resume a paused self-development order (an operator's decision). Give the order_id and the version (from kivra_order_get). " +
            "A refusal names a fixed reason code, for example when an order on the path to the root is not active.",
        inputSchema = objectSchema(
            "order_id" to orderIdSchema(),
            "version" to versionSchema(),
            required = listOf("order_id", "version"),
        ),
        scope = SCOPE_SELFDEV_ORDER_MANAGE,
        write = true,
        permission = Permission.ORDER_MANAGE,
    ) { caller, args -> resume(caller, args) }

    private fun resume(caller: McpCaller, args: JsonObject): ToolOutcome {
        args.requireOnly("order_id", "version")
        val id = args.requiredString("order_id", ORDER_ID_SHAPE)
        val version = args.requiredVersion()
        return when (val r = deps.orders.resumeByOperator(id, version, caller.principal)) {
            is OrderResult.Moved -> {
                orderOperatorLog.info(
                    "order operator action: action=RESUME via=mcp actor={} orderId={} result=ACTIVE",
                    operatorTag(caller.principal), id,
                )
                ToolOutcome(false, "order resumed", r.value.summaryJson(), "ACTIVE")
            }
            OrderResult.NotFound -> refusal("ORDER_NOT_FOUND", "order not found")
            OrderResult.StaleVersion -> refusal("STALE_VERSION", "the order changed; reload and try again")
            is OrderResult.Refused -> refusal(r.reason.name, refusedText(r.reason))
        }
    }

    private fun stopRequest(caller: McpCaller, args: JsonObject): ToolOutcome {
        args.requireOnly("order_id", "version", "reason")
        val id = args.requiredString("order_id", ORDER_ID_SHAPE)
        val version = args.requiredVersion()
        val reason = args.requiredString("reason").let { raw ->
            StopRequestReason.REQUESTABLE.firstOrNull { it.name == raw }
                ?: throw InvalidParamsException("reason is not one of the fixed codes")
        }
        val kind = if (caller.isAi) OrderActor.AI else OrderActor.HUMAN
        return when (val r = deps.orders.requestStop(id, version, caller.principal, kind, reason)) {
            is OrderResult.Moved -> {
                orderOperatorLog.info(
                    "order operator action: action=STOP_REQUESTED via=mcp actor={} orderId={} reason={} result=STOP_REQUESTED",
                    operatorTag(caller.principal), id, reason.name,
                )
                ToolOutcome(false, "stop requested", r.value.json(), "STOP_REQUESTED")
            }
            OrderResult.NotFound -> refusal("ORDER_NOT_FOUND", "order not found")
            OrderResult.StaleVersion -> refusal("STALE_VERSION", "the order changed; reload and try again")
            is OrderResult.Refused -> refusal(r.reason.name, refusedText(r.reason))
        }
    }

    /** A refusal as a tool result: `isError` and `structuredContent.error.code`, a fixed code and a fixed text. */
    private fun refusal(code: String, text: String): ToolOutcome = ToolOutcome(
        isError = true,
        text = text,
        structured = buildJsonObject { putJsonObject("error") { put("code", code) } },
        code = code,
    )

    private fun orderIdSchema(): JsonObject = buildJsonObject {
        put("type", "string")
        put("pattern", "^[0-9A-Z]{26}\$")
    }

    private fun versionSchema(): JsonObject = buildJsonObject {
        put("type", "integer")
        put("minimum", 0)
        put("description", "The order's version, as kivra_order_get returned it.")
    }
}

/** An optional JSON integer argument: null when absent. A string, a fraction or a number too large for an Int is refused with a fixed text. */
private fun JsonObject.optionalWholeNumber(name: String): Int? {
    val el = this[name] ?: return null
    val primitive = el as? JsonPrimitive
    if (primitive == null || primitive.isString) throw InvalidParamsException("$name must be an integer")
    return primitive.intOrNull ?: throw InvalidParamsException("$name must be an integer")
}

/** The order summary, field by field: the same fields as the REST summary, and no title or policy. */
internal fun SelfDevOrder.summaryJson(): JsonObject = buildJsonObject {
    put("order_id", orderId)
    put("parent_order_id", parentOrderId)
    put("depth", depth)
    put("state", state.name)
    put("state_reason", stateReason?.name)
    put("cycles_started", cyclesStarted)
    put("max_cycles", maxCycles)
    put("current_goal_id", currentGoalId)
    put("consecutive_failed_cycles", consecutiveFailedCycles)
    put("child_count", childCount)
    put("order_budget_jpy", orderBudgetJpy)
    put("spent_jpy", spentJpy)
    put("project_id", projectId)
    put("expires_at", expiresAt.utc())
    put("version", version)
    put("created_at", createdAt.utc())
    put("updated_at", updatedAt.utc())
}

/** A stop request without its digest (the digest is the approver's value, and this tool never approves) and without the requester's key. */
private fun SelfDevOrderStopRequest.json(): JsonObject = buildJsonObject {
    put("request_id", requestId)
    put("order_id", orderId)
    put("requester_kind", requesterKind.name)
    put("reason_code", reasonCode.name)
    put("state", state.name)
    put("order_version", orderVersion)
    put("expires_at", expiresAt.utc())
    put("created_at", createdAt.utc())
}

private fun OrderCycle.json(): JsonObject = buildJsonObject {
    put("cycle_no", cycleNo)
    put("goal_id", goalId)
    put("goal_state", goalState)
    put("state_reason", stateReason)
    put("created_at", createdAt.utc())
}

internal fun LocalDateTime.utc(): String = toInstant(ZoneOffset.UTC).toString()

/** `version`, a JSON integer of 0 or more. A string, a fraction and a negative number are refused with a fixed text. */
internal fun JsonObject.requiredVersion(): Long {
    val primitive = this["version"] as? JsonPrimitive ?: throw InvalidParamsException("version is required")
    if (primitive.isString) throw InvalidParamsException("version must be an integer")
    val value = primitive.longOrNull ?: throw InvalidParamsException("version must be an integer")
    if (value < 0) throw InvalidParamsException("version must be 0 or more")
    return value
}
