package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.FxConfig

private val fxIdentity = TaskApiIdentity { headers -> headers["X-Test-Operator"]?.takeIf { it.isNotBlank() } }
private val fxAsOf = Instant.parse("2026-10-08T00:00:00Z")

class BudgetFxRouteTest {
    private fun fxApp(
        fx: FxConfig?,
        now: String,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureBudgetAdminRoutes(
                    BudgetController(mapOf("t" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), Clock.systemUTC()),
                    fxIdentity,
                    fx = fx,
                    clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
                )
            }
        }
        block()
    }

    @Test
    fun `the fx state is returned to an authenticated operator`() = fxApp(FxConfig(BigDecimal("150"), fxAsOf), "2026-10-13T00:00:00Z") {
        val res = client.get("/api/admin/budget/fx") { header("X-Test-Operator", "alice") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"fx":{"asOf":"2026-10-08","daysLeft":2,"expired":false}}""", res.bodyAsText())
    }

    @Test
    fun `an expired rate says expired with no days left`() = fxApp(FxConfig(BigDecimal("150"), fxAsOf), "2026-10-20T00:00:00Z") {
        val body = client.get("/api/admin/budget/fx") { header("X-Test-Operator", "alice") }.bodyAsText()
        assertTrue(body.contains(""""expired":true"""))
    }

    @Test
    fun `without a configured rate the fx field is null`() = fxApp(null, "2026-10-13T00:00:00Z") {
        val body = client.get("/api/admin/budget/fx") { header("X-Test-Operator", "alice") }.bodyAsText()
        assertEquals("""{"fx":null}""", body)
    }

    @Test
    fun `an unauthenticated request is refused`() = fxApp(FxConfig(BigDecimal("150"), fxAsOf), "2026-10-13T00:00:00Z") {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/budget/fx").status)
    }
}
