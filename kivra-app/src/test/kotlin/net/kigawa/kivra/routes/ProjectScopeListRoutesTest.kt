package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb

/**
 * The `projectId` query of the run list (ADR 0022 PR-D): absent is every run of the caller, as before; a project id is that project's runs;
 * `unassigned` is the runs with no project; anything else is 400 with a fixed text that does not echo the value.
 */
class ProjectScopeListRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }
    private val alice = "alice"

    private fun ApplicationTestBuilder.setUp(db: TestDb) {
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        application {
            install(ContentNegotiation) { json() }
            routing { configureTaskApiRoutes(db.runs, identity, projectRepo = projects) }
        }
    }

    private suspend fun ApplicationTestBuilder.list(query: String) = client.request("/api/runs$query") {
        method = HttpMethod.Get
        header("X-Test-Principal", alice)
    }

    private fun ids(body: String): Set<String> =
        Json.parseToJsonElement(body).jsonArray.map { it.jsonObject.getValue("runId").jsonPrimitive.content }.toSet()

    @Test
    fun `the run list is narrowed by projectId, and absent is every run as before`() = TestDb().use { db ->
        val alpha = ProjectRepository(db.database, Clock.systemUTC()).create("Alpha", null).projectId
        val inAlpha = (db.runs.submitTask(alice, "in-alpha", "Run A", projectId = alpha) as TaskSubmitResult.Created).run.runId.value
        val none = (db.runs.submitTask(alice, "none", "Run N") as TaskSubmitResult.Created).run.runId.value
        testApplication {
            setUp(db)
            val all = list("")
            assertEquals(HttpStatusCode.OK, all.status)
            assertEquals(setOf(inAlpha, none), ids(all.bodyAsText()))

            val onlyAlpha = list("?projectId=$alpha")
            assertEquals(setOf(inAlpha), ids(onlyAlpha.bodyAsText()))
            assertTrue(onlyAlpha.bodyAsText().contains("\"projectId\":\"$alpha\""), "the response names the project of the run")

            val unassigned = list("?projectId=unassigned")
            assertEquals(setOf(none), ids(unassigned.bodyAsText()))

            val unknown = list("?projectId=01UNKNOWN" + "Z".repeat(17))
            assertEquals(emptySet(), ids(unknown.bodyAsText()), "an unknown project id has no runs")
        }
    }

    @Test
    fun `a projectId that is neither a project id nor unassigned is 400, and the value is not echoed`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val bad = list("?projectId=SECRET-VALUE-123")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue(!bad.bodyAsText().contains("SECRET-VALUE-123"), "the value is never echoed")
            // an empty value is the same as absent
            assertEquals(HttpStatusCode.OK, list("?projectId=").status)
        }
    }
}
