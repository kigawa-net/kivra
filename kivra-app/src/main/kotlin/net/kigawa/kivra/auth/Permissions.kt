package net.kigawa.kivra.auth

/**
 * Roles and permissions of the operator side (plan "assignees and permissions", PR 2). This file is pure: it depends on neither the
 * database package nor Ktor. The assignee store is reached only through [AssigneeLookup], which a later PR implements.
 *
 * The routes reach it through `PermissionGuard` (routes package), which also does the logging this file must not do.
 *
 * Nothing here logs. The [PrincipalAccess.source] and [Decision.Deny.code] values are fixed codes, never an owner key or a display name.
 */
enum class Role { ADMIN, OPERATOR, APPROVER, AI, VIEWER }

enum class Permission {
    /** Orders, goals, runs, memory, `/me`, MCP reads. */
    READ,

    /**
     * Reads that carry the operators' own text: a Goal's title, the memory. Split from [READ] so that an AI, which cannot read them today,
     * does not gain them when the table is enforced (ADR 0020, "why the split").
     */
    READ_TEXT,

    /** Stop a running Goal (`kivra_goal_stop`, the Goal stop route). An AI is refused today, so only ADMIN and OPERATOR have it. */
    GOAL_STOP,

    /** Create an order, start a cycle, pause, resume, change settings. */
    ORDER_MANAGE,

    /** Request a stop (REST and `kivra_goal_stop`). */
    STOP_REQUEST,

    /** Approve or reject a stop request. ADMIN (a human) and APPROVER have it; the requester must still differ from the approver. */
    STOP_DECIDE,

    /** Submit a goal, approve a plan or task, write memory. */
    GOAL_WRITE,

    /** `kivra_selfdev_start`, and the Task API's submit and cancel. */
    SPEND_START,

    /** Budget release, exchange rate, run release. */
    BUDGET_OVERRIDE,

    /** Create, re-role, bind and disable assignees. */
    ASSIGNEE_MANAGE,
}

private val OPERATOR_PERMISSIONS = setOf(
    Permission.READ,
    Permission.READ_TEXT,
    Permission.GOAL_STOP,
    Permission.ORDER_MANAGE,
    Permission.STOP_REQUEST,
    Permission.GOAL_WRITE,
    Permission.SPEND_START,
    Permission.BUDGET_OVERRIDE,
)

/** The fixed table. The database holds only assignees, roles and bindings, never this. */
val ROLE_PERMISSIONS: Map<Role, Set<Permission>> = mapOf(
    Role.ADMIN to (OPERATOR_PERMISSIONS + Permission.STOP_DECIDE + Permission.ASSIGNEE_MANAGE),
    Role.OPERATOR to OPERATOR_PERMISSIONS,
    Role.APPROVER to setOf(Permission.READ, Permission.STOP_REQUEST, Permission.STOP_DECIDE),
    Role.AI to setOf(Permission.READ, Permission.STOP_REQUEST, Permission.SPEND_START),
    Role.VIEWER to setOf(Permission.READ),
)

/** `KIVRA_SELFDEV_ROLES_MODE`. */
enum class PermissionMode { OFF, SHADOW, ENFORCE }

/** `KIVRA_SELFDEV_ROLES_DEFAULT`: what an admin-panel admin with no assignee gets. */
enum class RolesDefault { OPERATOR, NONE }

data class AssigneeAccess(val role: Role, val active: Boolean)

/** The assignee store as seen by the permission service. A later PR implements it over the database. */
interface AssigneeLookup {
    /** Null when the owner key is bound to no assignee. May throw; the service then denies (fail closed). */
    fun accessOf(ownerKey: String): AssigneeAccess?
}

enum class AccessSource {
    /** The owner key is the environment AI principal. */
    ENV_AI,

    /** As [ENV_AI], and the database holds another role for it. The caller records the error. The role stays AI. */
    ENV_AI_CONFLICT,

    /** The role comes from the assignee store. */
    ASSIGNEE,

    /** No assignee, and the default role applies (ENFORCE / SHADOW), or the legacy operator rules (OFF). */
    LEGACY_DEFAULT,

    /** The assignee is disabled. No permission. */
    ASSIGNEE_DISABLED,

    /** The assignee store failed. No permission, and the default is not used. */
    LOOKUP_FAILED,

    /** No role and no permission. */
    NONE,
}

data class PrincipalAccess(val role: Role?, val permissions: Set<Permission>, val source: AccessSource) {
    // Never prints anything that identifies a person.
    override fun toString(): String = "PrincipalAccess(role=$role, source=$source)"
}

sealed interface Decision {
    data object Allow : Decision

    /**
     * [code] is one of [DENY_NO_ROLE], [DENY_PERMISSION], [DENY_SCOPE]. [wouldDeny] is true in SHADOW mode: the caller must not refuse, only
     * record that it would have.
     */
    data class Deny(val code: String, val wouldDeny: Boolean = false) : Decision
}

const val DENY_NO_ROLE = "NO_ROLE"
const val DENY_PERMISSION = "PERMISSION_DENIED"
const val DENY_SCOPE = "SCOPE_DENIED"

class PermissionService(
    val mode: PermissionMode,
    private val default: RolesDefault,
    private val aiPrincipal: String?,
    private val rootApprover: String?,
    private val assignees: AssigneeLookup?,
) {
    init {
        require(aiPrincipal == null || aiPrincipal != rootApprover) { "the AI principal must not be the root approver" }
    }

    /**
     * [legacyAdmin]: the caller was authenticated as an operator by today's rule (the admin role of the verifier). The result depends on
     * [mode]: OFF reproduces today's decisions exactly, SHADOW and ENFORCE use the assignee store.
     */
    fun access(ownerKey: String, legacyAdmin: Boolean): PrincipalAccess =
        if (mode == PermissionMode.OFF) legacyAccess(ownerKey, legacyAdmin) else assigneeAccess(ownerKey, legacyAdmin)

    /**
     * Today's rules, whatever the mode. A caller in SHADOW mode acts on this and records what [access] says. Today the AI principal may
     * start a run, use the Task API and release budgets (no check exists); that is kept here and is a known gap, closed in ENFORCE.
     */
    fun legacyAccess(ownerKey: String, legacyAdmin: Boolean): PrincipalAccess = when {
        !legacyAdmin -> NO_ACCESS
        ownerKey == aiPrincipal -> PrincipalAccess(Role.AI, LEGACY_AI_PERMISSIONS, AccessSource.ENV_AI)
        ownerKey == rootApprover ->
            PrincipalAccess(Role.OPERATOR, OPERATOR_PERMISSIONS + Permission.STOP_DECIDE, AccessSource.LEGACY_DEFAULT)
        else -> PrincipalAccess(Role.OPERATOR, OPERATOR_PERMISSIONS, AccessSource.LEGACY_DEFAULT)
    }

    private fun assigneeAccess(ownerKey: String, legacyAdmin: Boolean): PrincipalAccess {
        val found: AssigneeAccess?
        try {
            found = assignees?.accessOf(ownerKey)
        } catch (_: Exception) {
            // The AI principal stays an AI even when the store is down: it can only lose permissions.
            return if (ownerKey == aiPrincipal) aiAccess(AccessSource.ENV_AI) else FAILED
        }
        if (ownerKey == aiPrincipal) {
            val conflict = found != null && (found.role != Role.AI)
            return aiAccess(if (conflict) AccessSource.ENV_AI_CONFLICT else AccessSource.ENV_AI)
        }
        if (assignees == null) return FAILED
        if (found == null) {
            return if (legacyAdmin && default == RolesDefault.OPERATOR) {
                PrincipalAccess(Role.OPERATOR, OPERATOR_PERMISSIONS, AccessSource.LEGACY_DEFAULT)
            } else {
                NO_ACCESS
            }
        }
        if (!found.active) return PrincipalAccess(null, emptySet(), AccessSource.ASSIGNEE_DISABLED)
        return PrincipalAccess(found.role, ROLE_PERMISSIONS.getValue(found.role), AccessSource.ASSIGNEE)
    }

    private fun aiAccess(source: AccessSource) = PrincipalAccess(Role.AI, ROLE_PERMISSIONS.getValue(Role.AI), source)

    /** [scopeOk]: the MCP token scope allows this operation (the caller computes it). The result is the intersection. */
    fun check(access: PrincipalAccess, permission: Permission, scopeOk: Boolean = true): Decision {
        val code = when {
            access.role == null -> DENY_NO_ROLE
            permission !in access.permissions -> DENY_PERMISSION
            !scopeOk -> DENY_SCOPE
            else -> return Decision.Allow
        }
        return Decision.Deny(code, wouldDeny = mode == PermissionMode.SHADOW)
    }

    private companion object {
        val NO_ACCESS = PrincipalAccess(null, emptySet(), AccessSource.NONE)
        val FAILED = PrincipalAccess(null, emptySet(), AccessSource.LOOKUP_FAILED)

        /** The AI's role permissions, plus the budget release that today has no AI check. Only OFF (legacy) uses it. */
        val LEGACY_AI_PERMISSIONS = ROLE_PERMISSIONS.getValue(Role.AI) + Permission.BUDGET_OVERRIDE
    }
}
