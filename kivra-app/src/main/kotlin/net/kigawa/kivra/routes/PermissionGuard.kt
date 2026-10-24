package net.kigawa.kivra.routes

import java.util.concurrent.TimeUnit
import net.kigawa.kivra.auth.AccessSource
import net.kigawa.kivra.auth.DENY_PERMISSION
import net.kigawa.kivra.auth.Decision
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.PrincipalAccess
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

    /** [code] is one of the fixed codes of Permissions.kt (`NO_ROLE`, `PERMISSION_DENIED`, `SCOPE_DENIED`). */
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
