package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * The demo API is never registered in normal deployments. Production startup
 * must fail rather than accidentally publishing unauthenticated development routes.
 */
internal fun isDevPreviewEnabled(environment: Map<String, String>): Boolean {
    if (environment["KIVRA_DEV_DEMO_ENABLED"] != "true") return false
    require(environment["KIVRA_RUNTIME_ENV"] == "development") {
        "KIVRA_DEV_DEMO_ENABLED requires KIVRA_RUNTIME_ENV=development"
    }
    return true
}

@Serializable
private data class DemoTask(val id: String, val title: String, val state: String)

@Serializable
private data class DemoTasksResponse(val mode: String, val tasks: List<DemoTask>)

@Serializable
private data class DemoRunPreviewResponse(
    val mode: String,
    val runId: String,
    val status: String,
    val stages: List<String>,
    val message: String,
)

/**
 * Read-only and simulated: no LLM, exkes, GitHub, memory or operation-journal writes.
 * The GET preview is not an actual task dispatch or completed development run.
 */
fun Route.configureDevPreviewRoutes() {
    get("/api/dev/tasks") {
        call.respond(
            DemoTasksResponse(
                mode = "SIMULATED",
                tasks = listOf(
                    DemoTask("demo-1", "Inspect a repository issue", "READY"),
                    DemoTask("demo-2", "Draft a review plan", "READY"),
                ),
            ),
        )
    }
    get("/api/dev/runs/preview") {
        call.respond(
            HttpStatusCode.OK,
            DemoRunPreviewResponse(
                mode = "SIMULATED",
                runId = "preview-only",
                status = "NOT_EXECUTED",
                stages = listOf("READ_ISSUE", "PLAN", "HUMAN_REVIEW"),
                message = "Preview only: no code executed, no tokens spent, no external writes",
            ),
        )
    }
}
