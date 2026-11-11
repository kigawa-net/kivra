package net.kigawa.kivra.agent.k8s

import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val K8S_NAMESPACE = "kivra-agent-runs"
internal const val K8S_IMAGE = "kivra-agent-runtime:local"
internal const val K8S_PROXY = "http://kivra-agent-proxy:8080"
internal const val K8S_RUN_ID = "01M545K02Q6YB4M5ZHAM5792CS"
internal const val K8S_ATTEMPT_ID = "att-1"

/** The settings and the standard spec that the tests build and then mutate. */
internal object K8sFixture {
    val settings: K8sSettings = K8sSettings(K8S_NAMESPACE, setOf(K8S_IMAGE))

    fun spec(
        runId: String = K8S_RUN_ID,
        attemptId: String = K8S_ATTEMPT_ID,
        namespace: String = K8S_NAMESPACE,
        image: String = K8S_IMAGE,
        proxy: String = K8S_PROXY,
        timeout: Duration = Duration.ofMinutes(30),
    ): K8sJobSpec = K8sJobSpec(runId, attemptId, namespace, image, proxy, timeout)

    /** The builder's output for the standard spec. */
    fun golden(): JsonObject = K8sJobBuilder(settings).build(spec())
}

internal fun str(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

/**
 * Sets the element at [path] (object keys as String, array indexes as Int). The keys that are not on the path stay as they are, and
 * their order is kept. A missing object key is added at the end.
 */
internal fun JsonObject.set(vararg path: Any, value: JsonElement): JsonObject = edit(path.toList()) { value } as JsonObject

/** Removes the element at [path]. */
internal fun JsonObject.remove(vararg path: Any): JsonObject = edit(path.toList()) { null } as JsonObject

/** Appends [value] to the array at [path]. */
internal fun JsonObject.append(vararg path: Any, value: JsonElement): JsonObject =
    edit(path.toList()) { old -> JsonArray((old as JsonArray) + value) } as JsonObject

private fun JsonElement.edit(path: List<Any>, transform: (JsonElement?) -> JsonElement?): JsonElement {
    require(path.isNotEmpty()) { "path must not be empty" }
    val head = path.first()
    val rest = path.drop(1)
    return when (this) {
        is JsonObject -> {
            val key = head as String
            val map = LinkedHashMap(this)
            if (rest.isEmpty()) {
                val updated = transform(map[key])
                if (updated == null) map.remove(key) else map[key] = updated
            } else {
                map[key] = (map[key] ?: error("no key on the path")).edit(rest, transform)
            }
            JsonObject(map)
        }
        is JsonArray -> {
            val index = head as Int
            val list = toMutableList()
            if (rest.isEmpty()) {
                val updated = transform(list[index])
                if (updated == null) list.removeAt(index) else list[index] = updated
            } else {
                list[index] = list[index].edit(rest, transform)
            }
            JsonArray(list)
        }
        else -> error("the path goes into a value")
    }
}
