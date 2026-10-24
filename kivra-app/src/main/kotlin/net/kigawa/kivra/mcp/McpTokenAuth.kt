package net.kigawa.kivra.mcp

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.InvalidReason
import net.kigawa.kivra.db.SelfDevAssigneeTokenRepository
import net.kigawa.kivra.db.TokenVerification
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/*
 * The MCP entrance for the API token of an assignee (ADR 0020, the addendum of 2026-10-25, PR T2).
 *
 * Only an `Authorization: Bearer kvt_...` header is handled here. Any other Bearer (a Keycloak JWT) never reaches this class: the route
 * hands it to [McpJwtVerifier] as before. The class exists only while the roles mode is shadow or enforce (Application wires it that way,
 * and passes null otherwise), so with the roles off a `kvt_` Bearer is verified as a JWT, fails, and is answered 401 exactly as before.
 *
 * What a valid token is:
 *  - the principal is the synthetic owner key of the assignee, the scopes are [TOKEN_ROLE_SCOPES] of its role (one constant, so the AI can
 *    lose `kivra.selfdev.start` in one place), and the caller is not a legacy admin ([McpCaller.legacyAdmin] false). The permission is then
 *    decided by the PermissionGuard from the assignee store, never by the scopes alone.
 *  - ENFORCE: the guard uses the store, so an AI assignee holds READ, STOP_REQUEST and SPEND_START and a VIEWER holds READ.
 *  - SHADOW: the guard acts on today's rules, in which a non-admin principal has no access, so EVERY tool is refused (`forbidden`) for a
 *    token. This is on purpose: tokens are meant to be used under ENFORCE; shadow must not grant what the old rules never gave. Each
 *    valid token in shadow records a rate-limited `TOKEN_SHADOW_DENY` line (fixed fields only).
 *
 * Every invalid token is the same answer ([McpTokenResult.Invalid], 401). Which check failed goes to the log as a fixed code only.
 * The failure budget: the failed `kvt_` verifications of this Pod are counted in one fixed window with one counter (no key, so the memory
 * does not grow). After [maxFailures] failures in the window the next `kvt_` requests are answered 429 without reading the database, valid
 * tokens included, until the window ends. Successes are not counted. A malformed token (no database read) is counted.
 * Nothing logged contains a token, a hash, an owner key or a name.
 */

/** The scopes a token holds, by role. The only place that decides it. A role absent here (never issued a token) gets none. */
internal val TOKEN_ROLE_SCOPES: Map<AssigneeRole, Set<String>> = mapOf(
    AssigneeRole.AI to setOf(SCOPE_RUNS_READ, SCOPE_SELFDEV_STOP, SCOPE_SELFDEV_START),
    AssigneeRole.VIEWER to setOf(SCOPE_RUNS_READ),
)

sealed interface McpTokenResult {
    data class Authenticated(val principal: String, val scopes: Set<String>) : McpTokenResult

    /** Every failure of a token: unknown, expired, revoked, disabled, wrong role, malformed. Never says which. */
    data object Invalid : McpTokenResult

    /** The failure budget of the window is used up. [retryAfterSeconds] is at least 1. */
    data class Throttled(val retryAfterSeconds: Long) : McpTokenResult
}

/** The log of the token entrance: fixed codes, each at most once per window. Never a token, hash, owner key or name. */
class McpTokenLog(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val sink: (String) -> Unit = { LoggerFactory.getLogger("net.kigawa.kivra.mcp.token").info(it) },
) {
    private class Window(var startedAt: Long, var suppressed: Int)

    private val windows = HashMap<String, Window>()

    fun invalid(reason: InvalidReason) = code(
        when (reason) {
            InvalidReason.UNKNOWN -> "TOKEN_UNKNOWN"
            InvalidReason.EXPIRED -> "TOKEN_EXPIRED"
            InvalidReason.REVOKED -> "TOKEN_REVOKED"
            InvalidReason.ASSIGNEE_DISABLED -> "ASSIGNEE_DISABLED"
            InvalidReason.ROLE_NOT_ALLOWED -> "ROLE_NOT_ALLOWED"
            InvalidReason.MALFORMED -> "MALFORMED"
            InvalidReason.OWNER_KEY_CONFLICT -> "OWNER_KEY_CONFLICT"
        },
    )

    fun code(code: String) {
        val suppressed = synchronized(windows) {
            val now = nowMillis()
            val w = windows[code]
            if (w == null || now - w.startedAt >= windowMillis) {
                windows[code] = Window(now, 0)
                w?.suppressed ?: 0
            } else {
                w.suppressed++
                return
            }
        }
        sink("MCP token: code=$code suppressed=$suppressed")
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L
    }
}

class McpTokenAuth(
    /** Blocking (the database). Run off the event loop by [authenticate]. */
    private val verify: (String) -> TokenVerification,
    private val mode: PermissionMode,
    private val log: McpTokenLog = McpTokenLog(),
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    /** Milliseconds; replaced by a test. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(mode != PermissionMode.OFF) { "the token entrance does not exist while the roles are off" }
    }

    // One counter for the whole Pod: no key, so nothing grows with the number of callers.
    private var windowStart = 0L
    private var failures = 0
    private var windowOpen = false
    private var throttleLogged = false

    /**
     * Null when the request does not carry a `kvt_` Bearer (the caller then goes on with the JWT verifier). Otherwise the answer for the token.
     */
    suspend fun authenticate(headers: Headers): McpTokenResult? {
        val token = kvtBearer(headers) ?: return null
        val wait = throttledFor()
        if (wait != null) return McpTokenResult.Throttled(wait)
        val verification = try {
            withContext(Dispatchers.IO) { verify(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The store is not reachable: fail closed with the same answer. Not the caller's failure, so it is not counted.
            log.code("TOKEN_STORE_ERROR")
            return McpTokenResult.Invalid
        }
        return when (verification) {
            is TokenVerification.Valid -> {
                if (mode == PermissionMode.SHADOW) log.code("TOKEN_SHADOW_DENY")
                McpTokenResult.Authenticated(verification.ownerKey, TOKEN_ROLE_SCOPES[verification.role] ?: emptySet())
            }
            TokenVerification.Invalid -> {
                countFailure()
                McpTokenResult.Invalid
            }
        }
    }

    /** Seconds to wait when the budget is used up, else null. */
    private fun throttledFor(): Long? = synchronized(this) {
        val now = nowMillis()
        if (windowOpen && now - windowStart >= windowMillis) reset()
        if (!windowOpen || failures < maxFailures) return null
        if (!throttleLogged) {
            throttleLogged = true
            log.code("TOKEN_FAILURE_BUDGET")
        }
        val remaining = windowMillis - (now - windowStart)
        return maxOf(1L, (remaining + 999) / 1000)
    }

    private fun countFailure() = synchronized(this) {
        val now = nowMillis()
        if (windowOpen && now - windowStart >= windowMillis) reset()
        if (!windowOpen) {
            windowOpen = true
            windowStart = now
        }
        failures++
    }

    private fun reset() {
        windowOpen = false
        failures = 0
        throttleLogged = false
    }

    companion object {
        /** Failed `kvt_` verifications per window before a 429. */
        const val DEFAULT_MAX_FAILURES = 30
        const val DEFAULT_WINDOW_MILLIS = McpTokenLog.DEFAULT_WINDOW_MILLIS

        /** The Bearer value when it starts with `kvt_`, else null (a JWT, or no Bearer). */
        internal fun kvtBearer(headers: Headers): String? {
            val authorization = headers[HttpHeaders.Authorization]?.trim() ?: return null
            if (!authorization.startsWith("Bearer ", ignoreCase = true)) return null
            val token = authorization.substring("Bearer ".length).trim()
            return token.takeIf { it.startsWith(SelfDevAssigneeTokenRepository.TOKEN_PREFIX) }
        }

        /** The production entrance on [database]. Call it only while the roles mode is not off. */
        fun forDatabase(database: Database, mode: PermissionMode): McpTokenAuth {
            val log = McpTokenLog()
            val repo = SelfDevAssigneeTokenRepository(database, onInvalid = log::invalid)
            return McpTokenAuth({ repo.verify(it) }, mode, log)
        }
    }
}
