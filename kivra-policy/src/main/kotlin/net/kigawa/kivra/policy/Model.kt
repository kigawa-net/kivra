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
 * A tool call from an Agent. [params] is opaque data for the handler: it is never interpreted for
 * authorization, so instructions embedded in task text / issue bodies cannot change a decision.
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
    data class Failed(val reason: String) : ToolResult
}

sealed interface Decision {
    val reason: String
    data class Allow(val risk: RiskClass, override val reason: String) : Decision
    data class RequireApproval(val risk: RiskClass, override val reason: String) : Decision
    data class Deny(override val reason: String) : Decision
}
