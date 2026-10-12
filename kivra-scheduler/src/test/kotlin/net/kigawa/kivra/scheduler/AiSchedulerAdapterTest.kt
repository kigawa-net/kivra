package net.kigawa.kivra.scheduler

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobRef
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleExecutionRef

class AiSchedulerAdapterTest {
    private val base = "http://ai-scheduler.internal:8080"
    private val cfg = AiSchedulerConfig(baseUrl = base, allowInsecureHttp = true)
    private val name = JobNaming.build("prod", "tick")
    private val jsonH = headersOf(HttpHeaders.ContentType, "application/json")

    private class Rec { val reqs = mutableListOf<HttpRequestData>(); fun count(m: HttpMethod) = reqs.count { it.method == m } }

    private fun adapter(
        rec: Rec = Rec(),
        tokens: ScheduleTokenProvider = ScheduleTokenProvider.NoAuth,
        config: AiSchedulerConfig = cfg,
        dedup: TriggerDedupStore = InMemoryTriggerDedupStore(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AiSchedulerAdapter {
        val engine = MockEngine { req -> rec.reqs += req; handler(req) }
        return AiSchedulerAdapter(aiSchedulerHttpClient(engine, config), config, tokens, backoff = {}, dedup = dedup)
    }

    private fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, jsonH)
    private fun jobJson(id: String, n: String = name, enabled: Boolean = true, trig: String = """{"type":"CRON","cronExpression":"*/5 * * * *","timezone":"UTC","lastRunAt":"2026-10-12T00:00:00Z"}""") =
        """{"id":"$id","name":"$n","enabled":$enabled,"prompt":"SECRET-PROMPT","trigger":$trig}"""
    private fun execJson(id: String, job: String = "j1", status: String = "RUNNING") =
        """{"id":"$id","jobId":"$job","status":"$status","output":"SECRET-OUT","scheduledAt":"2026-10-12T00:00:00Z"}"""

    private val spec get() = JobSpec(name, "*/5 * * * *", null)
    private val key = IdempotencyKey("upsert:$name")

    @Test fun `upsert creates when absent and sends idempotency key and deterministic name`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { req ->
            if (req.method == HttpMethod.Get) ok("[]") else ok(jobJson("j1"), HttpStatusCode.Created)
        }
        assertEquals(JobRef("j1"), a.upsertJob(spec, key))
        val post = rec.reqs.single { it.method == HttpMethod.Post }
        assertEquals(key.value, post.headers["Idempotency-Key"])
        assertTrue(post.url.toString().endsWith("/v1/jobs"))
    }

    @Test fun `upsert finds existing job by name and does not POST`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { ok("[${jobJson("other", "kivra:fusha:prod:x")},${jobJson("j1")}]") }
        assertEquals(JobRef("j1"), a.upsertJob(spec, key))
        assertEquals(0, rec.count(HttpMethod.Post))
    }

    @Test fun `create timeout is UNKNOWN, not resent, and the next upsert reconciles by name`() = runTest {
        val rec = Rec()
        var created = false
        val a = adapter(rec) { req ->
            when (req.method) {
                HttpMethod.Get -> ok(if (created) "[${jobJson("j1")}]" else "[]")
                else -> { created = true; throw java.io.IOException("timeout") }
            }
        }
        assertFailsWith<ScheduleUnknownException> { a.upsertJob(spec, key) }
        assertEquals(1, rec.count(HttpMethod.Post))
        assertEquals(JobRef("j1"), a.upsertJob(spec, key))
        assertEquals(1, rec.count(HttpMethod.Post), "no second create")
    }

    @Test fun `5xx on write is UNKNOWN and never resent`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { req -> if (req.method == HttpMethod.Get) ok("[]") else respond("boom", HttpStatusCode.BadGateway) }
        assertFailsWith<ScheduleUnknownException> { a.upsertJob(spec, key) }
        assertEquals(1, rec.count(HttpMethod.Post))
    }

    @Test fun `upsert rejects trigger change and duplicate names`() = runTest {
        val a = adapter { ok("[${jobJson("j1")}]") }
        assertFailsWith<ScheduleConflictException> { a.upsertJob(JobSpec(name, "0 * * * *", null), key) }
        val b = adapter { ok("[${jobJson("j1")},${jobJson("j2")}]") }
        assertFailsWith<ScheduleConflictException> { b.upsertJob(spec, key) }
    }

    @Test fun `create response with different name is an identity mismatch`() = runTest {
        val a = adapter { req -> if (req.method == HttpMethod.Get) ok("[]") else ok(jobJson("j1", "kivra:fusha:prod:evil"), HttpStatusCode.Created) }
        assertFailsWith<ScheduleIdentityMismatchException> { a.upsertJob(spec, key) }
    }

    @Test fun `getJob verifies id and hides prompt`() = runTest {
        val a = adapter { ok(jobJson("j1")) }
        val job = a.getJob(JobRef("j1"))
        assertEquals("*/5 * * * *", job.cron)
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), job.lastRunAt)
        assertFalse(job.toString().contains("SECRET"))
        val bad = adapter { ok(jobJson("jX")) }
        assertFailsWith<ScheduleIdentityMismatchException> { bad.getJob(JobRef("j1")) }
        val nf = adapter { respond("{}", HttpStatusCode.NotFound) }
        assertFailsWith<ScheduleNotFoundException> { nf.getJob(JobRef("j1")) }
    }

    @Test fun `ids with path characters are rejected before any request`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { ok("{}") }
        assertFailsWith<IllegalArgumentException> { a.getJob(JobRef("../v1/jobs")) }
        assertFailsWith<IllegalArgumentException> { a.getExecutionDetail(ScheduleExecutionRef("a/b")) }
        assertEquals(0, rec.reqs.size)
    }

    @Test fun `pause delete resume patch enabled and verify result`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { req ->
            val enabled = (req.body as io.ktor.http.content.TextContent).text.contains("true")
            ok(jobJson("j1", enabled = enabled))
        }
        a.pauseJob(JobRef("j1")); a.deleteJob(JobRef("j1")); a.resumeJob(JobRef("j1"))
        assertEquals(3, rec.count(HttpMethod.Patch))
        val stuck = adapter { ok(jobJson("j1", enabled = true)) }
        assertFailsWith<ScheduleIdentityMismatchException> { stuck.pauseJob(JobRef("j1")) }
    }

    @Test fun `triggerNow single POST and same key returns same execution without resend`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { ok(execJson("e1", "j1", "SCHEDULED"), HttpStatusCode.Created) }
        val k = IdempotencyKey("tick:2026-10-12T00:00:00Z")
        assertEquals(ScheduleExecutionRef("e1"), a.triggerNow(JobRef("j1"), k))
        assertEquals(ScheduleExecutionRef("e1"), a.triggerNow(JobRef("j1"), k))
        assertEquals(1, rec.count(HttpMethod.Post))
        assertEquals(k.value, rec.reqs.single().headers["Idempotency-Key"])
    }

    @Test fun `triggerNow timeout is UNKNOWN and the same key is refused without resend`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { throw java.io.IOException("timeout") }
        val k = IdempotencyKey("k1")
        assertFailsWith<ScheduleUnknownException> { a.triggerNow(JobRef("j1"), k) }
        assertFailsWith<ScheduleUnknownException> { a.triggerNow(JobRef("j1"), k) }
        assertEquals(1, rec.reqs.size)
    }

    @Test fun `triggerNow response for another job is an identity mismatch`() = runTest {
        val a = adapter { ok(execJson("e1", "other"), HttpStatusCode.Created) }
        assertFailsWith<ScheduleIdentityMismatchException> { a.triggerNow(JobRef("j1"), IdempotencyKey("k")) }
    }

    @Test fun `getExecution maps status verifies id and drops output`() = runTest {
        val a = adapter { ok(execJson("e1", status = "WAITING_APPROVAL")) }
        val d = a.getExecutionDetail(ScheduleExecutionRef("e1"))
        assertEquals(ScheduleExecutionState.WAITING_APPROVAL, d.state)
        assertFalse(d.toString().contains("SECRET"))
        assertEquals("WAITING_APPROVAL", a.getExecution(ScheduleExecutionRef("e1")).status)
        val unk = adapter { ok(execJson("e1", status = "NEW_STATE")) }
        assertEquals(ScheduleExecutionState.UNRECOGNIZED, unk.getExecutionDetail(ScheduleExecutionRef("e1")).state)
        val bad = adapter { ok(execJson("zzz")) }
        assertFailsWith<ScheduleIdentityMismatchException> { bad.getExecutionDetail(ScheduleExecutionRef("e1")) }
    }

    @Test fun `GET retries 5xx then succeeds, and exhausts to Network exception`() = runTest {
        var n = 0
        val a = adapter { if (n++ < 2) respond("x", HttpStatusCode.ServiceUnavailable) else ok(execJson("e1")) }
        assertEquals("RUNNING", a.getExecution(ScheduleExecutionRef("e1")).status)
        val rec = Rec()
        val down = adapter(rec) { throw java.io.IOException("refused") }
        assertFailsWith<ScheduleNetworkException> { down.getExecution(ScheduleExecutionRef("e1")) }
        assertEquals(3, rec.reqs.size)
    }

    @Test fun `cancel and retry map conflict and verify id`() = runTest {
        val a = adapter { req -> if (req.url.encodedPath.endsWith("/cancel")) respond("{}", HttpStatusCode.Conflict) else ok(execJson("e1", status = "SCHEDULED")) }
        assertFailsWith<ScheduleConflictException> { a.cancel(ScheduleExecutionRef("e1")) }
        assertEquals(ScheduleExecutionState.SCHEDULED, a.retry(ScheduleExecutionRef("e1")).state)
        val ok = adapter { ok(execJson("e1", status = "CANCELLED")) }
        ok.cancel(ScheduleExecutionRef("e1"))
        val bad = adapter { ok(execJson("e2")) }
        assertFailsWith<ScheduleIdentityMismatchException> { bad.cancel(ScheduleExecutionRef("e1")) }
    }

    @Test fun `listExecutions is unsupported against the real API`() = runTest {
        assertFailsWith<ScheduleUnsupportedException> { adapter { ok("[]") }.listExecutions(JobRef("j1")) }
    }

    @Test fun `redirect is a failure and is not followed`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://evil.example/")) }
        val e = assertFailsWith<ScheduleRejectedException> { a.getJob(JobRef("j1")) }
        assertEquals(302, e.status)
        assertEquals(1, rec.reqs.size)
    }

    @Test fun `bearer is sent only when provided, 401 on GET refreshes once, secrets never leak`() = runTest {
        val httpsCfg = AiSchedulerConfig("https://ai-scheduler.internal:8443")
        val rec = Rec()
        var tok = "tok-1"
        val provider = object : ScheduleTokenProvider {
            override suspend fun token() = tok
            override fun invalidate() { tok = "tok-2" }
        }
        val a = adapter(rec, provider, httpsCfg) { req -> if (req.headers[HttpHeaders.Authorization] == "Bearer tok-2") ok(jobJson("j1")) else respond("no", HttpStatusCode.Unauthorized) }
        a.getJob(JobRef("j1"))
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), rec.reqs.map { it.headers[HttpHeaders.Authorization] })

        val none = Rec()
        adapter(none) { ok(jobJson("j1")) }.getJob(JobRef("j1"))
        assertEquals(null, none.reqs.single().headers[HttpHeaders.Authorization])

        val denied = adapter(tokens = StaticScheduleTokenProvider("S3CRET"), config = httpsCfg) { respond("S3CRET body", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<ScheduleAuthException> { denied.getJob(JobRef("j1")) }
        assertFalse(e.message!!.contains("S3CRET"))
        assertFalse(StaticScheduleTokenProvider("S3CRET").toString().contains("S3CRET"))
        assertFalse(adapter(tokens = StaticScheduleTokenProvider("S3CRET"), config = httpsCfg) { ok("{}") }.toString().contains("S3CRET"))
    }

    @Test fun `write 401 is an auth failure, not UNKNOWN`() = runTest {
        val a = adapter { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ScheduleAuthException> { a.cancel(ScheduleExecutionRef("e1")) }
    }

    @Test fun `config enforces https unless explicitly opted out and rejects embedded credentials`() {
        assertFailsWith<IllegalArgumentException> { AiSchedulerConfig("http://x.internal") }
        AiSchedulerConfig("https://x.internal")
        AiSchedulerConfig("http://x.internal", allowInsecureHttp = true)
        assertFailsWith<IllegalArgumentException> { AiSchedulerConfig("https://u:p@x.internal") }
        assertFailsWith<IllegalArgumentException> { AiSchedulerConfig("https://x.internal/?a=b") }
        assertFailsWith<IllegalArgumentException> { AiSchedulerConfig("ftp://x.internal") }
        assertNotNull(AiSchedulerConfig("https://x.internal/base"))
    }

    @Test fun `job naming is deterministic and validated`() {
        assertEquals("kivra:fusha:prod:tick", JobNaming.build("prod", "tick"))
        assertEquals("prod" to "tick", JobNaming.parse("kivra:fusha:prod:tick"))
        assertEquals(null, JobNaming.parse("other:fusha:prod:tick"))
        assertFailsWith<IllegalArgumentException> { JobNaming.build("Prod", "tick") }
        assertFailsWith<IllegalArgumentException> { JobNaming.build("prod", "a:b") }
    }

    @Test fun `upsert rejects non-conforming job names without any request`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { ok("[]") }
        assertFailsWith<IllegalArgumentException> { a.upsertJob(JobSpec("random-name", null, null), key) }
        assertEquals(0, rec.reqs.size)
    }

    // ---- review regressions (triggerNow claim, upsert duplicates, safe destination) ----

    @Test fun `concurrent triggerNow with the same key sends exactly one POST`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { delay(100); ok(execJson("e1", "j1", "SCHEDULED"), HttpStatusCode.Created) }
        val k = IdempotencyKey("tick:race")
        val results = (1..2).map { async { runCatching { a.triggerNow(JobRef("j1"), k) } } }.awaitAll()
        assertEquals(1, rec.count(HttpMethod.Post))
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is ScheduleConflictException)
    }

    @Test fun `a restarted adapter sharing the durable claim store does not resend a completed key`() = runTest {
        val store = InMemoryTriggerDedupStore()
        val rec = Rec()
        val k = IdempotencyKey("tick:restart")
        val first = adapter(rec, dedup = store) { ok(execJson("e1", "j1", "SCHEDULED"), HttpStatusCode.Created) }
        assertEquals(ScheduleExecutionRef("e1"), first.triggerNow(JobRef("j1"), k))
        val second = adapter(rec, dedup = store) { ok(execJson("e2", "j1", "SCHEDULED"), HttpStatusCode.Created) }
        assertEquals(ScheduleExecutionRef("e1"), second.triggerNow(JobRef("j1"), k))
        assertEquals(1, rec.count(HttpMethod.Post))
    }

    @Test fun `a malformed body after a 2xx keeps the key UNKNOWN and is never re-sent`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { ok("not json", HttpStatusCode.Created) }
        val k = IdempotencyKey("tick:parse")
        assertFailsWith<ScheduleRejectedException> { a.triggerNow(JobRef("j1"), k) }
        assertFailsWith<ScheduleUnknownException> { a.triggerNow(JobRef("j1"), k) }
        assertEquals(1, rec.count(HttpMethod.Post))
    }

    @Test fun `cancellation during the POST keeps the key UNKNOWN`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { throw kotlinx.coroutines.CancellationException("caller cancelled") }
        val k = IdempotencyKey("tick:cancel")
        assertFailsWith<kotlinx.coroutines.CancellationException> { a.triggerNow(JobRef("j1"), k) }
        assertFailsWith<ScheduleUnknownException> { a.triggerNow(JobRef("j1"), k) }
        assertEquals(1, rec.count(HttpMethod.Post))
    }

    @Test fun `a 4xx refusal releases the key so a deliberate retry can send`() = runTest {
        val rec = Rec()
        var n = 0
        val a = adapter(rec) { if (n++ == 0) respond("", HttpStatusCode.BadRequest) else ok(execJson("e1", "j1", "SCHEDULED"), HttpStatusCode.Created) }
        val k = IdempotencyKey("tick:refused")
        assertFailsWith<ScheduleRejectedException> { a.triggerNow(JobRef("j1"), k) }
        assertEquals(ScheduleExecutionRef("e1"), a.triggerNow(JobRef("j1"), k))
        assertEquals(2, rec.count(HttpMethod.Post))
    }

    @Test fun `a 5xx on triggerNow keeps the key UNKNOWN`() = runTest {
        val rec = Rec()
        val a = adapter(rec) { respond("boom", HttpStatusCode.BadGateway) }
        val k = IdempotencyKey("tick:5xx")
        assertFailsWith<ScheduleUnknownException> { a.triggerNow(JobRef("j1"), k) }
        assertFailsWith<ScheduleUnknownException> { a.triggerNow(JobRef("j1"), k) }
        assertEquals(1, rec.count(HttpMethod.Post))
    }

    @Test fun `upsert fails closed when the name is duplicated after the create`() = runTest {
        val rec = Rec()
        var gets = 0
        val a = adapter(rec) { req ->
            if (req.method == HttpMethod.Get) {
                gets++
                ok(if (gets == 1) "[]" else "[${jobJson("j1")},${jobJson("j2")}]")
            } else ok(jobJson("j1"), HttpStatusCode.Created)
        }
        assertFailsWith<ScheduleConflictException> { a.upsertJob(spec, key) }
        assertEquals(1, rec.count(HttpMethod.Post))
    }

    @Test fun `a Bearer token over plain http is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            adapter(tokens = StaticScheduleTokenProvider("S3CRET")) { ok("{}") }
        }
        adapter(tokens = StaticScheduleTokenProvider("S3CRET"), config = AiSchedulerConfig("https://x.internal")) { ok("{}") }
    }

    @Test fun `a client that follows redirects is refused at construction`() {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonH) }
        val following = io.ktor.client.HttpClient(engine) { followRedirects = true }
        assertFailsWith<IllegalArgumentException> { AiSchedulerAdapter(following, cfg, backoff = {}, dedup = InMemoryTriggerDedupStore()) }
        assertNotNull(AiSchedulerAdapter(aiSchedulerHttpClient(engine, cfg), cfg, backoff = {}, dedup = InMemoryTriggerDedupStore()))
    }
}
