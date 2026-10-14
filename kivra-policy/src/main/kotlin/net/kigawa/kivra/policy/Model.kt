package net.kigawa.kivra.policy

/** Risk classes from design #1 section 6.3. */
enum class RiskClass { LOW, MEDIUM, HIGH, FORBIDDEN }

enum class Role { PLANNER, DEVELOPER, REVIEWER, ORCHESTRATOR }

/** Identity established by token verification. Never taken from the request body. */
data class Principal(
    val runId: String,
    val agentId: String,
    val role: Role,
    /** Action globs the short-lived token is scoped to. Empty scope allows nothing. */
    val scopes: Set<String>,
)

/**
 * A tool call from an Agent. Everything here is UNTRUSTED self-declaration. [resource] is only a hint and
 * [params] is raw input for the trusted per-action [CommandDecoder]; neither is used for authorization or
 * handed to a handler directly. Authorization uses the [CanonicalCommand] decoded from [params].
 */
data class ToolRequest(
    val token: String,
    val action: String,
    val resource: String,
    val params: Map<String, String> = emptyMap(),
    /** Id of a human approval previously requested for this exact operation (see [OperationDigest]). */
    val approvalId: String? = null,
)

sealed interface ToolResult {
    data class Success(val output: String) : ToolResult
    data class Denied(val reason: String, val decisionId: String) : ToolResult
    data class ApprovalRequired(val digest: OperationDigest, val risk: RiskClass, val reason: String, val decisionId: String) : ToolResult
    /** The handler definitely did not perform the side effect (it threw [ToolFailure]). */
    data class Failed(val reason: String) : ToolResult
    /**
     * The side effect may or may not have happened (handler crashed after the approval was consumed, or the
     * result could not be durably recorded). The operation must be treated as UNKNOWN and moved to
     * RECONCILIATION_REQUIRED (kivra-core OperationStatus); it must not be retried on a guess.
     */
    data class Unknown(val reason: String, val decisionId: String) : ToolResult
}

sealed interface Decision {
    val reason: String
    data class Allow(val risk: RiskClass, override val reason: String) : Decision
    data class RequireApproval(val risk: RiskClass, override val reason: String) : Decision
    data class Deny(override val reason: String) : Decision
}

/**
 * The real target of a tool call, derived by trusted code ([CommandDecoder]) from the raw params.
 * Immutable and shared as the SAME object by policy evaluation, the approval [OperationDigest] and the handler,
 * so what is authorized, approved and executed cannot diverge.
 */
class CanonicalCommand private constructor(
    val action: String,
    /** Canonical resource id, e.g. `repo:org/name` or `ns:prod`, derived from the actual params. */
    val resource: String,
    /** Normalized arguments the handler executes with (sorted, defensively copied). */
    val args: Map<String, String>,
) {
    companion object {
        fun of(action: String, resource: String, args: Map<String, String> = emptyMap()): CanonicalCommand {
            require(action.isNotBlank()) { "blank action" }
            require(resource.isNotBlank() && resource.none { it.isWhitespace() || it.isISOControl() }) { "invalid canonical resource" }
            // Dot segments would let a path climb out of an allowed glob (e.g. "repo:org/../other" matching "repo:org/*").
            require(resource.substringAfter(':', resource).split('/').none { it == "." || it == ".." }) { "dot segment in canonical resource" }
            return CanonicalCommand(action, resource, java.util.Collections.unmodifiableMap(java.util.TreeMap(args)))
        }
    }
    override fun equals(other: Any?) = other is CanonicalCommand && action == other.action && resource == other.resource && args == other.args
    override fun hashCode() = listOf(action, resource, args).hashCode()
    override fun toString() = "CanonicalCommand($action, $resource)"
}

/** Trusted, per-action decoder: turns untrusted request params into the [CanonicalCommand]. Throws on malformed input (fail-closed). */
fun interface CommandDecoder { fun decode(request: ToolRequest): CanonicalCommand }

/** Strict helper: only [allowedKeys] may be present, [requiredKeys] must be, and the resource is derived from params. */
class StrictParamsDecoder(
    private val action: String,
    private val requiredKeys: Set<String>,
    private val optionalKeys: Set<String> = emptySet(),
    private val resourceOf: (Map<String, String>) -> String,
) : CommandDecoder {
    override fun decode(request: ToolRequest): CanonicalCommand {
        val p = request.params
        require(p.keys.containsAll(requiredKeys)) { "missing params: ${requiredKeys - p.keys}" }
        require((requiredKeys + optionalKeys).containsAll(p.keys)) { "unknown params: ${p.keys - requiredKeys - optionalKeys}" }
        return CanonicalCommand.of(action, resourceOf(p), p)
    }
}

/** Throw from a handler to signal a definite failure with NO side effect performed. Any other exception is treated as UNKNOWN. */
class ToolFailure(message: String) : Exception(message)
