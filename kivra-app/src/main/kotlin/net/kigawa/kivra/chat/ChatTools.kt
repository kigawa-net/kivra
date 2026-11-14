package net.kigawa.kivra.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.db.PROJECT_MEMBER_ROLES
import net.kigawa.kivra.modelgateway.ToolSpec
import net.kigawa.kivra.selfdev.memory.MemoryKind

/**
 * The tools the project chat's AI may call (ADR 0023 追補 5). The AI never acts as itself: a tool runs under the operator's principal, and
 * the tools offered depend on that operator's permission. This file is the pure policy: the classification, the argument checks, the set offered
 * and the tool definitions sent to the model. The execution, the authorization against the project and the audit are in [ChatToolExecutor].
 */
enum class ChatToolDisposition {
    /** A read: runs at once, changes nothing. */
    READ,

    /** A safe, reversible operation: runs at once under the operator's principal. */
    IMMEDIATE,

    /** A destructive or consequential operation: never runs from the chat. The AI only proposes it; the operator confirms on the screen. */
    PROPOSE,
}

/**
 * The tools, with their classification. [needsOrderManage] tools are offered only to an operator who may manage orders. [needsMemory] tools are
 * offered only where the memory is enabled. [description] is the server's fixed text for the model (English, as the gateway's tool descriptions).
 */
enum class ChatTool(
    val disposition: ChatToolDisposition,
    val needsOrderManage: Boolean,
    val description: String,
    val needsMemory: Boolean = false,
) {
    ORDER_GET(ChatToolDisposition.READ, false, "Get one order of this project by orderId: its state, cycles, version and title."),
    ORDER_LIST(ChatToolDisposition.READ, false, "List the orders of this project (up to 50): ids, states, cycle counts and versions."),
    RUN_GET(ChatToolDisposition.READ, false, "Get one run of this project by runId: its state, stage and reason code."),
    RUN_LIST(ChatToolDisposition.READ, false, "List the newest runs of this project (up to 20): ids, states, stages and reason codes."),
    TASK_LIST(ChatToolDisposition.READ, false, "List the newest tasks of this project (up to 30): ids, states, reason codes and titles."),
    PROJECT_GET(ChatToolDisposition.READ, false, "Get this project: its name, description and, for an operator, its members."),
    MEMORY_SEARCH(
        ChatToolDisposition.READ, false,
        "Search this project's memory records, newest first (up to 10). Optional query (a word the text must contain) and kind.",
        needsMemory = true,
    ),
    MEMORY_GET(ChatToolDisposition.READ, false, "Get one memory record of this project by recordId.", needsMemory = true),
    ORDER_START_CYCLE(ChatToolDisposition.IMMEDIATE, true, "Start the next cycle of an order of this project now (as the operator)."),
    ORDER_PAUSE(ChatToolDisposition.IMMEDIATE, true, "Pause an active order of this project now (as the operator)."),
    ORDER_RESUME(ChatToolDisposition.IMMEDIATE, true, "Resume a paused order of this project now (as the operator)."),
    PROJECT_UPDATE(ChatToolDisposition.IMMEDIATE, true, "Change this project's name and/or description now (as the operator)."),
    ORDER_ABANDON_CYCLE(
        ChatToolDisposition.PROPOSE, true,
        "Propose abandoning the cycle in progress of an order. Only a proposal: the operator confirms it on the screen.",
    ),
    ORDER_STOP_REQUEST(
        ChatToolDisposition.PROPOSE, true,
        "Propose a stop request for an order. Only a proposal: the operator confirms it on the screen.",
    ),
    MEMBER_ADD(
        ChatToolDisposition.PROPOSE, true,
        "Propose adding an assignee to this project's members with a role. Only a proposal: the operator confirms it on the screen.",
    ),
    MEMBER_REMOVE(
        ChatToolDisposition.PROPOSE, true,
        "Propose removing a member from this project. Only a proposal: the operator confirms it on the screen.",
    ),
    ;

    /** The name the model sees and calls (lower case of the enum name). */
    val wireName: String get() = name.lowercase()

    /** The tool definition sent to the model: the fixed description and the JSON schema of [ChatToolArguments]. */
    fun spec(): ToolSpec = ToolSpec(wireName, description, ChatToolArguments.schemaOf(this))

    companion object {
        /** The tool with this enum name, or null for any name the server does not know (never guessed). */
        fun fromName(name: String): ChatTool? = entries.firstOrNull { it.name == name }

        /** The tool the model called by [wireName], or null for any other name (an unknown name is never guessed). */
        fun fromWireName(wireName: String): ChatTool? = entries.firstOrNull { it.wireName == wireName }

        /**
         * The tools offered to an operator: the reads always, the operations only with the order-management permission, and the memory reads
         * only when [memoryEnabled] (the memory is on and the operator may read its text).
         */
        fun offeredTo(canManageOrders: Boolean, memoryEnabled: Boolean = false): List<ChatTool> =
            entries.filter { (canManageOrders || !it.needsOrderManage) && (memoryEnabled || !it.needsMemory) }
    }
}

/** The argument checks of the tools. Every ID is a ULID (26 characters of Crockford base32); text has no control characters. */
object ChatToolArguments {
    private val ID = Regex("[0-9A-HJKMNP-TV-Z]{26}")
    const val NAME_MAX = 120

    /** The project's description column is VARCHAR(512). */
    const val DESCRIPTION_MAX = 512
    const val QUERY_MAX = 100

    /** The member roles a MEMBER_ADD may name: the members route's set ([PROJECT_MEMBER_ROLES]). Checked again at the confirmation. */
    val MEMBER_ROLES: List<String> = PROJECT_MEMBER_ROLES.toList()

    /** The memory kinds a search may name ([MemoryKind]). */
    val MEMORY_KINDS: List<String> = MemoryKind.entries.map { it.name }

    fun isId(value: String?): Boolean = value != null && ID.matches(value)

    /** Non-blank, at most [max] characters, and no control character (a line break is refused too: these are single values). */
    fun isText(value: String?, max: Int): Boolean =
        value != null && value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }

    /** The ID arguments a tool needs (all required): the order it acts on, the run, the memory record or the assignee. */
    fun idArgumentsOf(tool: ChatTool): Set<String> = when (tool) {
        ChatTool.ORDER_GET, ChatTool.ORDER_START_CYCLE, ChatTool.ORDER_PAUSE, ChatTool.ORDER_RESUME,
        ChatTool.ORDER_ABANDON_CYCLE, ChatTool.ORDER_STOP_REQUEST -> setOf("orderId")
        ChatTool.RUN_GET -> setOf("runId")
        ChatTool.MEMORY_GET -> setOf("recordId")
        ChatTool.MEMBER_ADD, ChatTool.MEMBER_REMOVE -> setOf("assigneeId")
        else -> emptySet()
    }

    /** The text arguments a tool takes (all optional), with their maximum length. */
    fun textArgumentsOf(tool: ChatTool): Map<String, Int> = when (tool) {
        ChatTool.PROJECT_UPDATE -> mapOf("name" to NAME_MAX, "description" to DESCRIPTION_MAX)
        ChatTool.MEMORY_SEARCH -> mapOf("query" to QUERY_MAX)
        else -> emptyMap()
    }

    /** The fixed-code arguments a tool takes, with their allowed values and whether they are required. */
    fun codeArgumentsOf(tool: ChatTool): Map<String, Pair<List<String>, Boolean>> = when (tool) {
        ChatTool.MEMBER_ADD -> mapOf("role" to (MEMBER_ROLES to true))
        ChatTool.MEMORY_SEARCH -> mapOf("kind" to (MEMORY_KINDS to false))
        else -> emptyMap()
    }

    /**
     * Whether [args] are valid for [tool]: each required ID is a ULID, each present text argument is in bounds, and each code is one of its
     * values. A key the tool does not take makes the call invalid, so an unexpected key cannot ride along. PROJECT_UPDATE needs one change.
     */
    fun isValid(tool: ChatTool, args: Map<String, String>): Boolean {
        val ids = idArgumentsOf(tool)
        val text = textArgumentsOf(tool)
        val codes = codeArgumentsOf(tool)
        if (!args.keys.all { it in ids || it in text || it in codes }) return false
        if (tool == ChatTool.PROJECT_UPDATE && args.isEmpty()) return false
        return ids.all { isId(args[it]) } &&
            text.all { (name, max) -> args[name] == null || isText(args[name], max) } &&
            codes.all { (name, rule) -> args[name]?.let { it in rule.first } ?: !rule.second }
    }

    /**
     * The model's arguments as a map of strings, or null when they are not a JSON object of strings (a number, a nested object or a null is
     * refused: every argument of these tools is a string).
     */
    fun parse(arguments: JsonElement): Map<String, String>? {
        val obj = arguments as? JsonObject ?: return null
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
    }

    /** The JSON schema of [tool]'s input: an object of string properties, the IDs required, no other property allowed. */
    fun schemaOf(tool: ChatTool): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            idArgumentsOf(tool).forEach { name ->
                putJsonObject(name) {
                    put("type", "string")
                    put("pattern", "^[0-9A-HJKMNP-TV-Z]{26}$")
                }
            }
            textArgumentsOf(tool).forEach { (name, max) ->
                putJsonObject(name) {
                    put("type", "string")
                    put("maxLength", max)
                }
            }
            codeArgumentsOf(tool).forEach { (name, rule) ->
                putJsonObject(name) {
                    put("type", "string")
                    putJsonArray("enum") { rule.first.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        val required = idArgumentsOf(tool) + codeArgumentsOf(tool).filterValues { it.second }.keys
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }
}
