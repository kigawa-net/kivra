package net.kigawa.kivra.exkes

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json

/**
 * A small in-memory stand-in for exkes-api, behind a MockEngine. It reproduces exactly the behaviours
 * verified on exkes main: 202 on create, CREATING/QUEUED initial states, UNIQUE workspace names (409),
 * RUNNING-only executions, paginated lists, `{"code","message"}` errors. Nothing here talks to a network.
 */
internal class ExkesSimulator {
    private val json = Json { ignoreUnknownKeys = true }
    private var seq = 0
    val workspaces = LinkedHashMap<String, WorkspaceDto>()
    val runtimes = LinkedHashMap<String, RuntimeDto>()
    val executions = LinkedHashMap<String, ExecutionDto>()
    val requests = mutableListOf<HttpRequestData>()
    val effects = HashMap<String, Int>()

    /** op -> afterEffect; the response of the next matching mutating call is replaced by a timeout. */
    val lost = ArrayDeque<Pair<String, Boolean>>()

    /** Test hook to override any response (return null to fall through to normal handling). */
    var override: (MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null

    val engine = MockEngine { req ->
        requests += req
        override?.invoke(this, req)?.let { return@MockEngine it }
        handle(req)
    }

    fun requestsOf(method: HttpMethod) = requests.filter { it.method == method }

    private fun MockRequestHandleScope.reply(status: HttpStatusCode, body: String) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, msg: String) =
        reply(status, """{"code":"$code","message":"$msg"}""")

    private fun bodyOf(req: HttpRequestData): String = when (val c = req.body) {
        is TextContent -> c.text
        is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
        else -> ""
    }

    private fun MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val path = req.url.encodedPath
        val seg = path.removePrefix("/").split("/")
        val m = req.method
        val q = req.url.parameters
        fun <T> page(all: List<T>, ser: kotlinx.serialization.KSerializer<T>): String {
            val limit = q["limit"]?.toInt() ?: 50
            val offset = q["offset"]?.toInt() ?: 0
            val items = all.drop(offset).take(limit)
            return json.encodeToString(ListDto.serializer(ser), ListDto(items, all.size.toLong(), limit, offset))
        }
        fun mutate(op: String, effect: () -> HttpResponseData): HttpResponseData {
            val l = lost.firstOrNull { it.first == op }
            if (l != null) {
                lost.remove(l)
                if (l.second) effect().also { if (it.statusCode == HttpStatusCode.Accepted) effects.merge(op, 1, Int::plus) }
                throw HttpRequestTimeoutException(req.url.toString(), 1000)
            }
            return effect().also { if (it.statusCode == HttpStatusCode.Accepted) effects.merge(op, 1, Int::plus) }
        }

        // /v1/workspaces
        if (seg.size == 2 && seg[0] == "v1" && seg[1] == "workspaces") {
            if (m == HttpMethod.Post) return mutate("createWorkspace") {
                val b = json.decodeFromString(CreateWorkspaceBody.serializer(), bodyOf(req))
                when {
                    !Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}").matches(b.name) ->
                        error(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "bad name")
                    workspaces.values.any { it.name == b.name } -> error(HttpStatusCode.Conflict, "CONFLICT", "duplicate name")
                    else -> {
                        val w = WorkspaceDto("ws-${++seq}", b.name, "CREATING", null, b.labels ?: emptyMap())
                        workspaces[w.id] = w
                        reply(HttpStatusCode.Accepted, json.encodeToString(WorkspaceDto.serializer(), w))
                    }
                }
            }
            if (m == HttpMethod.Get) {
                return reply(HttpStatusCode.OK, page(workspaces.values.filter { it.status != "DELETED" }, WorkspaceDto.serializer()))
            }
        }
        if (seg.size == 3 && seg[1] == "workspaces") {
            val w = workspaces[seg[2]] ?: return error(HttpStatusCode.NotFound, "NOT_FOUND", "workspace")
            if (m == HttpMethod.Get) return reply(HttpStatusCode.OK, json.encodeToString(WorkspaceDto.serializer(), w))
            if (m == HttpMethod.Delete) return mutate("deleteWorkspace") {
                val d = w.copy(status = "DELETING"); workspaces[w.id] = d
                reply(HttpStatusCode.Accepted, json.encodeToString(WorkspaceDto.serializer(), d))
            }
        }
        // /v1/workspaces/{ws}/runtimes[/{id}[/stop]]
        if (seg.size >= 4 && seg[1] == "workspaces" && seg[3] == "runtimes") {
            val ws = seg[2]
            if (ws !in workspaces) return error(HttpStatusCode.NotFound, "NOT_FOUND", "workspace")
            if (seg.size == 4 && m == HttpMethod.Post) return mutate("createRuntime") {
                val b = json.decodeFromString(CreateRuntimeBody.serializer(), bodyOf(req))
                val r = RuntimeDto("rt-${++seq}", ws, b.templateId, "CREATING", null, b.labels ?: emptyMap())
                runtimes[r.id] = r
                reply(HttpStatusCode.Accepted, json.encodeToString(RuntimeDto.serializer(), r))
            }
            if (seg.size == 4 && m == HttpMethod.Get) {
                return reply(HttpStatusCode.OK, page(runtimes.values.filter { it.workspaceId == ws }, RuntimeDto.serializer()))
            }
            val r = runtimes[seg.getOrNull(4)]?.takeIf { it.workspaceId == ws }
                ?: return error(HttpStatusCode.NotFound, "NOT_FOUND", "runtime")
            if (seg.size == 5 && m == HttpMethod.Get) return reply(HttpStatusCode.OK, json.encodeToString(RuntimeDto.serializer(), r))
            if (seg.size == 5 && m == HttpMethod.Delete) return mutate("deleteRuntime") {
                val d = r.copy(status = "DELETING"); runtimes[r.id] = d
                reply(HttpStatusCode.Accepted, json.encodeToString(RuntimeDto.serializer(), d))
            }
            if (seg.size == 6 && seg[5] == "stop" && m == HttpMethod.Post) return mutate("stopRuntime") {
                val d = r.copy(status = "STOPPING"); runtimes[r.id] = d
                reply(HttpStatusCode.Accepted, json.encodeToString(RuntimeDto.serializer(), d))
            }
        }
        // /v1/runtimes/{rt}/executions[/{id}]
        if (seg.size >= 4 && seg[1] == "runtimes" && seg[3] == "executions") {
            val rt = runtimes[seg[2]] ?: return error(HttpStatusCode.NotFound, "NOT_FOUND", "runtime")
            if (seg.size == 4 && m == HttpMethod.Post) return mutate("startExecution") {
                if (rt.status != "RUNNING") return@mutate error(HttpStatusCode.Conflict, "CONFLICT", "runtime not RUNNING")
                val b = json.decodeFromString(CreateExecutionBody.serializer(), bodyOf(req))
                val e = ExecutionDto("ex-${++seq}", rt.id, b.type, "QUEUED")
                executions[e.id] = e
                reply(HttpStatusCode.Accepted, json.encodeToString(ExecutionDto.serializer(), e))
            }
            if (seg.size == 4 && m == HttpMethod.Get) {
                return reply(HttpStatusCode.OK, page(executions.values.filter { it.runtimeId == rt.id }, ExecutionDto.serializer()))
            }
            val e = executions[seg.getOrNull(4)]?.takeIf { it.runtimeId == rt.id }
                ?: return error(HttpStatusCode.NotFound, "NOT_FOUND", "execution")
            if (seg.size == 5 && m == HttpMethod.Get) return reply(HttpStatusCode.OK, json.encodeToString(ExecutionDto.serializer(), e))
        }
        return error(HttpStatusCode.NotFound, "NOT_FOUND", "no route $m $path")
    }
}
