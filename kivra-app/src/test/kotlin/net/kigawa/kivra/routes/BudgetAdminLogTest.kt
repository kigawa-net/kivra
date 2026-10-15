package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.BUDGET_TENANT
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.MutableClock
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.jpy
import net.kigawa.kivra.db.newDbStore
import net.kigawa.kivra.modelgateway.ReserveResult
import org.slf4j.LoggerFactory

/**
 * The budget release must not leak its free text to any log. Every outcome is driven through the HTTP route over the durable
 * store, with secret markers in `evidence` and `reason`. All log events (every logger, TRACE level) and all response bodies are
 * checked: the markers never appear, and the only operator-facing value logged is the operator id with the key prefix.
 */
class BudgetAdminLogTest {
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var previousLevel: Level

    @BeforeTest
    fun attach() {
        previousLevel = root.level
        root.level = Level.TRACE
        appender.start()
        root.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        root.detachAppender(appender)
        appender.stop()
        root.level = previousLevel
    }

    private fun loggedText(): String = appender.list.joinToString("\n") { e ->
        buildString {
            append(e.loggerName).append(' ').append(e.level).append(' ').append(e.formattedMessage)
            e.argumentArray?.forEach { append(' ').append(it) }
            e.throwableProxy?.let { append(' ').append(it.className).append(' ').append(it.message.orEmpty()) }
        }
    }

    private val operator = "op-visible-1"
    private val key = "reservation-key-0001"
    private val evidenceMarker = "SECRET-EVIDENCE-xyz"
    private val reasonMarker = "SECRET-REASON-xyz"

    private fun body(actual: String?, period: String = "2026-10", k: String = key, evidence: String = evidenceMarker, reason: String = reasonMarker): String {
        val actualJson = actual?.let { "\"$it\"" } ?: "null"
        return """{"scopeId":"tenant","period":"$period","reservationKey":"$k","actualAmountJpy":$actualJson,"evidence":"$evidence","reason":"$reason"}"""
    }

    private fun ApplicationTestBuilder.setUp(store: DbBudgetStore) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureBudgetAdminRoutes(store, identity) }
        }
    }

    private val identity = TaskApiIdentity { headers -> headers["X-Test-Operator"]?.takeIf { it.isNotBlank() } }

    private suspend fun HttpClient.acknowledge(payload: String, operatorId: String? = operator): HttpResponse = post("/api/admin/budget/acknowledge") {
        contentType(ContentType.Application.Json)
        operatorId?.let { header("X-Test-Operator", it) }
        setBody(payload)
    }

    @Test
    fun `no log line and no error response carries the evidence, the reason, or the full key (route over the durable store)`() {
        TestDb().use { db ->
            // 2026-10-14 12:00 JST, so the request period is 2026-10.
            val clock = MutableClock(Instant.parse("2026-10-14T03:00:00Z"))
            val store = newDbStore(db, clock)
            assertEquals(ReserveResult.Reserved, store.reserve("reservation-key-r", listOf(BUDGET_TENANT), jpy("500")))
            assertEquals(ReserveResult.Reserved, store.reserve(key, listOf(BUDGET_TENANT), jpy("1000")))
            assertEquals(ReserveResult.Reserved, store.reserve("reservation-key-f", listOf(BUDGET_TENANT), jpy("100")))
            // All reservations are made before any is settled: an unpriced scope admits nothing new.
            store.commit(key, null)
            store.commit("reservation-key-f", null)

            val responses = mutableListOf<Pair<String, HttpStatusCode>>()
            val errorBodies = mutableListOf<String>()
            testApplication {
                setUp(store)
                suspend fun record(label: String, response: HttpResponse) {
                    responses += label to response.status
                    val text = response.bodyAsText()
                    if (response.status != HttpStatusCode.OK) errorBodies += text
                    assertFalse(text.contains("SECRET"), "$label response leaked a secret: $text")
                    assertFalse(text.contains(operator), "$label response echoed the operator: $text")
                }
                // Acknowledged (known actual).
                record("acknowledged", client.acknowledge(body("600")))
                // AlreadyAcknowledged.
                record("already", client.acknowledge(body("900")))
                // NotFound: unknown key.
                record("not-found", client.acknowledge(body("600", k = "reservation-key-missing")))
                // NotUnpriced: still reserved.
                record("not-unpriced", client.acknowledge(body("600", k = "reservation-key-r")))
                // Rejected in the store: an actual far above the charged cost.
                record("rejected-large", client.acknowledge(body("1000000", k = "reservation-key-f")))
                // Rejected by validation: a negative actual.
                record("rejected-negative", client.acknowledge(body("-5", k = "reservation-key-f")))
                // Invalid input: a non-numeric actual.
                record("invalid-number", client.acknowledge(body("NOT-A-NUMBER-SECRET", k = "reservation-key-f")))
                // Invalid input: a body that is not JSON.
                record("invalid-json", client.acknowledge("not json $evidenceMarker"))
                // Failure: the store cannot record the release (injected). The reservation stays unpriced.
                store.afterAcknowledgeWrite = { error("injected failure $reasonMarker") }
                record("failure", client.acknowledge(body("600", k = "reservation-key-f")))
                store.afterAcknowledgeWrite = null
                // Unauthenticated: no operator, so no store call.
                record("unauthenticated", client.acknowledge(body("600"), operatorId = null))
            }

            assertEquals(
                listOf(
                    "acknowledged" to HttpStatusCode.OK,
                    "already" to HttpStatusCode.OK,
                    "not-found" to HttpStatusCode.NotFound,
                    "not-unpriced" to HttpStatusCode.Conflict,
                    "rejected-large" to HttpStatusCode.BadRequest,
                    "rejected-negative" to HttpStatusCode.BadRequest,
                    "invalid-number" to HttpStatusCode.BadRequest,
                    "invalid-json" to HttpStatusCode.BadRequest,
                    "failure" to HttpStatusCode.ServiceUnavailable,
                    "unauthenticated" to HttpStatusCode.Unauthorized,
                ),
                responses,
            )
            assertTrue(errorBodies.all { !it.contains("reservation-key-0001") }, "an error response echoed the key")

            val logs = loggedText()
            assertFalse(logs.contains("SECRET"), "a log line carried a secret marker:\n$logs")
            assertFalse(logs.contains("injected failure"), "the injected exception reached the log")
            // What is logged: the operator, the first 8 characters of the key, and the result kind. Never the full key.
            assertTrue(logs.contains("operator=$operator"), "the operator id is missing from the audit log line:\n$logs")
            assertTrue(logs.contains("reservat"), "the key prefix is missing from the log")
            assertFalse(logs.contains("reservation-key-0001"), "the full key was logged:\n$logs")
            assertTrue(logs.contains("Acknowledged"), "the result kind is missing from the log")
        }
    }
}
