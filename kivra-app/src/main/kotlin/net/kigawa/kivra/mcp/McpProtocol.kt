package net.kigawa.kivra.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.routes.GuardAccess
import net.kigawa.kivra.routes.GuardResult
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.VIA_MCP
import net.kigawa.kivra.routes.offPermissionGuard
import org.slf4j.LoggerFactory

/** The protocol versions this server speaks (ADR 0013 section 2, decided in PR 1 against the MCP specification). */
internal const val MCP_LATEST_VERSION = "2025-06-18"
internal val MCP_SUPPORTED_VERSIONS = listOf(MCP_LATEST_VERSION, "2025-03-26")

/** Upper bound on one request body in bytes. */
const val MCP_MAX_BODY_BYTES = 64 * 1024

/** The fixed code and text of a refusal by the permission check. */
internal const val MCP_FORBIDDEN = "forbidden"

/** JSON-RPC 2.0 error codes. The -32000 range carries the transport and authentication failures of this endpoint. */
internal object RpcCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val UNAUTHORIZED = -32001
    const val FORBIDDEN = -32002
    const val INSUFFICIENT_SCOPE = -32003
    const val PAYLOAD_TOO_LARGE = -32005
    const val RATE_LIMITED = -32029
}

/** Thrown by a tool or the dispatcher for a bad argument. Maps to JSON-RPC -32602. Its message is fixed text, never a value. */
class InvalidParamsException(message: String) : RuntimeException(message)

/** The authenticated caller of one request: its owner key and the scopes its token carries. */
class McpCaller(
    val principal: String,
    val scopes: Set<String>,
    /** Whether the caller acts as the AI under the current mode. Set by the dispatcher from the permission guard, never by the route. */
    val isAi: Boolean = false,
)

/**
 * A tool's answer. [code] is a fixed outcome name for the audit line (for example `OK` or `NOT_FOUND`). [runId] is set only when the
 * answer names a run, and goes to the audit line (never a title or body).
 */
class ToolOutcome(val isError: Boolean, val text: String, val structured: JsonObject, val code: String, val runId: String? = null)

/**
 * One MCP tool. The scope, and then the [permission] (through the [PermissionGuard], ADR 0020 PR 3d), are checked by the dispatcher before
 * the handler runs; a handler never compares the caller with the AI principal.
 * - [permission]: what the caller needs. Required, with no default, so a tool that forgets it does not compile (never open by default).
 * - [refusalAction]: when set, a refusal by the permission check writes the Goal operator's refusal line with this action name.
 * - [write]: the tool changes state. Its calls count against the write rate limit of the dispatcher.
 * - [listed]: false keeps the tool out of `tools/list` while a call still reaches the handler (used for the self-development kill switch,
 *   whose refusal has a fixed reason). A tool that is not registered at all answers `unknown tool` instead.
 */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val scope: String,
    val permission: Permission,
    val write: Boolean = false,
    val listed: Boolean = true,
    val refusalAction: String? = null,
    val handler: suspend (McpCaller, JsonObject) -> ToolOutcome,
)

/**
 * The result of one message. [response] is null for a notification or a client response (answered with 202). [requiredScope] is set when
 * the caller lacks the scope of the tool it called: the route then answers 403 with `insufficient_scope` (the body is still JSON-RPC).
 */
class McpDispatch(
    val response: JsonObject?,
    val method: String,
    val tool: String?,
    val outcome: String,
    val requiredScope: String? = null,
    /** Set when the write rate limit refused the call: the route answers 429 (the body is still JSON-RPC). */
    val rateLimited: Boolean = false,
    /** The run the call named, for the audit line only. */
    val runId: String? = null,
)

internal fun rpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

internal fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

/**
 * Handles one JSON-RPC message of `/mcp` (ADR 0013 sections 2 and 3). Stateless: each message carries its own caller.
 *
 * Methods: `initialize` (version negotiation), `ping`, `tools/list` (only the tools the caller holds the scope for), `tools/call`.
 * A message without `id`, or one whose method starts with `notifications/`, gets no response. An unknown method is -32601.
 */
class McpDispatcher(
    private val tools: List<McpTool>,
    private val writeRateLimiter: McpRateLimiter? = null,
    private val guard: PermissionGuard = offPermissionGuard(),
) {
    private val logger = LoggerFactory.getLogger(McpDispatcher::class.java)

    /** The Goal operator's log, the same name as the REST routes' and the Goal tools' (ADR 0020 PR 3d moved the AI refusal here). */
    private val refusalLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.autopilot.operator")

    suspend fun dispatch(message: JsonObject, caller: McpCaller): McpDispatch {
        val rawId = message["id"]
        val idValid = rawId == null || isValidId(rawId)
        val id: JsonElement? = rawId?.takeIf { isValidId(it) }
        if (stringOf(message["jsonrpc"]) != "2.0") {
            return McpDispatch(rpcError(id, RpcCode.INVALID_REQUEST, "jsonrpc must be 2.0"), "", null, "invalid_request")
        }
        if (!message.containsKey("method")) {
            // A response from the client (to a server request, which kivra never makes) is accepted and ignored.
            return if (message.containsKey("result") || message.containsKey("error")) {
                McpDispatch(null, "", null, "client_response")
            } else {
                McpDispatch(rpcError(id, RpcCode.INVALID_REQUEST, "method is required"), "", null, "invalid_request")
            }
        }
        val method = stringOf(message["method"])
            ?: return McpDispatch(rpcError(id, RpcCode.INVALID_REQUEST, "method must be a string"), "", null, "invalid_request")
        if (!idValid) {
            return McpDispatch(rpcError(null, RpcCode.INVALID_REQUEST, "id must be a string or a number"), method, null, "invalid_request")
        }
        // A notification has no id, or is one of the notifications/* methods. Neither gets a response.
        if (rawId == null || method.startsWith("notifications/")) {
            return McpDispatch(null, method, null, "notification")
        }
        return try {
            handle(method, message["params"], rawId, caller)
        } catch (e: InvalidParamsException) {
            McpDispatch(rpcError(id, RpcCode.INVALID_PARAMS, e.message ?: "invalid params"), method, null, "invalid_params")
        }
    }

    private suspend fun handle(method: String, params: JsonElement?, id: JsonElement, caller: McpCaller): McpDispatch {
        return when (method) {
            "initialize" -> initialize(params, id)
            "ping" -> McpDispatch(rpcResult(id, buildJsonObject { }), method, null, "ok")
            "tools/list" -> McpDispatch(
                rpcResult(id, buildJsonObject {
                    putJsonArray("tools") {
                        tools.filter { it.listed && it.scope in caller.scopes }.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", tool.inputSchema)
                            })
                        }
                    }
                }),
                method, null, "ok",
            )
            "tools/call" -> toolsCall(params, id, caller)
            else -> McpDispatch(rpcError(id, RpcCode.METHOD_NOT_FOUND, "method not found"), method, null, "method_not_found")
        }
    }

    private fun initialize(params: JsonElement?, id: JsonElement): McpDispatch {
        val requested = stringOf((params as? JsonObject)?.get("protocolVersion"))
            ?: throw InvalidParamsException("protocolVersion is required")
        // A version this server does not speak is answered with the latest one it does. The client then decides whether to continue.
        val negotiated = if (requested in MCP_SUPPORTED_VERSIONS) requested else MCP_LATEST_VERSION
        val result = buildJsonObject {
            put("protocolVersion", negotiated)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", "kivra")
                put("version", "1")
            }
        }
        return McpDispatch(rpcResult(id, result), "initialize", null, "ok")
    }

    private suspend fun toolsCall(params: JsonElement?, id: JsonElement, caller: McpCaller): McpDispatch {
        val obj = params as? JsonObject ?: throw InvalidParamsException("params must be an object")
        val name = stringOf(obj["name"]) ?: throw InvalidParamsException("name is required")
        val tool = tools.firstOrNull { it.name == name } ?: throw InvalidParamsException("unknown tool")
        if (tool.scope !in caller.scopes) {
            return McpDispatch(rpcError(id, RpcCode.INSUFFICIENT_SCOPE, "insufficient scope"), "tools/call", tool.name, "insufficient_scope", tool.scope)
        }
        val arguments = when (val raw = obj["arguments"]) {
            null -> buildJsonObject { }
            is JsonObject -> raw
            else -> throw InvalidParamsException("arguments must be an object")
        }
        if (tool.write && writeRateLimiter != null && !writeRateLimiter.allow(caller.principal)) {
            return McpDispatch(
                rpcError(id, RpcCode.RATE_LIMITED, "rate limit exceeded"), "tools/call", tool.name, "rate_limited", rateLimited = true,
            )
        }
        // After the scope, the input shape and the write rate limit, and before the handler reads the input: where the handlers refused the AI.
        val access = guard.resolve(caller.principal, legacyAdmin = true, via = VIA_MCP)
        val denied = guard.check(access, tool.permission, tool.name, scopeOk = true, via = VIA_MCP)
        if (denied is GuardResult.Deny) return refused(id, tool, access, denied.code)
        val acting = McpCaller(caller.principal, caller.scopes, access.isAi)
        val outcome = try {
            tool.handler(acting, arguments)
        } catch (e: InvalidParamsException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("MCP tool failed: tool={} exceptionType={}", tool.name, e.javaClass.simpleName)
            return McpDispatch(rpcError(id, RpcCode.INTERNAL_ERROR, "internal error"), "tools/call", tool.name, "internal_error")
        }
        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", outcome.text)
                })
            }
            put("structuredContent", outcome.structured)
            put("isError", outcome.isError)
        }
        return McpDispatch(rpcResult(id, result), "tools/call", tool.name, outcome.code, runId = outcome.runId)
    }

    /** The tool's refusal for a caller without the permission: the fixed `forbidden` result the Goal tools always gave the AI. */
    private fun refused(id: JsonElement, tool: McpTool, access: GuardAccess, code: String): McpDispatch {
        tool.refusalAction?.let { action ->
            // For the AI that is the line its refusal has always written (`actor=AI result=forbidden`). Fixed values only.
            refusalLog.info(
                "autopilot operator refused: action={} via=mcp actor={} result={}",
                action, if (access.isAi) "AI" else "OPERATOR", if (access.isAi) MCP_FORBIDDEN else code,
            )
        }
        val outcome = ToolOutcome(
            isError = true,
            text = MCP_FORBIDDEN,
            structured = buildJsonObject { putJsonObject("error") { put("code", MCP_FORBIDDEN) } },
            code = MCP_FORBIDDEN,
        )
        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", outcome.text)
                })
            }
            put("structuredContent", outcome.structured)
            put("isError", true)
        }
        return McpDispatch(rpcResult(id, result), "tools/call", tool.name, outcome.code)
    }

    private fun isValidId(el: JsonElement): Boolean {
        if (el !is JsonPrimitive || el is JsonNull) return false
        return el.isString || el.intOrNull != null || el.content.toDoubleOrNull() != null
    }

    private fun stringOf(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The fixed fields of one request, for the audit line. Never an argument value, a run body or a token. */
internal fun mcpAuditLine(method: String, tool: String?, principal: String, outcome: String, runId: String? = null): String =
    "mcp.call via=mcp method=${method.take(64)} tool=${tool ?: "-"} principal=${principalHash(principal)} outcome=$outcome" +
        (runId?.let { " run_id=$it" } ?: "")

/** The first 12 hex characters of SHA-256 over the owner key. Stable for a caller, not reversible to the identity. */
internal fun principalHash(principal: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(principal.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(12)

/** Argument helpers. Each throws [InvalidParamsException] with a fixed text. */
internal fun JsonObject.requireOnly(vararg allowed: String) {
    val unknown = keys.firstOrNull { it !in allowed }
    if (unknown != null) throw InvalidParamsException("unknown argument")
}

internal fun JsonObject.optionalInt(name: String, range: IntRange, default: Int): Int {
    val el = this[name] ?: return default
    val primitive = el as? JsonPrimitive ?: throw InvalidParamsException("$name must be an integer")
    if (primitive.isString) throw InvalidParamsException("$name must be an integer")
    val value = primitive.intOrNull ?: throw InvalidParamsException("$name must be an integer")
    if (value !in range) throw InvalidParamsException("$name must be ${range.first} to ${range.last}")
    return value
}

internal fun JsonObject.optionalString(name: String, pattern: Regex? = null): String? {
    val el = this[name] ?: return null
    val primitive = el as? JsonPrimitive ?: throw InvalidParamsException("$name must be a string")
    if (!primitive.isString) throw InvalidParamsException("$name must be a string")
    val value = primitive.content
    if (pattern != null && !pattern.matches(value)) throw InvalidParamsException("$name has an invalid format")
    return value
}

internal fun JsonObject.requiredString(name: String, pattern: Regex? = null): String =
    optionalString(name, pattern) ?: throw InvalidParamsException("$name is required")

/** The JSON Schema object of a tool's input: strict (`additionalProperties: false`). */
internal fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (k, v) -> put(k, v) }
    }
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    put("additionalProperties", false)
}
