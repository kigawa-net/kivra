package net.kigawa.kivra.chat

import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.chat.ChatActionCode
import net.kigawa.kivra.core.chat.MEMBER_ABSENT
import net.kigawa.kivra.core.chat.MEMBER_PRESENT
import net.kigawa.kivra.db.AiProposalResult
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.MemoryFilter
import net.kigawa.kivra.db.MemoryRecordView
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectChatActionRepository
import net.kigawa.kivra.db.ProjectFilter
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.mcp.safeTaskTitle
import net.kigawa.kivra.mcp.summaryJson
import net.kigawa.kivra.memory.SecretFilter
import net.kigawa.kivra.routes.GuardResult
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.SelfDevOrderRouteDeps
import net.kigawa.kivra.routes.operatorTag
import net.kigawa.kivra.routes.ordersEnabled
import net.kigawa.kivra.routes.pauseAsOperator
import net.kigawa.kivra.routes.resumeAsOperator
import net.kigawa.kivra.routes.startCycleAsOperator
import net.kigawa.kivra.routes.updateAsOperator
import net.kigawa.kivra.routes.createChildAsOperator
import net.kigawa.kivra.routes.validatedPolicy
import net.kigawa.kivra.routes.validatedText
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.DEFAULT_CYCLE_BUDGET_JPY
import net.kigawa.kivra.selfdev.memory.MemoryKind
import net.kigawa.kivra.selfdev.memory.MemoryTier
import org.slf4j.LoggerFactory

/** The fixed status of one tool call, as the SSE `tool` frame carries it. STARTED is the frame before the call; the others end it. */
enum class ChatToolStatus { STARTED, OK, DENIED, PROPOSED, FAILED }

/** The end of one tool call: a fixed status and code (the frame and the audit), and the data the model reads (never instructions). */
data class ChatToolOutcome(val status: ChatToolStatus, val code: String, val data: JsonObject = JsonObject(emptyMap())) {
    /** True when the model should see the call as failed (refused or not done). */
    val isError: Boolean get() = status == ChatToolStatus.DENIED || status == ChatToolStatus.FAILED
}

/** One call as the loop sees it: the tool the name named (null for an unknown name) and the outcome. */
data class ChatToolCallResult(val tool: ChatTool?, val outcome: ChatToolOutcome)

/** What the reply loop needs of the tools of one turn: the tools offered, and the execution of a call by its wire name and JSON arguments. */
interface ChatToolRunner {
    val offered: List<ChatTool>

    /** Runs one call. Blocking (database). Never throws for a refusal or a failure: those are outcomes. */
    fun run(wireName: String, arguments: JsonElement): ChatToolCallResult
}

/**
 * The dependencies of the chat's tools (ADR 0023 追補 5). [orders] is null while the order routes are not registered: then no order or task tool
 * and no proposal is offered (a proposal is confirmed through the order routes). [chatActions] null: no proposal. [memory] null (the memory is off):
 * no memory tool.
 */
class ChatToolDeps(
    val projects: ProjectRepository,
    val runs: DevelopmentRunRepository,
    val members: ProjectMemberRepository,
    val guard: PermissionGuard,
    val orders: SelfDevOrderRouteDeps? = null,
    val chatActions: ProjectChatActionRepository? = null,
    val memory: SelfDevMemoryRepository? = null,
) {
    /** The tools of one turn: the chat's project, run as the operator [principal]. The permissions are read once, here. */
    fun forTurn(projectId: String, principal: String): ChatToolExecutor = ChatToolExecutor(this, projectId, principal)
}

/**
 * The execution of the chat AI's tool calls for one turn (ADR 0023 追補 5). The rules:
 *
 * - **The operator's principal**: every call runs as [principal], the operator who sent the message. The AI's own principal is never used. An
 *   operator the guard sees as the AI gets no tool at all.
 * - **The offered set**: [ChatTool.offeredTo] by the operator's ORDER_MANAGE (in the project), narrowed to what this server serves and to each
 *   tool's REST permission (a stop proposal needs STOP_REQUEST, the memory READ_TEXT). A call to a tool not offered is DENIED.
 * - **The same functions as REST**: the cycle start, the pause and resume, and the project update call the functions the REST routes call
 *   (with their audit lines). A proposal is a PROPOSED row of `project_chat_actions` with origin AI; nothing runs until the operator confirms.
 * - **The project**: every ID the model gives must be of the chat's project; any other (another project's, or a missing one) is NOT_FOUND, so
 *   the answer does not tell them apart.
 * - **The audit**: one line per call, `project chat tool: tool=… actor=<operatorTag> target=… result=…`, of ids and fixed codes. The arguments'
 *   text (a new name, a search word) is never logged.
 */
class ChatToolExecutor internal constructor(
    private val deps: ChatToolDeps,
    private val projectId: String,
    private val principal: String,
) : ChatToolRunner {
    private val access = deps.guard.resolve(principal, legacyAdmin = true)

    private fun allowed(permission: Permission): Boolean =
        !access.isAi && deps.guard.checkInProject(access, projectId, permission, ROUTE) is GuardResult.Allow

    private val canManageOrders = allowed(Permission.ORDER_MANAGE)
    private val orders = deps.orders?.takeIf { it.ordersEnabled() }

    /** One child order per turn (ADR 0023 追補 6). */
    private var childCreated = false

    override val offered: List<ChatTool> = if (access.isAi || !allowed(Permission.READ)) {
        emptyList()
    } else {
        ChatTool.offeredTo(canManageOrders, memoryEnabled = deps.memory != null && allowed(Permission.READ_TEXT)).filter(::serves)
    }

    /** Whether this server serves [tool] for this operator: the parts it needs exist, and the operator holds the REST route's permission. */
    private fun serves(tool: ChatTool): Boolean = when (tool) {
        ChatTool.ORDER_GET, ChatTool.ORDER_LIST, ChatTool.TASK_LIST -> orders != null
        ChatTool.ORDER_START_CYCLE, ChatTool.ORDER_PAUSE, ChatTool.ORDER_RESUME -> orders != null && canManageOrders
        ChatTool.ORDER_ABANDON_CYCLE, ChatTool.MEMBER_ADD, ChatTool.MEMBER_REMOVE ->
            orders != null && deps.chatActions != null && canManageOrders
        ChatTool.ORDER_STOP_REQUEST -> orders != null && deps.chatActions != null && allowed(Permission.STOP_REQUEST)
        ChatTool.PROJECT_UPDATE -> canManageOrders
        // A child needs the children to be on (KIVRA_SELFDEV_ORDER_MAX_DEPTH >= 1); the REST route answers CHILDREN_DISABLED otherwise.
        ChatTool.ORDER_CREATE_CHILD -> orders != null && canManageOrders && orders.orderSettings.maxDepth >= 1
        ChatTool.RUN_GET, ChatTool.RUN_LIST, ChatTool.PROJECT_GET, ChatTool.MEMORY_SEARCH, ChatTool.MEMORY_GET -> true
    }

    override fun run(wireName: String, arguments: JsonElement): ChatToolCallResult {
        val tool = ChatTool.fromWireName(wireName)
        val args = ChatToolArguments.parse(arguments, tool?.let { ChatToolArguments.numberArgumentsOf(it).keys }.orEmpty())
        val outcome = when {
            tool == null -> ChatToolOutcome(ChatToolStatus.DENIED, CODE_UNKNOWN_TOOL)
            tool !in offered -> ChatToolOutcome(ChatToolStatus.DENIED, CODE_NOT_OFFERED)
            args == null || !ChatToolArguments.isValid(tool, args) -> ChatToolOutcome(ChatToolStatus.DENIED, CODE_INVALID_ARGUMENTS)
            else -> try {
                execute(tool, args)
            } catch (e: Exception) {
                // The class only: a message could carry a value.
                log.warn("project chat tool failed: tool={} error={}", tool.name, e::class.simpleName)
                ChatToolOutcome(ChatToolStatus.FAILED, CODE_INTERNAL_ERROR)
            }
        }
        // The target is an ID the arguments carry (a valid one only), or the project. Never a text argument.
        val target = tool?.let { t ->
            ChatToolArguments.idArgumentsOf(t).firstNotNullOfOrNull { k -> args?.get(k)?.takeIf(ChatToolArguments::isId) }
        } ?: projectId
        log.info(
            "project chat tool: tool={} actor={} target={} result={}",
            tool?.name ?: "UNKNOWN", operatorTag(principal), target, outcome.code,
        )
        return ChatToolCallResult(tool, outcome)
    }

    private fun execute(tool: ChatTool, args: Map<String, String>): ChatToolOutcome = when (tool) {
        ChatTool.ORDER_LIST -> ok(buildJsonObject {
            put("orders", JsonArray(orders().orders.list(ORDER_LIST_LIMIT, ProjectFilter.Only(projectId)).map { it.summaryJson() }))
        })
        ChatTool.ORDER_GET -> withOrder(args) { order -> ok(orderJson(order)) }
        ChatTool.RUN_LIST -> ok(buildJsonObject {
            // The dashboard's scope: every Run of the project for an operator who manages orders, otherwise the caller's own.
            val rows = deps.runs.listProjectRuns(projectId, if (canManageOrders) null else principal, RUN_LIST_LIMIT)
            put("runs", JsonArray(rows.map(::runJson)))
        })
        ChatTool.RUN_GET -> {
            val run = runCatching { deps.runs.getAnyTask(RunId(args.getValue("runId"))) }.getOrNull()
                ?.takeIf { it.projectId == projectId && (canManageOrders || it.principal == principal) }
            if (run == null) notFound() else ok(runJson(run))
        }
        ChatTool.TASK_LIST -> ok(buildJsonObject {
            val tasks = orders().operations.listTasks(TASK_LIST_LIMIT, ProjectFilter.Only(projectId))
            put("tasks", JsonArray(tasks.map { t ->
                buildJsonObject {
                    put("task_id", t.taskId)
                    put("title", safeTaskTitle(t.title))
                    put("state", t.state.name)
                    put("state_reason", t.stateReason?.name)
                    put("requires_approval", t.requiresApproval)
                    put("parent_task_id", t.parentTaskId)
                    put("version", t.version)
                    put("updated_at", t.updatedAt.toInstant(ZoneOffset.UTC).toString())
                }
            }))
        })
        ChatTool.PROJECT_GET -> {
            val project = deps.projects.get(projectId)
            if (project == null) notFound() else ok(buildJsonObject {
                put("project_id", project.projectId)
                put("name", safeTaskTitle(project.name))
                put("description", project.description?.let(::safeText))
                // The members are the operator's view (the members route needs ORDER_MANAGE).
                if (canManageOrders) {
                    put("members", JsonArray(deps.members.list(projectId).map { m ->
                        buildJsonObject {
                            put("assignee_id", m.assigneeId)
                            put("display_name", safeTaskTitle(m.displayName))
                            put("role", m.role)
                            put("state", m.state)
                        }
                    }))
                }
            })
        }
        ChatTool.MEMORY_SEARCH -> memorySearch(args)
        ChatTool.MEMORY_GET -> {
            val record = deps.memory?.findInProject(args.getValue("recordId"), projectId)
            if (record == null) notFound() else ok(buildJsonObject { put("record", memoryJson(record, MEMORY_BODY_MAX)) })
        }
        ChatTool.ORDER_START_CYCLE -> withOrder(args) { order ->
            when (val r = orders().startCycleAsOperator(order.orderId, order.version, principal)) {
                is OrderResult.Moved -> ok(buildJsonObject {
                    put("order_id", order.orderId)
                    put("cycle_no", r.value.cycleNo)
                    put("goal_id", r.value.goalId)
                    put("version", r.value.order.version)
                })
                else -> refusalOf(r)
            }
        }
        ChatTool.ORDER_PAUSE -> withOrder(args) { order ->
            when (val r = orders().pauseAsOperator(order.orderId, order.version, principal)) {
                is OrderResult.Moved -> ok(buildJsonObject { put("order", r.value.summaryJson()) })
                else -> refusalOf(r)
            }
        }
        ChatTool.ORDER_RESUME -> withOrder(args) { order ->
            when (val r = orders().resumeAsOperator(order.orderId, order.version, principal)) {
                is OrderResult.Moved -> ok(buildJsonObject { put("order", r.value.summaryJson()) })
                else -> refusalOf(r)
            }
        }
        ChatTool.PROJECT_UPDATE -> {
            val current = deps.projects.get(projectId)
            if (current == null) {
                notFound()
            } else {
                // A field left out keeps its value; the REST route replaces both, so both are passed.
                val name = args["name"]?.trim() ?: current.name
                val description = if ("description" in args) args["description"]?.trim() else current.description
                val updated = deps.projects.updateAsOperator(projectId, name, description, principal)
                if (updated == null) notFound() else ok(buildJsonObject { put("project_id", updated.projectId) })
            }
        }
        ChatTool.ORDER_CREATE_CHILD -> createChild(args)
        ChatTool.ORDER_ABANDON_CYCLE -> withOrder(args) { order ->
            when {
                order.currentGoalId == null -> failed("NO_CYCLE_IN_PROGRESS")
                order.state == OrderState.STOPPED -> failed("TERMINAL")
                else -> propose(ChatActionCode.ORDER_ABANDON_CYCLE, order.orderId, order.version)
            }
        }
        ChatTool.ORDER_STOP_REQUEST -> withOrder(args) { order ->
            when (order.state) {
                OrderState.STOPPED -> failed("TERMINAL")
                OrderState.STOP_REQUESTED -> failed("STOP_ALREADY_PENDING")
                else -> propose(ChatActionCode.ORDER_STOP_REQUEST, order.orderId, order.version)
            }
        }
        ChatTool.MEMBER_ADD -> {
            val assignee = args.getValue("assigneeId")
            val actions = chatActions()
            when {
                !actions.assigneeExists(assignee) -> notFound()
                actions.membership(projectId, assignee) == MEMBER_PRESENT -> failed("ALREADY_MEMBER")
                else -> propose(ChatActionCode.MEMBER_ADD, assignee, MEMBER_ABSENT, args.getValue("role"))
            }
        }
        ChatTool.MEMBER_REMOVE -> {
            val assignee = args.getValue("assigneeId")
            // An assignee that is not a member of this project is not found here, as any ID outside the project.
            if (chatActions().membership(projectId, assignee) != MEMBER_PRESENT) notFound()
            else propose(ChatActionCode.MEMBER_REMOVE, assignee, MEMBER_PRESENT)
        }
    }

    /**
     * A child order under the project's root order, as `POST /api/selfdev/orders/{id}/children` creates it ([createChildAsOperator]), as the
     * operator (ADR 0023 追補 6). The guards against a flood: one per turn ([CODE_CREATE_LIMIT]), [CHILD_CREATE_PER_HOUR] per project and hour
     * (counted by the creations' key prefix [AI_CHILD_KEY_PREFIX] and their creation time), and no second ACTIVE child of the same title
     * ([CODE_ALREADY_EXISTS], with its ID). The budget is fixed by the server: the default cycle budget per cycle; never chosen by the model.
     */
    private fun createChild(args: Map<String, String>): ChatToolOutcome {
        if (childCreated) return failed(CODE_CREATE_LIMIT)
        val deps = orders()
        // The REST route's field checks, in code points.
        val title = validatedText(args.getValue("title"), ChatToolArguments.ORDER_TITLE_MAX) ?: return ChatToolOutcome(ChatToolStatus.DENIED, CODE_INVALID_ARGUMENTS)
        val policy = validatedPolicy(args.getValue("instruction")) ?: return ChatToolOutcome(ChatToolStatus.DENIED, CODE_INVALID_ARGUMENTS)
        val maxCycles = args["maxCycles"]?.toInt() ?: 1
        if (maxCycles !in 1..deps.orderSettings.maxCyclesCap) return failed("MAX_CYCLES_INVALID")
        val roots = deps.orders.list(ROOT_SCAN_LIMIT, ProjectFilter.Only(projectId)).filter { it.parentOrderId == null }
        if (roots.isEmpty()) return notFound()
        val active = roots.filter { it.state == OrderState.ACTIVE }
        val parent = when (active.size) {
            0 -> return failed(CODE_ROOT_NOT_ACTIVE)
            1 -> active.single()
            else -> return failed(CODE_AMBIGUOUS_ROOT)
        }
        deps.orders.activeChildWithTitle(projectId, title)?.let { existing ->
            return ChatToolOutcome(ChatToolStatus.FAILED, CODE_ALREADY_EXISTS, buildJsonObject {
                put("order_id", existing.orderId)
                put("state", existing.state.name)
            })
        }
        if (deps.orders.countChildrenWithKeyPrefixWithin(projectId, AI_CHILD_KEY_PREFIX, java.time.Duration.ofHours(1)) >= CHILD_CREATE_PER_HOUR) {
            return failed(CODE_HOURLY_CREATE_LIMIT)
        }
        val key = "$AI_CHILD_KEY_PREFIX${net.kigawa.kivra.db.Ulid.generate()}"
        val budget = DEFAULT_CYCLE_BUDGET_JPY * maxCycles
        return when (val r = deps.createChildAsOperator(parent.orderId, title, policy, budget, key, principal, maxCycles)) {
            is ChildCreateResult.Created -> ok(buildJsonObject {
                childCreated = true
                put("order_id", r.order.orderId)
                put("parent_order_id", parent.orderId)
                put("state", r.order.state.name)
                put("max_cycles", r.order.maxCycles)
                put("version", r.order.version)
                put("cycle_started", false)
            })
            is ChildCreateResult.Replayed -> ok(buildJsonObject {
                put("order_id", r.order.orderId)
                put("state", r.order.state.name)
            })
            is ChildCreateResult.Refused -> failed(r.reason.name)
            ChildCreateResult.KeyConflict -> failed("KEY_CONFLICT")
        }
    }

    /** The search: the newest records of the project (narrowed in SQL), then the word, matched against the text the model may see only. */
    private fun memorySearch(args: Map<String, String>): ChatToolOutcome {
        val repo = deps.memory ?: return notFound()
        val kind = args["kind"]?.let { k -> MemoryKind.entries.firstOrNull { it.name == k } }
        val word = args["query"]?.trim()?.lowercase()
        val rows = repo.search(MemoryFilter(kind = kind), MEMORY_SCAN_LIMIT, 0, ProjectFilter.Only(projectId))
        val hits = rows.filter { word == null || visibleText(it)?.lowercase()?.contains(word) == true || it.code?.lowercase()?.contains(word) == true }
            .take(MEMORY_HIT_LIMIT)
        var budget = MEMORY_TOTAL_MAX
        val items = hits.map { record ->
            val item = memoryJson(record, minOf(MEMORY_BODY_MAX, budget.coerceAtLeast(0)))
            budget -= visibleText(record)?.length ?: 0
            item
        }
        return ok(buildJsonObject {
            put("records", JsonArray(items))
            put("truncated", budget < 0)
        })
    }

    /**
     * The text of a record that the model may read: a TRUSTED_OPERATOR note's, with secrets redacted. An UNTRUSTED text (a model's or the
     * system's) is never given to a prompt (ADR 0016 U-1); a fact has no text.
     */
    private fun visibleText(record: MemoryRecordView): String? =
        record.body?.takeIf { record.tier == MemoryTier.TRUSTED_OPERATOR.name }?.let { SecretFilter.redact(it) }

    private fun memoryJson(record: MemoryRecordView, bodyMax: Int): JsonObject = buildJsonObject {
        put("record_id", record.recordId)
        put("kind", record.kind)
        put("tier", record.tier)
        put("goal_id", record.goalId)
        put("code", record.code)
        put("count_value", record.countValue)
        put("created_at", record.createdAt.toInstant(ZoneOffset.UTC).toString())
        val text = visibleText(record)
        when {
            text == null -> put("body_withheld", record.body != null)
            bodyMax <= 0 -> put("body_omitted", true)
            else -> {
                put("body", safeText(text).take(bodyMax))
                if (text.length > bodyMax) put("body_cut", true)
            }
        }
    }

    private fun propose(code: ChatActionCode, targetId: String, expected: Long, param: String? = null): ChatToolOutcome =
        when (val r = chatActions().proposeByAi(projectId, code, targetId, expected, param)) {
            is AiProposalResult.Created -> ChatToolOutcome(ChatToolStatus.PROPOSED, CODE_PROPOSED, proposalJson(r, code, already = false))
            is AiProposalResult.AlreadyOpen -> ChatToolOutcome(ChatToolStatus.PROPOSED, CODE_ALREADY_PROPOSED, proposalJson(r, code, already = true))
        }

    private fun proposalJson(r: AiProposalResult, code: ChatActionCode, already: Boolean): JsonObject = buildJsonObject {
        put("action_id", r.actionId)
        put("action_code", code.name)
        put("already_open", already)
        put("executed", false)
        put("next_step", "The operator confirms or dismisses this proposal on the screen. Nothing has been done yet.")
    }

    private fun orders(): SelfDevOrderRouteDeps = checkNotNull(orders) { "the order tools are not offered" }

    private fun chatActions(): ProjectChatActionRepository = checkNotNull(deps.chatActions) { "the proposals are not offered" }

    /** The order the arguments name, when it is of this project; NOT_FOUND otherwise (another project's order is never acted on). */
    private inline fun withOrder(args: Map<String, String>, block: (SelfDevOrder) -> ChatToolOutcome): ChatToolOutcome {
        val order = orders().orders.get(args.getValue("orderId"))?.takeIf { it.projectId == projectId } ?: return notFound()
        return block(order)
    }

    private fun orderJson(order: SelfDevOrder): JsonObject = buildJsonObject {
        order.summaryJson().forEach { (k, v) -> put(k, v) }
        put("title", safeTaskTitle(order.title))
        put("cycles", JsonArray(orders().orders.cyclesOf(order.orderId).map { c ->
            buildJsonObject {
                put("cycle_no", c.cycleNo)
                put("goal_id", c.goalId)
                put("goal_state", c.goalState)
                put("state_reason", c.stateReason)
            }
        }))
        put("stop_request_pending", orders().orders.pendingStopRequestOf(order.orderId) != null)
    }

    private fun runJson(run: DevelopmentRun): JsonObject = buildJsonObject {
        put("run_id", run.runId.value)
        put("state", run.state.name)
        put("stage", run.currentStage)
        put("reason_code", run.stateReason)
        put("created_at", run.createdAt.toInstant(ZoneOffset.UTC).toString())
        put("updated_at", run.updatedAt.toInstant(ZoneOffset.UTC).toString())
    }

    private fun refusalOf(r: OrderResult<*>): ChatToolOutcome = when (r) {
        OrderResult.NotFound -> notFound()
        OrderResult.StaleVersion -> failed("STALE_VERSION")
        is OrderResult.Refused -> failed(r.reason.name)
        is OrderResult.Moved<*> -> ok(JsonObject(emptyMap()))
    }

    private fun ok(data: JsonObject) = ChatToolOutcome(ChatToolStatus.OK, CODE_OK, data)

    private fun notFound() = ChatToolOutcome(ChatToolStatus.FAILED, CODE_NOT_FOUND)

    private fun failed(code: String) = ChatToolOutcome(ChatToolStatus.FAILED, code)

    /** Text for the model: control and bidi characters become a space (the text stays data; nothing in it can reorder what is shown). */
    private fun safeText(raw: String): String = raw.map { if (it.isISOControl() && it != '\n' || it in BIDI) ' ' else it }.joinToString("")

    companion object {
        private val log = LoggerFactory.getLogger("net.kigawa.kivra.project.chat")
        private const val ROUTE = "project_chat_tool"
        const val ORDER_LIST_LIMIT = 50
        const val RUN_LIST_LIMIT = 20
        const val TASK_LIST_LIMIT = 30

        /** How many of the newest records a search reads before the word is matched, and how many hits it returns. */
        const val MEMORY_SCAN_LIMIT = 100
        const val MEMORY_HIT_LIMIT = 10

        /** The longest text of one record, and of all the records of one search, given to the model. */
        const val MEMORY_BODY_MAX = 500
        const val MEMORY_TOTAL_MAX = 3_000

        const val CODE_OK = "OK"
        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_UNKNOWN_TOOL = "UNKNOWN_TOOL"
        const val CODE_NOT_OFFERED = "NOT_OFFERED"
        const val CODE_INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
        const val CODE_PROPOSED = "PROPOSED"
        const val CODE_ALREADY_PROPOSED = "ALREADY_PROPOSED"

        // The child order's codes (ADR 0023 追補 6).
        const val CODE_CREATE_LIMIT = "CREATE_LIMIT"
        const val CODE_HOURLY_CREATE_LIMIT = "HOURLY_CREATE_LIMIT"
        const val CODE_ALREADY_EXISTS = "ALREADY_EXISTS"
        const val CODE_ROOT_NOT_ACTIVE = "ROOT_NOT_ACTIVE"
        const val CODE_AMBIGUOUS_ROOT = "AMBIGUOUS_ROOT"

        /** The idempotency key prefix of the children the chat's AI creates: their origin, counted by the hourly limit. */
        const val AI_CHILD_KEY_PREFIX = "chat-ai:"

        /** The most children the chat's AI may create in one project within an hour. */
        const val CHILD_CREATE_PER_HOUR = 5

        private const val ROOT_SCAN_LIMIT = 200

        private val BIDI = setOf('؜', '‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩')
    }
}
