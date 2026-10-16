package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.selfdev.GitHubAuth
import net.kigawa.kivra.selfdev.SelfDevSettings

/**
 * Read-only views for the Web UI (`/ui`). Registered with the Task API identity, so every route needs an authenticated caller
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

internal fun Route.configureRunViewRoutes(runs: DevelopmentRunRepository, identity: TaskApiIdentity, selfDev: SelfDevSettings) {
    val targetRepo = selfDev.config?.repo

    get("/api/selfdev/status") {
        if (identity.principal(call.request.headers) == null) {
            call.respond(HttpStatusCode.Unauthorized, RunViewError("unauthorized"))
            return@get
        }
        call.respond(selfDevStatusBody(selfDev) { runs.activeSelfDevRunIds().size })
    }

    get("/api/admin/runs") {
        if (identity.principal(call.request.headers) == null) {
            call.respond(HttpStatusCode.Unauthorized, RunViewError("unauthorized"))
            return@get
        }
        val raw = call.request.queryParameters["limit"]
        val limit = if (raw == null) DEFAULT_OPERATOR_LIMIT else raw.toIntOrNull()
        if (limit == null || limit !in 1..MAX_OPERATOR_LIMIT) {
            call.respond(HttpStatusCode.BadRequest, RunViewError("limit must be 1-$MAX_OPERATOR_LIMIT"))
            return@get
        }
        call.respond(runs.listAllTasks(limit).map { runs.viewOf(it, targetRepo, createdBy = it.principal) })
    }

    get("/api/admin/runs/{id}") {
        if (identity.principal(call.request.headers) == null) {
            call.respond(HttpStatusCode.Unauthorized, RunViewError("unauthorized"))
            return@get
        }
        val id = call.parameters["id"]?.takeIf { RUN_ID_SHAPE.matches(it) }
        val run = id?.let { runs.getAnyTask(RunId(it)) }
        if (run == null) {
            call.respond(HttpStatusCode.NotFound, RunViewError("run not found"))
            return@get
        }
        call.respond(runs.viewOf(run, targetRepo, createdBy = run.principal, withEvents = true))
    }
}
