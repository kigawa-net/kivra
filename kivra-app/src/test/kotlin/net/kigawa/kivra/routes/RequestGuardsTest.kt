package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.selfdev.FakeGitHub
import org.slf4j.LoggerFactory

/**
 * The CSRF guard ([requireJsonSameOrigin]) on the four state-changing POST routes (`/api/tasks`, `/api/selfdev/tasks`,
 * `/api/admin/selfdev/runs/{id}/release`, `/api/admin/budget/acknowledge`). A refused request must be refused before the handler
 * runs, must change no state, must answer with fixed text, and must not log or echo the refused header values.
 */
private const val KIVRA_HOST = "kivra.example.net"

class RequestGuardsTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    /** Counts the calls that reach the budget port, so a test can see whether the handler ran. */
    private class CountingBudget(private val inner: BudgetPort) : BudgetPort by inner {
        val calls = AtomicInteger()

        override fun acknowledgeUnpriced(request: AcknowledgeUnpriced): AcknowledgeResult {
            calls.incrementAndGet()
            return inner.acknowledgeUnpriced(request)
        }
    }

    private fun budgetController(): BudgetController {
        val clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC)
        return BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), clock)
    }

    /**
     * [okStatus] is what a valid, allowed request gets: a new run is 202, and a reservation or run that does not exist is 404.
     * The release id is a well-formed ULID that is not in the database.
     */
    private enum class Endpoint(val path: String, val okStatus: HttpStatusCode) {
        TASKS("/api/tasks", HttpStatusCode.Accepted),
        SELFDEV_TASKS("/api/selfdev/tasks", HttpStatusCode.Accepted),
        RELEASE("/api/admin/selfdev/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV/release", HttpStatusCode.NotFound),
        BUDGET("/api/admin/budget/acknowledge", HttpStatusCode.NotFound),
    }

    private val keySeq = AtomicInteger()

    /** A fresh idempotency key per call, so a second accepted submit is not a replay (200). */
    private fun body(endpoint: Endpoint): String = when (endpoint) {
        Endpoint.TASKS, Endpoint.SELFDEV_TASKS -> """{"title":"README を直す","idempotencyKey":"csrf-key-${keySeq.incrementAndGet()}"}"""
        Endpoint.RELEASE -> """{"reason":"csrf"}"""
        Endpoint.BUDGET ->
            """{"scopeId":"tenant","period":"2026-10","reservationKey":"csrf-missing","actualAmountJpy":"0","evidence":"e","reason":"r"}"""
    }

    private class Harness(val db: TestDb, val budget: CountingBudget)

    private fun ApplicationTestBuilder.mount(h: Harness) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureTaskApiRoutes(h.db.runs, identity)
                configureSelfDevRoutes(SelfDevRouteDeps(h.db.runs, identity, RepoRef("kigawa-net", "kivra"), 1000, FakeGitHub()))
                configureSelfDevAdminRoutes(h.db.runs, identity)
                configureBudgetAdminRoutes(h.budget, identity)
            }
        }
    }

    /**
     * Sends one POST. [contentType] is the Content-Type header: the default is `application/json`, and an empty string sends no
     * Content-Type at all.
     */
    private suspend fun ApplicationTestBuilder.send(
        endpoint: Endpoint,
        principal: String? = "alice",
        contentType: String = ContentType.Application.Json.toString(),
        headers: Map<String, String> = emptyMap(),
        bodyText: String = body(endpoint),
    ): HttpResponse = client.post(endpoint.path) {
        header(HttpHeaders.Host, KIVRA_HOST)
        principal?.let { header("X-Test-Principal", it) }
        headers.forEach { (k, v) -> header(k, v) }
        setBody(
            if (contentType.isEmpty()) ByteArrayContent(bodyText.toByteArray())
            else io.ktor.http.content.TextContent(bodyText, ContentType.parse(contentType)),
        )
    }

    private fun TestDb.noTasksCreated(): Boolean = runs.listTasks("alice", limit = 100).isEmpty()

    @Test
    fun `a POST without application json is refused with 415 and changes nothing`() = TestDb().use { db ->
        testApplication {
            val budget = CountingBudget(budgetController())
            mount(Harness(db, budget))
            for (endpoint in Endpoint.entries) {
                for (ct in listOf("", "text/plain", "text/plain; charset=utf-8", "application/x-www-form-urlencoded", "application/jsonp")) {
                    val r = send(endpoint, contentType = ct)
                    assertEquals(HttpStatusCode.UnsupportedMediaType, r.status, "${endpoint.name} with [$ct]")
                    assertEquals("""{"error":"content-type must be application/json"}""", r.bodyAsText())
                }
            }
            assertTrue(db.noTasksCreated())
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `application json, with or without a charset, passes the content-type check`() = TestDb().use { db ->
        testApplication {
            mount(Harness(db, CountingBudget(budgetController())))
            for (endpoint in Endpoint.entries) {
                assertEquals(endpoint.okStatus, send(endpoint).status, "${endpoint.name} application/json")
                assertEquals(endpoint.okStatus, send(endpoint, contentType = "application/json; charset=utf-8").status, "${endpoint.name} charset")
            }
        }
    }

    @Test
    fun `a cross-site, same-site, or unknown Sec-Fetch-Site is refused with 403 and changes nothing`() = TestDb().use { db ->
        testApplication {
            val budget = CountingBudget(budgetController())
            mount(Harness(db, budget))
            for (endpoint in Endpoint.entries) {
                for (site in listOf("cross-site", "same-site", "bogus")) {
                    val r = send(endpoint, headers = mapOf("Sec-Fetch-Site" to site))
                    assertEquals(HttpStatusCode.Forbidden, r.status, "${endpoint.name} $site")
                    assertEquals("""{"error":"cross-site request refused"}""", r.bodyAsText())
                }
            }
            assertTrue(db.noTasksCreated())
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `same-origin, none, and an absent Sec-Fetch-Site header pass`() = TestDb().use { db ->
        testApplication {
            mount(Harness(db, CountingBudget(budgetController())))
            for (endpoint in Endpoint.entries) {
                assertEquals(endpoint.okStatus, send(endpoint, headers = mapOf("Sec-Fetch-Site" to "same-origin")).status, "${endpoint.name} same-origin")
                assertEquals(endpoint.okStatus, send(endpoint, headers = mapOf("Sec-Fetch-Site" to "none")).status, "${endpoint.name} none")
                assertEquals(endpoint.okStatus, send(endpoint).status, "${endpoint.name} absent")
            }
        }
    }

    @Test
    fun `an Origin whose authority differs from Host is refused with 403 and changes nothing`() = TestDb().use { db ->
        testApplication {
            val budget = CountingBudget(budgetController())
            mount(Harness(db, budget))
            for (endpoint in Endpoint.entries) {
                for (origin in listOf("https://evil.example", "https://localhost.evil.example", "null", "http://localhost:8080")) {
                    val r = send(endpoint, headers = mapOf(HttpHeaders.Origin to origin))
                    assertEquals(HttpStatusCode.Forbidden, r.status, "${endpoint.name} $origin")
                    assertEquals("""{"error":"cross-site request refused"}""", r.bodyAsText())
                }
            }
            assertTrue(db.noTasksCreated())
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `an Origin matching Host passes, with any scheme, and an absent Origin passes`() = TestDb().use { db ->
        testApplication {
            mount(Harness(db, CountingBudget(budgetController())))
            for (endpoint in Endpoint.entries) {
                assertEquals(endpoint.okStatus, send(endpoint, headers = mapOf(HttpHeaders.Origin to "http://$KIVRA_HOST")).status, "${endpoint.name} http")
                assertEquals(endpoint.okStatus, send(endpoint, headers = mapOf(HttpHeaders.Origin to "https://$KIVRA_HOST")).status, "${endpoint.name} https")
                assertEquals(endpoint.okStatus, send(endpoint).status, "${endpoint.name} absent")
            }
        }
    }

    @Test
    fun `the guard runs after authentication, so an unauthenticated caller still gets 401`() = TestDb().use { db ->
        testApplication {
            mount(Harness(db, CountingBudget(budgetController())))
            for (endpoint in Endpoint.entries) {
                assertEquals(HttpStatusCode.Unauthorized, send(endpoint, principal = null, contentType = "text/plain").status, endpoint.name)
            }
        }
    }

    @Test
    fun `refusals neither echo nor log the refused header values`() = TestDb().use { db ->
        val marker = "SECRET-CSRF-MARKER-7f3a"
        val root = LoggerFactory.getLogger("net.kigawa") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        val oldLevel = root.level
        root.level = Level.TRACE
        try {
            testApplication {
                mount(Harness(db, CountingBudget(budgetController())))
                for (endpoint in Endpoint.entries) {
                    val byType = send(endpoint, contentType = "text/plain; marker=$marker")
                    assertEquals(HttpStatusCode.UnsupportedMediaType, byType.status)
                    assertFalse(byType.bodyAsText().contains(marker))
                    val bySite = send(endpoint, headers = mapOf("Sec-Fetch-Site" to "cross-site-$marker"))
                    assertEquals(HttpStatusCode.Forbidden, bySite.status)
                    assertFalse(bySite.bodyAsText().contains(marker))
                    val byOrigin = send(endpoint, headers = mapOf(HttpHeaders.Origin to "https://$marker.example"))
                    assertEquals(HttpStatusCode.Forbidden, byOrigin.status)
                    assertFalse(byOrigin.bodyAsText().contains(marker))
                }
            }
        } finally {
            root.level = oldLevel
            root.detachAppender(appender)
        }
        val leaked = appender.list.filter { e ->
            e.formattedMessage.contains(marker) || e.argumentArray.orEmpty().any { it?.toString()?.contains(marker) == true }
        }
        assertTrue(leaked.isEmpty(), "a refused header value reached the log")
    }
}
