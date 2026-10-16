package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunsTable
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private const val SECRET_TITLE = "SECRET-TITLE-MARKER <script>alert(1)</script>"
private const val SECRET_RELEASE_REASON = "SECRET-RELEASE-REASON-MARKER"
private const val SECRET_GITHUB_TOKEN = "ghp_SECRET_TEST_VALUE_1234"
private const val SECRET_ANTHROPIC_KEY = "sk-ant-SECRET_TEST_VALUE_5678"
private val TARGET = RepoRef("kigawa-net", "kivra")

/** Settings with self-development enabled. The secret values are fakes and must never appear in a response. */
private fun enabledSettings(): SelfDevSettings = SelfDevSettings.fromEnv(
    mapOf(
        "KIVRA_SELFDEV_ENABLED" to "true",
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_SELFDEV_GITHUB_TOKEN" to SECRET_GITHUB_TOKEN,
        "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to SECRET_ANTHROPIC_KEY,
        "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "343288952",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/etc/kivra/model-registry.yaml",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-01",
    ),
)

/** Operator and owner views of the Task API runs, the self-development status, on H2 with the real repository. */
class RunViewRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private fun ApplicationTestBuilder.setUp(db: TestDb, settings: SelfDevSettings = SelfDevSettings.disabled()) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureTaskApiRoutes(db.runs, identity, settings.config?.repo)
                configureRunViewRoutes(db.runs, identity, settings)
            }
        }
    }

    private fun TestDb.selfDev(principal: String, key: String, title: String = SECRET_TITLE): String =
        (runs.submitTask(principal, key, title, selfDev = true, maxActiveSelfDev = 100) as TaskSubmitResult.Created).run.runId.value

    private fun TestDb.simulated(principal: String, key: String, title: String = "通知の文言を直す"): String =
        (runs.submitTask(principal, key, title) as TaskSubmitResult.Created).run.runId.value

    private fun TestDb.bindPullRequest(runId: String, repository: String, number: Long) {
        transaction(database) {
            DevelopmentRunsTable.update({ DevelopmentRunsTable.runId eq runId }) {
                it[prRepository] = repository
                it[prRepositoryId] = 42L
                it[prNumber] = number
                it[prNodeId] = "PR_node_$number"
                it[prHeadSha] = "0123456789abcdef0123456789abcdef01234567"
                it[prBaseRef] = "main"
                it[prPolicyRevision] = "rev-1"
                it[prReviewManifestDigest] = "digest-1"
            }
        }
    }

    private fun TestDb.setState(runId: String, state: String, reason: String?) {
        transaction(database) {
            DevelopmentRunsTable.update({ DevelopmentRunsTable.runId eq runId }) {
                it[DevelopmentRunsTable.state] = state
                it[stateReason] = reason
            }
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.arr(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.read(path: String, principal: String?): HttpResponse =
        client.get(path) { principal?.let { header("X-Test-Principal", it) } }

    @Test
    fun `every view requires authentication`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            for (path in listOf("/api/runs", "/api/admin/runs", "/api/admin/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV", "/api/selfdev/status")) {
                assertEquals(HttpStatusCode.Unauthorized, read(path, null).status, path)
            }
        }
    }

    @Test
    fun `a self-development run is SELF_DEV with no title and no executed flag, and a simulated run is unchanged`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            val selfDev = db.selfDev("alice", "k1")
            val simulated = db.simulated("alice", "k2")

            val list = read("/api/runs", "alice").arr().associateBy { it.jsonObject["runId"]!!.jsonPrimitive.content }
            val sd = list.getValue(selfDev).jsonObject
            assertEquals("SELF_DEV", sd["mode"]!!.jsonPrimitive.content)
            assertIs<JsonNull>(sd["executed"])
            assertIs<JsonNull>(sd["title"])
            assertEquals("CREATES_PULL_REQUEST", sd["effect"]!!.jsonPrimitive.content)
            assertEquals("CREATED", sd["state"]!!.jsonPrimitive.content)

            val sim = list.getValue(simulated).jsonObject
            assertEquals("SIMULATED", sim["mode"]!!.jsonPrimitive.content)
            assertEquals("false", sim["executed"].toString())
            assertEquals("通知の文言を直す", sim["title"]!!.jsonPrimitive.content)
            assertEquals("SIMULATED_ONLY", sim["effect"]!!.jsonPrimitive.content)

            // The task text of a self-development run never leaves the server, in any Task API view.
            val text = read("/api/runs", "alice").bodyAsText()
            assertFalse("SECRET-TITLE-MARKER" in text)
            assertFalse("<script>" in text)
            assertFalse(read("/api/runs/$selfDev", "alice").bodyAsText().contains("SECRET-TITLE-MARKER"))
        }
    }

    @Test
    fun `the detail of a run carries its audit events, without actors or free-text payloads`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            val runId = db.selfDev("alice", "k1")
            val detail = read("/api/runs/$runId", "alice")
            assertEquals(HttpStatusCode.OK, detail.status)
            val text = detail.bodyAsText()
            val events = Json.parseToJsonElement(text).jsonObject["events"]!!.jsonArray
            assertEquals(1, events.size)
            val first = events[0].jsonObject
            assertEquals("RUN_CREATED", first["action"]!!.jsonPrimitive.content)
            assertEquals("OK", first["result"]!!.jsonPrimitive.content)
            assertFalse("alice" in text, "the actor is not shown")
        }
    }

    @Test
    fun `an operator sees every run with its owner, and a list with a limit is validated`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            val aliceSelf = db.selfDev("alice", "k1")
            val bobSim = db.simulated("bob", "k2")

            // The owner sees only its own runs.
            assertEquals(listOf(aliceSelf), read("/api/runs", "alice").arr().map { it.jsonObject["runId"]!!.jsonPrimitive.content })

            // An operator (any authenticated caller in this test) sees both, each with createdBy.
            val all = read("/api/admin/runs", "carol")
            assertEquals(HttpStatusCode.OK, all.status)
            val allText = all.bodyAsText()
            val byId = Json.parseToJsonElement(allText).jsonArray.associateBy { it.jsonObject["runId"]!!.jsonPrimitive.content }
            assertEquals(setOf(aliceSelf, bobSim), byId.keys)
            assertEquals("alice", byId.getValue(aliceSelf).jsonObject["createdBy"]!!.jsonPrimitive.content)
            assertEquals("bob", byId.getValue(bobSim).jsonObject["createdBy"]!!.jsonPrimitive.content)
            assertEquals("SELF_DEV", byId.getValue(aliceSelf).jsonObject["mode"]!!.jsonPrimitive.content)
            assertFalse("SECRET-TITLE-MARKER" in allText)

            // The operator can read another owner's run in detail; a missing run is the same 404 as a malformed id.
            assertEquals(HttpStatusCode.OK, read("/api/admin/runs/$aliceSelf", "carol").status)
            assertEquals(HttpStatusCode.NotFound, read("/api/admin/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV", "carol").status)
            assertEquals(HttpStatusCode.NotFound, read("/api/admin/runs/not-a-run-id", "carol").status)

            for (bad in listOf("0", "101", "abc")) {
                assertEquals(HttpStatusCode.BadRequest, read("/api/admin/runs?limit=$bad", "carol").status, bad)
            }
            assertEquals(1, read("/api/admin/runs?limit=1", "carol").arr().size)
        }
    }

    @Test
    fun `the operator release reason is never shown in a run view, but the release itself is in the events`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            val runId = db.selfDev("alice", "k1")
            db.setState(runId, "UNKNOWN", "GITHUB_UNKNOWN_OUTCOME")
            val released = db.runs.releaseUnknownSelfDev(RunId(runId), "carol", SECRET_RELEASE_REASON)
            assertIs<net.kigawa.kivra.db.ReleaseResult.Released>(released)

            val text = read("/api/admin/runs/$runId", "carol").bodyAsText()
            assertFalse(SECRET_RELEASE_REASON in text, "the free-text reason stays in the audit payload")
            assertFalse("carol" in text, "the actor is not shown")
            val obj = Json.parseToJsonElement(text).jsonObject
            assertEquals("CANCELLED", obj["state"]!!.jsonPrimitive.content)
            assertEquals("OPERATOR_RELEASED", obj["reasonCode"]!!.jsonPrimitive.content)
            val actions = obj["events"]!!.jsonArray.map { it.jsonObject["action"]!!.jsonPrimitive.content }
            assertEquals(listOf("RUN_CREATED", "RUN_RELEASED"), actions)
            // The release is an audit event of its own; its free-text reason is not read out, so no code is shown for it.
            assertIs<JsonNull>(obj["events"]!!.jsonArray[1].jsonObject["toState"])
        }
    }

    @Test
    fun `the pull request comes from the binding, and is a link only for the configured repository`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            val inTarget = db.selfDev("alice", "k1")
            db.bindPullRequest(inTarget, "Kigawa-Net/Kivra", 241)
            val elsewhere = db.selfDev("alice", "k2")
            db.bindPullRequest(elsewhere, "someone/else", 5)
            val none = db.selfDev("alice", "k3")

            val byId = read("/api/runs", "alice").arr().associateBy { it.jsonObject["runId"]!!.jsonPrimitive.content }
            val linked = byId.getValue(inTarget).jsonObject
            assertEquals("https://github.com/kigawa-net/kivra/pull/241", linked["pullRequestUrl"]!!.jsonPrimitive.content)
            assertEquals(241L, linked["pullRequestNumber"]!!.jsonPrimitive.content.toLong())
            assertEquals("Kigawa-Net/Kivra", linked["pullRequestRepository"]!!.jsonPrimitive.content)

            val other = byId.getValue(elsewhere).jsonObject
            assertIs<JsonNull>(other["pullRequestUrl"], "another repository is never a link")
            assertEquals(5L, other["pullRequestNumber"]!!.jsonPrimitive.content.toLong())

            val without = byId.getValue(none).jsonObject
            assertTrue(without["pullRequestNumber"] == null || without["pullRequestNumber"] is JsonNull, "no binding, no pull request")
            assertTrue(without["pullRequestUrl"] == null || without["pullRequestUrl"] is JsonNull)
        }
    }

    @Test
    fun `without a configured repository a pull request is never linked`() = TestDb().use { db ->
        testApplication {
            setUp(db, SelfDevSettings.disabled())
            val runId = db.selfDev("alice", "k1")
            db.bindPullRequest(runId, "kigawa-net/kivra", 7)
            val run = read("/api/runs/$runId", "alice").obj()
            assertIs<JsonNull>(run["pullRequestUrl"])
            assertEquals(7L, run["pullRequestNumber"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun `status is only enabled false when self-development is off, and an enabled summary holds no secret`() = TestDb().use { db ->
        testApplication {
            setUp(db, SelfDevSettings.disabled())
            val off = read("/api/selfdev/status", "alice")
            assertEquals(HttpStatusCode.OK, off.status)
            assertEquals(mapOf("enabled" to "false"), off.obj().mapValues { it.value.toString() })
        }
        testApplication {
            setUp(db, enabledSettings())
            val on = read("/api/selfdev/status", "alice")
            assertEquals(HttpStatusCode.OK, on.status)
            val text = on.bodyAsText()
            val obj = Json.parseToJsonElement(text).jsonObject
            assertEquals("true", obj["enabled"].toString())
            assertEquals("kigawa-net/kivra", obj["targetRepo"]!!.jsonPrimitive.content)
            assertEquals("main", obj["baseBranch"]!!.jsonPrimitive.content)
            assertEquals("pat", obj["githubAuth"]!!.jsonPrimitive.content)
            assertEquals("api_key", obj["anthropicAuth"]!!.jsonPrimitive.content)
            for (secret in listOf(SECRET_GITHUB_TOKEN, SECRET_ANTHROPIC_KEY, "343288952", "KIVRA_SELFDEV", "/etc/kivra", "ANTHROPIC_API_KEY")) {
                assertFalse(secret in text, "status must not contain $secret")
            }
        }
    }

    @Test
    fun `the admin list does not include workflow runs that have no principal`() = TestDb().use { db ->
        testApplication {
            setUp(db, enabledSettings())
            db.runs.create("workflow-task", "system", net.kigawa.kivra.core.RunCompletionMode.PR_ONLY)
            db.simulated("alice", "k1")
            assertEquals(1, read("/api/admin/runs", "carol").arr().size)
        }
    }
}
