package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.ProjectRepository

/** Project routes (V38): CRUD for projects and Run assignment. */
internal fun Route.configureProjectRoutes(projectRepo: ProjectRepository, identity: TaskApiIdentity) {
    route("/api/projects") {
        get {
            val projects = projectRepo.listAll()
            call.respond(projects)
        }
        post {
            val input = call.receive<ProjectCreateRequest>()
            val project = projectRepo.create(input.name, input.description)
            call.respond(HttpStatusCode.Created, project)
        }
    }

    route("/api/projects/{projectId}") {
        get {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val project = projectRepo.get(projectId)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(project)
            }
        }
        put {
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val input = call.receive<ProjectUpdateRequest>()
            val project = projectRepo.update(projectId, input.name, input.description)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(project)
            }
        }
        delete {
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
            val runId = call.parameters["runId"]
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val input = call.receive<ProjectAssignRequest>()
            val assigned = projectRepo.assignRunToProject(runId, input.projectId)
            if (!assigned) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
        delete {
            val runId = call.parameters["runId"]
                ?: return@delete call.respond(HttpStatusCode.NotFound)
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
