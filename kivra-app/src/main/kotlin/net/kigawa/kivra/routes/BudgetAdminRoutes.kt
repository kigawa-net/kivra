package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.math.BigDecimal
import java.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.BudgetOperatorStatus
import net.kigawa.kivra.db.BudgetOperatorView
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_NOT_COMPARABLE
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_TOO_LARGE
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.modelgateway.FxConfig

/**
 * Body of `POST /api/admin/budget/acknowledge`. Unknown fields are ignored, so an `operatorId` sent by a caller has no effect:
 * the operator is always the authenticated principal.
 */
@Serializable
data class AcknowledgeBudgetRequest(
    val scopeId: String,
    val period: String,
    val reservationKey: String,
    /** The verified actual cost in JPY as a decimal string. Absent means the actual cost is not known; "0" means verified as not billed. */
    val actualAmountJpy: String? = null,
    val evidence: String,
    val reason: String,
)

@Serializable
data class AcknowledgeBudgetResponse(val status: String, val reservationKey: String)

/** The FX rate's expiry for operators. `fx` is null when the rate is not configured (USD is not used). Carries no secret. */
@Serializable
data class BudgetFxResponse(val fx: FxExpiryBody?)

@Serializable
data class FxExpiryBody(val asOf: String, val daysLeft: Long, val expired: Boolean)

/**
 * The operator's budget screen state (`GET /api/admin/budget/status`). Money is decimal yen as a string. [remainingJpy] is the
 * limit minus the committed total minus the open reservations (it may be negative when a late charge went over the limit).
 */
@Serializable
data class BudgetStatusResponse(
    val period: String,
    val limitJpy: String,
    val committedJpy: String,
    val reservedJpy: String,
    val remainingJpy: String,
    val paths: BudgetPathsBody,
    val unpricedCount: Long,
    val unpriced: List<UnpricedBody>,
)

@Serializable
data class BudgetPathsBody(val api: PathCommittedBody, val cli: PathCommittedBody)

@Serializable
data class PathCommittedBody(val committedJpy: String)

/** [state] is UNPRICED or LAPSED; [reason] is a fixed code (COST_NOT_ESTABLISHED, TTL_ELAPSED); [path] is `api` or `cli`. */
@Serializable
data class UnpricedBody(
    val reservationKey: String,
    val scopeId: String,
    val period: String,
    val state: String,
    val path: String,
    val amountJpy: String,
    val reason: String,
)

@Serializable
private data class BudgetAdminError(val error: String)

/** A refusal of a well-formed acknowledgement, with a fixed machine code the screen can explain (`TOO_LARGE`, `NOT_COMPARABLE`, `INVALID`). */
@Serializable
private data class BudgetAdminCodedError(val error: String, val code: String)

private const val INVALID_ACKNOWLEDGE_REQUEST = "invalid acknowledgement request"

private val budgetAdminJson = Json { ignoreUnknownKeys = true }

/**
 * Operator release of an UNPRICED budget reservation (ADR 0003, Issue #109). Authenticated like the Task API: [identity] is the
 * same [TaskApiIdentity] (`KIVRA_AUTH_MODE`). When [identity] is null the route is not registered at all (fail closed).
 *
 * Responses never include internal detail (store exceptions, evidence, or the request values in an error): 200 acknowledged or
 * already acknowledged, 400 invalid input (fixed text), 401 unauthenticated, 404 no such reservation in that scope and period,
 * 409 the reservation is not unpriced, 503 the store could not decide.
 */
internal fun Route.configureBudgetAdminRoutes(
    budget: BudgetPort,
    identity: TaskApiIdentity?,
    fx: FxConfig? = null,
    clock: Clock = Clock.systemUTC(),
    operatorView: BudgetOperatorView? = null,
) {
    if (identity == null) return
    if (operatorView != null) {
        // Read-only: the month's spend, the path split and the UNPRICED list. Same authentication as the routes below.
        get("/api/admin/budget/status") {
            if (identity.principal(call.request.headers) == null) {
                call.respond(HttpStatusCode.Unauthorized, BudgetAdminError("unauthorized"))
                return@get
            }
            call.respond(HttpStatusCode.OK, operatorView.operatorStatus().toBody())
        }
    }
    // Read-only: the FX expiry state (for the operator's check before a rate lapses). Same authentication as the POST below.
    get("/api/admin/budget/fx") {
        if (identity.principal(call.request.headers) == null) {
            call.respond(HttpStatusCode.Unauthorized, BudgetAdminError("unauthorized"))
            return@get
        }
        val expiry = fx?.expiry(clock)
        val body = expiry?.let { FxExpiryBody(it.asOfDate.toString(), it.daysLeft, it.expired) }
        call.respond(HttpStatusCode.OK, BudgetFxResponse(body))
    }
    post("/api/admin/budget/acknowledge") {
        val operatorId = identity.principal(call.request.headers)
        if (operatorId == null) {
            call.respond(HttpStatusCode.Unauthorized, BudgetAdminError("unauthorized"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val body = try {
            budgetAdminJson.decodeFromString(AcknowledgeBudgetRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, BudgetAdminError(INVALID_ACKNOWLEDGE_REQUEST))
            return@post
        }
        val actual = try {
            body.actualAmountJpy?.let { BudgetAmount(BigDecimal(it), "JPY") }
        } catch (_: NumberFormatException) {
            call.respond(HttpStatusCode.BadRequest, BudgetAdminError(INVALID_ACKNOWLEDGE_REQUEST))
            return@post
        }
        val request = AcknowledgeUnpriced(
            scopeId = body.scopeId,
            period = body.period,
            reservationKey = body.reservationKey,
            actualAmount = actual,
            evidence = body.evidence,
            operatorId = operatorId,
            reason = body.reason,
        )
        val result = budget.acknowledgeUnpriced(request)
        when (result) {
            is AcknowledgeResult.Acknowledged ->
                call.respond(HttpStatusCode.OK, AcknowledgeBudgetResponse("ACKNOWLEDGED", body.reservationKey))
            AcknowledgeResult.AlreadyAcknowledged ->
                call.respond(HttpStatusCode.OK, AcknowledgeBudgetResponse("ALREADY_ACKNOWLEDGED", body.reservationKey))
            AcknowledgeResult.NotFound -> call.respond(HttpStatusCode.NotFound, BudgetAdminError("reservation not found"))
            AcknowledgeResult.NotUnpriced -> call.respond(HttpStatusCode.Conflict, BudgetAdminError("reservation is not unpriced"))
            is AcknowledgeResult.Rejected -> call.respond(
                HttpStatusCode.BadRequest,
                BudgetAdminCodedError(INVALID_ACKNOWLEDGE_REQUEST, rejectionCode(result.reason)),
            )
            AcknowledgeResult.Failure -> call.respond(HttpStatusCode.ServiceUnavailable, BudgetAdminError("budget store unavailable"))
        }
    }
}

internal fun rejectionCode(reason: String): String = when (reason) {
    ACKNOWLEDGE_TOO_LARGE -> "TOO_LARGE"
    ACKNOWLEDGE_NOT_COMPARABLE -> "NOT_COMPARABLE"
    else -> "INVALID"
}

/** Money as plain decimal yen with no trailing zeros; the micro-yen scale of the store is kept (up to six places). */
private fun yenText(v: java.math.BigDecimal): String = v.stripTrailingZeros().toPlainString()

private fun BudgetOperatorStatus.toBody(): BudgetStatusResponse {
    val remaining = limitJpy - committedJpy - reservedJpy
    val api = committedJpy - cliCommittedJpy
    return BudgetStatusResponse(
        period = period,
        limitJpy = yenText(limitJpy),
        committedJpy = yenText(committedJpy),
        reservedJpy = yenText(reservedJpy),
        remainingJpy = yenText(remaining),
        paths = BudgetPathsBody(api = PathCommittedBody(yenText(api)), cli = PathCommittedBody(yenText(cliCommittedJpy))),
        unpricedCount = unpricedCount,
        unpriced = unpriced.map {
            UnpricedBody(
                reservationKey = it.reservationKey,
                scopeId = it.scopeId,
                period = it.period,
                state = it.state,
                path = if (it.cli) "cli" else "api",
                amountJpy = yenText(it.amountJpy),
                reason = if (it.state == "LAPSED") "TTL_ELAPSED" else "COST_NOT_ESTABLISHED",
            )
        },
    )
}
