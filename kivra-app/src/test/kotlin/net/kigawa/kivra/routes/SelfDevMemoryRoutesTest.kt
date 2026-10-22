package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import net.kigawa.kivra.selfdev.aiPrincipalFromEnv
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "op-1"
private const val AI_PRINCIPAL = "1:i:ai"
private const val AGENT_HEADER = "X-Test-Agent"
private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val UNTRUSTED_TEXT = "PLANNER-RATIONALE-SECRET-TEXT"
private const val TRUSTED_TEXT = "覚え書きの本文"
private const val ULID_NONE = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * ADR 0016 P2, the operator API on H2 with the real repository. The authenticator is a stub that reads the principal from a
 * header; the verifier itself is covered by OperatorAuthTest.
 */
class SelfDevMemoryRoutesTest {

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: Headers): OperatorAuth = when {
            headers[AGENT_HEADER] != null -> OperatorAuth.AgentRefused
            headers[PRINCIPAL_HEADER]?.isNotBlank() == true -> OperatorAuth.Authenticated(headers[PRINCIPAL_HEADER]!!)
            else -> OperatorAuth.Unauthenticated
        }
    }

    private fun ApplicationTestBuilder.setUp(memory: SelfDevMemoryRepository?, aiPrincipal: String? = null) {
        val deps = SelfDevMemoryRouteDeps(operators = StubAuth, memory = memory, aiPrincipal = aiPrincipal)
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevMemoryRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = null,
        principal: String? = OPERATOR,
        agent: Boolean = false,
    ): HttpResponse = client.request(path) {
        this.method = method
        principal?.let { header(PRINCIPAL_HEADER, it) }
        if (agent) header(AGENT_HEADER, "1")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.items(): JsonArray = getValue("items").jsonArray

    private fun JsonObject.intOf(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.ids(): List<String> = items().map { it.jsonObject.getValue("recordId").jsonPrimitive.content }

    private fun note(body: String, operator: String = OPERATOR, goalId: String? = null) = MemoryCandidate(
        kind = "OPERATOR_NOTE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operator,
        goalId = goalId, body = body,
    )

    private fun planFact(count: Int) = MemoryCandidate(kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, code = "PLAN_PROPOSED", countValue = count)

    private fun goal(db: TestDb, key: String): String {
        val result = SelfDevGoalRepository(db.database).submit(OPERATOR, key, "題名", "本文", "kigawa-net/kivra")
        return (result as SubmitResult.Created).goal.goalId
    }

    private fun repo(db: TestDb) = SelfDevMemoryRepository(db.database, enabled = true)

    /** Rows matching [where], read with raw SQL. The test checks what is stored, not the API. */
    private fun TestDb.count(table: String, where: String = "1 = 1"): Int =
        transaction(database) { execQuery("SELECT COUNT(*) FROM $table WHERE $where") { it.getInt(1) }.single() }

    // ---- the switch, authentication and the AI principal ------------------------------------------------------------------

    @Test
    fun `while the memory switch is off every endpoint answers 503 and reads nothing`() = TestDb().use { db ->
        testApplication {
            setUp(memory = null)
            val list = send(HttpMethod.Get, "/api/selfdev/memory")
            assertEquals(HttpStatusCode.ServiceUnavailable, list.status)
            assertEquals("memory is disabled", list.json().getValue("error").jsonPrimitive.content)
            assertEquals(HttpStatusCode.ServiceUnavailable, send(HttpMethod.Post, "/api/selfdev/memory/$ULID_NONE/delete", """{"reason":"x"}""").status)
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"kind":"OPERATOR_NOTE","dryRun":true}""").status,
            )
            // Authentication still comes first, so an unauthenticated caller learns nothing about the switch.
            assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/memory", principal = null).status)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/memory", agent = true).status)
        }
        assertEquals(0, db.count("selfdev_memory_records"), "nothing was written")
    }

    @Test
    fun `the operator only, no token is 401, an agent token is 403, and the AI principal is 403 on every endpoint`() = TestDb().use { db ->
        val r = repo(db)
        val id = (r.save(note("覚え書き")) as MemorySaveResult.Written).recordId
        testApplication {
            setUp(memory = r, aiPrincipal = AI_PRINCIPAL)
            assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/memory", principal = null).status)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/memory", agent = true).status)
            val listAsAi = send(HttpMethod.Get, "/api/selfdev/memory", principal = AI_PRINCIPAL)
            assertEquals(HttpStatusCode.Forbidden, listAsAi.status)
            assertEquals("forbidden", listAsAi.json().getValue("error").jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/memory/$id/delete", """{"reason":"x"}""", principal = AI_PRINCIPAL).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"kind":"OPERATOR_NOTE","dryRun":false,"reason":"x"}""", principal = AI_PRINCIPAL).status,
            )
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/memory").status, "the operator reads")
        }
        assertEquals(1, db.count("selfdev_memory_records", "status = 'ACTIVE'"), "the AI principal deleted nothing")
    }

    @Test
    fun `the AI principal read at startup is refused by the memory API even while the orders are off`() = TestDb().use { db ->
        // The same path Application.kt takes: the AI principal is read once, from the environment, while the autopilot is on.
        val env = mapOf(
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
            AutopilotSettings.ENV_ENABLED to "true",
            OrderSettings.ENV_ENABLED to "false",
            OrderSettings.ENV_AI_PRINCIPAL to AI_PRINCIPAL,
        )
        val autopilot = AutopilotSettings.fromEnv(env, SelfDevSettings.fromEnv(env))
        val aiPrincipal = aiPrincipalFromEnv(env, autopilot)
        assertEquals(AI_PRINCIPAL, aiPrincipal)
        assertFalse(OrderSettings.fromEnv(env, autopilot, aiPrincipal).enabled, "the orders are off")
        val r = repo(db)
        val id = (r.save(note("覚え書き")) as MemorySaveResult.Written).recordId
        testApplication {
            setUp(memory = r, aiPrincipal = aiPrincipal)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/memory", principal = AI_PRINCIPAL).status)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/memory/$id/delete", """{"reason":"x"}""", principal = AI_PRINCIPAL).status)
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/memory").status, "the operator still reads")
        }
        assertEquals(1, db.count("selfdev_memory_records", "status = 'ACTIVE'"), "the AI principal deleted nothing")
    }

    // ---- the list: filters, paging, the limit, and the UNTRUSTED text ---------------------------------------------------

    @Test
    fun `the list filters by kind, tier, goal and time, pages with a total, and rejects a bad query`() = TestDb().use { db ->
        val r = repo(db)
        val g = goal(db, "goal-list")
        r.save(note("一つ目", goalId = g))
        r.save(note("二つ目"))
        r.save(note("三つ目"))
        r.save(planFact(3))
        testApplication {
            setUp(memory = r)
            val all = send(HttpMethod.Get, "/api/selfdev/memory").json()
            assertEquals(4, all.intOf("total"))
            assertEquals(20, all.intOf("limit"), "the default limit")
            assertEquals(0, all.intOf("offset"))

            val notes = send(HttpMethod.Get, "/api/selfdev/memory?kind=OPERATOR_NOTE").json()
            assertEquals(3, notes.intOf("total"))
            assertEquals(3, notes.items().size)

            val paged = send(HttpMethod.Get, "/api/selfdev/memory?kind=OPERATOR_NOTE&limit=2&offset=2").json()
            assertEquals(3, paged.intOf("total"), "the total counts all matches, not the page")
            assertEquals(1, paged.items().size, "the third of three, after an offset of two")

            val trusted = send(HttpMethod.Get, "/api/selfdev/memory?tier=TRUSTED_OPERATOR").json()
            assertEquals(3, trusted.intOf("total"))
            assertEquals(0, send(HttpMethod.Get, "/api/selfdev/memory?tier=UNTRUSTED&kind=OPERATOR_NOTE").json().intOf("total"))

            val byGoal = send(HttpMethod.Get, "/api/selfdev/memory?goalId=$g").json()
            assertEquals(1, byGoal.intOf("total"))

            assertEquals(4, send(HttpMethod.Get, "/api/selfdev/memory?createdAfter=2000-01-01T00:00:00").json().intOf("total"))
            assertEquals(0, send(HttpMethod.Get, "/api/selfdev/memory?createdBefore=2000-01-01T00:00:00").json().intOf("total"))

            for (bad in listOf(
                "kind=NOPE", "tier=NOPE", "source=NOPE", "goalId=not-a-ulid", "createdAfter=yesterday",
                "limit=0", "limit=101", "limit=x", "offset=-1",
            )) {
                assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/selfdev/memory?$bad").status, bad)
            }
        }
    }

    @Test
    fun `an UNTRUSTED note's text is never returned, a TRUSTED note's text is, and a fact has none`() = TestDb().use { db ->
        val r = repo(db)
        r.save(MemoryCandidate(kind = "PLAN_RATIONALE", channel = MemoryChannel.PLANNER_AUTO, body = UNTRUSTED_TEXT))
        r.save(note(TRUSTED_TEXT))
        r.save(planFact(2))
        testApplication {
            setUp(memory = r)
            val text = send(HttpMethod.Get, "/api/selfdev/memory").bodyAsText()
            assertFalse(text.contains(UNTRUSTED_TEXT), "the UNTRUSTED text is not in the response at all")
            val items = Json.parseToJsonElement(text).jsonObject.items().map { it.jsonObject }
            val untrusted = items.single { it.getValue("kind").jsonPrimitive.content == "PLAN_RATIONALE" }
            assertEquals("UNTRUSTED", untrusted.getValue("tier").jsonPrimitive.content)
            assertTrue(untrusted["body"].let { it == null || it is JsonNull })
            assertTrue(untrusted.getValue("bodyWithheld").jsonPrimitive.boolean)
            val trusted = items.single { it.getValue("kind").jsonPrimitive.content == "OPERATOR_NOTE" }
            assertEquals(TRUSTED_TEXT, trusted.getValue("body").jsonPrimitive.content)
            assertFalse(trusted.getValue("bodyWithheld").jsonPrimitive.boolean)
            val fact = items.single { it.getValue("kind").jsonPrimitive.content == "PLAN_FACT" }
            assertTrue(fact["body"].let { it == null || it is JsonNull })
            assertFalse(fact.getValue("bodyWithheld").jsonPrimitive.boolean)
            assertEquals(2, fact.getValue("countValue").jsonPrimitive.int)
        }
    }

    // ---- deletion: single, conditional, dry run, the reason, and the audit counts --------------------------------------

    @Test
    fun `a deleted note leaves the list at once, its tombstone and the audit count agree, and a second delete is 404`() = TestDb().use { db ->
        val r = repo(db)
        val keep = (r.save(note("残す")) as MemorySaveResult.Written).recordId
        val gone = (r.save(note("消す")) as MemorySaveResult.Written).recordId
        testApplication {
            setUp(memory = r)
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/memory/$gone/delete", "{}").status, "a reason is required")
            assertEquals(
                HttpStatusCode.BadRequest,
                send(HttpMethod.Post, "/api/selfdev/memory/$gone/delete", """{"reason":"a\u0001b"}""").status,
                "a control character is refused",
            )
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/selfdev/memory/$ULID_NONE/delete", """{"reason":"x"}""").status)

            val ok = send(HttpMethod.Post, "/api/selfdev/memory/$gone/delete", """{"reason":"誤って書いた"}""")
            assertEquals(HttpStatusCode.OK, ok.status)
            assertTrue(ok.json().getValue("deleted").jsonPrimitive.boolean)
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/selfdev/memory/$gone/delete", """{"reason":"x"}""").status)

            val after = send(HttpMethod.Get, "/api/selfdev/memory").json()
            assertEquals(listOf(keep), after.ids(), "the deleted note is not in the list")
            assertEquals(1, after.intOf("total"))
            assertEquals(1, after.intOf("tombstones"))
            assertEquals(1, after.intOf("deletionAudit"), "the audit count matches the tombstones")
        }
        assertEquals(1, db.count("selfdev_memory_records", "status = 'DELETED' AND body IS NULL"), "the text is gone; a tombstone remains")
    }

    @Test
    fun `a conditional delete counts on a dry run and changes nothing, a real one needs a reason, and an empty condition is refused`() = TestDb().use { db ->
        val r = repo(db)
        repeat(3) { r.save(note("条件の $it")) }
        r.save(planFact(1))
        testApplication {
            setUp(memory = r)
            val dry = send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"kind":"OPERATOR_NOTE"}""").json()
            assertEquals(3, dry.intOf("matched"))
            assertEquals(0, dry.intOf("deleted"))
            assertTrue(dry.getValue("dryRun").jsonPrimitive.boolean, "a request without dryRun is a dry run")
            assertEquals(4, send(HttpMethod.Get, "/api/selfdev/memory").json().intOf("total"), "the dry run removed nothing")

            assertEquals(
                HttpStatusCode.BadRequest,
                send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"kind":"OPERATOR_NOTE","dryRun":false}""").status,
                "a real deletion needs a reason",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"dryRun":true}""").status,
                "an empty condition is refused",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"kind":"OPERATOR_NOTE","bogus":1}""").status,
                "an unknown field is refused",
            )

            val real = send(HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"kind":"OPERATOR_NOTE","dryRun":false,"reason":"テスト"}""").json()
            assertEquals(3, real.intOf("matched"))
            assertEquals(3, real.intOf("deleted"))
            assertFalse(real.getValue("dryRun").jsonPrimitive.boolean)

            val after = send(HttpMethod.Get, "/api/selfdev/memory").json()
            assertEquals(1, after.intOf("total"), "only the fact is left")
            assertEquals(3, after.intOf("tombstones"))
            assertEquals(3, after.intOf("deletionAudit"), "the audit count matches the tombstones")
        }
    }

    @Test
    fun `a reason is never echoed back and never stored in the rows`() = TestDb().use { db ->
        val r = repo(db)
        val id = (r.save(note("覚え書き")) as MemorySaveResult.Written).recordId
        testApplication {
            setUp(memory = r)
            val reason = "理由の文SECRET-REASON"
            val res = send(HttpMethod.Post, "/api/selfdev/memory/$id/delete", """{"reason":"$reason"}""")
            assertEquals(HttpStatusCode.OK, res.status)
            assertFalse(res.bodyAsText().contains("SECRET-REASON"))
        }
        assertEquals(0, db.count("selfdev_memory_events", "code LIKE '%SECRET%' OR scope LIKE '%SECRET%' OR detail LIKE '%SECRET%'"))
        assertEquals(0, db.count("selfdev_memory_records", "body LIKE '%SECRET%' OR code LIKE '%SECRET%'"))
    }
}
