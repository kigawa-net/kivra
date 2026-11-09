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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val OPERATOR = "1:i:oper"
private const val AI = "1:i:ai"
private const val ROOT_APPROVER = "1:i:root"
private const val TARGET = "owner/repo"
/** A project id of the right shape that names no project. */
private const val UNKNOWN_PROJECT = "01ZZZZZZZZZZZZZZZZZZZZZZZZ"

/**
 * The project-scoped aliases (ADR 0022 PR-E, `/api/projects/{projectId}/...`) and the old routes that announce them. The aliases run the same
 * handlers: the same answers for the same project, a body `projectId` that must match the path (400), an unknown project in the path (404),
 * the operator's token and the permission check with the path's project. The old routes keep their answers and add `Deprecation` and `Link`
 * headers (only the old routes that take a projectId).
 */
class ProjectScopedRoutesTest {
    private val openDbs = mutableListOf<TestDb>()

    @AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth = when {
            headers[PRINCIPAL_HEADER]?.isNotBlank() == true -> OperatorAuth.Authenticated(headers[PRINCIPAL_HEADER]!!)
            else -> OperatorAuth.Unauthenticated
        }
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

    /** Every route that has an alias, on one application (each `configure...` registers its old and new paths). Returns the projects. */
    private fun ApplicationTestBuilder.setUp(db: TestDb): ProjectRepository {
        val autopilot = enabledAutopilot()
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val goals = SelfDevGoalRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            targetRepo = TARGET,
            operations = SelfDevOperatorRepository(db.database),
            projects = projects,
            control = SelfDevAutopilotControlRepository(db.database),
            aiPrincipal = AI,
        )
        val orders = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = OrderSettings.fromEnv(
                mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to ROOT_APPROVER),
                autopilot,
                aiPrincipal = null,
            ),
            aiPrincipal = AI,
            targetRepo = TARGET,
            orders = SelfDevOrderRepository(db.database),
            operations = SelfDevOperatorRepository(db.database),
            projects = projects,
        )
        val memory = SelfDevMemoryRouteDeps(
            operators = StubAuth,
            memory = SelfDevMemoryRepository(db.database, enabled = true),
            aiPrincipal = AI,
            projects = projects,
        )
        val assignees = SelfDevAssigneeRouteDeps(
            operators = StubAuth,
            guard = offPermissionGuard(),
            assignees = null,
            tokens = null,
            oidcIssuer = null,
            projects = projects,
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureSelfDevGoalRoutes(goals)
                configureSelfDevOrderRoutes(orders)
                configureSelfDevMemoryRoutes(memory)
                configureSelfDevAssigneeRoutes(assignees)
            }
        }
        return projects
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = null,
        principal: String? = OPERATOR,
    ): HttpResponse = client.request(path) {
        this.method = method
        principal?.let { header(PRINCIPAL_HEADER, it) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.arr(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private fun goalBody(key: String, projectId: String? = null): String =
        if (projectId == null) """{"title":"goal $key","idempotencyKey":"$key"}"""
        else """{"title":"goal $key","idempotencyKey":"$key","projectId":"$projectId"}"""

    private fun orderBody(title: String, projectId: String? = null): String =
        if (projectId == null) """{"title":"$title","policy":"do it"}"""
        else """{"title":"$title","policy":"do it","projectId":"$projectId"}"""

    @Test
    fun `the project's goals are submitted and listed on its route, the same as the old route narrowed to the project`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val projectId = setUp(db).create("Alpha", null).projectId

        val created = send(HttpMethod.Post, "/api/projects/$projectId/tasks", goalBody("k1"))
        assertEquals(HttpStatusCode.Accepted, created.status)
        val task = created.obj()
        assertEquals(projectId, task["projectId"]?.jsonPrimitive?.content, "the path's project is the goal's project")
        assertNull(created.headers["Deprecation"], "the alias announces nothing")

        val viaAlias = send(HttpMethod.Get, "/api/projects/$projectId/tasks").arr()
        val viaOld = send(HttpMethod.Get, "/api/selfdev/goals?projectId=$projectId")
        assertEquals(HttpStatusCode.OK, viaOld.status, "the old route answers as before")
        val oldRows = viaOld.arr()
        assertEquals(listOf(task["taskId"]), viaAlias.map { it.jsonObject["taskId"] })
        assertEquals(viaAlias, oldRows, "the alias gives the old route's answer for the project")
    }

    @Test
    fun `the old routes that take a projectId announce the alias with Deprecation and Link, and keep their answers`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val projectId = setUp(db).create("Beta", null).projectId

        val oldWithProject = send(HttpMethod.Get, "/api/selfdev/goals?projectId=$projectId")
        assertEquals("true", oldWithProject.headers["Deprecation"])
        assertEquals("</api/projects/$projectId/tasks>; rel=\"successor-version\"", oldWithProject.headers["Link"])

        // Without a project the link names the alias as a template: the placeholder stays encoded, and no id is invented.
        val oldWithout = send(HttpMethod.Get, "/api/selfdev/goals")
        assertEquals("true", oldWithout.headers["Deprecation"])
        assertEquals("</api/projects/%7BprojectId%7D/tasks>; rel=\"successor-version\"", oldWithout.headers["Link"])

        val oldOrders = send(HttpMethod.Get, "/api/selfdev/orders?projectId=$projectId")
        assertEquals("</api/projects/$projectId/orders>; rel=\"successor-version\"", oldOrders.headers["Link"])
        val oldApprovals = send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING&projectId=$projectId")
        assertEquals("</api/projects/$projectId/approvals>; rel=\"successor-version\"", oldApprovals.headers["Link"])
        val oldMemory = send(HttpMethod.Get, "/api/selfdev/memory?projectId=$projectId")
        assertEquals("</api/projects/$projectId/memory>; rel=\"successor-version\"", oldMemory.headers["Link"])
        val oldAssignees = send(HttpMethod.Get, "/api/selfdev/assignees?projectId=$projectId")
        assertEquals("</api/projects/$projectId/assignees>; rel=\"successor-version\"", oldAssignees.headers["Link"])
        // The old route with a value that is not a project id: the same 400 as before, with the header.
        val bad = send(HttpMethod.Get, "/api/selfdev/goals?projectId=nope")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("true", bad.headers["Deprecation"])
    }

    @Test
    fun `a body projectId that differs from the path is 400, and nothing is stored, on every creation`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val projects = setUp(db)
        val mine = projects.create("Gamma", null).projectId
        val other = projects.create("Delta", null).projectId

        val goal = send(HttpMethod.Post, "/api/projects/$mine/tasks", goalBody("m1", projectId = other))
        assertEquals(HttpStatusCode.BadRequest, goal.status)
        assertEquals("projectId must be the project in the path", goal.obj()["error"]?.jsonPrimitive?.content)
        assertEquals(0, send(HttpMethod.Get, "/api/projects/$mine/tasks").arr().size, "no goal is stored")

        val order = send(HttpMethod.Post, "/api/projects/$mine/orders", orderBody("o1", projectId = other))
        assertEquals(HttpStatusCode.BadRequest, order.status)
        assertEquals(0, send(HttpMethod.Get, "/api/projects/$mine/orders").arr().size, "no order is stored")

        // A list with a projectId query that is not the path's project is refused too.
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/projects/$mine/tasks?projectId=$other").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/projects/$mine/memory?projectId=unassigned").status)

        // The same project in the body is fine.
        val same = send(HttpMethod.Post, "/api/projects/$mine/tasks", goalBody("m2", projectId = mine))
        assertEquals(HttpStatusCode.Accepted, same.status)
    }

    @Test
    fun `a project that does not exist is 404 on the aliases, and a malformed one too`() = testApplication {
        val db = TestDb().also { openDbs += it }
        setUp(db)
        for (path in listOf("/api/projects/$UNKNOWN_PROJECT", "/api/projects/not-a-project")) {
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "$path/tasks").status, "GET tasks of $path")
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "$path/tasks", goalBody("missing")).status, "POST tasks of $path")
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "$path/orders").status, "GET orders of $path")
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "$path/orders", orderBody("x")).status, "POST orders of $path")
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "$path/approvals?state=PENDING").status, "approvals of $path")
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "$path/memory?limit=5").status, "memory of $path")
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "$path/assignees").status, "assignees of $path")
        }
    }

    @Test
    fun `a query cannot name the project of an alias, the path decides on every alias`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val known = setUp(db).create("Theta", null).projectId

        // The path's project is missing, the query names a real one: still 404 (the query never replaces the path).
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/api/projects/$UNKNOWN_PROJECT/tasks?projectId=$known").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/api/projects/$UNKNOWN_PROJECT/orders?projectId=$known").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/api/projects/$UNKNOWN_PROJECT/memory?projectId=$known&limit=5").status)
        // The path is the real one, the query names another: a 400 (the list is not narrowed to the query's project).
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/projects/$known/tasks?projectId=$UNKNOWN_PROJECT").status)
        // The same project in the query and the path is fine.
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/projects/$known/tasks?projectId=$known").status)
    }

    @Test
    fun `the aliases need the operator's token, and the AI may read but not submit`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val projectId = setUp(db).create("Epsilon", null).projectId

        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/projects/$projectId/tasks", principal = null).status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Post, "/api/projects/$projectId/orders", orderBody("x"), principal = null).status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/projects/$projectId/memory?limit=5", principal = null).status)
        // With the roles off the AI principal may read, and may not change anything (as on the old routes).
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/projects/$projectId/tasks", principal = AI).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/projects/$projectId/tasks", goalBody("ai"), principal = AI).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/goals", goalBody("ai2", projectId), principal = AI).status)
    }

    @Test
    fun `an order is created and listed on the project's route, and the pending approvals need the state and the project`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val projectId = setUp(db).create("Zeta", null).projectId

        val created = send(HttpMethod.Post, "/api/projects/$projectId/orders", orderBody("first order"))
        assertEquals(HttpStatusCode.Created, created.status)
        val order = created.obj()
        assertEquals(projectId, order["projectId"]?.jsonPrimitive?.content)
        val listed = send(HttpMethod.Get, "/api/projects/$projectId/orders").arr()
        assertEquals(listOf(order["orderId"]), listed.map { it.jsonObject["orderId"] })

        // The approvals alias: the state is required as on the old route; the answer is the old one (no pending request yet).
        // Deciding the stop requests is the root approver's (the legacy rule), so the list is read with that principal.
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/projects/$projectId/approvals", principal = ROOT_APPROVER).status)
        val pending = send(HttpMethod.Get, "/api/projects/$projectId/approvals?state=PENDING", principal = ROOT_APPROVER)
        assertEquals(HttpStatusCode.OK, pending.status)
        assertEquals(0, pending.arr().size)
        assertEquals("true", send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING&projectId=$projectId", principal = ROOT_APPROVER).headers["Deprecation"])
    }

    @Test
    fun `the memory and the assignees of a project are read on their aliases, and the old route answers as before`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val projectId = setUp(db).create("Eta", null).projectId

        val memory = send(HttpMethod.Get, "/api/projects/$projectId/memory?limit=5")
        assertEquals(HttpStatusCode.OK, memory.status)
        val memoryJson = memory.obj()
        assertTrue(memoryJson.containsKey("items"), "the list has its items")
        val oldMemory = send(HttpMethod.Get, "/api/selfdev/memory?limit=5&projectId=$projectId")
        assertEquals(memoryJson, oldMemory.obj(), "the alias answers what the old route answers for the project")

        // The roles are off here, so the assignee list answers 503 once the project is known (the route's own answer).
        val assignees = send(HttpMethod.Get, "/api/projects/$projectId/assignees")
        assertEquals(HttpStatusCode.ServiceUnavailable, assignees.status)
        assertNotNull(send(HttpMethod.Get, "/api/selfdev/assignees?projectId=$projectId").headers["Deprecation"])
    }
}
