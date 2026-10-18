package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.BudgetOperatorStatus
import net.kigawa.kivra.db.BudgetOperatorView
import net.kigawa.kivra.db.UnpricedReservation
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_NOT_COMPARABLE
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_TOO_LARGE
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit

private val statusIdentity = TaskApiIdentity { headers -> headers["X-Test-Operator"]?.takeIf { it.isNotBlank() } }

/** A fixed snapshot, so the response shape and the arithmetic can be checked exactly. */
private class FixedView(private val status: BudgetOperatorStatus) : BudgetOperatorView {
    override fun operatorStatus(): BudgetOperatorStatus = status
}

private fun yen(s: String) = BigDecimal(s)

private val sampleStatus = BudgetOperatorStatus(
    period = "2026-10",
    limitJpy = yen("10000"),
    committedJpy = yen("3000.5"),
    reservedJpy = yen("500"),
    cliCommittedJpy = yen("1200.25"),
    unpricedCount = 2,
    unpriced = listOf(
        UnpricedReservation("coding:01ATTEMPTAAAAAAAAAAAAAAAAAA:1", "tenant", "2026-10", "UNPRICED", yen("120"), cli = true),
        UnpricedReservation("api-req-1", "tenant", "2026-09", "LAPSED", yen("80.5"), cli = false),
    ),
)

private fun ApplicationTestBuilder.statusApp(
    view: BudgetOperatorView?,
    identity: TaskApiIdentity? = statusIdentity,
    budget: BudgetController = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY"))),
) {
    application {
        install(ContentNegotiation) { json() }
        routing {
            configureBudgetAdminRoutes(
                budget,
                identity,
                operatorView = view,
            )
        }
    }
}

class BudgetStatusRouteTest {
    @Test
    fun `the month's figures, the path split and the unpriced list are returned to an operator`() = testApplication {
        statusApp(FixedView(sampleStatus))
        val res = client.get("/api/admin/budget/status") { header("X-Test-Operator", "alice") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            """{"period":"2026-10","limitJpy":"10000","committedJpy":"3000.5","reservedJpy":"500","remainingJpy":"6499.5",""" +
                """"paths":{"api":{"committedJpy":"1800.25"},"cli":{"committedJpy":"1200.25"}},"unpricedCount":2,""" +
                """"unpriced":[{"reservationKey":"coding:01ATTEMPTAAAAAAAAAAAAAAAAAA:1","scopeId":"tenant","period":"2026-10",""" +
                """"state":"UNPRICED","path":"cli","amountJpy":"120","reason":"COST_NOT_ESTABLISHED"},""" +
                """{"reservationKey":"api-req-1","scopeId":"tenant","period":"2026-09","state":"LAPSED","path":"api",""" +
                """"amountJpy":"80.5","reason":"TTL_ELAPSED"}]}""",
            res.bodyAsText(),
        )
    }

    @Test
    fun `an overspent month shows a negative remaining amount, not a clamped one`() = testApplication {
        val over = sampleStatus.copy(committedJpy = yen("10200"), reservedJpy = yen("0"), unpriced = emptyList(), unpricedCount = 0)
        statusApp(FixedView(over))
        val body = client.get("/api/admin/budget/status") { header("X-Test-Operator", "alice") }.bodyAsText()
        assertTrue(body.contains(""""remainingJpy":"-200""""), body)
    }

    @Test
    fun `the status is refused without an operator`() = testApplication {
        statusApp(FixedView(sampleStatus))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/budget/status").status)
    }

    @Test
    fun `without a view the status route is not registered`() = testApplication {
        statusApp(view = null)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/budget/status") { header("X-Test-Operator", "alice") }.status)
    }

    @Test
    fun `the response carries amounts and keys only, never a prompt, a body, a file path or a model name`() = testApplication {
        val leaky = sampleStatus.copy(
            unpriced = listOf(UnpricedReservation("coding:01ATTEMPTAAAAAAAAAAAAAAAAAA:1", "tenant", "2026-10", "UNPRICED", yen("1"), cli = true)),
        )
        statusApp(FixedView(leaky))
        val body = client.get("/api/admin/budget/status") { header("X-Test-Operator", "alice") }.bodyAsText()
        for (word in listOf("prompt", "summary", "change", "model", "stdout", "response", "workspace", "evidence", "operatorId", "/", "\\")) {
            assertFalse(body.contains(word, ignoreCase = true), "the status must not carry '$word'")
        }
    }

    @Test
    fun `an acknowledgement refused as too large carries a fixed code, and the existing message stays the same`() = testApplication {
        val clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC)
        val controller = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), clock)
        controller.reserve("k1", listOf("tenant"), BudgetAmount(BigDecimal("1000"), "JPY"))
        controller.commit("k1", null)
        statusApp(FixedView(sampleStatus), budget = controller)
        val res = client.post("/api/admin/budget/acknowledge") {
            contentType(ContentType.Application.Json)
            header("X-Test-Operator", "alice")
            setBody("""{"scopeId":"tenant","period":"2026-10","reservationKey":"k1","actualAmountJpy":"200000","evidence":"e","reason":"r"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"invalid acknowledgement request","code":"TOO_LARGE"}""", res.bodyAsText())
        assertEquals(1L, controller.usage("tenant").unpricedCalls, "a refused release changes nothing")

        val missing = client.post("/api/admin/budget/acknowledge") {
            contentType(ContentType.Application.Json)
            header("X-Test-Operator", "alice")
            setBody("""{"scopeId":"tenant","period":"2026-10","reservationKey":"nope","actualAmountJpy":"1","evidence":"e","reason":"r"}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("""{"error":"reservation not found"}""", missing.bodyAsText())
    }

    @Test
    fun `the rejection code maps the fixed refusal texts`() {
        assertEquals("TOO_LARGE", rejectionCode(ACKNOWLEDGE_TOO_LARGE))
        assertEquals("NOT_COMPARABLE", rejectionCode(ACKNOWLEDGE_NOT_COMPARABLE))
        assertEquals("INVALID", rejectionCode("reason is invalid"))
    }
}
