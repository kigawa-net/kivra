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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectChatActionRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.core.chat.ChatActionState
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.Ulid
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.transactions.transaction

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val OPERATOR = "1:i:oper"
private const val TARGET = "owner/repo"
private const val ROOT_APPROVER = "1:i:root"
private const val AI_PRINCIPAL = "1:i:ai-agent"
private const val CAP = 3

/**
 * The project chat's action proposals (ADR 0023) on H2 with the real repositories: the rule-based proposer (once per open key, expiry on a
 * version move, no re-proposal of a resolved version, the stuck cycle), and the confirm and dismiss routes (the same operation as the REST
 * route, the AI principal refused, a stale version expires, another project's order is not found, a second answer is 409).
 */
class ProjectChatActionRoutesTest {
    private val openDbs = mutableListOf<TestDb>()

    @AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth =
            headers[PRINCIPAL_HEADER]?.takeIf { it.isNotBlank() }?.let { OperatorAuth.Authenticated(it) } ?: OperatorAuth.Unauthenticated
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

    /** The order routes with the chat actions wired in, as Application does. Returns the order repository. */
    private fun ApplicationTestBuilder.setUp(db: TestDb, aiPrincipal: String? = null): SelfDevOrderRepository {
        val autopilot = enabledAutopilot()
        val orders = SelfDevOrderRepository(db.database)
        val deps = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = OrderSettings.fromEnv(
                mapOf(
                    OrderSettings.ENV_ENABLED to "true",
                    OrderSettings.ENV_ROOT_APPROVER to ROOT_APPROVER,
                    "KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP" to "$CAP",
                ),
                autopilot,
                aiPrincipal = aiPrincipal,
            ),
            aiPrincipal = aiPrincipal,
            targetRepo = TARGET,
            orders = orders,
            operations = SelfDevOperatorRepository(db.database),
            projects = ProjectRepository(db.database, Clock.systemUTC()),
            chatActions = ProjectChatActionRepository(db.database),
        )
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevOrderRoutes(deps) }
        }
        return orders
    }

    private fun newProject(db: TestDb, name: String): String = ProjectRepository(db.database, Clock.systemUTC()).create(name, null).projectId

    private fun newRoot(orders: SelfDevOrderRepository, project: String): SelfDevOrder =
        (orders.createRoot(OPERATOR, "Order title", "Improve the docs", TARGET, 2, project) as CreateRootResult.Created).order

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String?, principal: String = OPERATOR): HttpResponse =
        client.request(path) {
            this.method = method
            header(PRINCIPAL_HEADER, principal)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun confirmPath(project: String, actionId: String) = "/api/projects/$project/chat/actions/$actionId/confirm"

    private fun dismissPath(project: String, actionId: String) = "/api/projects/$project/chat/actions/$actionId/dismiss"

    @Test
    fun `a start is proposed once for an open key, and not again while it is open`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "Propose once")
        val order = newRoot(orders, project)

        assertEquals(1, repo.refresh(project))
        assertEquals(0, repo.refresh(project))
        val open = repo.proposed(project)
        assertEquals(1, open.size)
        assertEquals("ORDER_START_CYCLE", open[0].code)
        assertEquals(order.orderId, open[0].targetId)
        assertEquals(order.version, open[0].expectedVersion)
    }

    @Test
    fun `a proposal expires when the order version moves, and a new one is proposed at the new version`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "Expire")
        val order = newRoot(orders, project)
        repo.refresh(project)
        val first = repo.proposed(project).single()

        assertIs<OrderResult.Moved<*>>(orders.pauseByOperator(order.orderId, order.version, OPERATOR))
        assertEquals(0, repo.refresh(project))
        assertTrue(repo.proposed(project).isEmpty())
        val expired = assertNotNull(repo.find(project, first.actionId))
        assertEquals("EXPIRED", expired.state)
        assertEquals("VERSION_MOVED", expired.resultCode)

        assertIs<OrderResult.Moved<*>>(orders.resumeByOperator(order.orderId, order.version + 1, OPERATOR))
        assertEquals(1, repo.refresh(project))
        assertEquals(order.version + 2, repo.proposed(project).single().expectedVersion)
    }

    @Test
    fun `a dismissed or failed version is not proposed again, and a new version is`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "No repeat")
        newRoot(orders, project)
        repo.refresh(project)
        val row = repo.proposed(project).single()
        assertTrue(repo.resolve(row.actionId, net.kigawa.kivra.core.chat.ChatActionState.DISMISSED, "OPERATOR_DISMISSED", OPERATOR))
        assertEquals(0, repo.refresh(project))
        assertTrue(repo.proposed(project).isEmpty())
        // A second resolution of the same row is refused: nothing runs twice.
        assertTrue(!repo.resolve(row.actionId, net.kigawa.kivra.core.chat.ChatActionState.EXECUTED, "OK", OPERATOR))
    }

    @Test
    fun `confirm runs the start as the REST route does once, and a second answer is 409`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "Confirm")
        val order = newRoot(orders, project)
        repo.refresh(project)
        val action = repo.proposed(project).single()

        val confirmed = send(HttpMethod.Post, confirmPath(project, action.actionId), "{}")
        assertEquals(HttpStatusCode.OK, confirmed.status)
        val body = confirmed.obj()
        assertEquals("EXECUTED", body["state"]!!.jsonPrimitive.content)
        assertEquals("OK", body["resultCode"]!!.jsonPrimitive.content)
        val started = assertNotNull(orders.get(order.orderId))
        assertEquals(1, started.cyclesStarted)
        assertNotNull(started.currentGoalId)
        assertEquals("EXECUTED", repo.find(project, action.actionId)!!.state)

        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, confirmPath(project, action.actionId), "{}").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, dismissPath(project, action.actionId), "{}").status)
        assertEquals(1, orders.get(order.orderId)!!.cyclesStarted)
    }

    @Test
    fun `the AI principal is refused before anything runs, and the proposal stays open`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db, aiPrincipal = AI_PRINCIPAL)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "AI refused")
        val order = newRoot(orders, project)
        repo.refresh(project)
        val action = repo.proposed(project).single()

        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, confirmPath(project, action.actionId), "{}", principal = AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, dismissPath(project, action.actionId), "{}", principal = AI_PRINCIPAL).status)
        assertEquals("PROPOSED", repo.find(project, action.actionId)!!.state)
        assertEquals(0, orders.get(order.orderId)!!.cyclesStarted)
    }

    @Test
    fun `a stale version expires the proposal with a fixed code and runs nothing`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "Stale")
        val order = newRoot(orders, project)
        repo.refresh(project)
        val action = repo.proposed(project).single()
        // The version moves after the proposal, and the chat was not read again.
        assertIs<OrderResult.Moved<*>>(orders.pauseByOperator(order.orderId, order.version, OPERATOR))

        val answer = send(HttpMethod.Post, confirmPath(project, action.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("EXPIRED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("STALE_VERSION", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertEquals(0, orders.get(order.orderId)!!.cyclesStarted)
    }

    @Test
    fun `an order of another project is not found, whatever the proposal says`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val projectA = newProject(db, "A")
        val projectB = newProject(db, "B")
        val orderB = newRoot(orders, projectB)
        val actionId = Ulid.generate()
        transaction(db.database) {
            execUpdate(
                "INSERT INTO project_chat_actions (action_id, project_id, action_code, target_id, expected_version, state, proposed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                actionId, projectA, "ORDER_START_CYCLE", orderB.orderId, orderB.version, "PROPOSED", utcNow(Clock.systemUTC()),
            )
        }

        val answer = send(HttpMethod.Post, confirmPath(projectA, actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("FAILED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("ORDER_NOT_FOUND", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertEquals(0, orders.get(orderB.orderId)!!.cyclesStarted)
        assertEquals("FAILED", repo.find(projectA, actionId)!!.state)
        // A project the caller's path does not name is a 404 (the action is not in it).
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, confirmPath(projectB, actionId), "{}").status)
    }

    @Test
    fun `dismiss records the refusal and runs nothing`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "Dismiss")
        val order = newRoot(orders, project)
        repo.refresh(project)
        val action = repo.proposed(project).single()

        val answer = send(HttpMethod.Post, dismissPath(project, action.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("DISMISSED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("OPERATOR_DISMISSED", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertNull(orders.get(order.orderId)!!.currentGoalId)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, confirmPath(project, action.actionId), "{}").status)
    }

    @Test
    fun `a stuck cycle gets an abandon proposal, and it expires when a child can run again`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val project = newProject(db, "Stuck")
        val order = newRoot(orders, project)
        repo.refresh(project)
        assertIs<OrderResult.Moved<*>>(orders.startCycle(order.orderId, order.version, OPERATOR))
        val root = assertNotNull(orders.get(order.orderId)!!.currentGoalId)
        val failed = Ulid.generate()
        val child = Ulid.generate()
        insertTask(db, failed, project, "FAILED", parent = null, deps = "[]", key = "f")
        insertTask(db, child, project, "QUEUED", parent = root, deps = "[\"$failed\"]", key = "c")

        assertEquals(1, repo.refresh(project))
        val open = repo.proposed(project)
        assertEquals(listOf("ORDER_ABANDON_CYCLE"), open.map { it.code })
        assertEquals(order.orderId, open.single().targetId)

        // The child can run again (it is no longer blocked by a failed Task in the rule): the proposal expires.
        transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET state = ? WHERE task_id = ?", "SUBMITTED", child) }
        assertEquals(0, repo.refresh(project))
        assertTrue(repo.proposed(project).isEmpty())
    }

    private fun insertTask(db: TestDb, id: String, project: String, state: String, parent: String?, deps: String, key: String) {
        transaction(db.database) {
            val now = utcNow(Clock.systemUTC())
            execUpdate(
                "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, body, target_repo, state, dependencies, " +
                    "requires_approval, revision, attempts, version, created_at, updated_at, project_id, parent_task_id) " +
                    "VALUES (?, ?, ?, 'child', NULL, ?, ?, ?, TRUE, 0, 0, 0, ?, ?, ?, ?)",
                id, OPERATOR, "test:$key:$id", TARGET, state, deps, now, now, project, parent,
            )
        }
    }
}
