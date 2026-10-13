package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class DevTaskRequest(val title: String, val idempotencyKey: String)
@Serializable
internal data class DevTaskRun(val runId: String, val title: String, val state: String = "NOT_EXECUTED", val mode: String = "SIMULATED")
@Serializable
private data class DevTaskError(val error: String)

/** In-memory demonstration only; a JVM restart loses the data. */
internal class DevTaskStore {
    private val byKey = mutableMapOf<String, DevTaskRun>()
    private val byId = mutableMapOf<String, DevTaskRun>()
    @Synchronized
    fun submit(title: String, key: String): DevTaskRun {
        val existing = byKey[key]
        require(existing == null || existing.title == title) { "idempotency key reused for different task" }
        if (existing != null) return existing
        val run = DevTaskRun(UUID.randomUUID().toString(), title)
        byKey[key] = run
        byId[run.runId] = run
        return run
    }
    @Synchronized
    fun get(id: String) = byId[id]
}

/**
 * This endpoint creates only an ephemeral simulated preview, never a real
 * DevelopmentRun, external write, model request or Agent execution.
 */
internal fun Route.configureDevTaskSubmitRoutes(store: DevTaskStore = DevTaskStore()) {
    post("/api/dev/tasks") {
        val request = try { call.receive<DevTaskRequest>() } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, DevTaskError("invalid request"))
            return@post
        }
        val title = request.title.trim()
        val key = request.idempotencyKey.trim()
        if (title.isEmpty() || title.length > 256 || key.isEmpty() || key.length > 128) {
            call.respond(HttpStatusCode.BadRequest, DevTaskError("title or idempotencyKey is invalid"))
            return@post
        }
        val run = try { store.submit(title, key) } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, DevTaskError("idempotency key conflict"))
            return@post
        }
        call.respond(HttpStatusCode.Accepted, run)
    }
    get("/api/dev/runs/{id}") {
        val run = call.parameters["id"]?.let(store::get)
        if (run == null) call.respond(HttpStatusCode.NotFound, DevTaskError("run not found"))
        else call.respond(run)
    }
}
