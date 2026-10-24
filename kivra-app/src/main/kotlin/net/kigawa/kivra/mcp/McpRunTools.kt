package net.kigawa.kivra.mcp

import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SELF_DEV_TASK_PREFIX
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.pullRequestUrlOf

/** Scope of the read-only tools (ADR 0013 section 4). */
const val SCOPE_RUNS_READ = "kivra.runs.read"

private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val DEFAULT_LIST_LIMIT = 20
private const val MAX_LIST_LIMIT = 50

/**
 * The read-only tools of PR 1: `kivra_run_list` and `kivra_run_get`. Both see only the caller's own self-development runs (the owner key
 * `iss`+`sub`). Another caller's run and a missing run give the same answer (not found). No title, body, patch, token or log is returned.
 * Writes (`kivra_selfdev_start`, `kivra_run_stop`) are not defined here, so they are not in `tools/list` (ADR 0013 section 3).
 */
class McpRunTools(
    private val runs: DevelopmentRunRepository,
    private val targetRepo: RepoRef?,
) {
    fun tools(): List<McpTool> = listOf(runList(), runGet())

    private fun runList() = McpTool(
        name = "kivra_run_list",
        description = "List your own self-development runs, newest first. Returns run_id, state, stage, reason_code, pr_url and timestamps.",
        inputSchema = objectSchema(
            "limit" to buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", MAX_LIST_LIMIT)
                put("default", DEFAULT_LIST_LIMIT)
            },
            "cursor" to buildJsonObject {
                put("type", "string")
                put("pattern", "^[0-9A-Z]{26}$")
                put("description", "next_cursor from the previous page")
            },
        ),
        scope = SCOPE_RUNS_READ,
        permission = Permission.READ,
    ) { caller, args ->
        args.requireOnly("limit", "cursor")
        val limit = args.optionalInt("limit", 1..MAX_LIST_LIMIT, DEFAULT_LIST_LIMIT)
        val cursor = args.optionalString("cursor", RUN_ID_SHAPE)
        // The cursor must be one of the caller's own self-development runs. Anything else is the same invalid cursor.
        val after = cursor?.let { id ->
            runs.getTask(RunId(id), caller.principal)?.takeIf { it.taskId.startsWith(SELF_DEV_TASK_PREFIX) }
                ?: throw InvalidParamsException("cursor is not valid")
        }
        val page = runs.listSelfDevPage(caller.principal, limit, after)
        val items = buildJsonArray { page.forEach { add(runJson(it)) } }
        val structured = buildJsonObject {
            put("runs", items)
            put("next_cursor", if (page.size == limit) page.last().runId.value else null)
        }
        ToolOutcome(false, "${page.size} run(s)", structured, "OK")
    }

    private fun runGet() = McpTool(
        name = "kivra_run_get",
        description = "Get one of your own self-development runs by run_id. Another caller's run is reported as not found.",
        inputSchema = objectSchema(
            "run_id" to buildJsonObject {
                put("type", "string")
                put("pattern", "^[0-9A-Z]{26}$")
            },
            required = listOf("run_id"),
        ),
        scope = SCOPE_RUNS_READ,
        permission = Permission.READ,
    ) { caller, args ->
        args.requireOnly("run_id")
        val id = args.requiredString("run_id", RUN_ID_SHAPE)
        val run = runs.getTask(RunId(id), caller.principal)?.takeIf { it.taskId.startsWith(SELF_DEV_TASK_PREFIX) }
        if (run == null) {
            ToolOutcome(true, "run not found", buildJsonObject { put("reason_code", "NOT_FOUND") }, "NOT_FOUND")
        } else {
            ToolOutcome(false, "run ${run.runId.value}: ${run.state.name}", runJson(run), "OK")
        }
    }

    /** The allow-list of fields of one run. Built field by field, so nothing else of the row can leak into a response. */
    private fun runJson(run: DevelopmentRun): JsonObject {
        val binding = runs.loadPullRequestBinding(run.runId)
        return buildJsonObject {
            put("run_id", run.runId.value)
            put("kind", "selfdev")
            put("state", run.state.name)
            put("stage", run.currentStage)
            put("reason_code", run.stateReason)
            put("pr_url", binding?.let { pullRequestUrlOf(it, targetRepo) })
            put("created_at", run.createdAt.toInstant(ZoneOffset.UTC).toString())
            put("updated_at", run.updatedAt.toInstant(ZoneOffset.UTC).toString())
        }
    }
}
