package net.kigawa.kivra.acceptance

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * TEST ONLY in-memory stand-in for the exkes provider (runtimes in a workspace). It is the Provider side of the scenario:
 * a mutating request that reaches it always persists its effect. Whether the caller ever sees the answer is decided by
 * [FaultProxy], not here. Nothing in this class talks to a network.
 */
class FakeExkes {
    /** One persisted external effect. [stableRef] is the run id carried in the runtime label `kivra.run-id`. */
    data class Effect(val kind: String, val stableRef: String?, val resourceId: String)

    /** One mutating request that reached the provider, before any fault is applied. */
    data class Post(val path: String, val stableRef: String?)

    class Served(val status: HttpStatusCode, val body: String)

    private class RuntimeState(val id: String, val workspaceId: String, val templateId: String, val labels: Map<String, String>)

    private val workspaces = LinkedHashMap<String, String>() // id -> name
    private val runtimes = LinkedHashMap<String, RuntimeState>()
    val effects = mutableListOf<Effect>()
    val posts = mutableListOf<Post>()
    private var seq = 0
    private val json = Json { ignoreUnknownKeys = true }

    /** Test setup, not a provider effect: a workspace that already exists on the provider. */
    fun seedWorkspace(name: String): String = "ws-${++seq}".also { workspaces[it] = name }

    val externalRuntimeCount: Int get() = runtimes.size

    fun recordPost(path: String, body: String?) {
        posts += Post(path, stableRefOf(body))
    }

    /** Serves one request. Mutations persist their effect before returning. */
    fun serve(method: HttpMethod, path: String, body: String?): Served {
        val seg = path.trim('/').split('/')
        if (seg.size == 4 && seg[0] == "v1" && seg[1] == "workspaces" && seg[3] == "runtimes") {
            val ws = seg[2]
            if (ws !in workspaces) return error(HttpStatusCode.NotFound, "NOT_FOUND")
            if (method == HttpMethod.Post) {
                val b = json.parseToJsonElement(body ?: "{}").jsonObject
                val labels = b["labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val rt = RuntimeState("rt-${++seq}", ws, b["templateId"]!!.jsonPrimitive.content, labels)
                runtimes[rt.id] = rt
                effects += Effect("CREATE_RUNTIME", labels["kivra.run-id"], rt.id)
                return Served(HttpStatusCode.Accepted, runtimeJson(rt).toString())
            }
            if (method == HttpMethod.Get) {
                val items = runtimes.values.filter { it.workspaceId == ws }.map { runtimeJson(it) }
                return Served(HttpStatusCode.OK, listJson(items))
            }
        }
        return error(HttpStatusCode.NotFound, "NOT_FOUND")
    }

    private fun stableRefOf(body: String?): String? =
        body?.let { runCatching { json.parseToJsonElement(it).jsonObject["labels"]?.jsonObject?.get("kivra.run-id")?.jsonPrimitive?.content }.getOrNull() }

    private fun runtimeJson(rt: RuntimeState): JsonObject = buildJsonObject {
        put("id", rt.id)
        put("workspaceId", rt.workspaceId)
        put("templateId", rt.templateId)
        put("status", "CREATING")
        putJsonObject("labels") { rt.labels.forEach { (k, v) -> put(k, v) } }
    }

    private fun listJson(items: List<JsonElement>): String = buildJsonObject {
        put("items", JsonArray(items))
        put("total", items.size.toLong())
        put("limit", 100)
        put("offset", 0)
    }.toString()

    private fun error(status: HttpStatusCode, code: String) =
        Served(status, buildJsonObject { put("code", code); put("message", code) }.toString())
}
