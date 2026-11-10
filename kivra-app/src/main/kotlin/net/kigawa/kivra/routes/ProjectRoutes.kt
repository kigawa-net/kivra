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
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectDrainResult
import net.kigawa.kivra.db.ProjectDeleteResult
import net.kigawa.kivra.db.ProjectChatMessageRow
import net.kigawa.kivra.db.ProjectChatRepository
import net.kigawa.kivra.db.ProjectChatSessionRow
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

/** The 409 of a project that still has rows: a fixed code and the count of each kind. No ids and no names. */
@Serializable
private data class ProjectNotEmptyError(val error: String, val counts: Map<String, Long>)

/** The 409 of a drain refused because the project is busy: a fixed code and the counts of what blocks it. No ids and no names. */
@Serializable
private data class ProjectBusyError(val error: String, val counts: Map<String, Long>)

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
<<<<<<< HEAD
    /** The drain of a project into another (ADR 0022): `POST /api/projects/{id}/drain`. */
    drain: ProjectDrainRepository,
=======
    /** The project chat (V53): `/api/projects/{id}/chat`. Null: the chat routes are not registered. */
    chatRepo: ProjectChatRepository? = null,
>>>>>>> f09208e (feat(project): プロジェクトチャット機能の追加 (V53))
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

    /**
     * Whether the caller may read [projectId] (ADR 0022 PR-D3, the read scope). A project it may not read is a 404, the same as an unknown
     * project, so the answer does not tell them apart. Shadow only logs.
     */
    fun mayReadProject(principal: String, projectId: String, route: String): Boolean {
        val access = guard.resolve(principal, legacyAdmin = true)
        guard.recordReadShadow(access, listOf(projectId), route)
        return guard.readScope(access).sees(projectId)
    }

    route("/api/projects") {
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_list")) return@get
            // The projects the caller may read (ADR 0022 PR-D3): every project unless ENFORCE; then its member projects only.
            val access = guard.resolve(principal, legacyAdmin = true)
            val scope = guard.readScope(access)
            val projects = projectRepo.listAll()
            guard.recordReadShadow(access, projects.map { it.projectId }, "project_list")
            call.respond(projects.filter { scope.sees(it.projectId) }.map { it.toResponse() })
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
            if (projectRepo.get(projectId) == null || !mayReadProject(principal, projectId, "project_repos_list")) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
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
            if (!members.projectExists(projectId) || !mayReadProject(principal, projectId, "project_members_list")) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
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
            if (projectRepo.get(projectId) == null || !mayReadProject(principal, projectId, "project_budget_get")) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
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
            if (!mayReadProject(principal, projectId, "project_dashboard")) return@get call.respond(HttpStatusCode.NotFound)
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
        // The project chat (V53). Registered only when a chat repository is given. Reads need READ and the project's read scope; sending
        // a message needs ORDER_MANAGE. The message is only stored here: no Task is submitted.
        if (chatRepo != null) {
            get("chat") {
                val principal = call.principalOr401() ?: return@get
                if (!call.permitOr403(principal, Permission.READ, "project_chat_get")) return@get
                val projectId = call.pathParam("projectId")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                if (projectRepo.get(projectId) == null || !mayReadProject(principal, projectId, "project_chat_get")) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                val session = chatRepo.getSession(projectId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(chatSessionResponse(chatRepo, session))
            }
            post("chat") {
                val principal = call.principalOr401() ?: return@post
                if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_chat_send")) return@post
                if (!call.requireJsonSameOrigin()) return@post
                val projectId = call.pathParam("projectId")
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                if (projectRepo.get(projectId) == null || !mayReadProject(principal, projectId, "project_chat_send")) {
                    return@post call.respond(HttpStatusCode.NotFound)
                }
                val input = call.receive<ChatSendMessageRequest>()
                if (input.content.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_content"))
                if (input.title.length > CHAT_TITLE_MAX) return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_title"))
                val session = chatRepo.getSession(projectId)
                    ?: chatRepo.createSession(projectId, input.title.ifBlank { DEFAULT_CHAT_TITLE })
                val message = chatRepo.appendMessage(session.projectId, "user", input.content)
                call.respond(HttpStatusCode.Created, message.toResponse())
            }
        }
        get {
            val principal = call.principalOr401() ?: return@get
            if (!call.permitOr403(principal, Permission.READ, "project_get")) return@get
            val projectId = call.pathParam("projectId")
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val project = projectRepo.get(projectId)
            if (project == null || !mayReadProject(principal, projectId, "project_get")) {
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
        // Only an empty project is deleted (ADR 0022): nothing is cascaded or unassigned. A project that still has rows is a 409 with the
        // count of each kind; the caller removes them first (see docs/runbook-selfdev.md).
        delete {
            val principal = call.principalOr401() ?: return@delete
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_delete")) return@delete
            if (!call.requireJsonSameOrigin()) return@delete
            val projectId = call.pathParam("projectId")
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            when (val result = projectRepo.deleteIfEmpty(projectId)) {
                ProjectDeleteResult.Deleted -> {
                    projectAdminLog.info("project admin action: action=PROJECT_DELETED actor={} projectId={}", operatorTag(principal), projectId)
                    call.respond(HttpStatusCode.NoContent)
                }
                ProjectDeleteResult.NotFound -> call.respond(HttpStatusCode.NotFound)
                is ProjectDeleteResult.NotEmpty -> {
                    projectAdminLog.info(
                        "project admin action: action=PROJECT_DELETE_BLOCKED actor={} projectId={} counts={}",
                        operatorTag(principal),
                        projectId,
                        result.counts.toMap().entries.joinToString(",") { (kind, n) -> "$kind=$n" },
                    )
                    call.respond(HttpStatusCode.Conflict, ProjectNotEmptyError(error = "PROJECT_NOT_EMPTY", counts = result.counts.toMap()))
                }
            }
        }
        // Empties this project into the target (ADR 0022, the drain): every row moves to the target, the members and the budget row of this
        // project are removed (the last ADMIN included, the one place where that is allowed), and nothing is deleted. Operators only. A dry run
        // (the default) only counts. Refused with 409 PROJECT_BUSY while this project has a Run that is not terminal, or a reservation in progress.
        post("drain") {
            val principal = call.principalOr401() ?: return@post
            if (!call.permitOr403(principal, Permission.ORDER_MANAGE, "project_drain")) return@post
            if (!call.requireJsonSameOrigin()) return@post
            val dryRun = when (call.request.queryParameters["dryRun"]) {
                null, "true" -> true
                "false" -> false
                else -> return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_dry_run"))
            }
            val projectId = call.pathParam("projectId")
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val input = runCatching { call.receive<ProjectDrainRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ProjectError("invalid_body"))
            if (projectRepo.get(projectId) == null || !mayReadProject(principal, projectId, "project_drain")) {
                return@post call.respond(HttpStatusCode.NotFound)
            }
            // The target is checked with the same rules as a new entity's project (ProjectScope.kt): required, well formed, existing.
            val targetId = input.targetProjectId
            when (val target = projectRepo.scopeOf(targetId)) {
                is ProjectScope.Refused -> return@post call.respond(target.status, ProjectError(target.error))
                is ProjectScope.Assigned -> Unit
            }
            if (targetId == projectId) return@post call.respond(HttpStatusCode.BadRequest, ProjectError("same_project"))
            if (!mayReadProject(principal, targetId, "project_drain")) return@post call.respond(HttpStatusCode.NotFound)
            when (val result = drain.drain(projectId, targetId, dryRun)) {
                ProjectDrainResult.NotFound -> {
                    projectAdminLog.info("project admin action: action=PROJECT_DRAIN mode={} result=NOT_FOUND actor={}", drainMode(dryRun), operatorTag(principal))
                    call.respond(HttpStatusCode.NotFound)
                }
                is ProjectDrainResult.Busy -> {
                    projectAdminLog.info(
                        "project admin action: action=PROJECT_DRAIN mode={} result=PROJECT_BUSY actor={} counts={}",
                        drainMode(dryRun),
                        operatorTag(principal),
                        "activeRuns=${result.busy.activeRuns},reservedBudget=${result.busy.reservedBudget}",
                    )
                    call.respond(
                        HttpStatusCode.Conflict,
                        ProjectBusyError(error = "PROJECT_BUSY", counts = mapOf("activeRuns" to result.busy.activeRuns, "reservedBudget" to result.busy.reservedBudget)),
                    )
                }
                is ProjectDrainResult.Done -> {
                    projectAdminLog.info(
                        "project admin action: action=PROJECT_DRAIN mode={} result=OK actor={} counts={}",
                        drainMode(dryRun),
                        operatorTag(principal),
                        result.counts.toMap().entries.joinToString(",") { (kind, n) -> "$kind=$n" },
                    )
                    call.respond(ProjectDrainResponse(dryRun = dryRun, targetProjectId = targetId, counts = result.counts.toMap()))
                }
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

/** The body of `POST /api/projects/{id}/drain`: the project that receives every row of this one. */
@Serializable
data class ProjectDrainRequest(val targetProjectId: String)

/** The drain's answer: whether it was a dry run, the target, and the rows of each kind that were (or, for a dry run, would be) moved. */
@Serializable
data class ProjectDrainResponse(val dryRun: Boolean, val targetProjectId: String, val counts: Map<String, Long>)

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

/** The body of `POST /api/projects/{id}/chat`. [title] names the session when this message creates it; otherwise it is ignored. */
@Serializable
data class ChatSendMessageRequest(val content: String, val title: String = "")

@Serializable
data class ChatMessageResponse(val id: Long, val sessionId: String, val role: String, val content: String, val createdAt: String)

/** A chat session with its latest messages (oldest first). [sessionId] is the project id. */
@Serializable
data class ChatSessionResponse(
    val sessionId: String,
    val title: String,
    val updatedAt: String,
    val messages: List<ChatMessageResponse>,
    val messageCount: Long,
)

private const val CHAT_RECENT_MESSAGES = 50
private const val CHAT_TITLE_MAX = 256
private const val DEFAULT_CHAT_TITLE = "Chat"

private fun chatSessionResponse(repo: ProjectChatRepository, session: ProjectChatSessionRow): ChatSessionResponse {
    val count = repo.countMessages(session.projectId)
    val offset = (count - CHAT_RECENT_MESSAGES).coerceAtLeast(0)
    return ChatSessionResponse(
        sessionId = session.projectId,
        title = session.title,
        updatedAt = session.updatedAt.toString(),
        messages = repo.getMessages(session.projectId, CHAT_RECENT_MESSAGES, offset).map { it.toResponse() },
        messageCount = count,
    )
}

private fun ProjectChatMessageRow.toResponse() = ChatMessageResponse(
    id = id, sessionId = sessionId, role = role, content = content, createdAt = createdAt.toString(),
)

private const val DASHBOARD_RECENT_RUNS = 10

/** Audit lines of the project administration: fixed codes and ids only. A repository name is never logged. */
private val projectAdminLog = LoggerFactory.getLogger("net.kigawa.kivra.project.admin")

/** The audit mode of a drain: a dry run counts, a real drain writes. */
private fun drainMode(dryRun: Boolean): String = if (dryRun) "DRY_RUN" else "EXECUTE"

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
