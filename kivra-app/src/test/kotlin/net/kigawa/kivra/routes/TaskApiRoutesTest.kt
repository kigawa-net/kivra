package net.kigawa.kivra.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.appModule
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.auth.resolveTaskApiIdentity
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb

private const val ALICE = "alice@example.com"
private const val BOB = "bob@example.com"

/**
 * The Task API contract, run once per authentication scheme (see the subclasses). [credentialFor] returns the request
 * headers that identify [email] under that scheme, or none when [email] is null.
 */
abstract class TaskApiRoutesContractTest {
    protected abstract val identity: TaskApiIdentity

    /** Headers that identify [email] (null = no identity at all). */
    protected abstract fun credentialFor(email: String?): Map<String, String>

    private fun ApplicationTestBuilder.setUpRoutes(db: TestDb) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureTaskApiRoutes(db.runs, identity) }
        }
    }

    private fun HttpRequestBuilder.identify(email: String?) {
        credentialFor(email).forEach { (name, value) -> header(name, value) }
    }

    private suspend fun HttpClient.submit(body: String, email: String? = ALICE): HttpResponse = post("/api/tasks") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        identify(email)
        setBody(body)
    }

    private suspend fun HttpClient.read(path: String, email: String? = ALICE): HttpResponse = get(path) {
        identify(email)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.runId(): String = json().getValue("runId").jsonPrimitive.content

    @Test
    fun `request without an identity is 401 for every route`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            assertEquals(HttpStatusCode.Unauthorized, client.submit("""{"title":"t","idempotencyKey":"k"}""", email = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.read("/api/runs", email = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.read("/api/runs/01AAAAAAAAAAAAAAAAAAAAAAAA", email = null).status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `over-long identity header is 401 (control characters are covered in TaskApiAuthTest)`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            assertEquals(HttpStatusCode.Unauthorized, client.submit("""{"title":"t","idempotencyKey":"k"}""", email = "x".repeat(257)).status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `valid submit is 202 with the simulated run snapshot`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val response = client.submit("""{"title":"Write the release notes","idempotencyKey":"rn-1"}""")
            assertEquals(HttpStatusCode.Accepted, response.status)
            val body = response.json()
            assertEquals("Write the release notes", body.getValue("title").jsonPrimitive.content)
            assertEquals("CREATED", body.getValue("state").jsonPrimitive.content)
            assertEquals("SIMULATED", body.getValue("mode").jsonPrimitive.content)
            assertEquals("false", body.getValue("executed").toString())
            assertTrue(body.getValue("taskId").jsonPrimitive.content.startsWith("api:"))
            assertTrue(body.getValue("createdAt").jsonPrimitive.content.endsWith("Z"))
        }
    }

    @Test
    fun `replay returns the same run and a different title is 409`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val first = client.submit("""{"title":"Same","idempotencyKey":"k"}""")
            val replay = client.submit("""{"title":"Same","idempotencyKey":"k"}""")
            val conflict = client.submit("""{"title":"Different","idempotencyKey":"k"}""")
            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(first.runId(), replay.runId())
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals(1, db.count("development_runs"))
        }
    }

    @Test
    fun `another user's run is 404 and listing is scoped to the caller`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val aliceRun = client.submit("""{"title":"Alice task","idempotencyKey":"a"}""", email = ALICE).runId()
            client.submit("""{"title":"Bob task","idempotencyKey":"b"}""", email = BOB)

            assertEquals(HttpStatusCode.NotFound, client.read("/api/runs/$aliceRun", email = BOB).status)
            val aliceReads = client.read("/api/runs/$aliceRun", email = ALICE)
            assertEquals(HttpStatusCode.OK, aliceReads.status)
            assertEquals("Alice task", aliceReads.json().getValue("title").jsonPrimitive.content)

            val listed = Json.parseToJsonElement(client.read("/api/runs", email = ALICE).bodyAsText()).jsonArray
            assertEquals(listOf("Alice task"), listed.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        }
    }

    @Test
    fun `invalid title, key or body is 400 and nothing is written`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            val bad = listOf(
                """{"title":"   ","idempotencyKey":"k"}""",
                """{"title":"${"a".repeat(201)}","idempotencyKey":"k"}""",
                """{"title":"line\nbreak","idempotencyKey":"k"}""",
                """{"title":"bell\u0007","idempotencyKey":"k"}""",
                """{"title":"t","idempotencyKey":""}""",
                """{"title":"t","idempotencyKey":"${"k".repeat(129)}"}""",
                """{"title":"t"}""",
                "not-json",
            )
            for (body in bad) assertEquals(HttpStatusCode.BadRequest, client.submit(body).status, body)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `title of 200 characters is accepted`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db)
            assertEquals(HttpStatusCode.Accepted, client.submit("""{"title":"${"a".repeat(200)}","idempotencyKey":"max"}""").status)
        }
    }

}

/** Header-trust scheme (oauth2-proxy), development only. */
class OAuth2ProxyTaskApiRoutesTest : TaskApiRoutesContractTest() {
    override val identity: TaskApiIdentity = resolveTaskApiIdentity(
        mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "development"),
    )!!

    override fun credentialFor(email: String?): Map<String, String> = email?.let { mapOf("X-Auth-Request-Email" to it) } ?: emptyMap()
}

/**
 * oidc-jwt scheme: the caller presents a Keycloak access token that kivra verifies (no network, see [OidcTestTokens]).
 * Every contract test below must pass under this scheme too. The owner is the token's `iss` + `sub`, so each test
 * email gets its own `sub` ("sub-<local part>"); the email itself is only a claim that can change.
 */
class OidcJwtTaskApiRoutesTest : TaskApiRoutesContractTest() {
    override val identity: TaskApiIdentity = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks()).asIdentity()

    override fun credentialFor(email: String?): Map<String, String> =
        email?.let { mapOf("Authorization" to "Bearer ${tokenFor(it)}") } ?: emptyMap()

    private fun tokenFor(email: String, emailVerified: Boolean? = true): String =
        OidcTestTokens.token(email = email, emailVerified = emailVerified, subject = "sub-${email.substringBefore("@")}")

    private suspend fun HttpResponse.oidcRunId(): String = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("runId").jsonPrimitive.content

    private fun ApplicationTestBuilder.setUpOidcRoutes(db: TestDb) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureTaskApiRoutes(db.runs, identity) }
        }
    }

    @Test
    fun `a forged X-Auth-Request-Email header without a token is 401`() = TestDb().use { db ->
        testApplication {
            setUpOidcRoutes(db)
            val forged = client.post("/api/tasks") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("X-Auth-Request-Email", "admin@example.com")
                header("X-Auth-Request-User", "admin")
                setBody("""{"title":"forged","idempotencyKey":"forged-1"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, forged.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/runs") {
                header("X-Auth-Request-Email", "admin@example.com")
            }.status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `changing the email or username keeps access to the same runs`() = TestDb().use { db ->
        testApplication {
            setUpOidcRoutes(db)
            val runId = client.post("/api/tasks") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("Authorization", "Bearer ${OidcTestTokens.token(email = "alice@example.com", preferredUsername = "alice")}")
                setBody("""{"title":"before rename","idempotencyKey":"rename-1"}""")
            }.oidcRunId()
            // Same sub, new email and username: still the owner.
            val renamed = "Bearer ${OidcTestTokens.token(email = "alice.new@example.com", preferredUsername = "alice2")}"
            assertEquals(HttpStatusCode.OK, client.get("/api/runs/$runId") { header("Authorization", renamed) }.status)
        }
    }

    @Test
    fun `an email reused by another sub cannot read the old owner's runs`() = TestDb().use { db ->
        testApplication {
            setUpOidcRoutes(db)
            val runId = client.post("/api/tasks") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("Authorization", "Bearer ${OidcTestTokens.token(subject = "sub-old", email = "reused@example.com")}")
                setBody("""{"title":"old owner","idempotencyKey":"reuse-1"}""")
            }.oidcRunId()
            val newOwner = "Bearer ${OidcTestTokens.token(subject = "sub-new", email = "reused@example.com")}"
            assertEquals(HttpStatusCode.NotFound, client.get("/api/runs/$runId") { header("Authorization", newOwner) }.status)
            assertEquals("[]", client.get("/api/runs") { header("Authorization", newOwner) }.bodyAsText())
        }
    }

    @Test
    fun `a run owned by a legacy email principal is unreachable for every sub (404)`() = TestDb().use { db ->
        testApplication {
            setUpOidcRoutes(db)
            // Pre-#120 owner: the email itself, not an iss+sub key. It is not mapped to any sub (no automatic backfill).
            val legacy = db.runs.submitTask("alice@example.com", "legacy-1", "legacy task")
            val legacyRunId = (legacy as TaskSubmitResult.Created).run.runId.value
            val alice = "Bearer ${tokenFor("alice")}"
            assertEquals(HttpStatusCode.NotFound, client.get("/api/runs/$legacyRunId") { header("Authorization", alice) }.status)
            assertEquals("[]", client.get("/api/runs") { header("Authorization", alice) }.bodyAsText())
        }
    }

    @Test
    fun `the owner is the sub - the same sub sees the run whatever its email, another sub does not`() = TestDb().use { db ->
        testApplication {
            setUpOidcRoutes(db)
            // Default test subject is "sub-alice", email unverified.
            assertEquals(HttpStatusCode.Accepted, client.post("/api/tasks") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("Authorization", "Bearer ${OidcTestTokens.token(emailVerified = false, preferredUsername = "alice")}")
                setBody("""{"title":"unverified","idempotencyKey":"u-1"}""")
            }.status)
            val sameSub = Json.parseToJsonElement(client.get("/api/runs") {
                header("Authorization", "Bearer ${OidcTestTokens.token(email = "other@example.com", preferredUsername = "other")}")
            }.bodyAsText()).jsonArray
            assertEquals(listOf("unverified"), sameSub.map { it.jsonObject.getValue("title").jsonPrimitive.content })
            val otherSub = client.get("/api/runs") {
                header("Authorization", "Bearer ${OidcTestTokens.token(subject = "sub-bob", emailVerified = false, preferredUsername = "alice")}")
            }
            assertEquals("[]", otherSub.bodyAsText())
        }
    }
}

class TaskApiModuleTest {
    @Test
    fun `module without KIVRA_AUTH_MODE exposes no Task API`() = testApplication {
        environment { config = h2Config("kivra_route_absent") }
        application { appModule(emptyMap()) }
        assertEquals(HttpStatusCode.NotFound, client.submitWith("""{"title":"t","idempotencyKey":"k"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.readWith("/api/runs").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `module with oauth2-proxy requires an identity`() = testApplication {
        environment { config = h2Config("kivra_route_oauth") }
        application { appModule(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "development")) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/runs") { header("X-Auth-Request-Email", "") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/runs") { header("X-Auth-Request-Email", ALICE) }.status)
    }

    @Test
    fun `module with none in development accepts unauthenticated submits`() = testApplication {
        environment { config = h2Config("kivra_route_none") }
        application { appModule(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development")) }
        assertEquals(HttpStatusCode.Accepted, client.submitWith("""{"title":"dev task","idempotencyKey":"dev-1"}""", email = null).status)
    }

    @Test
    fun `module with oauth2-proxy outside development does not start`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                environment { config = h2Config("kivra_route_prod") }
                application { appModule(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy")) }
                startApplication()
            }
        }
    }

    @Test
    fun `module with oidc-jwt without required settings does not start`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                environment { config = h2Config("kivra_route_oidc_missing") }
                application { appModule(mapOf("KIVRA_AUTH_MODE" to "oidc-jwt")) }
                startApplication()
            }
        }
    }

    @Test
    fun `module with oidc-jwt requires a bearer token`() = testApplication {
        environment { config = h2Config("kivra_route_oidc") }
        application {
            appModule(
                mapOf(
                    "KIVRA_AUTH_MODE" to "oidc-jwt",
                    "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
                    "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
                    "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/runs") { header("X-Auth-Request-Email", ALICE) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/runs").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}

private suspend fun HttpClient.submitWith(body: String, email: String? = ALICE): HttpResponse = post("/api/tasks") {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    email?.let { header("X-Auth-Request-Email", it) }
    setBody(body)
}

private suspend fun HttpClient.readWith(path: String, email: String? = ALICE): HttpResponse = get(path) {
    email?.let { header("X-Auth-Request-Email", it) }
}

private fun h2Config(name: String) = MapApplicationConfig(
    "kivra.database.jdbcUrl" to "jdbc:h2:mem:${name}_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "kivra.database.user" to "sa",
    "kivra.database.password" to "",
    "kivra.lease.ttlSeconds" to "30",
    "kivra.lease.graceSeconds" to "5",
)
