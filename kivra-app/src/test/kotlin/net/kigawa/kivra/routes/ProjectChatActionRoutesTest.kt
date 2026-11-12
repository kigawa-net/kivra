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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.chat.ChatActionCode
import net.kigawa.kivra.core.chat.ChatResultCode
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectChatActionRepository
import net.kigawa.kivra.db.ProjectChatActionRow
import net.kigawa.kivra.db.ProjectChatRepository
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.newDbStore
import net.kigawa.kivra.mcp.safeTaskTitle
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
    private fun ApplicationTestBuilder.setUp(db: TestDb, aiPrincipal: String? = null, guard: PermissionGuard? = null): SelfDevOrderRepository {
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
            // The default is the roles off (today's rules), as Application builds it.
            guard = guard ?: PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, ROOT_APPROVER, null)),
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

    /** A project with one order whose cycle is in progress; its root Task is set to [state], [reason] and [version] (ADR 0023, TASK_REPLAN). */
    private data class Cycle(val project: String, val orderId: String, val taskId: String)

    private fun ApplicationTestBuilder.blockedCycle(
        db: TestDb,
        orders: SelfDevOrderRepository,
        name: String,
        state: String = "BLOCKED",
        reason: String? = "PLAN_INVALID",
        version: Long = 7,
    ): Cycle {
        val project = newProject(db, name)
        val order = newRoot(orders, project)
        assertIs<OrderResult.Moved<*>>(orders.startCycle(order.orderId, order.version, OPERATOR))
        val task = assertNotNull(orders.get(order.orderId)!!.currentGoalId)
        setTask(db, task, project, state, reason, version)
        return Cycle(project, order.orderId, task)
    }

    private fun setTask(db: TestDb, taskId: String, project: String, state: String, reason: String?, version: Long) {
        transaction(db.database) {
            execUpdate(
                "UPDATE selfdev_tasks SET state = ?, state_reason = ?, version = ?, project_id = ? WHERE task_id = ?",
                state, reason, version, project, taskId,
            )
        }
    }

    private fun taskOf(db: TestDb, taskId: String): SelfDevTask = assertNotNull(SelfDevOperatorRepository(db.database).task(taskId))

    @Test
    fun `a cycle whose root Task is blocked by a plan failure is proposed as a replan at the Task's version`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val cycle = blockedCycle(db, orders, "Replan proposal", version = 7)

        assertEquals(1, repo.refresh(cycle.project))
        val open = repo.proposed(cycle.project)
        assertEquals(listOf("TASK_REPLAN"), open.map { it.code })
        // The target is the Task, at the Task's version (7), not the order's.
        assertEquals(cycle.taskId, open.single().targetId)
        assertEquals(7L, open.single().expectedVersion)
    }

    @Test
    fun `a blocked Task for another reason, or another state, gets no replan proposal`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val cases = listOf(
            "pr-limit" to Triple("BLOCKED", "PENDING_PR_LIMIT", 7L),
            "planning" to Triple("PLANNING", null, 7L),
            "submitted" to Triple("TASK_SUBMITTED", "REPLAN_REQUESTED", 7L),
        )
        for ((name, c) in cases) {
            val cycle = blockedCycle(db, orders, name, state = c.first, reason = c.second, version = c.third)
            assertEquals(0, repo.refresh(cycle.project), name)
            assertTrue(repo.proposed(cycle.project).isEmpty(), name)
        }
    }

    @Test
    fun `a replan proposal expires when the Task is no longer blocked, and a moved version expires it too`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val cycle = blockedCycle(db, orders, "Expire replan", version = 7)
        repo.refresh(cycle.project)
        val first = repo.proposed(cycle.project).single()

        // The Task moves on without a new read: the version is 8, so the row expires as moved.
        setTask(db, cycle.taskId, cycle.project, "BLOCKED", "PLAN_INVALID", 8)
        assertEquals(1, repo.refresh(cycle.project))
        assertEquals("VERSION_MOVED", assertNotNull(repo.find(cycle.project, first.actionId)).resultCode)
        val second = repo.proposed(cycle.project).single()
        assertEquals(8L, second.expectedVersion)

        // The Task is no longer blocked, at the same version (a state change alone): the open row expires as no longer eligible.
        setTask(db, cycle.taskId, cycle.project, "TASK_SUBMITTED", "REPLAN_REQUESTED", 8)
        assertEquals(0, repo.refresh(cycle.project))
        assertTrue(repo.proposed(cycle.project).isEmpty())
        assertEquals("NO_LONGER_ELIGIBLE", assertNotNull(repo.find(cycle.project, second.actionId)).resultCode)
    }

    @Test
    fun `confirm runs the replan as the goal route does once, and a second answer is 409`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val cycle = blockedCycle(db, orders, "Replan confirm", version = 7)
        repo.refresh(cycle.project)
        val action = repo.proposed(cycle.project).single()

        val answer = send(HttpMethod.Post, confirmPath(cycle.project, action.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("EXECUTED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("OK", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        val replanned = taskOf(db, cycle.taskId)
        assertEquals("TASK_SUBMITTED", replanned.state.name)
        assertEquals("REPLAN_REQUESTED", replanned.stateReason?.name)
        assertEquals(8L, replanned.version)

        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, confirmPath(cycle.project, action.actionId), "{}").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, dismissPath(cycle.project, action.actionId), "{}").status)
        assertEquals(8L, taskOf(db, cycle.taskId).version)
    }

    @Test
    fun `a replan confirmed at a stale Task version expires with a fixed code and runs nothing`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val cycle = blockedCycle(db, orders, "Replan stale", version = 7)
        repo.refresh(cycle.project)
        val action = repo.proposed(cycle.project).single()
        // The Task moved after the proposal, and the chat was not read again.
        setTask(db, cycle.taskId, cycle.project, "BLOCKED", "PLAN_INVALID", 8)

        val answer = send(HttpMethod.Post, confirmPath(cycle.project, action.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("EXPIRED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("STALE_VERSION", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertEquals("BLOCKED", taskOf(db, cycle.taskId).state.name)
    }

    @Test
    fun `a replan of a Task of another project is not found, whatever the proposal says`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val other = blockedCycle(db, orders, "Other project", version = 7)
        val projectA = newProject(db, "Chat A")
        val actionId = Ulid.generate()
        transaction(db.database) {
            execUpdate(
                "INSERT INTO project_chat_actions (action_id, project_id, action_code, target_id, expected_version, state, active_key, proposed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                actionId, projectA, "TASK_REPLAN", other.taskId, 7L, "PROPOSED", utcNow(Clock.systemUTC()),
            )
        }

        val answer = send(HttpMethod.Post, confirmPath(projectA, actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("FAILED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("TASK_NOT_FOUND", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertEquals("BLOCKED", taskOf(db, other.taskId).state.name)
        assertEquals("FAILED", repo.find(projectA, actionId)!!.state)
    }

    @Test
    fun `the AI principal is refused a replan, and the proposal stays open`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db, aiPrincipal = AI_PRINCIPAL)
        val repo = ProjectChatActionRepository(db.database)
        val cycle = blockedCycle(db, orders, "AI replan", version = 7)
        repo.refresh(cycle.project)
        val action = repo.proposed(cycle.project).single()

        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, confirmPath(cycle.project, action.actionId), "{}", principal = AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, dismissPath(cycle.project, action.actionId), "{}", principal = AI_PRINCIPAL).status)
        assertEquals("PROPOSED", repo.find(cycle.project, action.actionId)!!.state)
        assertEquals("BLOCKED", taskOf(db, cycle.taskId).state.name)
    }

    /** The roles on: an operator whose role holds no GOAL_WRITE (a VIEWER has READ only). The chat's replan is then refused. */
    private fun viewerGuard(): PermissionGuard = PermissionGuard(
        PermissionService(
            PermissionMode.ENFORCE,
            RolesDefault.NONE,
            AI_PRINCIPAL,
            ROOT_APPROVER,
            object : AssigneeLookup {
                override fun accessOf(ownerKey: String): AssigneeAccess? =
                    if (ownerKey == OPERATOR) AssigneeAccess(Role.VIEWER, true, "assignee-viewer") else null
            },
        ),
    )

    @Test
    fun `without GOAL_WRITE a replan is refused with 403, nothing runs, and it is not shown to that operator`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db, guard = viewerGuard())
        val repo = ProjectChatActionRepository(db.database)
        val cycle = blockedCycle(db, orders, "No GOAL_WRITE", version = 7)
        repo.refresh(cycle.project)
        val action = repo.proposed(cycle.project).single()

        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, confirmPath(cycle.project, action.actionId), "{}").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, dismissPath(cycle.project, action.actionId), "{}").status)
        assertEquals("PROPOSED", repo.find(cycle.project, action.actionId)!!.state)
        assertEquals("BLOCKED", taskOf(db, cycle.taskId).state.name)

        // The chat's visibility is per code: the viewer sees neither code of this project; a global ADMIN sees both.
        val viewer = viewerGuard()
        assertFalse(chatActionVisibleTo(viewer, OPERATOR, cycle.project, ChatActionCode.TASK_REPLAN))
        assertFalse(chatActionVisibleTo(viewer, OPERATOR, cycle.project, ChatActionCode.ORDER_START_CYCLE))
        assertFalse(chatActionVisibleTo(viewer, AI_PRINCIPAL, cycle.project, ChatActionCode.TASK_REPLAN))
        val admin = PermissionGuard(
            PermissionService(
                PermissionMode.ENFORCE,
                RolesDefault.NONE,
                AI_PRINCIPAL,
                ROOT_APPROVER,
                object : AssigneeLookup {
                    override fun accessOf(ownerKey: String): AssigneeAccess? =
                        if (ownerKey == OPERATOR) AssigneeAccess(Role.ADMIN, true, "assignee-admin") else null
                },
            ),
        )
        assertTrue(chatActionVisibleTo(admin, OPERATOR, cycle.project, ChatActionCode.TASK_REPLAN))
        assertTrue(chatActionVisibleTo(admin, OPERATOR, cycle.project, ChatActionCode.ORDER_START_CYCLE))
    }

    @Test
    fun `each chat action code names its own permission, the replan's being GOAL_WRITE`() {
        assertEquals(Permission.GOAL_WRITE, ChatActionCode.TASK_REPLAN.permission())
        assertEquals(Permission.ORDER_MANAGE, ChatActionCode.ORDER_START_CYCLE.permission())
        assertEquals(Permission.ORDER_MANAGE, ChatActionCode.ORDER_ABANDON_CYCLE.permission())
        assertEquals(Permission.ORDER_MANAGE, permissionOfChatAction("NOT_A_CODE"))
    }

    /** A cycle in progress whose child Task waits for approval (ADR 0023 stage 2). [title] and [body] are what the planner wrote. */
    private data class Approval(val project: String, val orderId: String, val rootTaskId: String, val childId: String)

    private fun waitingCycle(
        db: TestDb,
        orders: SelfDevOrderRepository,
        name: String,
        title: String = "Write the docs",
        body: String? = "SECRET-BODY-TEXT",
        version: Long = 2,
        reason: String? = null,
        revision: Int = 0,
    ): Approval {
        val project = newProject(db, name)
        val order = newRoot(orders, project)
        assertIs<OrderResult.Moved<*>>(orders.startCycle(order.orderId, order.version, OPERATOR))
        val root = assertNotNull(orders.get(order.orderId)!!.currentGoalId)
        val child = Ulid.generate()
        insertWaiting(db, child, project, root, title, body, version, reason, revision)
        return Approval(project, order.orderId, root, child)
    }

    private fun insertWaiting(
        db: TestDb,
        id: String,
        project: String,
        root: String,
        title: String,
        body: String?,
        version: Long,
        reason: String?,
        revision: Int,
    ) {
        transaction(db.database) {
            val now = utcNow(Clock.systemUTC())
            execUpdate(
                "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, body, target_repo, state, state_reason, dependencies, " +
                    "requires_approval, revision, attempts, version, created_at, updated_at, project_id, parent_task_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, '[]', TRUE, ?, 0, ?, ?, ?, ?, ?)",
                id, OPERATOR, "test:wait:$id", title, body, TARGET, reason, revision, version, now, now, project, root,
            )
        }
    }

    private fun ProjectChatActionRepository.proposedWithCode(project: String, code: ChatActionCode): ProjectChatActionRow =
        proposed(project).single { it.code == code.name }

    @Test
    fun `a waiting child gets an approve and a reject at its version, and a plan root or another state gets neither`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Approve proposal", version = 5)

        assertEquals(2, repo.refresh(waiting.project))
        val open = repo.proposed(waiting.project).sortedBy { it.code }
        assertEquals(listOf("TASK_APPROVE", "TASK_REJECT"), open.map { it.code })
        assertTrue(open.all { it.targetId == waiting.childId && it.expectedVersion == 5L })

        val planRoot = waitingCycle(db, orders, "Plan root child", revision = 1)
        assertEquals(0, repo.refresh(planRoot.project))
        val queued = waitingCycle(db, orders, "Queued child")
        transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET state = 'QUEUED', state_reason = NULL WHERE task_id = ?", queued.childId) }
        assertEquals(0, repo.refresh(queued.project))
        assertTrue(repo.proposed(planRoot.project).isEmpty())
        assertTrue(repo.proposed(queued.project).isEmpty())
    }

    @Test
    fun `approve runs the goal page's approval once, the Task leaves PENDING_APPROVAL, and the other proposal is expired`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Approve confirm")
        repo.refresh(waiting.project)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)
        val reject = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_REJECT)

        val answer = send(HttpMethod.Post, confirmPath(waiting.project, approve.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("EXECUTED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("OK", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        val approved = taskOf(db, waiting.childId)
        assertEquals("QUEUED", approved.state.name)
        assertEquals("OPERATOR_APPROVED", approved.stateReason?.name)
        assertEquals(3L, approved.version)

        val sibling = assertNotNull(repo.find(waiting.project, reject.actionId))
        assertEquals("EXPIRED", sibling.state)
        assertEquals("SIBLING_RESOLVED", sibling.resultCode)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, confirmPath(waiting.project, approve.actionId), "{}").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, confirmPath(waiting.project, reject.actionId), "{}").status)
        assertEquals(3L, taskOf(db, waiting.childId).version)
        assertEquals(0, repo.refresh(waiting.project))
        assertTrue(repo.proposed(waiting.project).isEmpty())
    }

    @Test
    fun `reject skips the Task, its dependents do not run, and the approve proposal is expired`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Reject confirm")
        repo.refresh(waiting.project)
        val reject = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_REJECT)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)

        val answer = send(HttpMethod.Post, confirmPath(waiting.project, reject.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("EXECUTED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("OK", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        val skipped = taskOf(db, waiting.childId)
        assertEquals("SKIPPED", skipped.state.name)
        assertEquals("OPERATOR_SKIPPED", skipped.stateReason?.name)
        assertEquals("SIBLING_RESOLVED", assertNotNull(repo.find(waiting.project, approve.actionId)).resultCode)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, confirmPath(waiting.project, approve.actionId), "{}").status)
    }

    @Test
    fun `a failed approval expires its alternative, and the Task keeps waiting`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Failed approval")
        repo.refresh(waiting.project)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)
        val reject = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_REJECT)

        // A refused transition moves no version: the sibling must be expired by the resolution itself.
        assertTrue(repo.resolve(approve.actionId, ChatActionState.FAILED, ChatResultCode.TASK_NOT_APPROVABLE, OPERATOR))
        assertEquals("EXPIRED", assertNotNull(repo.find(waiting.project, reject.actionId)).state)
        assertEquals("SIBLING_RESOLVED", assertNotNull(repo.find(waiting.project, reject.actionId)).resultCode)
        assertEquals("PENDING_APPROVAL", taskOf(db, waiting.childId).state.name)
        assertEquals(0, repo.refresh(waiting.project))
    }

    @Test
    fun `a dismissed approval only hides itself, and its alternative stays open`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Dismiss approval")
        repo.refresh(waiting.project)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)

        assertTrue(repo.resolve(approve.actionId, ChatActionState.DISMISSED, ChatResultCode.OPERATOR_DISMISSED, OPERATOR))
        assertEquals(listOf("TASK_REJECT"), repo.proposed(waiting.project).map { it.code })
        assertEquals(0, repo.refresh(waiting.project))
        assertEquals("PENDING_APPROVAL", taskOf(db, waiting.childId).state.name)
    }

    @Test
    fun `a goal page's own approval moves the version, and both proposals expire as moved on the next read`() {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Goal page approval")
        repo.refresh(waiting.project)
        val open = repo.proposed(waiting.project).associateBy { it.code }

        // The Task leaves PENDING_APPROVAL on the goal page: its version moves.
        transaction(db.database) {
            execUpdate("UPDATE selfdev_tasks SET state = 'QUEUED', version = 3 WHERE task_id = ?", waiting.childId)
        }
        assertEquals(0, repo.refresh(waiting.project))
        assertTrue(repo.proposed(waiting.project).isEmpty())
        for (code in listOf("TASK_APPROVE", "TASK_REJECT")) {
            assertEquals("VERSION_MOVED", assertNotNull(repo.find(waiting.project, open.getValue(code).actionId)).resultCode, code)
        }
    }

    @Test
    fun `a stale approval version expires with a fixed code and runs nothing`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "Stale approval")
        repo.refresh(waiting.project)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)
        // The Task was changed after the proposal (still waiting), and the chat was not read again.
        transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET version = 3 WHERE task_id = ?", waiting.childId) }

        val answer = send(HttpMethod.Post, confirmPath(waiting.project, approve.actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("EXPIRED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("STALE_VERSION", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertEquals("PENDING_APPROVAL", taskOf(db, waiting.childId).state.name)
    }

    @Test
    fun `the AI principal is refused an approval, and the proposal stays open`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db, aiPrincipal = AI_PRINCIPAL)
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "AI approval")
        repo.refresh(waiting.project)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)

        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, confirmPath(waiting.project, approve.actionId), "{}", principal = AI_PRINCIPAL).status)
        assertEquals("PROPOSED", repo.find(waiting.project, approve.actionId)!!.state)
        assertEquals("PENDING_APPROVAL", taskOf(db, waiting.childId).state.name)
    }

    @Test
    fun `without GOAL_WRITE an approval is refused with 403, nothing runs, and it is not shown`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db, guard = viewerGuard())
        val repo = ProjectChatActionRepository(db.database)
        val waiting = waitingCycle(db, orders, "No GOAL_WRITE approval")
        repo.refresh(waiting.project)
        val approve = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_APPROVE)
        val reject = repo.proposedWithCode(waiting.project, ChatActionCode.TASK_REJECT)

        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, confirmPath(waiting.project, approve.actionId), "{}").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, confirmPath(waiting.project, reject.actionId), "{}").status)
        assertEquals("PROPOSED", repo.find(waiting.project, approve.actionId)!!.state)
        assertEquals("PENDING_APPROVAL", taskOf(db, waiting.childId).state.name)
        val viewer = viewerGuard()
        assertFalse(chatActionVisibleTo(viewer, OPERATOR, waiting.project, ChatActionCode.TASK_APPROVE))
        assertFalse(chatActionVisibleTo(viewer, OPERATOR, waiting.project, ChatActionCode.TASK_REJECT))
    }

    @Test
    fun `an approval of another project's Task is not found, whatever the proposal says`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val repo = ProjectChatActionRepository(db.database)
        val other = waitingCycle(db, orders, "Other approval")
        val projectA = newProject(db, "Approval chat A")
        val actionId = Ulid.generate()
        transaction(db.database) {
            execUpdate(
                "INSERT INTO project_chat_actions (action_id, project_id, action_code, target_id, expected_version, state, active_key, proposed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                actionId, projectA, "TASK_APPROVE", other.childId, 2L, "PROPOSED", utcNow(Clock.systemUTC()),
            )
        }

        val answer = send(HttpMethod.Post, confirmPath(projectA, actionId), "{}")
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("FAILED", answer.obj()["state"]!!.jsonPrimitive.content)
        assertEquals("TASK_NOT_FOUND", answer.obj()["resultCode"]!!.jsonPrimitive.content)
        assertEquals("PENDING_APPROVAL", taskOf(db, other.childId).state.name)
        assertEquals("FAILED", repo.find(projectA, actionId)!!.state)
    }

    @Test
    fun `the approval permission is GOAL_WRITE for both codes`() {
        assertEquals(Permission.GOAL_WRITE, ChatActionCode.TASK_APPROVE.permission())
        assertEquals(Permission.GOAL_WRITE, ChatActionCode.TASK_REJECT.permission())
    }

    /** The project chat's GET with the proposals in it: the project routes with the chat wired in, as Application does. */
    private fun ApplicationTestBuilder.setUpChat(db: TestDb, guard: PermissionGuard = offPermissionGuard()) {
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val chatRepo = ProjectChatRepository(db.database, Clock.systemUTC())
        val identity = TaskApiIdentity { headers -> headers[PRINCIPAL_HEADER]?.takeIf { it.isNotBlank() } }
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    projects, db.runs, identity, guard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, Clock.systemUTC()),
                    budgets = newDbStore(db, Clock.systemUTC()),
                    drain = ProjectDrainRepository(db.database, newDbStore(db, Clock.systemUTC())),
                    chatRepo = chatRepo,
                    chatActions = ProjectChatActionRepository(db.database),
                )
            }
        }
    }

    @Test
    fun `the chat shows an approval with the planner's title as plain text and the fixed reason, and no body`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        setUpChat(db)
        val waiting = waitingCycle(db, orders, "Detail", title = "Write the docs", reason = "AUTO_SCOPE_EXCEEDED")
        ProjectChatRepository(db.database, Clock.systemUTC()).createSession(waiting.project, "Chat")

        val text = send(HttpMethod.Get, "/api/projects/${waiting.project}/chat", null).bodyAsText()
        val actions = Json.parseToJsonElement(text).jsonObject["actions"]!!.jsonArray
        val approve = actions.map { it.jsonObject }.single { it["code"]!!.jsonPrimitive.content == "TASK_APPROVE" }
        val detail = approve["detail"]!!.jsonObject
        assertEquals("Write the docs", detail["taskTitle"]!!.jsonPrimitive.content)
        assertEquals("AUTO_SCOPE_EXCEEDED", detail["stateReason"]!!.jsonPrimitive.content)
        assertEquals("自動実行の範囲を超える変更があったため、承認が必要です。", detail["reasonText"]!!.jsonPrimitive.content)
        assertEquals("承認待ちの Task を承認する", approve["label"]!!.jsonPrimitive.content)
        // The body is never returned, and the label stays the server's fixed text (no title in it).
        assertFalse(text.contains("SECRET-BODY-TEXT"))
        assertFalse(approve["label"]!!.jsonPrimitive.content.contains("Write the docs"))
        assertEquals(2, actions.count { it.jsonObject["detail"] != null })
    }

    @Test
    fun `the title sanitizer cuts to 200 code points and marks control or bidi characters`() {
        assertEquals(200, safeTaskTitle("a".repeat(250)).codePointCount(0, safeTaskTitle("a".repeat(250)).length))
        assertEquals("[unsafe title omitted]", safeTaskTitle("ok\u0007"))
        assertEquals("[unsafe title omitted]", safeTaskTitle("x‮y"))
        assertEquals("plain", safeTaskTitle("plain"))
    }

    @Test
    fun `a control or bidi character in the planner's title gets the fixed marker, and a title of the column's 200 is shown whole`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = SelfDevOrderRepository(db.database)
        setUpChat(db)
        val bidi = waitingCycle(db, orders, "Bidi title", title = "Write‮evil the docs")
        ProjectChatRepository(db.database, Clock.systemUTC()).createSession(bidi.project, "Chat")
        val marked = Json.parseToJsonElement(send(HttpMethod.Get, "/api/projects/${bidi.project}/chat", null).bodyAsText())
            .jsonObject["actions"]!!.jsonArray.first().jsonObject["detail"]!!.jsonObject
        assertEquals("[unsafe title omitted]", marked["taskTitle"]!!.jsonPrimitive.content)

        // The column is VARCHAR(200), so a stored title is at most 200 characters; it is shown whole.
        val long = waitingCycle(db, orders, "Long title", title = "a".repeat(200))
        ProjectChatRepository(db.database, Clock.systemUTC()).createSession(long.project, "Chat")
        val whole = Json.parseToJsonElement(send(HttpMethod.Get, "/api/projects/${long.project}/chat", null).bodyAsText())
            .jsonObject["actions"]!!.jsonArray.first().jsonObject["detail"]!!.jsonObject
        assertEquals("a".repeat(200), whole["taskTitle"]!!.jsonPrimitive.content)
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
