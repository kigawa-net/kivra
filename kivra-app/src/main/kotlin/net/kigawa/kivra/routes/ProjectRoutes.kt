package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.ProjectRow

@Serializable
private data class ProjectError(val error: String)

/**
 * Project routes (V38): CRUD for projects and Run assignment.
 *
 * Authentication and authorization (Issue #387): every handler first resolves the caller through the Task API
 * [identity] (unauthenticated -> 401), then checks the [guard] (insufficient permission -> 403), exactly like
 * the neighbouring Task API routes. Reads need [Permission.READ]; creating, updating and deleting projects and
 * changing a Run's project need [Permission.ORDER_MANAGE], so under `KIVRA_SELFDEV_ROLES_MODE=enforce` a
 * viewer or the AI cannot manage projects. State-changing requests also go through [requireJsonSameOrigin],
 * like the other management routes.
 *
 * Projects have no owner column yet (the per-project authorization model is Issue #425), so visibility is
 * role-based, not per-principal. A Run, on the other hand, has an owning principal: assigning or unassigning a
 * Run owned by another principal additionally needs [Permission.ORDER_MANAGE] (the operator case, as with
 * `/api/admin/runs`); a caller without it gets a 404 for another principal's Run, so its existence is not
 * revealed.
 */
internal fun Route.configureProjectRoutes(
    projectRepo: ProjectRepository,
    runs: DevelopmentRunRepository,
    identity: TaskApiIdentity,
    guard: PermissionGuard = offPermissionGuard(),
) {
    /** The authenticated caller, or null after responding 401. */
    suspend fun ApplicationCall.principalOr401(): String? {
        val principal = identity.principal(request.headers)
        if (principal == null) respond(HttpStatusCode.Unauthorized, ProjectError("unauthorized"))
        return principal
    }

    /** True when [principal] holds [permission]; responds 403 otherwise. */
    suspend fun ApplicationCall.permitOr403(principal: String, permission: Permission, route: String): Boolean {
        if (guard.permits(principal, permission, route)) return true
        respond(HttpStatusCode.Forbidden, ProjectError("forbidden"))
        return false
    }

    route("/api/projects") {
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_list")) return@get
            val projects = projectRepo.listAll()
            call.respond(projects.map { it.toResponse() })
        }
        post {
            val principal = call.principalOr401() ?: return@post
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_create")) return@post
            if (!call.requireJsonSameOrigin()) return@post
            val input = call.receive<ProjectCreateRequest>()
            val project = projectRepo.create(input.name, input.description)
            call.respond(HttpStatusCode.Created, project.toResponse())
        }
    }

    route("/api/projects/{projectId}") {
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_get")) return@get
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val project = projectRepo.get(projectId)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(project.toResponse())
            }
        }
        put {
            val principal = call.principalOr401() ?: return@put
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_update")) return@put
            if (!call.requireJsonSameOrigin()) return@put
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val input = call.receive<ProjectUpdateRequest>()
            val project = projectRepo.update(projectId, input.name, input.description)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(project.toResponse())
            }
        }
        delete {
            val principal = call.principalOr401() ?: return@delete
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_delete")) return@delete
            if (!call.requireJsonSameOrigin()) return@delete
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val deleted = projectRepo.delete(projectId)
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/runs/{runId}/project") {
        put {
            val principal = call.principalOr401() ?: return@put
            val runId = call.parameters["runId"]
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val run = runs.getAnyTask(RunId(runId))
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val canManage = guard.permits(principal, Permission.ORDER_MANAGE, "project_run_assign")
            if (!canManage && run.principal != principal) {
                // Another principal's Run is indistinguishable from a missing one.
                return@put call.respond(HttpStatusCode.NotFound)
            }
            if (!canManage) {
                return@put call.respond(HttpStatusCode.Forbidden, ProjectError("forbidden"))
            }
            if (!call.requireJsonSameOrigin()) return@put
            val input = call.receive<ProjectAssignRequest>()
            val assigned = projectRepo.assignRunToProject(runId, input.projectId)
            if (!assigned) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
        delete {
            val principal = call.principalOr401() ?: return@delete
            val runId = call.parameters["runId"]
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val run = runs.getAnyTask(RunId(runId))
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val canManage = guard.permits(principal, Permission.ORDER_MANAGE, "project_run_unassign")
            if (!canManage && run.principal != principal) {
                return@delete call.respond(HttpStatusCode.NotFound)
            }
            if (!canManage) {
                return@delete call.respond(HttpStatusCode.Forbidden, ProjectError("forbidden"))
            }
            if (!call.requireJsonSameOrigin()) return@delete
            val unassigned = projectRepo.unassignRun(runId)
            if (!unassigned) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

@Serializable
data class ProjectCreateRequest(val name: String, val description: String? = null)

@Serializable
data class ProjectUpdateRequest(val name: String, val description: String? = null)

@Serializable
data class ProjectAssignRequest(val projectId: String)

@Serializable
data class ProjectResponse(
    val projectId: String,
    val name: String,
    val description: String?,
)

private fun ProjectRow.toResponse() = ProjectResponse(
    projectId = projectId,
    name = name,
    description = description,
)
