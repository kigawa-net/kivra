package net.kigawa.kivra.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.db.AssigneeRefusal
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.AssigneeSummary
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevAssigneeTokenRepository
import net.kigawa.kivra.db.TokenRefusal
import net.kigawa.kivra.db.TokenResult
import net.kigawa.kivra.mcp.McpRateLimiter
import org.slf4j.LoggerFactory

/*
 * The assignee admin API and `GET /api/selfdev/me` (ADR 0020 addendum 2026-10-25, PR T3).
 *
 * Authentication is the operator's (the OIDC token with `admin-panel:admin`; an agent token is refused). An AI assignee's API token is
 * accepted by the MCP endpoint only, so it cannot reach these routes. The admin routes need ASSIGNEE_MANAGE, judged by the permissions the
 * assignee store gives (also in SHADOW, see [PermissionGuard.permitsEffective]), so only a person bound to an ACTIVE ADMIN assignee passes.
 * The AI is refused whatever it holds. With the roles off the admin routes answer 503 `ROLES_DISABLED` and nothing reads the store.
 * `/me` answers in every mode.
 *
 * What never appears in a response, a log line or an error: an owner key, a display name, a token's hash. The one response that holds a
 * token's plaintext is the issue, once, with `Cache-Control: no-store`. A person is named by the Keycloak `sub` only on the way in: the
 * server builds the owner key from the configured issuer and answers with the hashed `memberRef`.
 * Every refusal is a fixed code (an enum name or a constant below); no request value is echoed.
 */
class SelfDevAssigneeRouteDeps(
    val operators: OperatorAuthenticator,
    val guard: PermissionGuard,
    /** Null while the roles are off: nothing is read or written then. */
    val assignees: SelfDevAssigneeRepository?,
    val tokens: SelfDevAssigneeTokenRepository?,
    /** `KIVRA_OIDC_ISSUER`, the issuer the owner key of a person is built with. Null: binding a person answers 503. */
    val oidcIssuer: String?,
    /** Writes per principal per minute. */
    val writeLimiter: McpRateLimiter = McpRateLimiter(WRITES_PER_MINUTE),
    val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        const val WRITES_PER_MINUTE = 5
    }
}

@Serializable
data class MeResponse(val mode: String, val role: String, val permissions: List<String>, val source: String)

@Serializable
data class AssigneeResponse(
    val assigneeId: String,
    val displayName: String,
    val role: String,
    val state: String,
    val version: Long,
    val memberCount: Int,
    val activeTokenCount: Int,
)

@Serializable
data class CreateAssigneeRequest(val displayName: String, val role: String)

@Serializable
data class ChangeAssigneeRoleRequest(val role: String, val version: Long)

@Serializable
data class AssigneeVersionRequest(val version: Long)

@Serializable
data class BindMemberRequest(val sub: String)

@Serializable
data class MemberRefResponse(val memberRef: String)

@Serializable
data class IssueTokenRequest(val ttlDays: Int? = null)

/** The plaintext [token] is shown once. [toString] hides it, so that logging the response by mistake leaks nothing. */
@Serializable
data class IssuedTokenResponse(val token: String, val tokenRef: String, val expiresAt: String) {
    override fun toString(): String = "IssuedTokenResponse(token=<redacted>, tokenRef=$tokenRef, expiresAt=$expiresAt)"
}

@Serializable
data class TokenListItem(val tokenRef: String, val status: String, val createdBy: String, val createdAt: String, val expiresAt: String)

@Serializable
private data class AssigneeError(val error: String, val code: String)

private class AssigneeCaller(val principal: String, val access: GuardAccess)

private val assigneeJson = Json { ignoreUnknownKeys = false }
private val ASSIGNEE_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val REF_SHAPE = Regex("[0-9a-f]{16}")
private val SUB_SHAPE = Regex("[A-Za-z0-9._:-]{1,255}")
private const val MAX_BODY_CHARS = 2_048

private const val CODE_ROLES_DISABLED = "ROLES_DISABLED"
private const val CODE_BINDING_UNAVAILABLE = "BINDING_UNAVAILABLE"
private const val CODE_FORBIDDEN = "FORBIDDEN"
private const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
private const val CODE_RATE_LIMITED = "RATE_LIMITED"
private const val CODE_INVALID_BODY = "INVALID_BODY"
private const val CODE_INVALID_ROLE = "INVALID_ROLE"
private const val CODE_INVALID_SUB = "INVALID_SUB"
private const val CODE_INVALID_VERSION = "INVALID_VERSION"
private const val CODE_NOT_FOUND = "NOT_FOUND"
private const val CODE_STALE_VERSION = "STALE_VERSION"

private val assigneeLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.assignee.admin")

internal fun Route.configureSelfDevAssigneeRoutes(deps: SelfDevAssigneeRouteDeps) {
    // Every operator may ask what the UI should show. No admin right is needed, and it answers with the roles off (mode "off").
    get("/api/selfdev/me") {
        val caller = deps.caller(call) ?: return@get
        val effective = caller.access.effective
        call.respond(
            MeResponse(
                mode = deps.guard.mode.name.lowercase(),
                role = effective.role?.name ?: "NONE",
                permissions = effective.permissions.sortedBy { it.ordinal }.map { it.name },
                source = effective.source.name,
            ),
        )
    }

    get("/api/selfdev/assignees") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE)) return@get
        call.respond(deps.assignees().listSummaries().map { it.response() })
    }

    post("/api/selfdev/assignees") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        val body = call.decodeBody<CreateAssigneeRequest>() ?: return@post
        val role = roleOf(body.role) ?: return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_ROLE)
        when (val r = deps.assignees().create(body.displayName, role, caller.principal)) {
            is AssigneeResult.Moved -> {
                assigneeLog.info("assignee admin action: action=ASSIGNEE_CREATED assigneeId={} role={}", r.value.assigneeId, role.name)
                val summary = deps.assignees().summaryOf(r.value.assigneeId)?.response()
                    ?: return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
                call.respond(HttpStatusCode.Created, summary)
            }
            else -> call.respondRefused(r)
        }
    }

    get("/api/selfdev/assignees/{id}/members") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE)) return@get
        val id = assigneeIdOf(call) ?: return@get call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        if (deps.assignees().find(id) == null) return@get call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        call.respond(deps.assignees().humanMemberRefs(id).map { MemberRefResponse(it) })
    }

    post("/api/selfdev/assignees/{id}/role") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        val id = assigneeIdOf(call)
        val body = call.decodeBody<ChangeAssigneeRoleRequest>() ?: return@post
        val role = roleOf(body.role) ?: return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_ROLE)
        if (body.version < 0) return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_VERSION)
        if (id == null) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        when (val r = deps.assignees().changeRole(id, role, body.version, caller.principal)) {
            is AssigneeResult.Moved -> {
                assigneeLog.info("assignee admin action: action=ROLE_CHANGED assigneeId={} role={}", id, role.name)
                call.respondSummary(deps, id)
            }
            else -> call.respondRefused(r)
        }
    }

    post("/api/selfdev/assignees/{id}/disable") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        val id = assigneeIdOf(call)
        val body = call.decodeBody<AssigneeVersionRequest>() ?: return@post
        if (body.version < 0) return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_VERSION)
        if (id == null) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        when (val r = deps.assignees().setState(id, AssigneeState.DISABLED, body.version, caller.principal)) {
            is AssigneeResult.Moved -> {
                assigneeLog.info("assignee admin action: action=DISABLED assigneeId={}", id)
                call.respondSummary(deps, id)
            }
            else -> call.respondRefused(r)
        }
    }

    post("/api/selfdev/assignees/{id}/enable") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        val id = assigneeIdOf(call)
        val body = call.decodeBody<AssigneeVersionRequest>() ?: return@post
        if (body.version < 0) return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_VERSION)
        if (id == null) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        when (val r = deps.assignees().setState(id, AssigneeState.ACTIVE, body.version, caller.principal)) {
            is AssigneeResult.Moved -> {
                assigneeLog.info("assignee admin action: action=ENABLED assigneeId={}", id)
                call.respondSummary(deps, id)
            }
            else -> call.respondRefused(r)
        }
    }

    post("/api/selfdev/assignees/{id}/members") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        // Without the configured issuer the owner key cannot be built the way the login builds it: refuse, never guess.
        val issuer = deps.oidcIssuer ?: return@post call.refuse(HttpStatusCode.ServiceUnavailable, CODE_BINDING_UNAVAILABLE)
        val id = assigneeIdOf(call)
        val body = call.decodeBody<BindMemberRequest>() ?: return@post
        if (!SUB_SHAPE.matches(body.sub)) return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_SUB)
        val ownerKey = oidcOwnerKey(issuer, body.sub) ?: return@post call.refuse(HttpStatusCode.BadRequest, CODE_INVALID_SUB)
        if (id == null) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        when (val r = deps.assignees().bindMember(id, ownerKey, caller.principal)) {
            is AssigneeResult.Moved -> {
                assigneeLog.info("assignee admin action: action=MEMBER_BOUND assigneeId={}", id)
                call.respond(HttpStatusCode.Created, MemberRefResponse(SelfDevAssigneeRepository.memberRef(ownerKey)))
            }
            else -> call.respondRefused(r)
        }
    }

    delete("/api/selfdev/assignees/{id}/members/{memberRef}") {
        val caller = deps.caller(call) ?: return@delete
        if (!call.requireJsonSameOrigin()) return@delete
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@delete
        val id = assigneeIdOf(call)
        val ref = call.parameters["memberRef"]?.takeIf { REF_SHAPE.matches(it) }
        if (!call.readEmptyBody()) return@delete
        if (id == null || ref == null) return@delete call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        when (val r = deps.assignees().unbindMemberByRef(id, ref, caller.principal)) {
            is AssigneeResult.Moved -> {
                assigneeLog.info("assignee admin action: action=MEMBER_UNBOUND assigneeId={}", id)
                call.respond(HttpStatusCode.NoContent)
            }
            else -> call.respondRefused(r)
        }
    }

    post("/api/selfdev/assignees/{id}/tokens") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        val id = assigneeIdOf(call)
        val body = call.decodeBodyOrEmpty(IssueTokenRequest()) ?: return@post
        if (id == null) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        val ttl = body.ttlDays ?: SelfDevAssigneeTokenRepository.DEFAULT_TTL_DAYS
        when (val r = deps.tokens().issue(id, ttl, caller.principal)) {
            is TokenResult.Ok -> {
                // The plaintext is in this response and nowhere else. The log line has the assignee id only.
                assigneeLog.info("assignee admin action: action=TOKEN_ISSUED assigneeId={}", id)
                call.response.header(HttpHeaders.Pragma, "no-cache")
                call.respond(HttpStatusCode.Created, IssuedTokenResponse(r.value.token, r.value.tokenRef, r.value.expiresAt.utc()))
            }
            is TokenResult.Refused -> call.respondTokenRefused(r.reason)
        }
    }

    get("/api/selfdev/assignees/{id}/tokens") {
        val caller = deps.caller(call) ?: return@get
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE)) return@get
        val id = assigneeIdOf(call) ?: return@get call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        if (deps.assignees().find(id) == null) return@get call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        val now = LocalDateTime.now(deps.clock)
        call.respond(
            deps.tokens().list(id).map {
                val status = when {
                    it.revoked -> "REVOKED"
                    !it.expiresAt.isAfter(now) -> "EXPIRED"
                    else -> "ACTIVE"
                }
                TokenListItem(it.tokenRef, status, it.createdBy, it.createdAt.utc(), it.expiresAt.utc())
            },
        )
    }

    post("/api/selfdev/assignees/{id}/tokens/{tokenRef}/revoke") {
        val caller = deps.caller(call) ?: return@post
        if (!call.requireJsonSameOrigin()) return@post
        if (!deps.manageable(call, caller, Permission.ASSIGNEE_MANAGE, write = true)) return@post
        val id = assigneeIdOf(call)
        val ref = call.parameters["tokenRef"]?.takeIf { REF_SHAPE.matches(it) }
        if (!call.readEmptyBody()) return@post
        if (id == null || ref == null) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        // The token must be one of this assignee's: a ref of another assignee's token is a miss.
        if (deps.tokens().list(id).none { it.tokenRef == ref }) return@post call.refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        when (val r = deps.tokens().revoke(ref, caller.principal)) {
            is TokenResult.Ok -> {
                assigneeLog.info("assignee admin action: action=TOKEN_REVOKED assigneeId={}", id)
                call.respond(HttpStatusCode.NoContent)
            }
            is TokenResult.Refused -> call.respondTokenRefused(r.reason)
        }
    }
}

private fun SelfDevAssigneeRouteDeps.assignees(): SelfDevAssigneeRepository = checkNotNull(assignees) { "the roles are off" }

private fun SelfDevAssigneeRouteDeps.tokens(): SelfDevAssigneeTokenRepository = checkNotNull(tokens) { "the roles are off" }

/** Authenticates the caller: 401 (no valid operator token) or 403 (an agent token). Every response of these routes is `no-store`. */
private suspend fun SelfDevAssigneeRouteDeps.caller(call: ApplicationCall): AssigneeCaller? {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    return when (val auth = operators.authenticate(call.request.headers)) {
        is OperatorAuth.Authenticated -> AssigneeCaller(auth.principal, guard.resolve(auth.principal, legacyAdmin = true))
        OperatorAuth.Unauthenticated -> {
            call.refuse(HttpStatusCode.Unauthorized, CODE_UNAUTHORIZED)
            null
        }
        OperatorAuth.AgentRefused -> {
            call.refuse(HttpStatusCode.Forbidden, CODE_FORBIDDEN)
            null
        }
    }
}

/**
 * The gate of an admin route, after the authentication (and the CSRF check of a write): 503 with the roles off (nothing was read), 403
 * unless the caller's effective access holds [permission] (the AI never does), and, for a write, 429 over the per-principal write limit.
 * The limit counts only a caller that passed the permission check, so a refused caller cannot use up an administrator's allowance.
 */
private suspend fun SelfDevAssigneeRouteDeps.manageable(
    call: ApplicationCall,
    caller: AssigneeCaller,
    permission: Permission,
    write: Boolean = false,
): Boolean {
    if (!guard.managementEnabled || assignees == null || tokens == null) {
        call.refuse(HttpStatusCode.ServiceUnavailable, CODE_ROLES_DISABLED)
        return false
    }
    if (!guard.permitsEffective(caller.access, permission)) {
        call.refuse(HttpStatusCode.Forbidden, CODE_FORBIDDEN)
        return false
    }
    if (write && !writeLimiter.allow(caller.principal)) {
        call.refuse(HttpStatusCode.TooManyRequests, CODE_RATE_LIMITED)
        return false
    }
    return true
}

private fun assigneeIdOf(call: ApplicationCall): String? = call.parameters["id"]?.takeIf { ASSIGNEE_ID_SHAPE.matches(it) }

private fun roleOf(name: String): AssigneeRole? = AssigneeRole.entries.firstOrNull { it.name == name }

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String) =
    respond(status, AssigneeError(error = fixedText(status), code = code))

private fun fixedText(status: HttpStatusCode): String = when (status) {
    HttpStatusCode.Unauthorized -> "unauthorized"
    HttpStatusCode.Forbidden -> "forbidden"
    HttpStatusCode.NotFound -> "not found"
    HttpStatusCode.Conflict -> "the request conflicts with the current state"
    HttpStatusCode.ServiceUnavailable -> "unavailable"
    HttpStatusCode.TooManyRequests -> "too many requests"
    else -> "invalid request"
}

private suspend fun ApplicationCall.respondSummary(deps: SelfDevAssigneeRouteDeps, id: String) {
    val summary = deps.assignees().summaryOf(id)
    if (summary == null) refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND) else respond(summary.response())
}

/** A miss or a refusal of the assignee repository, as its fixed code. */
private suspend fun ApplicationCall.respondRefused(outcome: AssigneeResult<*>) {
    when (outcome) {
        AssigneeResult.NotFound -> refuse(HttpStatusCode.NotFound, CODE_NOT_FOUND)
        AssigneeResult.StaleVersion -> refuse(HttpStatusCode.Conflict, CODE_STALE_VERSION)
        is AssigneeResult.Refused -> refuse(
            when (outcome.reason) {
                AssigneeRefusal.INVALID_DISPLAY_NAME, AssigneeRefusal.INVALID_OWNER_KEY, AssigneeRefusal.SYNTHETIC_KEY_RESERVED ->
                    HttpStatusCode.BadRequest
                AssigneeRefusal.NOT_BOUND -> HttpStatusCode.NotFound
                AssigneeRefusal.OWNER_KEY_ALREADY_BOUND, AssigneeRefusal.LAST_ADMIN, AssigneeRefusal.SELF_CHANGE, AssigneeRefusal.NO_CHANGE ->
                    HttpStatusCode.Conflict
            },
            outcome.reason.name,
        )
        is AssigneeResult.Moved<*> -> respond(HttpStatusCode.OK)
    }
}

private suspend fun ApplicationCall.respondTokenRefused(reason: TokenRefusal) {
    val status = when (reason) {
        TokenRefusal.ASSIGNEE_NOT_FOUND, TokenRefusal.NOT_FOUND -> HttpStatusCode.NotFound
        TokenRefusal.TTL_OUT_OF_RANGE, TokenRefusal.INVALID_ACTOR -> HttpStatusCode.BadRequest
        TokenRefusal.ROLE_NOT_ALLOWED, TokenRefusal.TOO_MANY_ACTIVE, TokenRefusal.ALREADY_REVOKED, TokenRefusal.OWNER_KEY_CONFLICT ->
            HttpStatusCode.Conflict
    }
    refuse(status, reason.name)
}

/** Reads the text of the body, up to [MAX_BODY_CHARS]. Responds 400 and returns null when it is longer. */
private suspend fun ApplicationCall.boundedText(): String? {
    val text = receiveText()
    if (text.length > MAX_BODY_CHARS) {
        refuse(HttpStatusCode.BadRequest, CODE_INVALID_BODY)
        return null
    }
    return text
}

/** Decodes a strict JSON body or responds 400. */
private suspend inline fun <reified T> ApplicationCall.decodeBody(): T? {
    val text = boundedText() ?: return null
    return try {
        assigneeJson.decodeFromString<T>(text)
    } catch (_: Exception) {
        refuse(HttpStatusCode.BadRequest, CODE_INVALID_BODY)
        null
    }
}

/** Like [decodeBody], but an empty body is [whenEmpty]. */
private suspend inline fun <reified T> ApplicationCall.decodeBodyOrEmpty(whenEmpty: T): T? {
    val text = boundedText() ?: return null
    if (text.isBlank()) return whenEmpty
    return try {
        assigneeJson.decodeFromString<T>(text)
    } catch (_: Exception) {
        refuse(HttpStatusCode.BadRequest, CODE_INVALID_BODY)
        null
    }
}

@Serializable
private class EmptyAssigneeBody

/** Accepts an empty body or `{}`. Responds 400 and returns false otherwise. */
private suspend fun ApplicationCall.readEmptyBody(): Boolean {
    val text = boundedText() ?: return false
    if (text.isBlank()) return true
    val ok = try {
        assigneeJson.decodeFromString(EmptyAssigneeBody.serializer(), text)
        true
    } catch (_: Exception) {
        false
    }
    if (!ok) refuse(HttpStatusCode.BadRequest, CODE_INVALID_BODY)
    return ok
}

private fun LocalDateTime.utc(): String = toInstant(ZoneOffset.UTC).toString()

private fun AssigneeSummary.response() = AssigneeResponse(
    assigneeId = assignee.assigneeId,
    displayName = assignee.displayName,
    role = assignee.role.name,
    state = assignee.state.name,
    version = assignee.version,
    memberCount = memberCount,
    activeTokenCount = activeTokenCount,
)
