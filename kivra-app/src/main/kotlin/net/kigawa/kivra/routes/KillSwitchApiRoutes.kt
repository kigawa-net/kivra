package net.kigawa.kivra.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.kigawa.kivra.orchestrator.KillSwitch
import net.kigawa.kivra.orchestrator.KillSwitchState
import org.slf4j.LoggerFactory

/**
 * Kill Switch API endpoints (design #3 section 9, Phase C6).
 *
 * Only humans should call these endpoints. The endpoints are unprotected by default
 * because they are intended for emergency use; in production, they should be behind
 * a separate authentication layer (e.g., admin-only API key).
 *
 * - GET /admin/kill-switch: Returns the current state.
 * - POST /admin/kill-switch/activate: Activate the kill switch with a specific state.
 * - POST /admin/kill-switch/deactivate: Deactivate the kill switch (return to normal).
 */
fun Route.configureKillSwitchRoutes(killSwitch: KillSwitch?) {
    if (killSwitch == null) return

    val logger = LoggerFactory.getLogger("net.kigawa.kivra.routes.KillSwitch")

    route("/admin/kill-switch") {
        get {
            call.respond(mapOf(
                "state" to killSwitch.currentState.name,
                "shouldBlockAdmission" to killSwitch.shouldBlockAdmission,
                "shouldStopAll" to killSwitch.shouldStopAll,
            ))
        }

        post("activate") {
            val body = call.receiveText().lowercase().trim()
            val newState = when (body) {
                "stop_new" -> KillSwitchState.STOP_NEW
                "stop_all" -> KillSwitchState.STOP_ALL
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "error" to "Invalid state. Use 'stop_new' or 'stop_all'."
                    ))
                    return@post
                }
            }
            killSwitch.activate(newState)
            logger.warn("kill switch activated via API: {}", newState)
            call.respond(mapOf(
                "state" to killSwitch.currentState.name,
                "message" to "Kill switch activated to $newState",
            ))
        }

        post("deactivate") {
            killSwitch.deactivate()
            logger.info("kill switch deactivated via API")
            call.respond(mapOf(
                "state" to killSwitch.currentState.name,
                "message" to "Kill switch deactivated, normal operation resumed",
            ))
        }
    }
}
