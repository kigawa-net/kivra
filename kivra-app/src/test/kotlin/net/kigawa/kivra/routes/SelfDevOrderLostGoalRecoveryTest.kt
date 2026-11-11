package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.transactions.transaction

private const val HEADER = "X-Test-Principal"
private const val OPERATOR = "1:i:oper"
private const val AI = "1:i:ai-agent"
private const val TARGET = "owner/repo"
private const val LOST_GOAL_ID = "01M60G8C7GXDKN8KNM51CQ2F1H"

/**
 * An order whose `current_goal_id` points to no Task row (the goal V40 dropped; ADR 0015 addendum 2026-11-11, stage 0): the automatic
 * evaluation closes the cycle as a failure, an operator may abandon a cycle, and the detail shows the state of the cycle in progress.
 * The database is the real schema on H2.
 */
class SelfDevOrderLostGoalRecoveryTest {
    private val openDbs = mutableListOf<TestDb>()

    @AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth =
            headers[HEADER]?.takeIf { it.isNotBlank() }?.let { OperatorAuth.Authenticated(it) } ?: OperatorAuth.Unauthenticated
    }

    private fun enabledAutopilot(): AutopilotSettings = AutopilotSettings.fromEnv(
        mapOf(
            AutopilotSettings.ENV_ENABLED to "true",
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        ),
        SelfDevSettings.fromEnv(
            mapOf(
                "KIVRA_SELFDEV_ENABLED" to "true",
                "KIVRA_AUTH_MODE" to "oidc-jwt",
                "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
                "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
                "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
                "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
                "KIVRA_FX_USD_JPY" to "150",
                "KIVRA_FX_AS_OF" to "2026-10-18",
                "KIVRA_SELFDEV_RUNTIME" to "cli",
                "KIVRA_AGENT_API_ENABLED" to "true",
            ),
        ),
    )

    private fun ApplicationTestBuilder.setUp(db: TestDb): SelfDevOrderRepository {
        val autopilot = enabledAutopilot()
        val orders = SelfDevOrderRepository(db.database)
        val deps = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = OrderSettings.fromEnv(
                mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to "1:i:root", "KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP" to "5"),
                autopilot,
                aiPrincipal = AI,
            ),
            aiPrincipal = AI,
            targetRepo = TARGET,
            orders = orders,
            operations = SelfDevOperatorRepository(db.database),
            projects = ProjectRepository(db.database, Clock.systemUTC()),
        )
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevOrderRoutes(deps) }
        }
        return orders
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String?, principal: String? = OPERATOR): HttpResponse =
        client.request(path) {
            this.method = method
            if (principal != null) header(HEADER, principal)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun newOrder(db: TestDb, orders: SelfDevOrderRepository, maxCycles: Int = 5): SelfDevOrder {
        val project = ProjectRepository(db.database, Clock.systemUTC()).create("Lost goal", null).projectId
        return (orders.createRoot(OPERATOR, "Order title", "Improve the docs", TARGET, maxCycles, project) as CreateRootResult.Created).order
    }

    /** The order of the incident: one cycle counted, `current_goal_id` pointing to an ID that no Task row has, and no cycle rows. */
    private fun lostGoalOrder(db: TestDb, orders: SelfDevOrderRepository, failures: Int = 0, maxCycles: Int = 5): SelfDevOrder {
        val order = newOrder(db, orders, maxCycles)
        transaction(db.database) {
            execUpdate(
                "UPDATE selfdev_orders SET current_goal_id = ?, cycles_started = 1, consecutive_failed_cycles = ? WHERE order_id = ?",
                LOST_GOAL_ID, failures, order.orderId,
            )
        }
        return orders.get(order.orderId)!!
    }

    private fun events(db: TestDb, orderId: String, kind: String): List<Map<String, String?>> = transaction(db.database) {
        execQuery(
            "SELECT reason_code, actor_kind, actor_ref, payload_json, cycle_no FROM selfdev_order_events WHERE order_id = ? AND kind = ? ORDER BY occurred_at",
            orderId, kind,
        ) { mapOf("reason" to it.getString(1), "actor" to it.getString(2), "ref" to it.getString(3), "payload" to it.getString(4), "cycle" to it.getString(5)) }
    }

    private fun abandonPath(orderId: String) = "/api/selfdev/orders/$orderId/cycles/abandon"

    private fun abandonBody(version: Long, reason: String = "old goal is gone") = """{"expectedVersion":$version,"reason":"$reason"}"""

    // ---- (a) (b) (c) the automatic repair ------------------------------------------------------------------------------------------

    @Test
    fun `a current goal with no Task row is closed as a failed cycle and the order becomes a candidate of the next cycle`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val order = lostGoalOrder(db, orders)
        assertTrue(order.orderId !in orders.autoCycleCandidatesAfter(null, 50))

        assertEquals(1, orders.evaluateEndedCycles(10))

        val after = orders.get(order.orderId)!!
        assertNull(after.currentGoalId)
        assertEquals(1, after.consecutiveFailedCycles)
        assertEquals(1, after.cyclesStarted)
        assertEquals(OrderState.ACTIVE, after.state)
        assertEquals(order.version + 1, after.version)
        val evaluated = events(db, order.orderId, "CYCLE_EVALUATED").single()
        assertEquals("LOST_GOAL", evaluated["reason"])
        assertEquals("SYSTEM", evaluated["actor"])
        assertEquals("1", evaluated["cycle"])
        assertTrue(order.orderId in orders.autoCycleCandidatesAfter(null, 50))
        // A second evaluation has nothing to do.
        assertEquals(0, orders.evaluateEndedCycles(10))
        assertEquals(1, orders.get(order.orderId)!!.consecutiveFailedCycles)
    }

    @Test
    fun `a lost goal at the failure limit pauses the order with CYCLE_FAILURES`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val order = lostGoalOrder(db, orders, failures = 1)

        assertEquals(1, orders.evaluateEndedCycles(10))

        val after = orders.get(order.orderId)!!
        assertEquals(2, after.consecutiveFailedCycles)
        assertNull(after.currentGoalId)
        assertEquals(OrderState.PAUSED, after.state)
        assertEquals(OrderReason.CYCLE_FAILURES, after.stateReason)
        assertEquals(1, events(db, order.orderId, "PAUSED").size)
    }

    @Test
    fun `a lost goal on an order at its cycle limit pauses it with LIMIT_REACHED`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val order = lostGoalOrder(db, orders, maxCycles = 1)

        assertEquals(1, orders.evaluateEndedCycles(10))

        assertEquals(OrderReason.LIMIT_REACHED, orders.get(order.orderId)!!.stateReason)
    }

    @Test
    fun `a STOP_REQUESTED order with a lost goal is left alone`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val order = lostGoalOrder(db, orders)
        transaction(db.database) { execUpdate("UPDATE selfdev_orders SET state = 'STOP_REQUESTED', state_reason = NULL WHERE order_id = ?", order.orderId) }

        assertEquals(0, orders.evaluateEndedCycles(10))

        assertEquals(LOST_GOAL_ID, orders.get(order.orderId)!!.currentGoalId)
        assertEquals(0, events(db, order.orderId, "CYCLE_EVALUATED").size)
    }

    @Test
    fun `a cycle whose Task is still running is not closed by the evaluation`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val order = newOrder(db, orders)
        val started = (orders.startCycle(order.orderId, order.version, OPERATOR) as OrderResult.Moved).value

        assertEquals(0, orders.evaluateEndedCycles(10))

        val after = orders.get(order.orderId)!!
        assertEquals(started.goalId, after.currentGoalId)
        assertEquals(0, after.consecutiveFailedCycles)
        assertEquals(0, events(db, order.orderId, "CYCLE_EVALUATED").size)
        // Its state is read from the Task, and the Task is not missing.
        val current = assertNotNull(orders.currentCycleOf(after))
        assertEquals("TASK_SUBMITTED", current.taskState)
        assertEquals(false, current.taskMissing)
    }

    // ---- (e) the visible state ---------------------------------------------------------------------------------------------------

    @Test
    fun `the detail shows a lost goal as taskMissing, a running cycle by its state, and nothing without a cycle`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val idle = newOrder(db, orders)
        val lost = lostGoalOrder(db, orders)
        val running = newOrder(db, orders)
        (orders.startCycle(running.orderId, running.version, OPERATOR) as OrderResult.Moved)

        val idleBody = send(HttpMethod.Get, "/api/selfdev/orders/${idle.orderId}", null).obj()
        assertEquals("null", idleBody["currentCycle"].toString())

        val lostCycle = send(HttpMethod.Get, "/api/selfdev/orders/${lost.orderId}", null).obj()["currentCycle"]!!.jsonObject
        assertEquals("true", lostCycle["taskMissing"]!!.jsonPrimitive.content)
        assertEquals(LOST_GOAL_ID, lostCycle["taskId"]!!.jsonPrimitive.content)
        assertEquals("null", lostCycle["taskState"].toString())

        val runningCycle = send(HttpMethod.Get, "/api/selfdev/orders/${running.orderId}", null).obj()["currentCycle"]!!.jsonObject
        assertEquals("false", runningCycle["taskMissing"]!!.jsonPrimitive.content)
        assertEquals("TASK_SUBMITTED", runningCycle["taskState"]!!.jsonPrimitive.content)

        // The cycles list keeps its shape: an array, and empty for the lost goal (it has no Task row).
        val cycles = send(HttpMethod.Get, "/api/selfdev/orders/${lost.orderId}/cycles", null)
        assertEquals(HttpStatusCode.OK, cycles.status)
        assertEquals("[]", cycles.bodyAsText())
    }

    // ---- (d) the abandonment ---------------------------------------------------------------------------------------------------

    @Test
    fun `an operator abandons a lost goal - the failure is counted, the event keeps the reason in its payload, and the state stays`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = lostGoalOrder(db, orders)

        val ok = send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(order.version, "the goal was dropped by V40"))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(OrderState.ACTIVE.name, ok.obj()["state"]!!.jsonPrimitive.content)

        val after = orders.get(order.orderId)!!
        assertNull(after.currentGoalId)
        assertEquals(1, after.consecutiveFailedCycles)
        assertEquals(OrderState.ACTIVE, after.state)
        val event = events(db, order.orderId, "CYCLE_ABANDONED").single()
        assertEquals("OPERATOR_ABANDONED", event["reason"])
        assertEquals("HUMAN", event["actor"])
        assertNotNull(event["ref"])
        assertTrue(event["payload"]!!.contains("the goal was dropped by V40"))
        assertTrue(event["payload"]!!.contains("\"taskMissing\":true"))
        assertTrue(order.orderId in orders.autoCycleCandidatesAfter(null, 50))

        // Abandoning twice: the second finds no cycle (the order's version moved, so the old version is stale first).
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(order.version)).status)
        val again = send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(after.version))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("NO_CYCLE_IN_PROGRESS", again.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(1, orders.get(order.orderId)!!.consecutiveFailedCycles)
    }

    @Test
    fun `abandoning a running cycle stops its Task first, then closes the cycle as a failure`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = newOrder(db, orders)
        val started = (orders.startCycle(order.orderId, order.version, OPERATOR) as OrderResult.Moved).value
        val version = orders.get(order.orderId)!!.version

        val ok = send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(version))
        assertEquals(HttpStatusCode.OK, ok.status)

        val task = SelfDevOperatorRepository(db.database).task(started.goalId)!!
        assertEquals("STOPPED", task.state.name)
        assertEquals("OPERATOR_STOPPED", task.stateReason?.name)
        val after = orders.get(order.orderId)!!
        assertNull(after.currentGoalId)
        assertEquals(1, after.consecutiveFailedCycles)
        assertEquals(OrderState.ACTIVE, after.state)
        assertTrue(events(db, order.orderId, "CYCLE_ABANDONED").single()["payload"]!!.contains("\"taskMissing\":false"))
        // The stopped Task is not evaluated again.
        assertEquals(0, orders.evaluateEndedCycles(10))
        assertEquals(1, orders.get(order.orderId)!!.consecutiveFailedCycles)
    }

    @Test
    fun `a cycle whose Task ended with a success is not abandoned`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = newOrder(db, orders)
        val started = (orders.startCycle(order.orderId, order.version, OPERATOR) as OrderResult.Moved).value
        transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET state = 'DONE' WHERE task_id = ?", started.goalId) }

        val refused = send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(orders.get(order.orderId)!!.version))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(started.goalId, orders.get(order.orderId)!!.currentGoalId)
        assertEquals(0, events(db, order.orderId, "CYCLE_ABANDONED").size)
    }

    @Test
    fun `an order with no cycle in progress cannot abandon one`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = newOrder(db, orders)

        val refused = send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(order.version))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("NO_CYCLE_IN_PROGRESS", refused.obj()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `abandon is refused for STOP_REQUESTED and STOPPED orders and for a stale version, and nothing is written`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = lostGoalOrder(db, orders)

        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(order.version + 5)).status)

        for ((state, code) in listOf("STOP_REQUESTED" to "STOP_ALREADY_PENDING", "STOPPED" to "TERMINAL")) {
            transaction(db.database) { execUpdate("UPDATE selfdev_orders SET state = ?, state_reason = NULL WHERE order_id = ?", state, order.orderId) }
            val current = orders.get(order.orderId)!!
            val refused = send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(current.version))
            assertEquals(HttpStatusCode.Conflict, refused.status, state)
            assertEquals(code, refused.obj()["code"]!!.jsonPrimitive.content, state)
        }
        val unchanged = orders.get(order.orderId)!!
        assertEquals(LOST_GOAL_ID, unchanged.currentGoalId)
        assertEquals(0, unchanged.consecutiveFailedCycles)
        assertEquals(0, events(db, order.orderId, "CYCLE_ABANDONED").size)
    }

    @Test
    fun `abandon is the operator's - 401 without a caller, 403 for the AI before its body is read, 404 and 400 as fixed answers`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = lostGoalOrder(db, orders)
        val path = abandonPath(order.orderId)

        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Post, path, abandonBody(order.version), principal = null).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, path, "not json", principal = AI).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, path, abandonBody(order.version), principal = AI).status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, abandonPath("01AAAAAAAAAAAAAAAAAAAAAAAA"), abandonBody(0)).status)

        // Strict body: an extra field, no reason, an empty or too long reason, a negative version, or the old key name.
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, path, """{"expectedVersion":${order.version},"reason":"x","extra":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, path, """{"expectedVersion":${order.version}}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, path, """{"version":${order.version},"reason":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, path, abandonBody(order.version, "   ")).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, path, abandonBody(order.version, "x".repeat(501))).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, path, abandonBody(-1)).status)
        assertEquals(LOST_GOAL_ID, orders.get(order.orderId)!!.currentGoalId)
        assertEquals(0, events(db, order.orderId, "CYCLE_ABANDONED").size)
    }

    @Test
    fun `a 500 character reason is kept in the payload cut to fit the column`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val order = lostGoalOrder(db, orders)

        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, abandonPath(order.orderId), abandonBody(order.version, "あ".repeat(500))).status)

        val payload = events(db, order.orderId, "CYCLE_ABANDONED").single()["payload"]!!
        assertTrue(payload.length <= 255)
        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals("500", json["reasonChars"]!!.jsonPrimitive.content)
        assertTrue(json["reason"]!!.jsonPrimitive.content.startsWith("あああ"))
    }
}
