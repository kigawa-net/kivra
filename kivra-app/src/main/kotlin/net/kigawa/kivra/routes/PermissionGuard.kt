package net.kigawa.kivra.routes

import java.util.concurrent.TimeUnit
import net.kigawa.kivra.auth.AccessSource
import net.kigawa.kivra.auth.DENY_PERMISSION
import net.kigawa.kivra.auth.Decision
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.DENY_PROJECT_LOOKUP
import net.kigawa.kivra.auth.PrincipalAccess
import net.kigawa.kivra.auth.READ_NOT_MEMBER
import net.kigawa.kivra.auth.READ_UNASSIGNED
import net.kigawa.kivra.auth.ReadScope
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import org.slf4j.LoggerFactory

/*
 * The routes' entrance to the permission service (ADR 0020, PR 3a). Permissions.kt stays pure; the logging lives here.
 *
 *  - OFF: today's rules ([PermissionService.legacyAccess]). No store is touched. The decision is the one the route made before.
 *  - SHADOW: acts on today's rules, and records, with fixed fields only, a request that ENFORCE would have refused.
 *  - ENFORCE: [PermissionService.access] and [PermissionService.check]. A failure of the store is a refusal (fail closed).
 *
 * Nothing that identifies a person is logged: no owner key, no operator tag, no display name. The fields are the permission name, a fixed
 * code, the access source, the role name and the route name (a constant of the caller).
 */
/** The `via` of a guard log line: the entrance of the request. Fixed values only. */
internal const val VIA_REST = "rest"
internal const val VIA_MCP = "mcp"

sealed interface GuardResult {
    data object Allow : GuardResult

    /**
     * [code] is one of the fixed codes of Permissions.kt (`NO_ROLE`, `PERMISSION_DENIED`, `SCOPE_DENIED`, and for the project check
     * `PROJECT_NOT_MEMBER`, `PROJECT_PERMISSION_DENIED`, `PROJECT_LOOKUP_FAILED`).
     */
    data class Deny(val code: String) : GuardResult
}

/** The access of one request, looked up once. [isAi]: whether the caller acts as the AI under the current mode. */
class GuardAccess internal constructor(
    internal val effective: PrincipalAccess,
    internal val legacy: PrincipalAccess,
    val isAi: Boolean,
) {
    override fun toString(): String = "GuardAccess(isAi=$isAi)"
}

/** Where the guard writes. A test replaces it. */
interface GuardLog {
    fun info(message: String)
    fun warn(message: String)
}

private object Slf4jGuardLog : GuardLog {
    private val log = LoggerFactory.getLogger("net.kigawa.kivra.permission.guard")
    override fun info(message: String) = log.info(message)
    override fun warn(message: String) = log.warn(message)
}

class PermissionGuard(
    private val service: PermissionService,
    private val log: GuardLog = Slf4jGuardLog,
    /** Milliseconds; replaced by a test. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = TimeUnit.SECONDS.toMillis(60),
) {
    val mode: PermissionMode get() = service.mode

    /** Whether the project check can refuse or log anything: the roles and the project mode are both on (ADR 0022 PR-D). Reads nothing. */
    val projectScopeOn: Boolean get() = service.mode != PermissionMode.OFF && service.projectMode != PermissionMode.OFF

    private class Window(var startedAt: Long, var suppressed: Int)

    private val windows = HashMap<String, Window>()

    /** Looks the caller up. In OFF nothing is read. In SHADOW and ENFORCE the store is read once, and a failure is not an exception. */
    fun resolve(ownerKey: String, legacyAdmin: Boolean, via: String = VIA_REST): GuardAccess {
        val legacy = service.legacyAccess(ownerKey, legacyAdmin)
        if (service.mode == PermissionMode.OFF) return GuardAccess(legacy, legacy, legacy.role == Role.AI)
        val effective = try {
            service.access(ownerKey, legacyAdmin)
        } catch (_: Exception) {
            PrincipalAccess(null, emptySet(), AccessSource.LOOKUP_FAILED)
        }
        if (effective.source == AccessSource.ENV_AI_CONFLICT || effective.source == AccessSource.LOOKUP_FAILED) {
            throttled("anomaly|${effective.source}|$via")?.let {
                log.warn("permission anomaly: source=${effective.source} mode=${service.mode} via=$via suppressed=$it")
            }
        }
        val isAi = if (service.mode == PermissionMode.ENFORCE) effective.role == Role.AI else legacy.role == Role.AI
        return GuardAccess(effective, legacy, isAi)
    }

    /**
     * [humanOnly]: the AI is refused whatever its permissions (a route that the AI must not use even though it holds the permission, the
     * stop request's cancel). [route] is a fixed name, never a request value.
     */
    fun check(
        access: GuardAccess,
        permission: Permission,
        route: String,
        scopeOk: Boolean = true,
        humanOnly: Boolean = false,
        via: String = VIA_REST,
    ): GuardResult {
        val enforce = service.mode == PermissionMode.ENFORCE
        if (humanOnly && access.isAi) return GuardResult.Deny(DENY_PERMISSION)
        val acting = if (enforce) access.effective else access.legacy
        val decision = service.check(acting, permission, scopeOk)
        if (decision is Decision.Deny) return GuardResult.Deny(decision.code)
        if (service.mode == PermissionMode.SHADOW) recordWouldDeny(access, permission, route, scopeOk, humanOnly, via)
        return GuardResult.Allow
    }

    /**
     * The check of a request whose project is known (ADR 0022 PR-D, `KIVRA_SELFDEV_PROJECT_MODE`). [projectId] null (a row with no project, or
     * a request whose project is not known) is the plain [check]: the project is never guessed.
     *
     *  - Project OFF (or the roles OFF): the plain [check].
     *  - Project SHADOW: the plain [check] decides. When it allows and the project check would refuse, a fixed code is logged, and nothing else.
     *  - Project ENFORCE: a project refusal is a refusal. The global ADMIN always passes the project check.
     */
    fun checkInProject(
        access: GuardAccess,
        projectId: String?,
        permission: Permission,
        route: String,
        scopeOk: Boolean = true,
        humanOnly: Boolean = false,
        via: String = VIA_REST,
    ): GuardResult {
        val result = check(access, permission, route, scopeOk, humanOnly, via)
        if (result is GuardResult.Deny || projectId == null || !projectScopeOn) return result
        val code = projectDenialOf(access, projectId, permission, humanOnly) ?: return result
        return when (service.projectMode) {
            PermissionMode.ENFORCE -> GuardResult.Deny(code)
            PermissionMode.SHADOW -> {
                recordProjectWouldDeny(access, permission, route, code, via)
                result
            }
            PermissionMode.OFF -> result
        }
    }

    /**
     * The project refusal of the caller, or null. Null when the global check refuses (that refusal is the global check's own), or when the
     * caller is refused for humans only. It is only what the project adds to the global check.
     */
    private fun projectDenialOf(access: GuardAccess, projectId: String, permission: Permission, humanOnly: Boolean): String? {
        val eff = access.effective
        if (humanOnly && access.isAi) return null
        if (service.check(eff, permission) is Decision.Deny) return null
        return service.projectDenial(eff, projectId, permission)
    }

    /**
     * What the caller may read of the projects (ADR 0022 PR-D3). All unless the project mode is ENFORCE. A read narrows its list to it, or
     * answers 404 for a row it does not see.
     */
    fun readScope(access: GuardAccess): ReadScope = service.readScope(access.effective)

    /**
     * SHADOW only (ADR 0022 PR-D3): the rows a read returns that ENFORCE would hide, as fixed codes. [projectIds] are the projects of the rows
     * (null: a row with no project). It changes no answer. Nothing that identifies a person or a project is logged.
     */
    fun recordReadShadow(access: GuardAccess, projectIds: Collection<String?>, route: String, via: String = VIA_REST) {
        if (service.mode == PermissionMode.OFF || service.projectMode != PermissionMode.SHADOW) return
        val eff = access.effective
        val codes: List<String> = when (val scope = service.readsAsEnforce(eff)) {
            ReadScope.All -> return
            ReadScope.Refused -> listOf(DENY_PROJECT_LOOKUP)
            is ReadScope.Members -> buildList {
                if (projectIds.any { it == null }) add(READ_UNASSIGNED)
                if (projectIds.any { it != null && it !in scope.projectIds }) add(READ_NOT_MEMBER)
            }
        }
        for (code in codes) {
            throttled("read|$code|${eff.source}|$route|$via")?.let {
                log.info(
                    "project read shadow: wouldHide code=$code source=${eff.source} role=${eff.role ?: "NONE"} route=$route via=$via suppressed=$it",
                )
            }
        }
    }

    /** One call for a caller that has no [GuardAccess] yet. */
    fun check(ownerKey: String, legacyAdmin: Boolean, permission: Permission, scopeOk: Boolean = true, route: String): GuardResult =
        check(resolve(ownerKey, legacyAdmin), permission, route, scopeOk)

    /** Whether the caller holds [permission] under the current mode. Records nothing; for what a response shows, not for a refusal. */
    fun can(access: GuardAccess, permission: Permission): Boolean {
        val acting = if (service.mode == PermissionMode.ENFORCE) access.effective else access.legacy
        return service.check(acting, permission) == Decision.Allow
    }

    /**
     * The assignee admin API (ADR 0020, PR T3) needs the assignees to exist, so it is off with the roles off, and in SHADOW it is judged by
     * the permissions the store gives ([GuardAccess.effective]), not by today's rules: under today's rules nobody holds ASSIGNEE_MANAGE,
     * and the first AI assignee could never be made while the roles are only being watched.
     */
    val managementEnabled: Boolean get() = service.mode != PermissionMode.OFF

    /**
     * Whether the effective access holds [permission], whatever the mode. The AI is refused whatever it holds (the environment AI, a
     * database AI, and a caller that only is the AI under today's rules). A failed store has no role, so it is refused too. Records nothing.
     */
    fun permitsEffective(access: GuardAccess, permission: Permission): Boolean {
        if (access.isAi || access.legacy.role == Role.AI || access.effective.role == Role.AI) return false
        return service.check(access.effective, permission) == Decision.Allow
    }

    /** The project shadow's record: a fixed code, the permission, the source, the role and the route. No project id and no person. */
    private fun recordProjectWouldDeny(access: GuardAccess, permission: Permission, route: String, code: String, via: String) {
        val eff = access.effective
        val key = "project|$permission|$code|${eff.source}|$route|$via"
        throttled(key)?.let {
            log.info(
                "project shadow: wouldDeny permission=$permission code=$code source=${eff.source} role=${eff.role ?: "NONE"} " +
                    "route=$route via=$via suppressed=$it",
            )
        }
    }

    private fun recordWouldDeny(access: GuardAccess, permission: Permission, route: String, scopeOk: Boolean, humanOnly: Boolean, via: String) {
        val eff = access.effective
        val serviceDeny = (service.check(eff, permission, scopeOk) as? Decision.Deny)?.code
        val code = if (humanOnly && eff.role == Role.AI) DENY_PERMISSION else serviceDeny
        if (code == null) return
        val key = "deny|$permission|$code|${eff.source}|$route|$via"
        throttled(key)?.let {
            log.info(
                "permission shadow: wouldDeny permission=$permission code=$code source=${eff.source} role=${eff.role ?: "NONE"} " +
                    "route=$route via=$via suppressed=$it",
            )
        }
    }

    /** Null when the same [key] was logged within the window (the call is counted); otherwise the number suppressed since the last log. */
    private fun throttled(key: String): Int? = synchronized(windows) {
        val now = nowMillis()
        val w = windows[key]
        if (w == null || now - w.startedAt >= windowMillis) {
            windows[key] = Window(now, 0)
            w?.suppressed ?: 0
        } else {
            w.suppressed++
            null
        }
    }
}

/** The guard of a route that is not wired to the roles: OFF, no store. Today's rules, for every caller. */
internal fun offPermissionGuard(): PermissionGuard = legacyPermissionGuard(null)

/**
 * The guard of the roles off for a caller that knows the AI principal: today's rules, in which that principal is refused what it has always
 * been refused (the Goal and order tools of `/mcp`, ADR 0020 PR 3d). No store is read.
 */
internal fun legacyPermissionGuard(aiPrincipal: String?): PermissionGuard =
    PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, null, null))

/**
 * The check of a REST route that authenticates through the Task API identity (ADR 0020, PR 3c). The caller is already authenticated, so
 * it is an operator by today's rule (legacyAdmin). [route] is a fixed name. Call it after the authentication and before anything else.
 */
internal fun PermissionGuard.permits(principal: String, permission: Permission, route: String): Boolean =
    check(principal, legacyAdmin = true, permission = permission, route = route) is GuardResult.Allow
