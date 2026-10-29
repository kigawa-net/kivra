package net.kigawa.kivra.fusha

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskProviderException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private const val T1 = """{"id":1,"title":"Task one","description":null,"assigneeId":"alice","reporterId":"r","reporterName":"R","dueDate":null,"customFields":{"status":"ready"},"sortOrder":0,"relatedTaskIds":[],"groupId":null,"viewerGroupId":null,"nextTaskIds":[2],"createdAt":"2026-09-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z"}"""
private const val T2 = """{"id":2,"title":"Task two","description":"d","assigneeId":"bob","reporterId":"r","reporterName":"R","dueDate":"2026-12-01","customFields":{},"sortOrder":1,"relatedTaskIds":[],"groupId":null,"viewerGroupId":null,"nextTaskIds":[],"createdAt":"2026-09-01T00:00:00.000Z","updatedAt":"2026-10-02T12:34:56.789Z"}"""
private const val T3 = """{"id":3,"title":"Task three","description":null,"assigneeId":"alice","reporterId":"r","reporterName":"R","dueDate":null,"customFields":{},"sortOrder":2,"relatedTaskIds":[],"groupId":null,"viewerGroupId":null,"nextTaskIds":[],"createdAt":"2026-09-01T00:00:00.000Z","updatedAt":"2026-10-02T12:34:56.789Z"}"""

/** Emulates the fusha-task endpoints used by the adapter, using the same seed as the Fake contract. */
private fun MockRequestHandleScope.fakeServer(req: HttpRequestData): HttpResponseData {
    val path = req.url.encodedPath
    val tasks = mapOf(1L to ("alice" to T1), 2L to ("bob" to T2), 3L to ("alice" to T3))
    // POST /tasks/:id/comments
    if (req.method.value == "POST" && path.endsWith("/comments")) {
        val id = path.substringAfter("/tasks/").substringBefore("/comments").toLongOrNull()
        if (id == null || !tasks.containsKey(id)) {
            return respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON_HEADERS)
        }
        return respond("""{"id":1,"body":"test","author_id":"svc","created_at":"2026-10-15T00:00:00.000Z"}""", HttpStatusCode.Created, JSON_HEADERS)
    }
    // PATCH /tasks/:id
    if (req.method.value == "PATCH") {
        val id = path.substringAfterLast('/').toLongOrNull()
        val t = tasks[id]
        if (t == null) return respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON_HEADERS)
        return respond(t.second, HttpStatusCode.OK, JSON_HEADERS)
    }
    if (path.endsWith("/tasks")) {
        val assignee = req.url.parameters["assigneeId"]
        val view = req.url.parameters["viewId"]?.toLong()
        val body = tasks.filter { (id, v) ->
            (assignee == null || v.first == assignee) && (view == null || (view == 10L && id in setOf(1L, 2L)))
        }.values.joinToString(",", "[", "]") { it.second }
        return respond(body, HttpStatusCode.OK, JSON_HEADERS)
    }
    val id = path.substringAfterLast('/').toLongOrNull()
    val t = tasks[id] ?: return respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON_HEADERS)
    return respond(t.second, HttpStatusCode.OK, JSON_HEADERS)
}

private fun config(retries: Int = 3) = FushaTaskConfig(
    baseUrl = "https://fusha.test/api/v1", environment = ContractFixture.ENV, staticToken = "tok",
    maxRetries = retries, initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofMillis(400),
)

private class Harness(
    val engine: MockEngine,
    val tokens: TokenProvider = StaticTokenProvider("tok"),
    cfg: FushaTaskConfig = config(),
) {
    val sleeps = mutableListOf<Long>()
    val adapter = FushaTaskAdapter(cfg, FushaTaskAdapter.createClient(cfg, engine), tokens, sleep = { sleeps += it }, jitter = { 1.0 })
}

class FushaTaskAdapterContractTest : TaskProviderContract() {
    override fun provider(): TaskProvider = Harness(MockEngine { fakeServer(it) }).adapter
}

class FushaTaskAdapterTest {
    @Test fun `sends bearer token and hits api v1 paths`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val h = Harness(MockEngine { seen += it; fakeServer(it) })
        h.adapter.get(ContractFixture.id(1))
        h.adapter.list(TaskFilter(assigneeId = "alice", viewId = 10))
        assertEquals("Bearer tok", seen[0].headers[HttpHeaders.Authorization])
        assertEquals("/api/v1/tasks/1", seen[0].url.encodedPath)
        assertEquals("/api/v1/tasks", seen[1].url.encodedPath)
        assertEquals("alice", seen[1].url.parameters["assigneeId"])
        assertEquals("10", seen[1].url.parameters["viewId"])
        assertTrue(seen.all { it.method.value == "GET" })
    }

    @Test fun `retries 5xx with exponential backoff then succeeds`() = runTest {
        var n = 0
        val h = Harness(MockEngine { if (n++ < 2) respond("", HttpStatusCode.ServiceUnavailable) else fakeServer(it) })
        assertNotNull(h.adapter.get(ContractFixture.id(1)))
        assertEquals(3, n)
        assertEquals(listOf(100L, 200L), h.sleeps)
    }

    @Test fun `gives up after max retries as TRANSIENT`() = runTest {
        var n = 0
        val h = Harness(MockEngine { n++; respond("", HttpStatusCode.InternalServerError) }, cfg = config(retries = 2))
        val e = assertFailsWith<TaskProviderException> { h.adapter.list() }
        assertEquals(TaskErrorKind.TRANSIENT, e.kind)
        assertEquals(3, n)
        assertEquals(listOf(100L, 200L), h.sleeps)
    }

    @Test fun `retries IO errors`() = runTest {
        var n = 0
        val h = Harness(MockEngine { if (n++ == 0) throw IOException("boom") else fakeServer(it) })
        assertNotNull(h.adapter.get(ContractFixture.id(2)))
        assertEquals(2, n)
    }

    @Test fun `backoff is capped`() = runTest {
        val h = Harness(MockEngine { respond("", HttpStatusCode.BadGateway) }, cfg = config(retries = 4))
        assertFailsWith<TaskProviderException> { h.adapter.list() }
        assertEquals(listOf(100L, 200L, 400L, 400L), h.sleeps)
    }

    @Test fun `429 is retried`() = runTest {
        var n = 0
        val h = Harness(MockEngine { if (n++ == 0) respond("", HttpStatusCode.TooManyRequests) else fakeServer(it) })
        assertNotNull(h.adapter.get(ContractFixture.id(1)))
    }

    @Test fun `400 and 403 are not retried`() = runTest {
        for ((status, kind) in listOf(HttpStatusCode.BadRequest to TaskErrorKind.REJECTED, HttpStatusCode.Forbidden to TaskErrorKind.FORBIDDEN)) {
            var n = 0
            val h = Harness(MockEngine { n++; respond("""{"error":"x"}""", status, JSON_HEADERS) })
            val e = assertFailsWith<TaskProviderException> { h.adapter.list() }
            assertEquals(kind, e.kind)
            assertEquals(status.value, e.httpStatus)
            assertEquals(1, n)
            assertTrue(h.sleeps.isEmpty())
        }
    }

    @Test fun `401 refreshes token once and retries`() = runTest {
        val used = mutableListOf<String>()
        val tokens = object : TokenProvider {
            var calls = 0
            override suspend fun token(forceRefresh: Boolean) = if (forceRefresh) "fresh" else "stale"
        }
        val h = Harness(MockEngine {
            val auth = it.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
            used += auth
            if (auth == "stale") respond("", HttpStatusCode.Unauthorized) else fakeServer(it)
        }, tokens)
        assertNotNull(h.adapter.get(ContractFixture.id(1)))
        assertEquals(listOf("stale", "fresh"), used)
    }

    @Test fun `persistent 401 fails with UNAUTHORIZED after a single refresh`() = runTest {
        var n = 0
        val h = Harness(MockEngine { n++; respond("", HttpStatusCode.Unauthorized) })
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.UNAUTHORIZED, e.kind)
        assertEquals(2, n)
    }

    @Test fun `list 404 (view not found) is an error not empty`() = runTest {
        val h = Harness(MockEngine { respond("""{"error":"view not found"}""", HttpStatusCode.NotFound, JSON_HEADERS) })
        assertFailsWith<TaskProviderException> { h.adapter.list(TaskFilter(viewId = 99)) }
    }

    @Test fun `get 404 returns null`() = runTest {
        val h = Harness(MockEngine { respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON_HEADERS) })
        assertNull(h.adapter.get(ContractFixture.id(5)))
    }

    @Test fun `malformed body is PROTOCOL`() = runTest {
        val h = Harness(MockEngine { respond("not json", HttpStatusCode.OK, JSON_HEADERS) })
        assertEquals(TaskErrorKind.PROTOCOL, assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }.kind)
    }

    @Test fun `bad updatedAt is PROTOCOL`() = runTest {
        val h = Harness(MockEngine { respond(T1.replace("2026-10-01T00:00:00.000Z\"}", "yesterday\"}"), HttpStatusCode.OK, JSON_HEADERS) })
        assertEquals(TaskErrorKind.PROTOCOL, assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }.kind)
    }

    @Test fun `foreign environment or malformed TaskId is rejected without a request`() = runTest {
        var n = 0
        val h = Harness(MockEngine { n++; fakeServer(it) })
        for (bad in listOf("fusha:prod:1", "1", "fusha:test:abc")) {
            assertEquals(TaskErrorKind.REJECTED, assertFailsWith<TaskProviderException> { h.adapter.get(net.kigawa.kivra.core.TaskId(bad)) }.kind)
        }
        assertEquals(0, n)
    }

    @Test fun `get with mismatched response id is PROTOCOL`() = runTest {
        // Request task 1 but the server answers with task 2.
        val h = Harness(MockEngine { respond(T2, HttpStatusCode.OK, JSON_HEADERS) })
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.PROTOCOL, e.kind)
    }

    @Test fun `get with non-positive response id is PROTOCOL`() = runTest {
        val h = Harness(MockEngine { respond(T1.replaceFirst("\"id\":1,", "\"id\":0,"), HttpStatusCode.OK, JSON_HEADERS) })
        assertEquals(TaskErrorKind.PROTOCOL, assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }.kind)
    }

    @Test fun `non-positive requested id is rejected without a request`() = runTest {
        var n = 0
        val h = Harness(MockEngine { n++; fakeServer(it) })
        assertEquals(TaskErrorKind.REJECTED, assertFailsWith<TaskProviderException> { h.adapter.get(net.kigawa.kivra.core.TaskId("fusha:${ContractFixture.ENV}:0")) }.kind)
        assertEquals(0, n)
    }

    @Test fun `list with duplicate ids fails closed`() = runTest {
        val h = Harness(MockEngine { respond("[$T1,$T2,$T1]", HttpStatusCode.OK, JSON_HEADERS) })
        assertEquals(TaskErrorKind.PROTOCOL, assertFailsWith<TaskProviderException> { h.adapter.list() }.kind)
    }

    @Test fun `list with invalid id fails closed`() = runTest {
        val h = Harness(MockEngine { respond("[$T1," + T2.replaceFirst("\"id\":2,", "\"id\":-5,") + "]", HttpStatusCode.OK, JSON_HEADERS) })
        assertEquals(TaskErrorKind.PROTOCOL, assertFailsWith<TaskProviderException> { h.adapter.list() }.kind)
    }

    @Test fun `redirect to another host is not followed and token is not forwarded`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val h = Harness(MockEngine {
            seen += it
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/steal"))
        })
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.PROTOCOL, e.kind)
        assertEquals(1, seen.size)
        assertTrue(seen.none { it.url.host == "evil.example" })
    }

    @Test fun `base url validation`() {
        fun cfg(url: String, insecure: Boolean = false) =
            FushaTaskConfig(baseUrl = url, environment = "dev", staticToken = "tok", allowInsecureHttp = insecure)
        cfg("https://fusha.example/api/v1")
        cfg("http://localhost:8787/api/v1")
        cfg("http://127.0.0.1:8787/api/v1")
        cfg("http://[::1]:8787/api/v1")
        cfg("http://remote.example/api/v1", insecure = true)
        listOf(
            "http://remote.example/api/v1",
            "ftp://fusha.example/api/v1",
            "https://user:pw@fusha.example/api/v1",
            "https://fusha.example/api/v1?token=x",
            "https://fusha.example/api/v1#frag",
            "https:///api/v1",
            "not a url",
        ).forEach { u -> assertFailsWith<IllegalArgumentException>(u) { cfg(u) } }
    }

    @Test fun `token provider and config strings never contain the token`() {
        val cfg = config()
        assertTrue("tok" !in cfg.toString().replace("staticToken", ""))
        assertTrue("secret-token" !in StaticTokenProvider("secret-token").toString())
    }

    @Test fun `401 asks the token provider for a forced refresh exactly once and never without one first`() = runTest {
        val flags = mutableListOf<Boolean>()
        val tokens = object : TokenProvider {
            override suspend fun token(forceRefresh: Boolean): String {
                flags += forceRefresh
                return if (forceRefresh) "fresh" else "stale"
            }
        }
        var n = 0
        val h = Harness(MockEngine { n++; respond("", HttpStatusCode.Unauthorized) }, tokens)
        assertEquals(TaskErrorKind.UNAUTHORIZED, assertFailsWith<TaskProviderException> { h.adapter.list() }.kind)
        assertEquals(listOf(false, true), flags)
        assertEquals(2, n)
        assertTrue(h.sleeps.isEmpty())
    }

    @Test fun `401 on list also refreshes once and succeeds`() = runTest {
        val used = mutableListOf<String>()
        val tokens = object : TokenProvider {
            override suspend fun token(forceRefresh: Boolean) = if (forceRefresh) "fresh" else "stale"
        }
        val h = Harness(MockEngine {
            val auth = it.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
            used += auth
            if (auth == "stale") respond("", HttpStatusCode.Unauthorized) else fakeServer(it)
        }, tokens)
        assertEquals(3, h.adapter.list().size)
        assertEquals(listOf("stale", "fresh"), used)
    }

    @Test fun `persistent 401 leaks no token into exception message, cause or stack trace`() = runTest {
        val secret = "secret-xyz-123"
        val h = Harness(MockEngine { respond("", HttpStatusCode.Unauthorized) }, StaticTokenProvider(secret))
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertFalse(secret in e.toString())
        assertFalse(secret in (e.message ?: ""))
        assertFalse(secret in e.stackTraceToString())
    }

    @Test fun `persistent 429 is TRANSIENT after maxRetries and sends no more requests`() = runTest {
        var n = 0
        val h = Harness(MockEngine { n++; respond("", HttpStatusCode.TooManyRequests) }, cfg = config(retries = 2))
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.TRANSIENT, e.kind)
        assertEquals(429, e.httpStatus)
        assertEquals(3, n)
        assertEquals(listOf(100L, 200L), h.sleeps)
    }

    @Test fun `socket timeout is retried and then TRANSIENT`() = runTest {
        var n = 0
        val h = Harness(MockEngine { n++; throw SocketTimeoutException("read timed out") }, cfg = config(retries = 2))
        assertEquals(TaskErrorKind.TRANSIENT, assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }.kind)
        assertEquals(3, n)
    }

    @Test fun `token provider failure sends no request (fail closed)`() = runTest {
        var n = 0
        val tokens = object : TokenProvider {
            override suspend fun token(forceRefresh: Boolean): String = throw IllegalStateException("no credential")
        }
        val h = Harness(MockEngine { n++; fakeServer(it) }, tokens)
        assertFails { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(0, n)
    }

    @Test fun `token fetch IO failure is retried and no request is sent without a token`() = runTest {
        var n = 0
        val tokens = object : TokenProvider {
            override suspend fun token(forceRefresh: Boolean): String = throw IOException("token endpoint down")
        }
        val h = Harness(MockEngine { n++; fakeServer(it) }, tokens, config(retries = 2))
        assertEquals(TaskErrorKind.TRANSIENT, assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }.kind)
        assertEquals(0, n)
    }

    @Test fun `fetchedAt comes from the injected clock`() = runTest {
        val fixed = Instant.parse("2026-10-15T01:02:03Z")
        val cfg = config()
        val adapter = FushaTaskAdapter(
            cfg,
            FushaTaskAdapter.createClient(cfg, MockEngine { fakeServer(it) }),
            StaticTokenProvider("tok"),
            clock = Clock.fixed(fixed, ZoneOffset.UTC),
        )
        assertEquals(fixed, adapter.get(ContractFixture.id(1))!!.fetchedAt)
    }

    @Test fun `config toString hides token`() {
        assertTrue(!config().toString().contains("tok\""), config().toString())
        assertTrue(!config().toString().contains("staticToken=tok"))
    }
}
