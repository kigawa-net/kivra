package net.kigawa.kivra.memory.recl

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.DeleteMode
import net.kigawa.kivra.memory.MemoryCapability
import net.kigawa.kivra.memory.MemoryEntry
import net.kigawa.kivra.memory.MemoryErrorKind
import net.kigawa.kivra.memory.MemoryException
import net.kigawa.kivra.memory.MemoryId
import net.kigawa.kivra.memory.MemoryKind
import net.kigawa.kivra.memory.MemoryPatch
import net.kigawa.kivra.memory.MemoryProvenance
import net.kigawa.kivra.memory.MemoryQuery
import net.kigawa.kivra.memory.MemoryStatus
import net.kigawa.kivra.memory.ProviderHealth
import net.kigawa.kivra.memory.ScopeRef
import net.kigawa.kivra.memory.Sensitivity
import net.kigawa.kivra.memory.T
import net.kigawa.kivra.memory.WriteProvenance
import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReclAdapterTest {
    private val ctx: AccessContext = T.ctx(T.tenant)
    private val secretToken = "tok-SUPER-SECRET-123456"
    // Mutations are enabled only in this test fixture; the default config must refuse them (see the guard test).
    private val cfg = ReclConfig("https://recl.example.test", mutationsEnabled = true)

    private fun adapter(engine: MockEngine, config: ReclConfig = cfg, sleeps: MutableList<Long>? = null) =
        ReclAdapter(config, engine, { secretToken }, sleep = { sleeps?.add(it) }, jitter = { 0.0 })

    private fun stored(id: String = "m1", version: Int = 1, scope: ScopeRef = T.project, sensitivity: Sensitivity = Sensitivity.INTERNAL,
                       status: MemoryStatus = MemoryStatus.ACTIVE, kind: MemoryKind = MemoryKind.SEMANTIC, pinned: Boolean = false) = MemoryEntry(
        MemoryId(id), version, status, kind, listOf(scope), "title $id", "summary jdk $id", "body", listOf(T.source()),
        MemoryProvenance.PRIMARY_SOURCE, 0.9, 0.5, sensitivity, null, null, "default", emptyList(), pinned, T.at, T.at, WriteProvenance("task-1", "exec-1", "agent:dev"),
    )

    @Test
    fun `config requires https and rejects credentials, query and fragment`() {
        assertFailsWith<IllegalArgumentException> { ReclConfig("http://recl.example.test") }
        assertFailsWith<IllegalArgumentException> { ReclConfig("https://user:pw@recl.example.test") }
        assertFailsWith<IllegalArgumentException> { ReclConfig("https://recl.example.test?x=1") }
        assertFailsWith<IllegalArgumentException> { ReclConfig("https://recl.example.test#f") }
        assertFailsWith<IllegalArgumentException> { ReclConfig("not a url") }
    }

    @Test
    fun `no capabilities are claimed until confirmed`() {
        val a = adapter(MockEngine { respond("{}") })
        assertTrue(a.capabilities.isEmpty())
        assertEquals(setOf(MemoryCapability.IDEMPOTENT_SAVE, MemoryCapability.REDACT), adapter(MockEngine { respond("{}") }, ReclConfig("https://r.test", idempotentSaveConfirmed = true, redactSupported = true)).capabilities)
    }

    @Test
    fun `mutations are refused by default before any request is sent`() = runTest {
        var calls = 0
        val a = adapter(MockEngine { calls++; respond("{}", HttpStatusCode.Created, jsonHeaders) }, ReclConfig("https://recl.example.test"))
        val key = T.key("k")
        assertEquals(MemoryErrorKind.UNSUPPORTED, assertFailsWith<MemoryException> { a.save(T.entry(), key, ctx) }.kind)
        assertEquals(MemoryErrorKind.UNSUPPORTED, assertFailsWith<MemoryException> { a.update(MemoryId("m1"), 1, MemoryPatch(summary = "x"), ctx) }.kind)
        assertEquals(MemoryErrorKind.UNSUPPORTED, assertFailsWith<MemoryException> { a.supersede(MemoryId("m1"), 1, T.entry(), key, ctx) }.kind)
        assertEquals(MemoryErrorKind.UNSUPPORTED, assertFailsWith<MemoryException> { a.delete(MemoryId("m1"), DeleteMode.SOFT, ctx) }.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `redirects are not followed and the token is not forwarded`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/steal")) }
        val ex = assertFailsWith<MemoryException> { adapter(engine).get(MemoryId("m1"), ctx) }
        assertEquals(MemoryErrorKind.PROTOCOL, ex.kind)
        assertEquals(1, calls)
        assertTrue(engine.requestHistory.none { it.url.host == "evil.example" })
    }

    @Test
    fun `POST is never auto re-sent`() = runTest {
        for ((status, kind) in listOf(HttpStatusCode.ServiceUnavailable to MemoryErrorKind.OUTCOME_UNKNOWN, HttpStatusCode.TooManyRequests to MemoryErrorKind.UNAVAILABLE)) {
            var calls = 0
            val engine = MockEngine { calls++; respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) }
            val ex = assertFailsWith<MemoryException> { adapter(engine).save(T.entry(), T.key("k"), ctx) }
            assertEquals(kind, ex.kind)
            assertEquals(1, calls, "status $status")
        }
        var calls = 0
        val a = adapter(MockEngine { calls++; respond("{}", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<MemoryException> { a.search(MemoryQuery("jdk", listOf(T.tenant)), ctx) }
        assertFailsWith<MemoryException> { a.update(MemoryId("m1"), 1, MemoryPatch(summary = "x"), ctx) }
        assertFailsWith<MemoryException> { a.delete(MemoryId("m1"), DeleteMode.SOFT, ctx) }
        assertEquals(3, calls)
    }

    @Test
    fun `GET is retried with backoff and then succeeds`() = runTest {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val engine = MockEngine {
            calls++
            if (calls < 3) respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(testJson.encodeToString(reclContext(stored())), HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("m1", adapter(engine, sleeps = sleeps).get(MemoryId("m1"), ctx)?.id?.value)
        assertEquals(3, calls)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun `GET gives up after the attempt budget and 4xx is not retried`() = runTest {
        var calls = 0
        val a = adapter(MockEngine { calls++; respond("{}", HttpStatusCode.InternalServerError, jsonHeaders) })
        assertEquals(MemoryErrorKind.OUTCOME_UNKNOWN, assertFailsWith<MemoryException> { a.get(MemoryId("m1"), ctx) }.kind)
        assertEquals(3, calls)
        calls = 0
        val b = adapter(MockEngine { calls++; respond("{}", HttpStatusCode.Forbidden, jsonHeaders) })
        assertEquals(MemoryErrorKind.FORBIDDEN, assertFailsWith<MemoryException> { b.get(MemoryId("m1"), ctx) }.kind)
        assertEquals(1, calls)
    }

    @Test
    fun `get verifies the response identity`() = runTest {
        val a = adapter(MockEngine { respond(testJson.encodeToString(reclContext(stored(id = "other"))), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { a.get(MemoryId("m1"), ctx) }.kind)
    }

    @Test
    fun `get refuses a response outside the allowed scopes`() = runTest {
        val a = adapter(MockEngine { respond(testJson.encodeToString(reclContext(stored(scope = T.otherTenant))), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { a.get(MemoryId("m1"), ctx) }.kind)
    }

    @Test
    fun `get returns null on 404 and for deleted entries`() = runTest {
        assertNull(adapter(MockEngine { respond("{}", HttpStatusCode.NotFound, jsonHeaders) }).get(MemoryId("m1"), ctx))
        assertNull(adapter(MockEngine { respond(testJson.encodeToString(reclContext(stored(status = MemoryStatus.DELETED))), HttpStatusCode.OK, jsonHeaders) }).get(MemoryId("m1"), ctx))
    }

    @Test
    fun `search drops hits that violate scope, sensitivity, kind, status, duplicates and malformed rows`() = runTest {
        val good = stored("good")
        val hits = listOf(
            ReclSearchHit(reclContext(good), 0.9),
            ReclSearchHit(reclContext(good), 0.8), // duplicate id
            ReclSearchHit(reclContext(stored("leak", scope = T.otherTenant)), 0.99), // other tenant
            ReclSearchHit(reclContext(stored("secret", sensitivity = Sensitivity.RESTRICTED)), 0.7),
            ReclSearchHit(reclContext(stored("old", status = MemoryStatus.SUPERSEDED)), 0.7),
            ReclSearchHit(reclContext(stored("bad")).copy(kind = "nonsense"), 0.7), // malformed
            ReclSearchHit(reclContext(stored("proc", kind = MemoryKind.PROCEDURAL)), 0.6),
        )
        val a = adapter(MockEngine { respond(testJson.encodeToString(ReclSearchResponse(hits)), HttpStatusCode.OK, jsonHeaders) })
        val r = a.search(MemoryQuery("jdk", listOf(T.tenant), kinds = setOf(MemoryKind.SEMANTIC)), ctx)
        assertEquals(listOf("good"), r.map { it.entry.id.value })
        assertNull(r[0].entry.body)
    }

    @Test
    fun `search truncates to the limit`() = runTest {
        val hits = (1..5).map { ReclSearchHit(reclContext(stored("m$it")), 0.5) }
        val a = adapter(MockEngine { respond(testJson.encodeToString(ReclSearchResponse(hits)), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(2, a.search(MemoryQuery("jdk", listOf(T.tenant), limit = 2), ctx).size)
    }

    @Test
    fun `scope outside the access context is refused before any request`() = runTest {
        var calls = 0
        val a = adapter(MockEngine { calls++; respond("{}") })
        assertEquals(MemoryErrorKind.FORBIDDEN, assertFailsWith<MemoryException> { a.search(MemoryQuery("jdk", listOf(T.otherTenant)), ctx) }.kind)
        assertEquals(MemoryErrorKind.FORBIDDEN, assertFailsWith<MemoryException> { a.pinned(listOf(T.otherTenant), ctx) }.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `pinned filters non-pinned and out-of-scope entries`() = runTest {
        val resp = ReclPinnedResponse(listOf(reclContext(stored("p1", pinned = true)), reclContext(stored("np")), reclContext(stored("x", scope = T.otherTenant, pinned = true))))
        val a = adapter(MockEngine { respond(testJson.encodeToString(resp), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(listOf("p1"), a.pinned(listOf(T.tenant), ctx).map { it.id.value })
    }

    @Test
    fun `secrets are refused before anything is sent`() = runTest {
        var calls = 0
        val a = adapter(MockEngine { calls++; respond("{}") })
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4"
        val ex = assertFailsWith<MemoryException> { a.save(T.entry(summary = "deploy with $token ok"), T.key("k"), ctx) }
        assertEquals(MemoryErrorKind.REJECTED, ex.kind)
        assertFalse(ex.message!!.contains(token))
        assertFailsWith<MemoryException> { a.update(MemoryId("m1"), 1, MemoryPatch(body = "-----BEGIN RSA PRIVATE KEY-----"), ctx) }
        assertEquals(0, calls)
    }

    @Test
    fun `token and memory text never leak into messages, toString or config`() = runTest {
        val a = adapter(MockEngine { respond("""{"error":"echo $secretToken and the secret body"}""", HttpStatusCode.InternalServerError, jsonHeaders) })
        val ex = assertFailsWith<MemoryException> { a.save(T.entry(), T.key("k"), ctx) }
        assertFalse(ex.message!!.contains(secretToken))
        assertFalse(ex.message!!.contains("secret body"))
        assertFalse(cfg.toString().contains(secretToken))
        val q = MemoryQuery("my private question about the project", listOf(T.tenant))
        assertFalse(q.toString().contains("private question"))
        assertFalse(T.entry().toString().contains("JDK 21 because"))
    }

    @Test
    fun `bearer token is sent in the Authorization header only, with trace id and idempotency key`() = runTest {
        val engine = MockEngine { respond(testJson.encodeToString(ReclIdResponse("m9")), HttpStatusCode.Created, jsonHeaders) }
        assertEquals("m9", adapter(engine).save(T.entry(), T.key("key-1"), ctx).value)
        val req = engine.requestHistory.single()
        assertEquals("Bearer $secretToken", req.headers[HttpHeaders.Authorization])
        assertEquals("key-1", req.headers["Idempotency-Key"])
        assertEquals("trace-1", req.headers["X-Trace-Id"])
        assertEquals("https", req.url.protocol.name)
        assertFalse(req.url.toString().contains(secretToken))
    }

    @Test
    fun `invalid credentials are refused without a request`() = runTest {
        var calls = 0
        val a = ReclAdapter(cfg, MockEngine { calls++; respond("{}") }, { "bad token\r\nX-Evil: 1" }, sleep = {})
        assertEquals(MemoryErrorKind.FORBIDDEN, assertFailsWith<MemoryException> { a.get(MemoryId("m1"), ctx) }.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `redact is unsupported until confirmed and is never downgraded to soft delete`() = runTest {
        var calls = 0
        val a = adapter(MockEngine { calls++; respond("", HttpStatusCode.NoContent) })
        assertEquals(MemoryErrorKind.UNSUPPORTED, assertFailsWith<MemoryException> { a.delete(MemoryId("m1"), DeleteMode.REDACT, ctx) }.kind)
        assertEquals(0, calls)
        a.delete(MemoryId("m1"), DeleteMode.SOFT, ctx)
        assertEquals(1, calls)
    }

    @Test
    fun `non-json and oversize responses are protocol errors`() = runTest {
        val html = adapter(MockEngine { respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { html.get(MemoryId("m1"), ctx) }.kind)
        val big = adapter(MockEngine { respond("x".repeat(2000), HttpStatusCode.OK, jsonHeaders) }, ReclConfig("https://r.test", maxResponseBytes = 1000))
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { big.get(MemoryId("m1"), ctx) }.kind)
        val garbage = adapter(MockEngine { respond("{not json", HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { garbage.get(MemoryId("m1"), ctx) }.kind)
    }

    @Test
    fun `update requires the version to advance and the same id`() = runTest {
        val same = adapter(MockEngine { respond(testJson.encodeToString(reclContext(stored(version = 1))), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { same.update(MemoryId("m1"), 1, MemoryPatch(summary = "x"), ctx) }.kind)
        val other = adapter(MockEngine { respond(testJson.encodeToString(reclContext(stored(id = "zzz", version = 2))), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { other.update(MemoryId("m1"), 1, MemoryPatch(summary = "x"), ctx) }.kind)
    }

    @Test
    fun `supersede and save validate the returned id`() = runTest {
        val bad = adapter(MockEngine { respond("""{"id":"../etc/passwd"}""", HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { bad.save(T.entry(), T.key("k"), ctx) }.kind)
        val same = adapter(MockEngine { respond("""{"id":"m1"}""", HttpStatusCode.OK, jsonHeaders) })
        assertEquals(MemoryErrorKind.PROTOCOL, assertFailsWith<MemoryException> { same.supersede(MemoryId("m1"), 1, T.entry(), T.key("k"), ctx) }.kind)
    }

    @Test
    fun `network failures are classified by whether the request could have been applied`() = runTest {
        val refused = adapter(MockEngine { throw ConnectException("refused") })
        assertEquals(MemoryErrorKind.UNAVAILABLE, assertFailsWith<MemoryException> { refused.save(T.entry(), T.key("k"), ctx) }.kind)
        val reset = adapter(MockEngine { throw IOException("connection reset") })
        assertEquals(MemoryErrorKind.OUTCOME_UNKNOWN, assertFailsWith<MemoryException> { reset.save(T.entry(), T.key("k"), ctx) }.kind)
    }

    @Test
    fun `health never throws`() = runTest {
        assertEquals(ProviderHealth.DOWN, adapter(MockEngine { throw ConnectException("refused") }).health())
        assertEquals(ProviderHealth.DOWN, adapter(MockEngine { respond("{}", HttpStatusCode.InternalServerError, jsonHeaders) }).health())
        assertEquals(ProviderHealth.UP, adapter(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }).health())
    }

    @Test
    fun `memory ids are percent-encoded in the path`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.NotFound, jsonHeaders) }
        adapter(engine).get(MemoryId("a.b-c_d"), ctx)
        assertTrue(engine.requestHistory.single().url.encodedPath.endsWith("/contexts/a.b-c_d"))
        assertFailsWith<IllegalArgumentException> { MemoryId("../x") }
    }
}
