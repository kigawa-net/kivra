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
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.BackfillReport
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.ProjectActivityEntry
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.ProjectRow
import net.kigawa.kivra.db.ProjectSpend
import net.kigawa.kivra.db.RepoAddResult
import net.kigawa.kivra.github.RepoRef
import org.slf4j.LoggerFactory

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
 *
 * `GET /api/projects/{id}/dashboard` (read only, one screen): the project's Runs by state, the most recent Runs, the Runs' ledger
 * cost and their recent audit events. The operator (ORDER_MANAGE) counts every Run of the project; anyone else only their own, so
 * the scope is the same as the Run list's (scope "ALL" or "OWN" says which).
 */
internal fun Route.configureProjectRoutes(
    projectRepo: ProjectRepository,
    runs: DevelopmentRunRepository,
    identity: TaskApiIdentity,
    guard: PermissionGuard = offPermissionGuard(),
    /** The configured self-development repository: a pull request link is made only for it. Null: no link. */
    targetRepo: RepoRef? = null,
    /** The backfill of the project scope (ADR 0022, PR-B): `POST /api/projects/backfill`. */
    backfill: ProjectBackfillRepository,
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
        // The backfill (ADR 0022, PR-B). An operator's action: it changes rows of every project, so it needs ORDER_MANAGE. A dry run
        // (the default) only counts. Idempotent: it writes only rows whose project_id is NULL.
        post("backfill") {
            val principal = call.principalOr401() ?: return@post
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_backfill")) return@post
            if (!call.requireJsonSameOrigin()) return@post
            val dryRun = when (call.request.queryParameters["dryRun"]) {
                null, "true" -> true
                "false" -> false
                else -> return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_dry_run"))
            }
            val report = backfill.run(dryRun)
            projectAdminLog.info(
                "project admin action: action=PROJECT_BACKFILL mode={} actor={} counts={}",
                if (dryRun) "DRY_RUN" else "EXECUTE",
                operatorTag(principal),
                report.tables.entries.joinToString(",") { (table, t) -> "$table:${t.matched}/${t.updated}/${t.unmatched}" },
            )
            call.respond(report.toResponse())
        }
    }

    // The repositories a project owns (ADR 0022, V51). Reads need READ; changes need ORDER_MANAGE, as the project itself does.
    route("/api/projects/{projectId}/repos") {
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_repos_list")) return@get
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (projectRepo.get(projectId) == null) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(projectRepo.listRepos(projectId).map { ProjectRepoResponse(repoKey = it) })
        }
        put {
            val principal = call.principalOr401() ?: return@put
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_repo_add")) return@put
            if (!call.requireJsonSameOrigin()) return@put
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val input = call.receive<ProjectRepoRequest>()
            val repoKey = parseRepoKey(input.repoKey)
                ?: return@put call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_repo_key"))
            when (projectRepo.addRepo(projectId, repoKey)) {
                RepoAddResult.ADDED -> {
                    projectAdminLog.info("project admin action: action=PROJECT_REPO_ADDED actor={} projectId={}", operatorTag(principal), projectId)
                    call.respond(HttpStatusCode.Created, ProjectRepoResponse(repoKey = repoKey))
                }
                RepoAddResult.CONFLICT -> call.respond(HttpStatusCode.Conflict, ProjectError("repo_already_mapped"))
                RepoAddResult.PROJECT_NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
            }
        }
        delete {
            val principal = call.principalOr401() ?: return@delete
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_repo_remove")) return@delete
            if (!call.requireJsonSameOrigin()) return@delete
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            // The repository is named in the query (`?repoKey=owner/name`), so the path stays one segment.
            val repoKey = call.request.queryParameters["repoKey"]?.let { parseRepoKey(it) }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_repo_key"))
            if (!projectRepo.removeRepo(projectId, repoKey)) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                projectAdminLog.info("project admin action: action=PROJECT_REPO_REMOVED actor={} projectId={}", operatorTag(principal), projectId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/projects/{projectId}") {
        get("dashboard") {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_dashboard")) return@get
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val project = projectRepo.get(projectId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val everyRun = guard.permits(principal, Permission.ORDER_MANAGE, "project_dashboard")
            val scope = if (everyRun) null else principal
            val countsByState = runs.countProjectRunsByState(projectId, scope)
            call.respond(
                ProjectDashboardResponse(
                    project = project.toResponse(),
                    scope = if (everyRun) "ALL" else "OWN",
                    tasks = ProjectTaskCounts(total = countsByState.values.sum(), byState = countsByState),
                    recentRuns = runs.listProjectRuns(projectId, scope, DASHBOARD_RECENT_RUNS).map {
                        // The creator is shown only to the operator, as in GET /api/admin/runs.
                        runs.viewOf(it, targetRepo, createdBy = if (everyRun) it.principal else null, projectRepo = projectRepo)
                    },
                    spend = projectRepo.runSpend(projectId, scope).toResponse(),
                    activity = runs.projectActivity(projectId, scope, DASHBOARD_ACTIVITY).map { it.toResponse() },
                ),
            )
        }
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
data class ProjectRepoRequest(val repoKey: String)

@Serializable
data class ProjectRepoResponse(val repoKey: String)

/** The backfill result per table: matched (with a project to give), updated (written), unmatched (stays NULL). */
@Serializable
data class BackfillResponse(val dryRun: Boolean, val tables: List<BackfillTableResponse>)

@Serializable
data class BackfillTableResponse(val table: String, val matched: Long, val updated: Long, val unmatched: Long)

@Serializable
data class ProjectResponse(
    val projectId: String,
    val name: String,
    val description: String?,
)

/** The project dashboard (`GET /api/projects/{id}/dashboard`). Money is decimal yen as a string (the budget screen's form). */
@Serializable
data class ProjectDashboardResponse(
    val project: ProjectResponse,
    /** "ALL": the caller may manage projects, so every Run of the project is counted. "OWN": only the caller's own Runs are. */
    val scope: String,
    val tasks: ProjectTaskCounts,
    val recentRuns: List<TaskRunResponse>,
    val spend: ProjectSpendResponse,
    val activity: List<ProjectActivityResponse>,
)

/** The Runs of the dashboard's scope: their total and the count per state (only states that occur). */
@Serializable
data class ProjectTaskCounts(val total: Long, val byState: Map<String, Long>)

/**
 * The ledger cost of the dashboard's Runs, in yen. A Run has no spending limit of its own, so there is no limit here.
 * The Planner's cost is not included (it belongs to a cycle, not to one Run).
 */
@Serializable
data class ProjectSpendResponse(
    val committedJpy: String,
    val reservedJpy: String,
    val lapsedJpy: String,
    val unpricedJpy: String,
    val totalJpy: String,
)

/** One audit event of a Run of the dashboard. Only fixed codes; no actor and no free text. */
@Serializable
data class ProjectActivityResponse(
    val runId: String,
    val occurredAt: String,
    val action: String,
    val result: String,
    val toState: String? = null,
    val reasonCode: String? = null,
)

private const val DASHBOARD_RECENT_RUNS = 10

/** Audit lines of the project administration: fixed codes and ids only. A repository name is never logged. */
private val projectAdminLog = LoggerFactory.getLogger("net.kigawa.kivra.project.admin")

/** owner/name as [RepoRef] accepts it (the rule of the self-development target), or null. The value is returned as given. */
internal fun parseRepoKey(value: String): String? {
    val parts = value.split('/')
    if (parts.size != 2) return null
    return runCatching { RepoRef(parts[0], parts[1]) }.getOrNull()?.toString()
}

private fun BackfillReport.toResponse() = BackfillResponse(
    dryRun = dryRun,
    tables = tables.map { (table, t) -> BackfillTableResponse(table = table, matched = t.matched, updated = t.updated, unmatched = t.unmatched) },
)
private const val DASHBOARD_ACTIVITY = 30

private fun ProjectSpend.toResponse(): ProjectSpendResponse {
    val total = committedMicroYen + reservedMicroYen + lapsedMicroYen + unpricedMicroYen
    return ProjectSpendResponse(
        committedJpy = microYenToJpy(committedMicroYen),
        reservedJpy = microYenToJpy(reservedMicroYen),
        lapsedJpy = microYenToJpy(lapsedMicroYen),
        unpricedJpy = microYenToJpy(unpricedMicroYen),
        totalJpy = microYenToJpy(total),
    )
}

/** Micro-yen (6 decimal places) as decimal yen with no trailing zeros. */
private fun microYenToJpy(micro: Long): String = BigDecimal.valueOf(micro, 6).stripTrailingZeros().toPlainString()

private fun ProjectActivityEntry.toResponse() = ProjectActivityResponse(
    runId = runId,
    occurredAt = occurredAt.toInstant(ZoneOffset.UTC).toString(),
    action = action,
    result = result,
    toState = toState,
    reasonCode = reasonCode,
)

private fun ProjectRow.toResponse() = ProjectResponse(
    projectId = projectId,
    name = name,
    description = description,
)
