package net.kigawa.kivra.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.appModule
import net.kigawa.kivra.auth.resolveTaskApiIdentity
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.PrepareOperation
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb

private const val ALICE = "alice@example.com"
private const val BOB = "bob@example.com"
private val TTL = Duration.ofSeconds(30)
private const val SECRET_HASH = "SECRET-REQUEST-HASH-9f2c"
private const val SECRET_STABLE = "SECRET-STABLE-REF-7d1e"
private const val SECRET_EXTERNAL = "SECRET-EXTERNAL-REF-3b8a"
private const val SECRET_ERROR = "SECRET-RAW-PROVIDER-BODY-5e0f"

private val identity = resolveTaskApiIdentity(
    mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "development"),
)!!

private fun ApplicationTestBuilder.setUpRoutes(db: TestDb) {
    application {
        install(ContentNegotiation) { json() }
        routing { configureOperationApiRoutes(db.runs, db.journal, identity) }
    }
}

private fun pathOf(runId: String) = "/api/runs/$runId/operations"

private suspend fun HttpClient.list(runId: String, email: String? = ALICE): HttpResponse = get(pathOf(runId)) {
    email?.let { header("X-Auth-Request-Email", it) }
}

/** Like [list], with the `after` cursor. A null [after] sends no query parameter at all. */
private suspend fun HttpClient.listPage(runId: String, after: String?, email: String? = ALICE): HttpResponse =
    get(pathOf(runId)) {
        email?.let { header("X-Auth-Request-Email", it) }
        after?.let { parameter("after", it) }
    }

private fun TestDb.submitRun(email: String, key: String): RunId =
    when (val r = runs.submitTask(email, key, "task $key")) {
        is TaskSubmitResult.Created -> r.run.runId
        else -> error("run not created")
    }

private fun TestDb.leaseFor(runId: RunId): Lease = leases.acquire(runId, "w", TTL)!!

/** Prepares one operation under [lease] with secret-looking fields that must never reach the response. */
private fun TestDb.prepareOp(lease: Lease, index: Int, status: OperationStatus = OperationStatus.PREPARED): String {
    val p = journal.prepare(
        lease,
        PrepareOperation(
            opType = "workspace",
            idempotencyKey = "op-$index",
            externalSystem = "github",
            stableRef = SECRET_STABLE,
            requestHash = SECRET_HASH,
            tenantId = "tenant-a",
            providerContractVersion = "v1",
        ),
    )
    var current = p
    if (status == OperationStatus.UNKNOWN) {
        val inFlight = journal.transition(lease, p.opId, p.version, OperationStatus.IN_FLIGHT)
        current = journal.transition(lease, inFlight.opId, inFlight.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT", error = SECRET_ERROR)
    }
    return current.opId
}

class OperationApiRoutesTest {
    @Test
    fun `owner gets the operations of its own run`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a1")
            val lease = db.leaseFor(run)
            val op1 = db.prepareOp(lease, 1)
            val op2 = db.prepareOp(lease, 2)

            val response = client.list(run.value)
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val json: JsonObject = Json.parseToJsonElement(body).jsonObject
            assertEquals("ui.v1", json.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals(run.value, json.getValue("runId").jsonPrimitive.content)
            assertEquals(listOf(op1, op2).sorted(), json.getValue("operations").jsonArray.map {
                it.jsonObject.getValue("operationId").jsonPrimitive.content
            })
            val first = json.getValue("operations").jsonArray.first().jsonObject
            assertEquals("PREPARED", first.getValue("state").jsonPrimitive.content)
            assertEquals("workspace", first.getValue("opType").jsonPrimitive.content)
            assertEquals("github", first.getValue("externalSystem").jsonPrimitive.content)
        }
    }

    @Test
    fun `another user's run and a missing run get the same 404 body`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val bobRun = db.submitRun(BOB, "b1")
            val bobLease = db.leaseFor(bobRun)
            db.prepareOp(bobLease, 1)
            val missing = "01ZZZZZZZZZZZZZZZZZZZZZZZZ"

            val other = client.list(bobRun.value, email = ALICE)
            val absent = client.list(missing, email = ALICE)
            assertEquals(HttpStatusCode.NotFound, other.status)
            assertEquals(HttpStatusCode.NotFound, absent.status)
            assertEquals(absent.bodyAsText(), other.bodyAsText())
            assertFalse(other.bodyAsText().contains("op-1"))
        }
    }

    @Test
    fun `request without an identity is 401`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a2")
            assertEquals(HttpStatusCode.Unauthorized, client.list(run.value, email = null).status)
        }
    }

    @Test
    fun `UNKNOWN stays UNKNOWN and is not reported as success`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a3")
            val lease = db.leaseFor(run)
            db.prepareOp(lease, 1, status = OperationStatus.UNKNOWN)

            val body = client.list(run.value).bodyAsText()
            val op = Json.parseToJsonElement(body).jsonObject.getValue("operations").jsonArray.single().jsonObject
            assertEquals("UNKNOWN", op.getValue("state").jsonPrimitive.content)
            assertFalse(body.contains("CONFIRMED"))
            assertFalse(body.contains("FAILED"))
        }
    }

    @Test
    fun `secrets, request hashes, external refs and raw errors are not in the response`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a4")
            val lease = db.leaseFor(run)
            val p = db.journal.prepare(
                lease,
                PrepareOperation(
                    opType = "workspace",
                    idempotencyKey = "secret-op",
                    externalSystem = "github",
                    stableRef = SECRET_STABLE,
                    requestHash = SECRET_HASH,
                    tenantId = "tenant-a",
                    providerContractVersion = "v1",
                ),
            )
            val inFlight = db.journal.transition(lease, p.opId, p.version, OperationStatus.IN_FLIGHT)
            db.journal.transition(
                lease, inFlight.opId, inFlight.version, OperationStatus.UNKNOWN,
                externalRef = SECRET_EXTERNAL, errorClass = "TIMEOUT", error = SECRET_ERROR,
            )

            val body = client.list(run.value).bodyAsText()
            assertTrue(body.contains("\"state\":\"UNKNOWN\""))
            for (secret in listOf(SECRET_HASH, SECRET_STABLE, SECRET_EXTERNAL, SECRET_ERROR, "idempotencyKey", "tenantId", "evidence")) {
                assertFalse(body.contains(secret, ignoreCase = true), "response leaked $secret")
            }
        }
    }

    @Test
    fun `write methods are not routed`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a5")
            val lease = db.leaseFor(run)
            db.prepareOp(lease, 1)
            val path = pathOf(run.value)
            val statuses = listOf(
                client.post(path) { header("X-Auth-Request-Email", ALICE); setBody("{}") }.status,
                client.put(path) { header("X-Auth-Request-Email", ALICE); setBody("{}") }.status,
                client.patch(path) { header("X-Auth-Request-Email", ALICE); setBody("{}") }.status,
                client.delete(path) { header("X-Auth-Request-Email", ALICE) }.status,
            )
            for (s in statuses) assertTrue(s == HttpStatusCode.NotFound || s == HttpStatusCode.MethodNotAllowed, "got $s")
            // The operation is untouched by the rejected writes.
            assertTrue(client.list(run.value).bodyAsText().contains("\"state\":\"PREPARED\""))
        }
    }

    @Test
    fun `list is capped at 100 in creation order and says it is truncated`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a6")
            val lease = db.leaseFor(run)
            repeat(101) { db.prepareOp(lease, it) }

            val body = client.list(run.value).bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val ids = json.getValue("operations").jsonArray.map {
                it.jsonObject.getValue("operationId").jsonPrimitive.content
            }
            assertEquals(100, ids.size)
            assertEquals(ids.sorted(), ids)
            assertEquals("100", json.getValue("limit").toString())
            assertEquals("true", json.getValue("truncated").toString())
        }
    }

    @Test
    fun `a run with few operations is not truncated`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "a7")
            val lease = db.leaseFor(run)
            db.prepareOp(lease, 1)
            val json = Json.parseToJsonElement(client.list(run.value).bodyAsText()).jsonObject
            assertEquals("false", json.getValue("truncated").toString())
            assertEquals(1, json.getValue("operations").jsonArray.size)
        }
    }

    @Test
    fun `exactly 100 operations is not truncated and has no nextAfter, 101 is truncated with nextAfter`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "c1")
            val lease = db.leaseFor(run)
            repeat(100) { db.prepareOp(lease, it) }
            val exact = client.list(run.value).bodyAsText()
            assertEquals("false", Json.parseToJsonElement(exact).jsonObject.getValue("truncated").toString())
            assertFalse(exact.contains("nextAfter"))

            db.prepareOp(lease, 100)
            val over = Json.parseToJsonElement(client.list(run.value).bodyAsText()).jsonObject
            assertEquals("true", over.getValue("truncated").toString())
            val ids = over.getValue("operations").jsonArray.map { it.jsonObject.getValue("operationId").jsonPrimitive.content }
            assertEquals(ids.last(), over.getValue("nextAfter").jsonPrimitive.content)
        }
    }

    @Test
    fun `cursor pages cover 150 operations once each, in order, and the last page has no nextAfter`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "c2")
            val lease = db.leaseFor(run)
            val created = (1..150).map { db.prepareOp(lease, it) }

            val seen = mutableListOf<String>()
            var after: String? = null
            var pages = 0
            do {
                val json = Json.parseToJsonElement(client.listPage(run.value, after).bodyAsText()).jsonObject
                seen += json.getValue("operations").jsonArray.map { it.jsonObject.getValue("operationId").jsonPrimitive.content }
                after = json["nextAfter"]?.jsonPrimitive?.content
                pages++
            } while (after != null)
            assertEquals(2, pages)
            assertEquals(created.sorted(), seen)
        }
    }

    @Test
    fun `a page never contains another run's operations`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val mine = db.submitRun(ALICE, "c3")
            val mineLease = db.leaseFor(mine)
            val myOps = (1..3).map { db.prepareOp(mineLease, it) }
            val theirs = db.submitRun(ALICE, "c4")
            val theirLease = db.leaseFor(theirs)
            (10..14).forEach { db.prepareOp(theirLease, it) }

            val ids = Json.parseToJsonElement(client.list(mine.value).bodyAsText()).jsonObject
                .getValue("operations").jsonArray.map { it.jsonObject.getValue("operationId").jsonPrimitive.content }
            assertEquals(myOps.sorted(), ids)
        }
    }

    @Test
    fun `a page of 500 operations returns 100 and the cursor is the 100th`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "c5")
            val lease = db.leaseFor(run)
            val created = (1..500).map { db.prepareOp(lease, it) }

            val json = Json.parseToJsonElement(client.list(run.value).bodyAsText()).jsonObject
            val ids = json.getValue("operations").jsonArray.map { it.jsonObject.getValue("operationId").jsonPrimitive.content }
            assertEquals(100, ids.size)
            assertEquals(created.sorted().take(100), ids)
            assertEquals(ids.last(), json.getValue("nextAfter").jsonPrimitive.content)
        }
    }

    @Test
    fun `an invalid after is 400 with a fixed message that does not echo the input`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val run = db.submitRun(ALICE, "c6")
            val lease = db.leaseFor(run)
            db.prepareOp(lease, 1)
            val bad = listOf(
                "SECRET-CURSOR-INPUT",
                "",
                "A".repeat(27),
                "01ARZ3NDEKTSV4RRFFQ69G5FA!",
                "01ARZ3NDEKTSV4RRFFQ69G5F\n",
            )
            for (value in bad) {
                val response = client.listPage(run.value, after = value)
                assertEquals(HttpStatusCode.BadRequest, response.status, "after=$value")
                val body = response.bodyAsText()
                assertEquals("{\"error\":\"invalid after\"}", body)
                assertFalse(body.contains("SECRET-CURSOR-INPUT"))
            }
        }
    }

    @Test
    fun `another user's run and a missing run get the same 404 with after, valid or not`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val bobRun = db.submitRun(BOB, "c7")
            db.prepareOp(db.leaseFor(bobRun), 1)
            val missing = "01ZZZZZZZZZZZZZZZZZZZZZZZZ"
            val cursor = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

            val otherValid = client.listPage(bobRun.value, after = cursor, email = ALICE)
            val absentValid = client.listPage(missing, after = cursor, email = ALICE)
            val otherBad = client.listPage(bobRun.value, after = "NOT-A-CURSOR", email = ALICE)
            val absentBad = client.listPage(missing, after = "NOT-A-CURSOR", email = ALICE)
            assertEquals(HttpStatusCode.NotFound, otherValid.status)
            assertEquals(HttpStatusCode.NotFound, absentValid.status)
            assertEquals(HttpStatusCode.NotFound, otherBad.status)
            assertEquals(HttpStatusCode.NotFound, absentBad.status)
            assertEquals(absentValid.bodyAsText(), otherValid.bodyAsText())
            assertEquals(absentBad.bodyAsText(), otherBad.bodyAsText())
            assertEquals(otherValid.bodyAsText(), otherBad.bodyAsText())
        }
    }

    @Test
    fun `module without KIVRA_AUTH_MODE exposes no operation route`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_op_absent_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "kivra.database.user" to "sa",
                "kivra.database.password" to "",
                "kivra.lease.ttlSeconds" to "30",
                "kivra.lease.graceSeconds" to "5",
            )
        }
        application { appModule(emptyMap()) }
        assertEquals(HttpStatusCode.NotFound, client.get(pathOf("01AAAAAAAAAAAAAAAAAAAAAAAA")) { header("X-Auth-Request-Email", ALICE) }.status)
    }
}
