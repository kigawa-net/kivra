package net.kigawa.kivra.scheduler

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobRef
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleExecutionRef
import net.kigawa.kivra.core.ScheduleExecutionStatus

/**
 * @property baseUrl ai-scheduler base URL. Must be https, unless [allowInsecureHttp] is set. Plain http is only for an
 *   explicit, unauthenticated, network-limited (loopback or in-cluster) deployment: the adapter also refuses to send a
 *   Bearer token over http. Userinfo / query / fragment are rejected.
 * @property placeholderPrompt / provider / model: ai-scheduler requires an AI prompt+model on every job even though
 *   Kivra only wants a wake-up. These are configuration, never derived from task content.
 */
data class AiSchedulerConfig(
    val baseUrl: String,
    val allowInsecureHttp: Boolean = false,
    val timezone: String = "UTC",
    val provider: String = "none",
    val model: String = "none",
    val placeholderPrompt: String = "kivra wake-up (no-op)",
    /** Total attempts for a GET (first try + retries). POST/PATCH are never auto-resent. */
    val maxGetAttempts: Int = 3,
    val requestTimeoutMillis: Long = 15_000,
    val connectTimeoutMillis: Long = 5_000,
) {
    init {
        val url = runCatching { Url(baseUrl) }.getOrNull() ?: throw IllegalArgumentException("invalid baseUrl")
        require(url.protocol.name == "https" || (url.protocol.name == "http" && allowInsecureHttp)) {
            "baseUrl must be https (set allowInsecureHttp only for in-cluster, network-limited use)"
        }
        require(url.user == null && url.password == null) { "baseUrl must not contain credentials" }
        require(url.parameters.isEmpty() && url.fragment.isEmpty()) { "baseUrl must not contain query or fragment" }
        require(maxGetAttempts >= 1) { "invalid limits" }
    }
}

/** Builds the HTTP client: timeouts on, redirects OFF (a redirect could carry the Bearer token elsewhere). Tests pass a MockEngine. */
fun aiSchedulerHttpClient(engine: HttpClientEngine, config: AiSchedulerConfig): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = config.requestTimeoutMillis
        connectTimeoutMillis = config.connectTimeoutMillis
    }
}

/**
 * ai-scheduler REST adapter (`/v1/jobs`, `/v1/jobs/{id}`, `/v1/jobs/{id}/executions`, `/v1/executions/{id}[/cancel|/retry]`).
 *
 * Safety rules (lessons from the #34/#35 reviews):
 * - Every response is checked to be about the requested object (id / name); a mismatch is
 *   [ScheduleIdentityMismatchException].
 * - Writes (POST/PATCH) are sent exactly once. Timeout / transport error / 5xx yields [ScheduleUnknownException]; the
 *   adapter never resends. Reconciliation for job creation is by the deterministic job name (see [upsertJob]);
 *   `triggerNow` is claimed in [dedup] before the POST (see [triggerNow]).
 * - Any 3xx is a failure (and the factory [aiSchedulerHttpClient] does not follow redirects).
 * - Tokens, prompts and response bodies are never put into exception messages or `toString`.
 *
 * The real ai-scheduler ignores the `Idempotency-Key` header today (only a `(job_id, scheduled_at)` unique constraint
 * exists server side, and a manual execution uses `now`); the header is sent so that enabling it is a server-only
 * change. See docs/ai-scheduler-requests.md.
 */
class AiSchedulerAdapter(
    private val client: HttpClient,
    private val config: AiSchedulerConfig,
    private val tokens: ScheduleTokenProvider = ScheduleTokenProvider.NoAuth,
    private val backoff: suspend (attempt: Int) -> Unit = { delay(200L * (1L shl (it - 1))) },
    /** Durable claim store for `triggerNow`. Production must pass a persistent implementation (see [TriggerDedupStore]). */
    private val dedup: TriggerDedupStore,
) : ScheduleClient {
    init {
        require(!(config.baseUrl.startsWith("http:") && tokens !== ScheduleTokenProvider.NoAuth)) {
            "a Bearer token must not be sent over plain http"
        }
        require(client.pluginOrNull(HttpRedirect) == null) { "the HttpClient must not follow redirects" }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- wire types (only fields Kivra needs; prompt/output/error are never read) ----
    @Serializable private class WireTrigger(
        val type: String? = null, val cronExpression: String? = null, val timezone: String? = null,
        val onceAt: String? = null, val lastRunAt: String? = null, val nextRunAt: String? = null,
    )
    @Serializable private class WireJob(val id: String? = null, val name: String? = null, val enabled: Boolean? = null, val trigger: WireTrigger? = null)
    @Serializable private class WireExecution(
        val id: String? = null, val jobId: String? = null, val status: String? = null,
        val scheduledAt: String? = null, val startedAt: String? = null, val completedAt: String? = null,
    )
    @Serializable private class ReqTrigger(val type: String, val cronExpression: String? = null, val timezone: String? = null, val onceAt: String? = null)
    @Serializable private class ReqModel(val provider: String, val model: String)
    @Serializable private class ReqCreate(
        val name: String, val enabled: Boolean, val executionMode: String, val trigger: ReqTrigger,
        val model: ReqModel, val prompt: String,
    )
    @Serializable private class ReqEnabled(val enabled: Boolean)

    private class Raw(val status: Int, val body: String)

    // ---- transport ----
    private suspend fun send(method: HttpMethod, path: String, body: String?, idempotencyKey: String?): Raw? {
        return try {
            val resp = client.request(config.baseUrl.trimEnd('/') + path) {
                this.method = method
                tokens.token()?.let { header("Authorization", "Bearer $it") }
                header("Accept", "application/json")
                if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            Raw(resp.status.value, resp.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // transport error / timeout; detail deliberately dropped (may embed URLs)
        }
    }

    private fun fail(op: String, status: Int): Nothing = when {
        status in 300..399 -> throw ScheduleRejectedException(status, "$op: unexpected redirect (not followed)")
        status == 401 || status == 403 -> throw ScheduleAuthException("$op: HTTP $status")
        status == 404 -> throw ScheduleNotFoundException("$op: not found")
        status == 409 -> throw ScheduleConflictException("$op: conflict")
        else -> throw ScheduleRejectedException(status, "$op: HTTP $status")
    }

    /** GET with retry on I/O and 5xx, and one token refresh on 401. */
    private suspend fun get(op: String, path: String): String {
        var refreshed = false
        var attempt = 1
        while (true) {
            val r = send(HttpMethod.Get, path, null, null)
            when {
                r != null && r.status in 200..299 -> return r.body
                r != null && r.status == 401 && !refreshed -> {
                    refreshed = true
                    tokens.invalidate()
                    continue
                }
                r != null && r.status < 500 -> fail(op, r.status)
                attempt >= config.maxGetAttempts ->
                    throw ScheduleNetworkException("$op: unavailable" + (r?.let { " (HTTP ${it.status})" } ?: ""))
            }
            backoff(attempt)
            attempt++
        }
    }

    /** Write: exactly one send. Transport error / 5xx -> UNKNOWN. */
    private suspend fun write(op: String, method: HttpMethod, path: String, body: String?, idempotencyKey: String? = null): String {
        val r = send(method, path, body, idempotencyKey) ?: throw ScheduleUnknownException(op, "transport error or timeout")
        if (r.status in 200..299) return r.body
        if (r.status >= 500) throw ScheduleUnknownException(op, "HTTP ${r.status}")
        if (r.status == 401) tokens.invalidate()
        fail(op, r.status)
    }

    private inline fun <reified T> parse(op: String, body: String): T =
        try { json.decodeFromString<T>(body) } catch (e: Exception) { throw ScheduleRejectedException(200, "$op: malformed response") }

    // ---- validation / mapping ----
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private fun id(v: String): String {
        require(idPattern.matches(v) && !v.contains("..")) { "invalid id" }
        return v
    }
    private fun key(k: IdempotencyKey): String {
        require(k.value.isNotEmpty() && k.value.length <= 200 && k.value.all { it in ' '..'~' }) { "invalid idempotency key" }
        return k.value
    }
    private fun instant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun toJob(op: String, expectedId: String?, w: WireJob): ScheduleJob {
        val id = w.id ?: throw ScheduleIdentityMismatchException("$op: response has no id")
        if (expectedId != null && id != expectedId) throw ScheduleIdentityMismatchException("$op: response is for a different job")
        val t = w.trigger
        return ScheduleJob(
            JobRef(id), w.name ?: "", w.enabled ?: false,
            if (t?.type == "CRON") t.cronExpression else null,
            if (t?.type == "ONCE") instant(t.onceAt) else null,
            instant(t?.lastRunAt), instant(t?.nextRunAt),
        )
    }

    private fun toExecution(op: String, expectedId: String, w: WireExecution): ScheduleExecution {
        if (w.id != expectedId) throw ScheduleIdentityMismatchException("$op: response is for a different execution")
        val raw = w.status ?: ""
        return ScheduleExecution(
            ScheduleExecutionRef(expectedId), JobRef(w.jobId ?: ""), ScheduleExecutionState.fromWire(raw), raw,
            instant(w.scheduledAt), instant(w.startedAt), instant(w.completedAt),
        )
    }

    // ---- ScheduleClient ----
    /**
     * Idempotent by deterministic name: list jobs and look for [JobSpec.name] first; create only when absent. After an
     * UNKNOWN create the next call finds the job by name instead of creating a second one. The create POST is
     * never resent automatically. Changing the trigger of an existing job is not possible via PATCH and raises
     * [ScheduleConflictException].
     *
     * Fail-closed on duplicates: more than one job with the name, before or after the POST, raises
     * [ScheduleConflictException]. Nothing is deleted automatically (ai-scheduler has no DELETE).
     *
     * **Known residual race (not fixed here):** ai-scheduler job names are not unique server-side, so lookup-then-POST
     * is not atomic. Two creators that both see "absent" both POST, and the post-create check only *detects* the
     * duplicate; it cannot prevent it. The precondition is that only one Kivra process runs this at a time, i.e. it
     * runs under the Orchestrator's Leader Lease, including the old owner's in-flight POST. A permanent fix needs a
     * server-side unique name or Idempotency-Key support in ai-scheduler (docs/ai-scheduler-requests.md).
     */
    override suspend fun upsertJob(spec: JobSpec, key: IdempotencyKey): JobRef {
        require(JobNaming.isValid(spec.name)) { "job name must follow kivra:fusha:{env}:{id}" }
        require(spec.cron == null || spec.runAt == null) { "cron and runAt are mutually exclusive" }
        val k = key(key)
        val existing = jobsByName(spec.name)
        if (existing.size > 1) throw ScheduleConflictException("upsertJob: multiple jobs share the deterministic name")
        existing.firstOrNull()?.let {
            if (it.cron != spec.cron || it.runAt != spec.runAt) {
                throw ScheduleConflictException("upsertJob: trigger of existing job differs and cannot be updated")
            }
            return it.ref
        }
        val trigger = when {
            spec.cron != null -> ReqTrigger("CRON", cronExpression = spec.cron, timezone = config.timezone)
            spec.runAt != null -> ReqTrigger("ONCE", onceAt = spec.runAt.toString())
            else -> ReqTrigger("MANUAL")
        }
        val body = json.encodeToString(
            ReqCreate(spec.name, true, "REALTIME", trigger, ReqModel(config.provider, config.model), config.placeholderPrompt),
        )
        val resp = write("upsertJob", HttpMethod.Post, "/v1/jobs", body, k)
        val job = toJob("upsertJob", null, parse("upsertJob", resp))
        if (job.name != spec.name) throw ScheduleIdentityMismatchException("upsertJob: response is for a different job name")
        // Detect a concurrent creator that raced past our lookup. Detection only: the duplicate is not removed here.
        if (jobsByName(spec.name).size > 1) throw ScheduleConflictException("upsertJob: duplicate job created concurrently")
        return job.ref
    }

    private suspend fun jobsByName(name: String): List<ScheduleJob> {
        val list = parse<List<WireJob>>("listJobs", get("listJobs", "/v1/jobs"))
        return list.filter { it.name == name }.map { toJob("listJobs", null, it) }
    }

    override suspend fun getJob(ref: JobRef): ScheduleJob {
        val i = id(ref.id)
        return toJob("getJob", i, parse("getJob", get("getJob", "/v1/jobs/$i")))
    }

    private suspend fun setEnabled(op: String, ref: JobRef, enabled: Boolean) {
        val i = id(ref.id)
        val job = toJob(op, i, parse("$op", write(op, HttpMethod.Patch, "/v1/jobs/$i", json.encodeToString(ReqEnabled(enabled)))))
        if (job.enabled != enabled) throw ScheduleIdentityMismatchException("$op: job state does not match the request")
    }

    override suspend fun pauseJob(ref: JobRef) = setEnabled("pauseJob", ref, false)
    override suspend fun resumeJob(ref: JobRef) = setEnabled("resumeJob", ref, true)
    override suspend fun deleteJob(ref: JobRef) = setEnabled("deleteJob", ref, false)

    /**
     * Single POST, never auto-resent, and at most one execution per `(job, key)`.
     *
     * The claim is atomic and happens before the POST: [TriggerDedupStore.tryClaim] is one conditional write, so two
     * concurrent callers cannot both send. Outcomes after the claim:
     * - 2xx with a valid body: DONE, same ref for the same key from then on (no POST).
     * - Refused by ai-scheduler before execution (4xx, 3xx not followed): claim released, a deliberate retry may send.
     * - Transport error, timeout, 5xx, cancellation, or any failure after a 2xx (parse / identity check): UNKNOWN. The
     *   same key is refused with [ScheduleUnknownException] until reconciled. ai-scheduler cannot list a job's
     *   executions, so Kivra cannot confirm it from its side.
     * - A concurrent call while IN_FLIGHT gets [ScheduleConflictException] and does not send.
     *
     * Persistence: the claim must survive restarts (see [TriggerDedupStore]); the in-process LRU this replaces could
     * evict an UNKNOWN key and re-open a duplicate.
     */
    override suspend fun triggerNow(ref: JobRef, key: IdempotencyKey): ScheduleExecutionRef {
        val i = id(ref.id)
        val k = key(key)
        val claimKey = "$i|$k"
        dedup.tryClaim(claimKey)?.let { existing ->
            return when (existing.state) {
                ClaimState.DONE -> ScheduleExecutionRef(existing.result ?: throw ScheduleUnknownException("triggerNow", "claim has no execution id"))
                ClaimState.IN_FLIGHT -> throw ScheduleConflictException("triggerNow: the same key is already in flight")
                ClaimState.UNKNOWN -> throw ScheduleUnknownException("triggerNow", "earlier attempt with this key had an unknown outcome; reconcile before retrying")
            }
        }
        val r = try {
            send(HttpMethod.Post, "/v1/jobs/$i/executions", null, k)
        } catch (e: Throwable) {
            dedup.markUnknown(claimKey)
            throw e
        }
        if (r == null || r.status >= 500) {
            dedup.markUnknown(claimKey)
            throw ScheduleUnknownException("triggerNow", if (r == null) "transport error or timeout" else "HTTP ${r.status}")
        }
        if (r.status !in 200..299) {
            // Refused before any execution was created: safe to release.
            if (r.status == 401) tokens.invalidate()
            dedup.release(claimKey)
            fail("triggerNow", r.status)
        }
        try {
            val w = parse<WireExecution>("triggerNow", r.body)
            val eid = w.id ?: throw ScheduleIdentityMismatchException("triggerNow: response has no id")
            if (w.jobId != i) throw ScheduleIdentityMismatchException("triggerNow: response is for a different job")
            dedup.complete(claimKey, eid)
            return ScheduleExecutionRef(eid)
        } catch (e: Throwable) {
            // The execution exists (2xx) even though we could not read it back: never release.
            dedup.markUnknown(claimKey)
            throw e
        }
    }

    override suspend fun getExecutionDetail(ref: ScheduleExecutionRef): ScheduleExecution {
        val i = id(ref.id)
        return toExecution("getExecution", i, parse("getExecution", get("getExecution", "/v1/executions/$i")))
    }

    override suspend fun getExecution(ref: ScheduleExecutionRef): ScheduleExecutionStatus =
        getExecutionDetail(ref).let { ScheduleExecutionStatus(it.ref, it.rawStatus) }

    override suspend fun listExecutions(job: JobRef, limit: Int): List<ScheduleExecution> =
        throw ScheduleUnsupportedException("ai-scheduler has no GET /v1/jobs/{id}/executions (POST only); see docs/ai-scheduler-requests.md")

    override suspend fun cancel(ref: ScheduleExecutionRef) {
        val i = id(ref.id)
        toExecution("cancel", i, parse("cancel", write("cancel", HttpMethod.Post, "/v1/executions/$i/cancel", null)))
    }

    override suspend fun retry(ref: ScheduleExecutionRef): ScheduleExecution {
        val i = id(ref.id)
        return toExecution("retry", i, parse("retry", write("retry", HttpMethod.Post, "/v1/executions/$i/retry", null)))
    }

    override fun toString() = "AiSchedulerAdapter(baseUrl=${config.baseUrl}, tokens=$tokens)"
}
