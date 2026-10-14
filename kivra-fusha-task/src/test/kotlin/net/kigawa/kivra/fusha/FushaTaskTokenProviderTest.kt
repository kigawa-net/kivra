package net.kigawa.kivra.fusha

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskProviderException
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TOKEN_TASK_1 = """{"id":1,"title":"Task one","description":null,"assigneeId":"alice","reporterId":"r","reporterName":"R","dueDate":null,"customFields":{},"sortOrder":0,"relatedTaskIds":[],"groupId":null,"viewerGroupId":null,"nextTaskIds":[],"createdAt":"2026-09-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z"}"""

private fun tokenConfig(retries: Int = 2) = FushaTaskConfig(
    baseUrl = "https://fusha.test/api/v1", environment = ContractFixture.ENV, staticToken = "tok",
    maxRetries = retries, initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofMillis(400),
)

/** Token provider whose behaviour is fixed by the test; records the forceRefresh flag of each call. */
private class ScriptedTokens(
    val onCall: (forceRefresh: Boolean) -> String,
) : TokenProvider {
    val flags = mutableListOf<Boolean>()
    override suspend fun token(forceRefresh: Boolean): String {
        flags += forceRefresh
        return onCall(forceRefresh)
    }
}

private class TokenHarness(engine: MockEngine, tokens: TokenProvider, cfg: FushaTaskConfig = tokenConfig()) {
    val sleeps = mutableListOf<Long>()
    val adapter = FushaTaskAdapter(cfg, FushaTaskAdapter.createClient(cfg, engine), tokens, sleep = { sleeps += it }, jitter = { 1.0 })
}

class FushaTaskTokenProviderTest {

    @Test fun `token provider IllegalStateException becomes TaskProviderException and sends no request`() = runTest {
        var requests = 0
        val tokens = ScriptedTokens { throw IllegalStateException("no credential") }
        val h = TokenHarness(MockEngine { requests++; respond(TOKEN_TASK_1, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }, tokens)
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.UNAUTHORIZED, e.kind)
        assertEquals(0, requests)
    }

    @Test fun `token provider IOException is retried and then becomes TaskProviderException without any request`() = runTest {
        var requests = 0
        var calls = 0
        val tokens = ScriptedTokens { calls++; throw IOException("token endpoint down") }
        val h = TokenHarness(MockEngine { requests++; respond("", HttpStatusCode.OK) }, tokens)
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.TRANSIENT, e.kind)
        assertEquals(0, requests)
        assertEquals(3, calls)
    }

    @Test fun `token provider failure message and stack trace never contain the token or the exception message`() = runTest {
        val secret = "secret-token-xyz-987"
        val tokens = ScriptedTokens { throw IllegalStateException("bad credential $secret https://fusha.test/api/v1?token=$secret") }
        val h = TokenHarness(MockEngine { respond("", HttpStatusCode.OK) }, tokens)
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertFalse(secret in (e.message ?: ""))
        assertFalse(secret in e.toString())
        assertFalse(secret in e.stackTraceToString())
        assertFalse("fusha.test" in (e.message ?: ""))
    }

    @Test fun `CancellationException from the token provider propagates unwrapped and sends no request`() = runTest {
        var requests = 0
        val tokens = ScriptedTokens { throw CancellationException("cancelled") }
        val h = TokenHarness(MockEngine { requests++; respond("", HttpStatusCode.OK) }, tokens)
        val e = assertFailsWith<CancellationException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(0, requests)
    }

    @Test fun `empty or blank token is rejected before any request`() = runTest {
        for (blank in listOf("", "   ", "\t\n")) {
            var requests = 0
            val h = TokenHarness(MockEngine { requests++; respond("", HttpStatusCode.OK) }, ScriptedTokens { blank })
            val e = assertFailsWith<TaskProviderException> { h.adapter.list() }
            assertEquals(TaskErrorKind.UNAUTHORIZED, e.kind)
            assertEquals(0, requests, "no request must be sent for token '${blank.length}' chars")
        }
    }

    @Test fun `401 then forced refresh then retry still succeeds with a valid token`() = runTest {
        val used = mutableListOf<String>()
        val tokens = ScriptedTokens { if (it) "fresh" else "stale" }
        val h = TokenHarness(MockEngine {
            val auth = it.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
            used += auth
            if (auth == "stale") respond("", HttpStatusCode.Unauthorized)
            else respond(TOKEN_TASK_1, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }, tokens)
        assertNotNull(h.adapter.get(ContractFixture.id(1)))
        assertEquals(listOf("stale", "fresh"), used)
        assertEquals(listOf(false, true), tokens.flags)
        assertTrue(h.sleeps.isEmpty())
    }

    @Test fun `forced refresh that throws does not loop and sends only the first request`() = runTest {
        var requests = 0
        val tokens = ScriptedTokens { if (it) throw IllegalStateException("refresh failed") else "stale" }
        val h = TokenHarness(MockEngine { requests++; respond("", HttpStatusCode.Unauthorized) }, tokens)
        val e = assertFailsWith<TaskProviderException> { h.adapter.get(ContractFixture.id(1)) }
        assertEquals(TaskErrorKind.UNAUTHORIZED, e.kind)
        assertEquals(1, requests)
        assertEquals(listOf(false, true), tokens.flags)
    }
}
