package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.ActiveSelfDevLimitException
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SELF_DEV_TASK_PREFIX
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RunBranch

/**
 * Self-development routes (ADR 0008 part 7, Issue #56). They are registered only when `KIVRA_SELFDEV_ENABLED=true` (see
 * `SelfDevSettings`). Authentication is the Task API's: the same [TaskApiIdentity], so a caller sees only its own runs.
 *
 * The only write is `POST /api/selfdev/tasks`. It creates a PR_ONLY run. kivra never merges or closes a pull request.
 */
internal class SelfDevRouteDeps(
    val runs: DevelopmentRunRepository,
    val identity: TaskApiIdentity,
    /** The one repository the runner may touch. Pull requests are looked up in this repository only. */
    val repo: RepoRef,
    val maxActiveRuns: Int,
    val github: GitHubPort,
)

/**
 * Body of `POST /api/selfdev/tasks`. Strict: any other field (`target`, `repo`, `branch`, `description`, ...) is rejected with 400
 * and never reflected. The MVP task text is the title only (see `DbTaskTextSource`).
 */
@Serializable
data class SubmitSelfDevTaskRequest(val title: String, val idempotencyKey: String)

/** A self-development run as the caller may see it. Never the task text, a patch, or model output. */
@Serializable
data class SelfDevRunResponse(
    val runId: String,
    val state: String,
    val mode: String = "SELF_DEV",
    val note: String? = null,
    val reasonCode: String? = null,
    val pullRequestNumber: Int? = null,
    val pullRequestUrl: String? = null,
)

@Serializable
private data class SelfDevError(val error: String)

private const val SELF_DEV_NOTE = "PR を作るまで、数分かかる。進捗は GET /api/selfdev/runs/{id}"
private val strictJson = Json { ignoreUnknownKeys = false }
private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")

internal fun Route.configureSelfDevRoutes(deps: SelfDevRouteDeps) {
    post("/api/selfdev/tasks") {
        val principal = deps.identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, SelfDevError("unauthorized"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val request = try {
            strictJson.decodeFromString(SubmitSelfDevTaskRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, SelfDevError("invalid request body"))
            return@post
        }
        val title = validatedText(request.title, MAX_TASK_TITLE_CODE_POINTS)
        val key = validatedText(request.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        if (title == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, SelfDevError("title must be 1-200 characters and idempotencyKey 1-128 characters, without control characters"))
            return@post
        }
        try {
            when (val result = deps.runs.submitTask(principal, key, title, selfDev = true, maxActiveSelfDev = deps.maxActiveRuns)) {
                is TaskSubmitResult.Created -> call.respond(HttpStatusCode.Accepted, result.run.toSelfDevResponse(note = SELF_DEV_NOTE))
                is TaskSubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.run.toSelfDevResponse(note = SELF_DEV_NOTE))
                is TaskSubmitResult.ConflictingTitle ->
                    call.respond(HttpStatusCode.Conflict, SelfDevError("idempotency key was already used with a different request"))
            }
        } catch (_: ActiveSelfDevLimitException) {
            call.respond(HttpStatusCode.TooManyRequests, SelfDevError("too many active self-development runs"))
        }
    }

    get("/api/selfdev/runs/{id}") {
        val principal = deps.identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, SelfDevError("unauthorized"))
            return@get
        }
        val id = call.parameters["id"]?.takeIf { RUN_ID_SHAPE.matches(it) }
        val run = id?.let { deps.runs.getTask(RunId(it), principal) }?.takeIf { it.taskId.startsWith(SELF_DEV_TASK_PREFIX) }
        if (run == null) {
            // Another caller's run and a missing run are the same 404.
            call.respond(HttpStatusCode.NotFound, SelfDevError("run not found"))
            return@get
        }
        val pullRequest = pullRequestOf(deps, run.runId.value)
        call.respond(
            run.toSelfDevResponse().copy(
                pullRequestNumber = pullRequest?.number,
                pullRequestUrl = pullRequest?.htmlUrl,
            ),
        )
    }

    get("/api/selfdev/runs") {
        val principal = deps.identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, SelfDevError("unauthorized"))
            return@get
        }
        call.respond(deps.runs.listTasks(principal, limit = LIST_LIMIT, selfDev = true).map { it.toSelfDevResponse() })
    }
}

private const val LIST_LIMIT = 100

/** The one open-or-closed pull request of the run in the target repository, found by its marker. Null when none or ambiguous. */
private suspend fun pullRequestOf(deps: SelfDevRouteDeps, runId: String): net.kigawa.kivra.github.PullRequest? {
    val list = when (val r = deps.github.findPullRequestsByHead(deps.repo, RunBranch.name(runId))) {
        is GitHubResult.Success -> r.value
        else -> return null
    }
    return list.filter { it.repo == deps.repo && it.hasMarker(runId) }.singleOrNull()
}

/**
 * Fields a caller may see on the self-development API (paths under `/api/selfdev`). The title is not returned here. The Task API views
 * (`/api/runs`, `/api/admin/runs`) do show it, to the principal and operators (ADR 0008 addendum, 2026-10-17).
 */
private fun DevelopmentRun.toSelfDevResponse(note: String? = null): SelfDevRunResponse = SelfDevRunResponse(
    runId = runId.value,
    state = state.name,
    note = note,
    reasonCode = stateReason,
)
