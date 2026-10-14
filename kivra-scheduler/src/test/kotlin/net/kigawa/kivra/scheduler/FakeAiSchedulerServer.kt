package net.kigawa.kivra.scheduler

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Stateful in-process stand-in for the ai-scheduler REST surface Kivra uses (MockEngine only, no network). Like the real
 * server it has no Idempotency-Key handling: every `POST /v1/jobs/{id}/executions` creates a new execution, so tests can
 * count the POSTs a client actually sent. [down] makes every request fail at transport level (ai-scheduler stopped).
 */
class FakeAiSchedulerServer(private val now: String = "2026-10-14T00:00:00Z") {
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = linkedMapOf<String, JsonObject>()
    private val executions = linkedMapOf<String, JsonObject>()
    private var seq = 0

    @Volatile var down = false
    var executionPosts = 0
        private set

    fun jobCount() = jobs.size

    fun engine(): MockEngine = MockEngine { req -> route(req) }

    private fun MockRequestHandleScope.route(req: HttpRequestData): HttpResponseData {
        if (down) throw IOException("connection refused")
        val path = req.url.encodedPath
        val body = (req.body as? TextContent)?.text ?: ""
        val reply = { status: HttpStatusCode, obj: Any -> respond(obj.toString(), status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return when {
            req.method == HttpMethod.Get && path == "/v1/jobs" ->
                reply(HttpStatusCode.OK, jobs.values.joinToString(",", "[", "]"))
            req.method == HttpMethod.Post && path == "/v1/jobs" -> {
                val b = json.parseToJsonElement(body).jsonObject
                val trig = b["trigger"]!!.jsonObject
                val id = "j${++seq}"
                val job = buildJsonObject {
                    put("id", id)
                    put("name", b["name"]!!.jsonPrimitive.content)
                    put("enabled", true)
                    put("prompt", "SECRET-PROMPT")
                    put("trigger", trig)
                }
                jobs[id] = job
                reply(HttpStatusCode.Created, job)
            }
            req.method == HttpMethod.Get && path.matches(Regex("/v1/jobs/[^/]+")) ->
                jobs[path.substringAfterLast('/')]?.let { reply(HttpStatusCode.OK, it) } ?: reply(HttpStatusCode.NotFound, "{}")
            req.method == HttpMethod.Patch && path.matches(Regex("/v1/jobs/[^/]+")) -> {
                val id = path.substringAfterLast('/')
                val old = jobs[id] ?: return reply(HttpStatusCode.NotFound, "{}")
                val enabled = json.parseToJsonElement(body).jsonObject["enabled"]!!.jsonPrimitive.boolean
                val updated = JsonObject(old + ("enabled" to JsonPrimitive(enabled)))
                jobs[id] = updated
                reply(HttpStatusCode.OK, updated)
            }
            req.method == HttpMethod.Post && path.matches(Regex("/v1/jobs/[^/]+/executions")) -> {
                val jobId = path.split('/')[3]
                if (jobs[jobId] == null) return reply(HttpStatusCode.NotFound, "{}")
                executionPosts++
                // like ai-scheduler's trigger.lastRunAt: the job now reports this run (what the poller observes)
                val job = jobs.getValue(jobId)
                val trig = job["trigger"]!!.jsonObject + ("lastRunAt" to JsonPrimitive(now))
                jobs[jobId] = JsonObject(job + ("trigger" to JsonObject(trig)))
                val eid = "e${++seq}"
                val ex = buildJsonObject {
                    put("id", eid); put("jobId", jobId); put("status", "RUNNING")
                    put("scheduledAt", now); put("output", "SECRET-OUT")
                }
                executions[eid] = ex
                reply(HttpStatusCode.Created, ex)
            }
            req.method == HttpMethod.Get && path.matches(Regex("/v1/executions/[^/]+")) ->
                executions[path.substringAfterLast('/')]?.let { reply(HttpStatusCode.OK, it) } ?: reply(HttpStatusCode.NotFound, "{}")
            else -> reply(HttpStatusCode.NotFound, "{}")
        }
    }
}
