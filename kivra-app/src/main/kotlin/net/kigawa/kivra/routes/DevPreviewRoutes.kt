package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Deliberately simulated and read-only. These endpoints never invoke the LLM,
 * exkes, GitHub or any production operation journal transition.
 *
 * Only wire this route behind an explicit development flag.
 */
fun Route.configureDevPreviewRoutes() {
    get("/api/dev/tasks") {
        call.respond(
            mapOf(
                "mode" to "SIMULATED",
                "tasks" to listOf(
                    mapOf("id" to "demo-1", "title" to "Inspect a repository issue", "state" to "READY"),
                    mapOf("id" to "demo-2", "title" to "Draft a review plan", "state" to "READY"),
                ),
            ),
        )
    }
    get("/api/dev/runs/preview") {
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "mode" to "SIMULATED",
                "runId" to "preview-only",
                "status" to "NOT_EXECUTED",
                "stages" to listOf("READ_ISSUE", "PLAN", "HUMAN_REVIEW"),
                "message" to "Preview only: no code executed, no tokens spent, no external writes",
            ),
        )
    }
}
