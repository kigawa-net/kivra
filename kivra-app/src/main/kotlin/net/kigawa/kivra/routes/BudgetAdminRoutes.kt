package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetPort

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

@Serializable
private data class BudgetAdminError(val error: String)

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
internal fun Route.configureBudgetAdminRoutes(budget: BudgetPort, identity: TaskApiIdentity?) {
    if (identity == null) return
    post("/api/admin/budget/acknowledge") {
        val operatorId = identity.principal(call.request.headers)
        if (operatorId == null) {
            call.respond(HttpStatusCode.Unauthorized, BudgetAdminError("unauthorized"))
            return@post
        }
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
        when (budget.acknowledgeUnpriced(request)) {
            is AcknowledgeResult.Acknowledged ->
                call.respond(HttpStatusCode.OK, AcknowledgeBudgetResponse("ACKNOWLEDGED", body.reservationKey))
            AcknowledgeResult.AlreadyAcknowledged ->
                call.respond(HttpStatusCode.OK, AcknowledgeBudgetResponse("ALREADY_ACKNOWLEDGED", body.reservationKey))
            AcknowledgeResult.NotFound -> call.respond(HttpStatusCode.NotFound, BudgetAdminError("reservation not found"))
            AcknowledgeResult.NotUnpriced -> call.respond(HttpStatusCode.Conflict, BudgetAdminError("reservation is not unpriced"))
            is AcknowledgeResult.Rejected -> call.respond(HttpStatusCode.BadRequest, BudgetAdminError(INVALID_ACKNOWLEDGE_REQUEST))
            AcknowledgeResult.Failure -> call.respond(HttpStatusCode.ServiceUnavailable, BudgetAdminError("budget store unavailable"))
        }
    }
}
