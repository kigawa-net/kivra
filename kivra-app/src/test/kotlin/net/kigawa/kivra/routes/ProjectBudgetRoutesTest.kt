package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
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
import java.math.BigDecimal
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.newDbStore
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.Cost
import org.jetbrains.exposed.sql.transactions.transaction

/** The project budget API (ADR 0022, PR-E): auth, CSRF, JSON, 404 and 400, the stored values, and what the dashboard shows of it. */
class ProjectBudgetRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private class Lookup(private val roles: Map<String, Role>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = roles[ownerKey]?.let { AssigneeAccess(it, true) }
    }

    private val operatorGuard = PermissionGuard(
        PermissionService(
            PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root",
            Lookup(mapOf("operator" to Role.ADMIN, "viewer" to Role.VIEWER)),
        ),
    )

    private fun ApplicationTestBuilder.setUp(db: TestDb) {
        val budgets = newDbStore(db, Clock.systemUTC())
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    ProjectRepository(db.database, Clock.systemUTC()), db.runs, identity, operatorGuard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, Clock.systemUTC()),
                    budgets = budgets,
                    drain = ProjectDrainRepository(db.database, budgets),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod,
        path: String,
        principal: String?,
        body: String? = null,
        contentTypeHeader: ContentType? = ContentType.Application.Json,
        fetchSite: String? = null,
    ) = client.request(path) {
        this.method = method
        principal?.let { header("X-Test-Principal", it) }
        fetchSite?.let { header("Sec-Fetch-Site", it) }
        if (contentTypeHeader != null) contentType(contentTypeHeader)
        body?.let { setBody(it) }
    }

    private fun TestDb.projectOf(name: String = "Alpha"): String = ProjectRepository(database, Clock.systemUTC()).create(name, null).projectId

    private fun budgetPath(projectId: String) = "/api/projects/$projectId/budget"

    /** The stored limit, read straight from the table (not through the API). Null when there is no row. */
    private fun TestDb.limitRow(projectId: String): String? = transaction(database) {
        execQuery("SELECT monthly_limit_jpy FROM project_budgets WHERE project_id = ?", projectId) { it.getBigDecimal(1).toPlainString() }
            .firstOrNull()
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `unauthenticated requests are refused with 401 and change nothing`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val path = budgetPath(project)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, path, null).status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Put, path, null, """{"monthlyLimitJpy":1000}""").status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Delete, path, null, "{}").status)
            assertNull(db.limitRow(project))
        }
    }

    @Test
    fun `a viewer is refused with 403 on every budget endpoint, and sees no budget on the dashboard`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val path = budgetPath(project)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, path, "viewer").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, path, "viewer", """{"monthlyLimitJpy":1000}""").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, path, "viewer", "{}").status)
            assertNull(db.limitRow(project))

            val dashboard = obj(call(HttpMethod.Get, "/api/projects/$project/dashboard", "viewer").bodyAsText())
            assertEquals(JsonNull, dashboard["budget"])
        }
    }

    @Test
    fun `a state change without the JSON content type is refused with 415, a cross-site one with 403, and neither changes the limit`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val path = budgetPath(project)
            assertEquals(
                HttpStatusCode.UnsupportedMediaType,
                call(HttpMethod.Put, path, "operator", """{"monthlyLimitJpy":1000}""", contentTypeHeader = ContentType.Text.Plain).status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                call(HttpMethod.Put, path, "operator", """{"monthlyLimitJpy":1000}""", fetchSite = "cross-site").status,
            )
            assertEquals(HttpStatusCode.UnsupportedMediaType, call(HttpMethod.Delete, path, "operator", "{}", contentTypeHeader = null).status)
            assertNull(db.limitRow(project))
        }
    }

    @Test
    fun `an operator sets, reads, and clears the limit, and the dashboard shows the month`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val path = budgetPath(project)

            val put = call(HttpMethod.Put, path, "operator", """{"monthlyLimitJpy":1000,"softRatio":0.75}""")
            assertEquals(HttpStatusCode.OK, put.status)
            val set = obj(put.bodyAsText())
            assertEquals("1000", set.text("monthlyLimitJpy"))
            assertEquals("0.75", set.text("softRatio"))
            assertEquals("750", set.text("softLimitJpy"))
            assertEquals("1000", set.text("remainingJpy"))
            assertEquals("false", set.text("softExceeded"))
            assertEquals("0", set.text("committedJpy"))
            assertEquals("1000", db.limitRow(project))

            val got = obj(call(HttpMethod.Get, path, "operator").bodyAsText())
            assertEquals("1000", got.text("monthlyLimitJpy"))

            val dashboard = obj(call(HttpMethod.Get, "/api/projects/$project/dashboard", "operator").bodyAsText())
            val budget = assertNotNull(dashboard["budget"]).jsonObject
            assertEquals("1000", budget.text("monthlyLimitJpy"))
            assertEquals("ALL", dashboard.text("scope"))

            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, path, "operator", "{}").status)
            assertNull(db.limitRow(project))
            val cleared = obj(call(HttpMethod.Get, path, "operator").bodyAsText())
            assertNull(cleared.text("monthlyLimitJpy"))
            assertNull(cleared.text("remainingJpy"))
            assertEquals("false", cleared.text("softExceeded"))
            // Clearing again is still a success: the state asked for is the state there is.
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, path, "operator", "{}").status)
        }
    }

    @Test
    fun `the soft ratio defaults to 0_8 when it is not sent`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val set = obj(call(HttpMethod.Put, budgetPath(project), "operator", """{"monthlyLimitJpy":500}""").bodyAsText())
            assertEquals("0.8", set.text("softRatio"))
            assertEquals("400", set.text("softLimitJpy"))
        }
    }

    @Test
    fun `invalid values are refused with 400 and change nothing`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val path = budgetPath(project)
            val bad = listOf(
                """{}""",
                """{"monthlyLimitJpy":"1000"}""",
                """{"monthlyLimitJpy":true}""",
                """{"monthlyLimitJpy":null}""",
                """{"monthlyLimitJpy":1000.5}""",
                """{"monthlyLimitJpy":-1}""",
                """{"monthlyLimitJpy":1000000000000}""",
                """{"monthlyLimitJpy":1000,"softRatio":"0.8"}""",
                """{"monthlyLimitJpy":1000,"softRatio":0}""",
                """{"monthlyLimitJpy":1000,"softRatio":1.5}""",
                """{"monthlyLimitJpy":1000,"softRatio":0.8123}""",
                """{"monthlyLimitJpy":1000,"unknown":1}""",
                """[]""",
                """{"monthlyLimitJpy":""",
            )
            bad.forEach { body ->
                assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, path, "operator", body).status, body)
            }
            assertNull(db.limitRow(project))
        }
    }

    @Test
    fun `an unknown project is 404 on every budget endpoint`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val path = budgetPath("01MISSINGPROJECTAAAAAAAAA")
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, path, "operator").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, path, "operator", """{"monthlyLimitJpy":1000}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, path, "operator", "{}").status)
        }
    }

    @Test
    fun `the dashboard reports the month's consumption against the limit, and warns at the soft threshold`() = TestDb().use { db ->
        val project = db.projectOf()
        val budgets: DbBudgetStore = newDbStore(db, Clock.systemUTC())
        budgets.setProjectBudget(project, BigDecimal("1000"), BigDecimal("0.8"))
        budgets.recordSpend("coding:spent:1", BudgetAmount(BigDecimal("850"), "JPY", Cost.UNIT_CURRENCY_UNITS), unpriced = false, projectId = project)
        testApplication {
            setUp(db)
            val dashboard = obj(call(HttpMethod.Get, "/api/projects/$project/dashboard", "operator").bodyAsText())
            val budget = assertNotNull(dashboard["budget"]).jsonObject
            assertEquals("850", budget.text("committedJpy"))
            assertEquals("0", budget.text("reservedJpy"))
            assertEquals("150", budget.text("remainingJpy"))
            assertEquals("true", budget.text("softExceeded"))
        }
    }
}
