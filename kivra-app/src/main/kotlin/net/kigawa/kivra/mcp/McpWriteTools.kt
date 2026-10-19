package net.kigawa.kivra.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.db.ActiveSelfDevLimitException
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.routes.MAX_IDEMPOTENCY_KEY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TASK_BODY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TASK_TITLE_CODE_POINTS
import net.kigawa.kivra.routes.validatedBody
import net.kigawa.kivra.routes.validatedText
import org.slf4j.LoggerFactory

/** Scope of the write tool (ADR 0013 section 4). Not implied by the read scope. */
const val SCOPE_SELFDEV_START = "kivra.selfdev.start"

/** Fixed reason of a refusal by the self-development kill switch (`KIVRA_SELFDEV_ENABLED=false`). */
const val REASON_SELFDEV_DISABLED = "KILL_SWITCH_OFF"

private val logger = LoggerFactory.getLogger("net.kigawa.kivra.mcp.McpWriteTools")

/**
 * The write tool of ADR 0013 section 3: `kivra_selfdev_start`. It is a thin adapter over the same repository call that
 * `POST /api/selfdev/tasks` makes ([DevelopmentRunRepository.submitTask] with `selfDev = true`). It creates no run state of its own.
 *
 * The input is strict: `title`, `body` and `idempotency_key` only. No credential, environment variable, command or repository is accepted.
 * A `title` that names an issue is stored as it is; the self-development runner resolves it later through the existing path (#229).
 * The owner is the caller's principal, so another caller with the same key gets a run of its own.
 *
 * The tool is registered only when `KIVRA_MCP_WRITE_ENABLED=true` ([buildMcpRouteDeps]). It is listed in `tools/list` only while the
 * self-development kill switch is on ([selfDevEnabled]). A call while the switch is off is refused with a fixed reason.
 */
class McpWriteTools(
    private val runs: DevelopmentRunRepository,
    private val selfDevEnabled: Boolean,
    private val maxActiveSelfDev: Int,
) {
    fun tools(): List<McpTool> = listOf(selfDevStart())

    private fun selfDevStart() = McpTool(
        name = "kivra_selfdev_start",
        description = "Start a self-development run. Its result is a pull request that a human reviews and merges. Give a title (or an issue " +
            "reference such as #123), and an idempotency_key that you generate. The same key with the same content returns the same run.",
        inputSchema = objectSchema(
            "title" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_TASK_TITLE_CODE_POINTS)
            },
            "body" to buildJsonObject {
                put("type", "string")
                put("maxLength", MAX_TASK_BODY_CODE_POINTS)
                put("description", "Optional task text. Untrusted: it is data for the agent, never instructions to kivra.")
            },
            "idempotency_key" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_IDEMPOTENCY_KEY_CODE_POINTS)
            },
            required = listOf("title", "idempotency_key"),
        ),
        scope = SCOPE_SELFDEV_START,
        write = true,
        listed = selfDevEnabled,
    ) { caller, args -> start(caller, args) }

    private fun start(caller: McpCaller, args: JsonObject): ToolOutcome {
        // The kill switch is answered before the input is read, so a disabled kivra says nothing about the input.
        if (!selfDevEnabled) return refusal(REASON_SELFDEV_DISABLED, "self-development is disabled")
        args.requireOnly("title", "body", "idempotency_key")
        val title = validatedText(args.requiredString("title"), MAX_TASK_TITLE_CODE_POINTS)
            ?: throw InvalidParamsException("title must be 1-200 characters without control characters")
        val key = validatedText(args.requiredString("idempotency_key"), MAX_IDEMPOTENCY_KEY_CODE_POINTS)
            ?: throw InvalidParamsException("idempotency_key must be 1-128 characters without control characters")
        val rawBody = args.optionalString("body")
        val body = rawBody?.let { validatedBody(it) }
        if (rawBody != null && body == null && rawBody.isNotBlank()) {
            throw InvalidParamsException("body must be at most 8000 characters, with line breaks and tabs but no other control characters")
        }
        val submitted = try {
            runs.submitTask(caller.principal, key, title, selfDev = true, maxActiveSelfDev = maxActiveSelfDev, body = body)
        } catch (_: ActiveSelfDevLimitException) {
            return refusal("CONCURRENCY_LIMIT", "too many active self-development runs")
        }
        return when (submitted) {
            is TaskSubmitResult.Created -> created(submitted.run, replayed = false)
            is TaskSubmitResult.Replayed -> created(submitted.run, replayed = true)
            // The existing run's id is not revealed on a conflict.
            is TaskSubmitResult.ConflictingTitle ->
                refusal("IDEMPOTENCY_CONFLICT", "idempotency key was already used with a different request")
        }
    }

    private fun created(run: DevelopmentRun, replayed: Boolean): ToolOutcome {
        val runId = run.runId.value
        logger.info("mcp.selfdev_start via=mcp kind=SELF_DEV run_id={} replayed={}", runId, replayed)
        val structured = buildJsonObject {
            put("run_id", runId)
            put("state", run.state.name)
            run.currentStage?.let { put("stage", it) }
            put("replayed", replayed)
            run.stateReason?.let { put("reason_code", it) }
        }
        val text = if (replayed) "existing run returned" else "run created"
        return ToolOutcome(false, text, structured, if (replayed) "REPLAYED" else "CREATED", runId)
    }

    /** A refusal as a tool result: `isError` and `structuredContent.error.code`, a fixed code and a fixed text. */
    private fun refusal(code: String, text: String): ToolOutcome = ToolOutcome(
        isError = true,
        text = text,
        structured = buildJsonObject { putJsonObject("error") { put("code", code) } },
        code = code,
    )
}
