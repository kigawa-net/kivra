package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.ProjectFilter
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.selfdev.GitHubAuth
import net.kigawa.kivra.selfdev.SelfDevSettings

/**
 * Read-only views for the Web UI (`/ui/compose`). Registered with the Task API identity, so every route needs an authenticated caller
 * (401 otherwise). Responses are allow-lists: no token, key, app id, WIF id or file path is ever returned.
 *
 * - `GET /api/admin/runs`: every Task API run of every principal (the operator view). Each run carries `createdBy`.
 * - `GET /api/admin/runs/{id}`: one run of any principal, with its audit events. A missing run is a 404.
 * - `GET /api/selfdev/status`: whether self-development is enabled, and a summary of its settings. When disabled, only
 *   `enabled: false` is returned (the other settings are not read).
 */
/**
 * The status body, built as an explicit JSON object so that only the listed keys appear. When disabled, the body is `{"enabled": false}`.
 * The GitHub credential is reported by kind only ("pat" or "app"), and the Anthropic one by kind only ("wif" or "api_key"). Never
 * a credential, a variable name, an app id, a WIF id or a file path.
 */
private fun selfDevStatusBody(settings: SelfDevSettings, activeRuns: () -> Int): JsonObject {
    val config = settings.config?.takeIf { settings.enabled } ?: return buildJsonObject { put("enabled", false) }
    return buildJsonObject {
        put("enabled", true)
        put("targetRepo", "${config.repo.owner}/${config.repo.repo}")
        put("baseBranch", config.baseBranch)
        put("maxActiveRuns", settings.maxActiveRuns)
        put("activeRuns", activeRuns())
        when (settings.githubAuth) {
            is GitHubAuth.Pat -> put("githubAuth", "pat")
            is GitHubAuth.App -> put("githubAuth", "app")
            else -> Unit
        }
        when {
            settings.anthropicWif != null -> put("anthropicAuth", "wif")
            settings.anthropicApiKeyEnvVar != null -> put("anthropicAuth", "api_key")
            else -> Unit
        }
    }
}

@Serializable
private data class RunViewError(val error: String)

private const val DEFAULT_OPERATOR_LIMIT = 50
private const val MAX_OPERATOR_LIMIT = 100
private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")

internal fun Route.configureRunViewRoutes(runs: DevelopmentRunRepository, identity: TaskApiIdentity,
    selfDev: SelfDevSettings,
    guard: PermissionGuard = offPermissionGuard(),
    projectRepo: ProjectRepository? = null,
) {
    val targetRepo = selfDev.config?.repo

    get("/api/selfdev/status") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RunViewError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "selfdev_status")) {
            call.respond(HttpStatusCode.Forbidden, RunViewError("forbidden"))
            return@get
        }
        // The active count covers the caller's projects only (ADR 0022 PR-D3). Off or shadow: every run, as before.
        val scope = guard.readScope(guard.resolve(principal, legacyAdmin = true))
        call.respond(selfDevStatusBody(selfDev) { runs.activeSelfDevRunIds(project = scope.narrow(ProjectFilter.All)).size })
    }

    get("/api/admin/runs") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RunViewError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "admin_runs")) {
            call.respond(HttpStatusCode.Forbidden, RunViewError("forbidden"))
            return@get
        }
        val raw = call.request.queryParameters["limit"]
        val limit = if (raw == null) DEFAULT_OPERATOR_LIMIT else raw.toIntOrNull()
        if (limit == null || limit !in 1..MAX_OPERATOR_LIMIT) {
            call.respond(HttpStatusCode.BadRequest, RunViewError("limit must be 1-$MAX_OPERATOR_LIMIT"))
            return@get
        }
        // The project narrowing (ADR 0022 PR-D): absent is every run, as before. A run with no project is `unassigned`.
        val project = projectFilterOf(call.request.queryParameters["projectId"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, RunViewError(PROJECT_FILTER_INVALID))
        // The rows the caller may read (ADR 0022 PR-D3): narrowed in SQL, before the limit. Shadow only records what would be hidden.
        val access = guard.resolve(principal, legacyAdmin = true)
        val rows = runs.listAllTasks(limit, project = guard.readScope(access).narrow(project))
        guard.recordReadShadow(access, rows.map { it.projectId }, "admin_runs")
        call.respond(rows.map { runs.viewOf(it, targetRepo, createdBy = it.principal, projectRepo = projectRepo) })
    }

    get("/api/admin/runs/{id}") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RunViewError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "admin_run_get")) {
            call.respond(HttpStatusCode.Forbidden, RunViewError("forbidden"))
            return@get
        }
        val id = call.pathParam("id")?.takeIf { RUN_ID_SHAPE.matches(it) }
        val run = id?.let { runs.getAnyTask(RunId(it)) }
        // A run of a project the caller may not read is the same 404 as a missing one (ADR 0022 PR-D3).
        val access = guard.resolve(principal, legacyAdmin = true)
        if (run != null) guard.recordReadShadow(access, listOf(run.projectId), "admin_run_get")
        if (run == null || !guard.readScope(access).sees(run.projectId)) {
            call.respond(HttpStatusCode.NotFound, RunViewError("run not found"))
            return@get
        }
        call.respond(runs.viewOf(run, targetRepo, createdBy = run.principal, withEvents = true, projectRepo = projectRepo))
    }
}
