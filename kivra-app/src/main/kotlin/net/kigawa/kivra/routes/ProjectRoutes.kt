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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.BackfillReport
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.ProjectBudgetStatus
import net.kigawa.kivra.db.ProjectActivityEntry
import net.kigawa.kivra.db.ImportAllResult
import net.kigawa.kivra.db.MemberPutResult
import net.kigawa.kivra.db.MemberRemoveResult
import net.kigawa.kivra.db.PROJECT_MEMBER_ROLES
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectMemberRow
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
    /** The members of a project (ADR 0022, PR-D2): `/api/projects/{id}/members`. */
    members: ProjectMemberRepository,
    /** The monthly budget of a project (ADR 0022, PR-E): `/api/projects/{id}/budget` and the dashboard's budget. */
    budgets: DbBudgetStore,
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
            val projectId = call.pathParam("projectId")
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (projectRepo.get(projectId) == null) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(projectRepo.listRepos(projectId).map { ProjectRepoResponse(repoKey = it) })
        }
        put {
            val principal = call.principalOr401() ?: return@put
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_repo_add")) return@put
            if (!call.requireJsonSameOrigin()) return@put
            val projectId = call.pathParam("projectId")
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
            val projectId = call.pathParam("projectId")
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

    // The members of a project (ADR 0022, PR-D2). Everything here needs ORDER_MANAGE: the list shows the assignees' names and the roles,
    // and a change decides who may act in the project. A project with members keeps at least one ADMIN (409 otherwise).
    route("/api/projects/{projectId}/members") {
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_members_list")) return@get
            val projectId = call.pathParam("projectId")
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!members.projectExists(projectId)) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(members.list(projectId).map { it.toResponse() })
        }
        // Every ACTIVE assignee that is not a member yet, added with the given role. `dryRun=true` (the default) only counts.
        post("import-all") {
            val principal = call.principalOr401() ?: return@post
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_members_import")) return@post
            if (!call.requireJsonSameOrigin()) return@post
            val dryRun = when (call.request.queryParameters["dryRun"]) {
                null, "true" -> true
                "false" -> false
                else -> return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_dry_run"))
            }
            val projectId = call.pathParam("projectId")
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val input = call.receive<ProjectMemberImportRequest>()
            if (input.role !in PROJECT_MEMBER_ROLES) return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_role"))
            val result = members.importAll(projectId, input.role, dryRun)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (!dryRun) {
                projectAdminLog.info(
                    "project admin action: action=PROJECT_MEMBERS_IMPORTED actor={} projectId={} role={} added={}",
                    operatorTag(principal), projectId, input.role, result.added,
                )
            }
            call.respond(result.toResponse(input.role))
        }
        route("{assigneeId}") {
            // Adds the assignee with the role, or changes the role of a member. The same role again changes nothing (200).
            put {
                val principal = call.principalOr401() ?: return@put
                if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_member_put")) return@put
                if (!call.requireJsonSameOrigin()) return@put
                val projectId = call.pathParam("projectId")
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                val assigneeId = call.pathParam("assigneeId")
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                val input = call.receive<ProjectMemberRequest>()
                if (input.role !in PROJECT_MEMBER_ROLES) return@put call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_role"))
                when (members.put(projectId, assigneeId, input.role)) {
                    MemberPutResult.CREATED -> {
                        projectAdminLog.info(
                            "project admin action: action=PROJECT_MEMBER_ADDED actor={} projectId={} target={} role={}",
                            operatorTag(principal), projectId, operatorTag(assigneeId), input.role,
                        )
                        call.respond(HttpStatusCode.Created, ProjectMemberRoleResponse(assigneeId, input.role))
                    }
                    MemberPutResult.UPDATED -> {
                        projectAdminLog.info(
                            "project admin action: action=PROJECT_MEMBER_ROLE_CHANGED actor={} projectId={} target={} role={}",
                            operatorTag(principal), projectId, operatorTag(assigneeId), input.role,
                        )
                        call.respond(ProjectMemberRoleResponse(assigneeId, input.role))
                    }
                    MemberPutResult.UNCHANGED -> call.respond(ProjectMemberRoleResponse(assigneeId, input.role))
                    MemberPutResult.PROJECT_NOT_FOUND, MemberPutResult.ASSIGNEE_NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
                    MemberPutResult.LAST_ADMIN -> call.respond(HttpStatusCode.Conflict, ProjectError("last_admin"))
                }
            }
            // Removes a member. The body is `{}`, as with the repository removal: the server accepts only JSON on a state-changing request.
            delete {
                val principal = call.principalOr401() ?: return@delete
                if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_member_remove")) return@delete
                if (!call.requireJsonSameOrigin()) return@delete
                val projectId = call.pathParam("projectId")
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                val assigneeId = call.pathParam("assigneeId")
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                when (members.remove(projectId, assigneeId)) {
                    MemberRemoveResult.REMOVED -> {
                        projectAdminLog.info(
                            "project admin action: action=PROJECT_MEMBER_REMOVED actor={} projectId={} target={}",
                            operatorTag(principal), projectId, operatorTag(assigneeId),
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    MemberRemoveResult.NOT_MEMBER, MemberRemoveResult.PROJECT_NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
                    MemberRemoveResult.LAST_ADMIN -> call.respond(HttpStatusCode.Conflict, ProjectError("last_admin"))
                }
            }
        }
    }

    // The monthly budget of a project (ADR 0022, PR-E). Everything here needs ORDER_MANAGE: the limit and the consumption are the operator's
    // view, and the dashboard shows them to the operator only. A project without a row has no limit. Audit lines carry no amounts.
    route("/api/projects/{projectId}/budget") {
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_budget_get")) return@get
            val projectId = call.pathParam("projectId")
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (projectRepo.get(projectId) == null) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(budgets.projectBudget(projectId).toResponse())
        }
        // Sets the monthly limit (whole yen) and the soft ratio (default 0.8). Both are checked before anything is written: 400 otherwise.
        put {
            val principal = call.principalOr401() ?: return@put
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_budget_put")) return@put
            if (!call.requireJsonSameOrigin()) return@put
            val projectId = call.pathParam("projectId")
                ?: return@put call.respond(HttpStatusCode.NotFound)
            val input = call.parseBudgetRequest()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_budget"))
            val status = budgets.setProjectBudget(projectId, input.monthlyLimitJpy, input.softRatio)
                ?: return@put call.respond(HttpStatusCode.NotFound)
            projectAdminLog.info("project admin action: action=PROJECT_BUDGET_SET actor={} projectId={}", operatorTag(principal), projectId)
            call.respond(status.toResponse())
        }
        // Removes the limit. The consumption is still booked. Idempotent: a project without a limit is 204 as well.
        delete {
            val principal = call.principalOr401() ?: return@delete
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_budget_delete")) return@delete
            if (!call.requireJsonSameOrigin()) return@delete
            val projectId = call.pathParam("projectId")
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            if (budgets.clearProjectBudget(projectId) == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                projectAdminLog.info("project admin action: action=PROJECT_BUDGET_CLEARED actor={} projectId={}", operatorTag(principal), projectId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/projects/{projectId}") {
        get("dashboard") {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_dashboard")) return@get
            val projectId = call.pathParam("projectId")
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
                    // The operator's view only: a viewer sees no limit and no consumption of the project.
                    budget = if (everyRun) budgets.projectBudget(projectId).toResponse() else null,
                ),
            )
        }
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_get")) return@get
            val projectId = call.pathParam("projectId")
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
            val projectId = call.pathParam("projectId")
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
            val projectId = call.pathParam("projectId")
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
            val runId = call.pathParam("runId")
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
            val runId = call.pathParam("runId")
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

@Serializable
data class ProjectMemberRequest(val role: String)

@Serializable
data class ProjectMemberImportRequest(val role: String)

/** A member: the assignee's id and display name, its role in the project and its state (ACTIVE or DISABLED). */
@Serializable
data class ProjectMemberResponse(val assigneeId: String, val displayName: String, val role: String, val state: String)

@Serializable
data class ProjectMemberRoleResponse(val assigneeId: String, val role: String)

/** The result of a bulk import (`import-all`): the counts only. */
@Serializable
data class ImportAllResponse(
    val dryRun: Boolean,
    val role: String,
    val candidates: Long,
    val added: Long,
    val alreadyMember: Long,
    val disabled: Long,
)

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
    /** The project's monthly budget (ADR 0022, PR-E). Only for the operator (scope "ALL"); null for everyone else. */
    val budget: ProjectBudgetResponse? = null,
)

/**
 * A project's budget for the current JST month (ADR 0022, PR-E). Money is decimal yen as a string. [monthlyLimitJpy], [softRatio],
 * [softLimitJpy] and [remainingJpy] are null when the project has no limit. [softExceeded] is a warning only.
 */
@Serializable
data class ProjectBudgetResponse(
    val projectId: String,
    val period: String,
    val monthlyLimitJpy: String?,
    val softRatio: String?,
    val softLimitJpy: String?,
    val committedJpy: String,
    val reservedJpy: String,
    val remainingJpy: String?,
    val softExceeded: Boolean,
)

/** The body of `PUT /api/projects/{id}/budget`: the limit in whole yen, and the soft ratio (0 < ratio <= 1; default 0.8). */
internal data class ProjectBudgetInput(val monthlyLimitJpy: BigDecimal, val softRatio: BigDecimal)

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

private fun ProjectMemberRow.toResponse() = ProjectMemberResponse(assigneeId = assigneeId, displayName = displayName, role = role, state = state)

private fun ImportAllResult.toResponse(role: String) = ImportAllResponse(
    dryRun = dryRun,
    role = role,
    candidates = candidates,
    added = added,
    alreadyMember = alreadyMember,
    disabled = disabled,
)

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

private fun ProjectBudgetStatus.toResponse(): ProjectBudgetResponse {
    val limit = monthlyLimitJpy
    val ratio = softRatio
    return ProjectBudgetResponse(
        projectId = projectId,
        period = period,
        monthlyLimitJpy = limit?.toPlainString(),
        softRatio = ratio?.stripTrailingZeros()?.toPlainString(),
        softLimitJpy = if (limit != null && ratio != null) limit.multiply(ratio).stripTrailingZeros().toPlainString() else null,
        committedJpy = microYenToJpy(committedMicroYen),
        reservedJpy = microYenToJpy(reservedMicroYen),
        remainingJpy = remainingMicroYen?.let { microYenToJpy(it) },
        softExceeded = softExceeded,
    )
}

private val BUDGET_FIELDS = setOf("monthlyLimitJpy", "softRatio")
private val DEFAULT_SOFT_RATIO = BigDecimal("0.8")

/**
 * The body of a budget PUT, or null: not an object, an unknown field, a value that is not a JSON number, or a value the store would refuse.
 * Nothing from the body is echoed back. A missing `softRatio` is the default 0.8.
 */
private suspend fun ApplicationCall.parseBudgetRequest(): ProjectBudgetInput? {
    val body = runCatching { receive<JsonObject>() }.getOrNull() ?: return null
    if (body.keys.any { it !in BUDGET_FIELDS }) return null
    val monthly = body["monthlyLimitJpy"]?.jsonNumber() ?: return null
    val ratio = body["softRatio"]?.let { it.jsonNumber() ?: return null } ?: DEFAULT_SOFT_RATIO
    return runCatching { DbBudgetStore.requireProjectBudget(monthly, ratio) }
        .map { ProjectBudgetInput(monthly, ratio) }
        .getOrNull()
}

/** A JSON number (never a string or a boolean), as a decimal, or null. */
private fun JsonElement.jsonNumber(): BigDecimal? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigDecimalOrNull()
