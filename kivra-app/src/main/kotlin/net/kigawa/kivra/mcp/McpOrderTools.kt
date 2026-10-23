package net.kigawa.kivra.mcp

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.OrderCycle
import net.kigawa.kivra.routes.operatorTag
import net.kigawa.kivra.routes.refusedText
import org.slf4j.LoggerFactory

/** Scope of the order's stop request tool (ADR 0013 section 4, ADR 0015 section 6). Not implied by the read scope. */
const val SCOPE_SELFDEV_STOP = "kivra.selfdev.stop"

private val ORDER_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val ORDER_LIST_LIMIT = 50

/** The order's operator log, the same name as the REST routes' (SelfDevOrderRoutes): fixed codes and IDs only. */
private val orderOperatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.order.operator")

/**
 * The order dependencies of `/mcp`. Built only while the orders are on (`KIVRA_SELFDEV_ORDERS_ENABLED`, with the autopilot), so with the orders
 * off no order tool is registered at all. [aiPrincipal] is the AI principal of the REST routes (`KIVRA_SELFDEV_AI_PRINCIPAL`): a caller with
 * that owner key is the AI actor, and every other caller is a human actor, as in `SelfDevOrderRoutes`.
 */
class McpOrderDeps(val orders: SelfDevOrderRepository, val aiPrincipal: String?)

/**
 * The order tools of ADR 0015 section 6 on `/mcp`, limited to what ADR 0015 U-9 allows an MCP client: reading the orders and requesting a stop.
 * Approval, rejection, creation, cycle start, pause, resume and cancellation are not defined here, so they are not in `tools/list` and a call to them
 * is an unknown tool.
 *
 * - `kivra_order_list` and `kivra_order_get` need `kivra.runs.read`. They return fixed codes, IDs, dates and counts only: never a title, a
 *   policy, a goal's title or body, or a stop request's digest (the REST detail view returns the title and policy, and this tool does not).
 * - `kivra_order_request_stop` needs `kivra.selfdev.stop`. It is listed and answered only while `KIVRA_MCP_WRITE_ENABLED=true` ([tools]).
 *   It calls the same repository request as `POST /api/selfdev/orders/{id}/stop-requests`, so the rules (the state machine, the version check,
 *   one pending request per tree) are the repository's and are not repeated here. Its input is strict: the order id, the version and one
 *   of the fixed reason codes. No free text is accepted.
 */
class McpOrderTools(private val deps: McpOrderDeps) {

    /** The order tools for the current settings. The stop request is registered only when the MCP write switch is on. */
    fun tools(writeEnabled: Boolean): List<McpTool> =
        listOf(orderList(), orderGet()) + if (writeEnabled) listOf(stopTool()) else emptyList()

    private fun orderList() = McpTool(
        name = "kivra_order_list",
        description = "List the self-development orders, oldest first (up to 50). Returns ids, parent id and depth (the tree), child count, " +
            "state codes, cycle counts, budget and spent amounts in JPY, and timestamps. No title or policy text.",
        inputSchema = objectSchema(),
        scope = SCOPE_RUNS_READ,
    ) { _, args ->
        args.requireOnly()
        val orders = deps.orders.list(ORDER_LIST_LIMIT).map { it.summaryJson() }
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
    ) { _, args ->
        args.requireOnly("order_id")
        val id = args.requiredString("order_id", ORDER_ID_SHAPE)
        val order = deps.orders.get(id)
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
    ) { caller, args -> stopRequest(caller, args) }

    private fun stopRequest(caller: McpCaller, args: JsonObject): ToolOutcome {
        args.requireOnly("order_id", "version", "reason")
        val id = args.requiredString("order_id", ORDER_ID_SHAPE)
        val version = args.requiredVersion()
        val reason = args.requiredString("reason").let { raw ->
            StopRequestReason.REQUESTABLE.firstOrNull { it.name == raw }
                ?: throw InvalidParamsException("reason is not one of the fixed codes")
        }
        val kind = if (caller.principal == deps.aiPrincipal) OrderActor.AI else OrderActor.HUMAN
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
}

/** The order summary, field by field: the same fields as the REST summary, and no title or policy. */
private fun SelfDevOrder.summaryJson(): JsonObject = buildJsonObject {
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
    put("goal_state", goalState.name)
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
