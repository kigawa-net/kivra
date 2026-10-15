package net.kigawa.kivra.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertNull
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.BudgetPort

private const val TENANT = "tenant"
private const val PATH = "/api/admin/budget/acknowledge"

/** Records the request the route hands to the port, so a test can see which operator the route used. */
private class RecordingBudget(private val inner: BudgetPort) : BudgetPort by inner {
    var last: AcknowledgeUnpriced? = null

    override fun acknowledgeUnpriced(request: AcknowledgeUnpriced): AcknowledgeResult {
        last = request
        return inner.acknowledgeUnpriced(request)
    }
}

/** Authenticates the caller by the `X-Test-Operator` header. A missing or blank header is unauthenticated. */
private val headerIdentity = TaskApiIdentity { headers -> headers["X-Test-Operator"]?.takeIf { it.isNotBlank() } }

/**
 * A controller in JST month 2026-10 (the clock is fixed so the request period is known). [unpriced] keys are reserved and then
 * settled without an actual cost; [reservedOnly] keys stay reserved. All are reserved before any is settled, because a scope
 * with unpriced usage admits nothing new.
 */
private fun controllerWith(unpriced: List<String>, reservedOnly: List<String> = emptyList()): BudgetController {
    val clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC)
    val c = BudgetController(mapOf(TENANT to BudgetLimit(BigDecimal("10000"), currency = "JPY")), clock)
    for (k in reservedOnly + unpriced) c.reserve(k, listOf(TENANT), BudgetAmount(BigDecimal("1000"), "JPY"))
    for (k in unpriced) c.commit(k, null)
    return c
}

private fun controllerWithUnpriced(vararg keys: String): BudgetController = controllerWith(keys.toList())

private fun ApplicationTestBuilder.setUp(budget: BudgetPort, identity: TaskApiIdentity?) {
    application {
        install(ContentNegotiation) { json() }
        routing { configureBudgetAdminRoutes(budget, identity) }
    }
}

private suspend fun HttpClient.acknowledge(body: String, operator: String? = "alice"): HttpResponse = post(PATH) {
    contentType(ContentType.Application.Json)
    operator?.let { header("X-Test-Operator", it) }
    setBody(body)
}

private fun validBody(key: String, actual: String? = "600", extra: String = ""): String {
    val actualJson = actual?.let { "\"$it\"" } ?: "null"
    return """{"scopeId":"tenant","period":"2026-10","reservationKey":"$key","actualAmountJpy":$actualJson,"evidence":"invoice checked","reason":"lost response"$extra}"""
}

class BudgetAdminRoutesTest {
    @Test
    fun `without an identity (KIVRA_AUTH_MODE unset) no route is registered (fail closed)`() = testApplication {
        setUp(controllerWithUnpriced("k1"), identity = null)
        assertEquals(HttpStatusCode.NotFound, client.acknowledge(validBody("k1")).status)
    }

    @Test
    fun `an unauthenticated request is refused and changes nothing`() = testApplication {
        val controller = controllerWithUnpriced("k1")
        val recording = RecordingBudget(controller)
        setUp(recording, headerIdentity)
        assertEquals(HttpStatusCode.Unauthorized, client.acknowledge(validBody("k1"), operator = null).status)
        assertNull(recording.last)
        assertEquals(1L, controller.usage(TENANT).unpricedCalls)
    }

    @Test
    fun `the operator is the authenticated caller, an operatorId in the body is ignored`() = testApplication {
        val recording = RecordingBudget(controllerWithUnpriced("k1"))
        setUp(recording, headerIdentity)
        val spoofed = """{"scopeId":"tenant","period":"2026-10","reservationKey":"k1","actualAmountJpy":"600","evidence":"e","reason":"r","operatorId":"someone-else"}"""
        val response = client.acknowledge(spoofed, operator = "alice")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("alice", recording.last?.operatorId)
    }

    @Test
    fun `invalid input gets a fixed refusal that never echoes the input`() = testApplication {
        val controller = controllerWithUnpriced("k1")
        setUp(RecordingBudget(controller), headerIdentity)

        val notANumber = client.acknowledge(
            """{"scopeId":"tenant","period":"2026-10","reservationKey":"k1","actualAmountJpy":"SECRET-NOT-A-NUMBER","evidence":"SECRET-EVIDENCE-7","reason":"r"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, notANumber.status)
        assertFalse(notANumber.bodyAsText().contains("SECRET"))

        val blankReason = client.acknowledge(
            """{"scopeId":"tenant","period":"2026-10","reservationKey":"k1","actualAmountJpy":"600","evidence":"SECRET-EVIDENCE-8","reason":" "}""",
        )
        assertEquals(HttpStatusCode.BadRequest, blankReason.status)
        assertFalse(blankReason.bodyAsText().contains("SECRET"))

        val negative = client.acknowledge(validBody("k1", actual = "-5"))
        assertEquals(HttpStatusCode.BadRequest, negative.status)

        val notJson = client.acknowledge("not json")
        assertEquals(HttpStatusCode.BadRequest, notJson.status)

        // None of the refused requests released the reservation.
        assertEquals(1L, controller.usage(TENANT).unpricedCalls)
    }

    @Test
    fun `the release outcomes map to their statuses`() = testApplication {
        val controller = controllerWith(unpriced = listOf("k1"), reservedOnly = listOf("k2"))
        setUp(RecordingBudget(controller), headerIdentity)

        val released = client.acknowledge(validBody("k1", actual = "600"))
        assertEquals(HttpStatusCode.OK, released.status)
        assertEquals(true, released.bodyAsText().contains("\"status\":\"ACKNOWLEDGED\""))

        val again = client.acknowledge(validBody("k1", actual = "900"))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(true, again.bodyAsText().contains("\"status\":\"ALREADY_ACKNOWLEDGED\""))

        assertEquals(HttpStatusCode.NotFound, client.acknowledge(validBody("no-such-key")).status)
        assertEquals(HttpStatusCode.Conflict, client.acknowledge(validBody("k2")).status)
    }

    @Test
    fun `a store failure is reported as 503 with a fixed message`() = testApplication {
        val controller = controllerWithUnpriced("k1")
        val failing = object : BudgetPort by controller {
            override fun acknowledgeUnpriced(request: AcknowledgeUnpriced): AcknowledgeResult = AcknowledgeResult.Failure
        }
        setUp(failing, headerIdentity)
        val response = client.acknowledge(validBody("k1"))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(true, response.bodyAsText().contains("budget store unavailable"))
    }
}
