package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.db.MemoryConditionDeleteResult
import net.kigawa.kivra.db.MemoryDeleteResult
import net.kigawa.kivra.db.MemoryFilter
import net.kigawa.kivra.db.MemoryRecordView
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.selfdev.memory.MemoryKind
import net.kigawa.kivra.selfdev.memory.MemorySource
import net.kigawa.kivra.selfdev.memory.MemoryTier
import org.slf4j.LoggerFactory

/*
 * The self-development memory's operator API (ADR 0016 P2): the list, the filters, the single and the conditional deletion.
 *
 * Operator only. The OIDC operator role is checked by [OperatorAuthenticator] (401 without a token, 403 for an agent token), and
 * [PermissionGuard] decides the rest (ADR 0020, PR 3b): the list needs READ_TEXT, a deletion GOAL_WRITE. With the roles off (the default) the
 * AI principal (`KIVRA_SELFDEV_AI_PRINCIPAL`) is refused on every endpoint with 403 `forbidden`. Every POST passes
 * [requireJsonSameOrigin].
 *
 * While `KIVRA_SELFDEV_MEMORY_ENABLED` is off the repository is null and every endpoint answers 503 without reading anything.
 *
 * What the list returns (U-1): an UNTRUSTED note's text is never returned (`body` is null and `bodyWithheld` is true). A fact has
 * no text. Deletion keeps no text either: the reason in the body is checked and then dropped, because the audit holds no text
 * (ADR 0016 section 4.2). Errors use fixed text and never echo a request value.
 */
class SelfDevMemoryRouteDeps(
    val operators: OperatorAuthenticator,
    /** The repository, or null while the memory switch is off. */
    val memory: SelfDevMemoryRepository?,
    /** The AI principal's owner key (`KIVRA_SELFDEV_AI_PRINCIPAL`), or null. Only the default [guard] reads it. */
    val aiPrincipal: String?,
    /** The permission entrance. The default is the roles off and no store: today's rules, from [aiPrincipal]. */
    val guard: PermissionGuard = PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, null, null)),
)

@Serializable
data class MemoryItemResponse(
    val recordId: String,
    val kind: String,
    val source: String,
    val tier: String,
    val goalId: String?,
    /** The text of a TRUSTED_OPERATOR note. Null for an UNTRUSTED note (see [bodyWithheld]) and for a fact. */
    val body: String?,
    /** True when an UNTRUSTED note has a text that is not returned. */
    val bodyWithheld: Boolean,
    val code: String?,
    val countValue: Int?,
    val createdAt: String,
)

@Serializable
data class MemoryListResponse(
    val items: List<MemoryItemResponse>,
    /** How many ACTIVE records match the filter (for the paging). */
    val total: Int,
    val limit: Int,
    val offset: Int,
    /** The DELETED notes (tombstones) that are kept (ADR 0016 section 8.2). */
    val tombstones: Int,
    /** The sum of the DELETED audit rows' counts (ADR 0016 section 8.5). Matches the tombstones until the retention purges them. */
    val deletionAudit: Int,
)

@Serializable
data class MemoryDeleteRequest(val reason: String)

@Serializable
data class MemoryConditionRequest(
    val kind: String? = null,
    val source: String? = null,
    val tier: String? = null,
    val goalId: String? = null,
    val createdAfter: String? = null,
    val createdBefore: String? = null,
    val dryRun: Boolean = true,
    val reason: String? = null,
)

@Serializable
data class MemoryConditionResponse(val matched: Int, val deleted: Int, val dryRun: Boolean)

@Serializable
data class MemoryDeletedResponse(val recordId: String, val deleted: Boolean)

@Serializable
private data class MemoryError(val error: String, val matched: Int? = null)

private val memoryJson = Json { ignoreUnknownKeys = false }
/** A ULID: a record id and a goal id. */
private val MEMORY_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val MEMORY_CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")
private const val MEMORY_REASON_MAX = 500
private const val DEFAULT_LIST_LIMIT = 20
private const val MAX_LIST_LIMIT = 100
private const val MAX_LIST_OFFSET = 100_000

private const val ACTION_DELETE = "MEMORY_DELETE"
private const val ACTION_CONDITION_DELETE = "MEMORY_CONDITION_DELETE"
private const val ACTION_LIST = "MEMORY_LIST"

private const val MEMORY_DISABLED = "memory is disabled"
private const val INVALID_QUERY = "invalid query"
private const val INVALID_BODY = "invalid request body"
private const val REASON_INVALID = "reason must be 1-500 characters without control characters"
private const val RECORD_NOT_FOUND = "record not found"
private const val FILTER_EMPTY = "give at least one condition"
private const val FILTER_INVALID = "invalid condition"
private const val LIMIT_EXCEEDED = "too many records match; narrow the condition"
private const val FORBIDDEN = "forbidden"

private val memoryLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.memory.operator")

internal fun Route.configureSelfDevMemoryRoutes(deps: SelfDevMemoryRouteDeps) {
    get("/api/selfdev/memory") {
        val caller = deps.requireOperator(call) ?: return@get
        val repo = deps.requireEnabled(call) ?: return@get
        if (!deps.permit(call, caller, Permission.READ_TEXT, "memory.list", ACTION_LIST)) return@get
        val params = call.request.queryParameters
        val filter = filterOf(
            kind = params["kind"],
            source = params["source"],
            tier = params["tier"],
            goalId = params["goalId"],
            createdAfter = params["createdAfter"],
            createdBefore = params["createdBefore"],
        )
        val limit = intParam(params["limit"], DEFAULT_LIST_LIMIT, 1..MAX_LIST_LIMIT)
        val offset = intParam(params["offset"], 0, 0..MAX_LIST_OFFSET)
        if (filter == null || limit == null || offset == null) {
            call.respond(HttpStatusCode.BadRequest, MemoryError(INVALID_QUERY))
            return@get
        }
        val rows = repo.search(filter, limit, offset)
        val total = repo.count(filter)
        val audit = repo.auditSummary()
        call.respond(
            MemoryListResponse(
                items = rows.map { it.toOperatorItem() },
                total = total,
                limit = limit,
                offset = offset,
                tombstones = audit.tombstones,
                deletionAudit = audit.deletionAudit,
            ),
        )
    }

    post("/api/selfdev/memory/{id}/delete") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin()) return@post
        val repo = deps.requireEnabled(call) ?: return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "memory.delete", ACTION_DELETE)) return@post
        val request = try {
            memoryJson.decodeFromString(MemoryDeleteRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, MemoryError(INVALID_BODY))
            return@post
        }
        if (validReason(request.reason) == null) {
            call.respond(HttpStatusCode.BadRequest, MemoryError(REASON_INVALID))
            return@post
        }
        val id = call.parameters["id"]?.takeIf { MEMORY_ID_SHAPE.matches(it) }
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, MemoryError(RECORD_NOT_FOUND))
            return@post
        }
        when (repo.deleteOne(id, principal)) {
            is MemoryDeleteResult.Deleted -> {
                memoryLog.info("selfdev memory operator action: action={} actor={} recordId={} result=DELETED", ACTION_DELETE, operatorTag(principal), id)
                call.respond(HttpStatusCode.OK, MemoryDeletedResponse(id, deleted = true))
            }
            MemoryDeleteResult.NotFound -> call.respond(HttpStatusCode.NotFound, MemoryError(RECORD_NOT_FOUND))
        }
    }

    post("/api/selfdev/memory/delete-by-condition") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin()) return@post
        val repo = deps.requireEnabled(call) ?: return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "memory.delete_by_condition", ACTION_CONDITION_DELETE)) return@post
        val request = try {
            memoryJson.decodeFromString(MemoryConditionRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, MemoryError(INVALID_BODY))
            return@post
        }
        // A real deletion needs a reason; a dry run does not (it changes nothing).
        if (!request.dryRun && validReason(request.reason) == null) {
            call.respond(HttpStatusCode.BadRequest, MemoryError(REASON_INVALID))
            return@post
        }
        val filter = filterOf(
            kind = request.kind,
            source = request.source,
            tier = request.tier,
            goalId = request.goalId,
            createdAfter = request.createdAfter,
            createdBefore = request.createdBefore,
        )
        if (filter == null) {
            call.respond(HttpStatusCode.BadRequest, MemoryError(FILTER_INVALID))
            return@post
        }
        when (val r = repo.deleteByCondition(filter, principal, request.dryRun)) {
            is MemoryConditionDeleteResult.Done -> {
                if (!r.dryRun) {
                    memoryLog.info(
                        "selfdev memory operator action: action={} actor={} matched={} deleted={} result=DELETED",
                        ACTION_CONDITION_DELETE, operatorTag(principal), r.matched, r.deleted,
                    )
                }
                call.respond(HttpStatusCode.OK, MemoryConditionResponse(r.matched, r.deleted, r.dryRun))
            }
            is MemoryConditionDeleteResult.Refused -> when (r.code) {
                SelfDevMemoryRepository.CODE_EMPTY_FILTER -> call.respond(HttpStatusCode.BadRequest, MemoryError(FILTER_EMPTY))
                else -> call.respond(HttpStatusCode.Conflict, MemoryError(LIMIT_EXCEEDED, matched = r.matched))
            }
        }
    }
}

/** The operator's view of a record (ADR 0016 U-1). An UNTRUSTED note's text is withheld; the rest is returned as stored. */
internal fun MemoryRecordView.toOperatorItem(): MemoryItemResponse {
    val untrusted = tier == MemoryTier.UNTRUSTED.name
    return MemoryItemResponse(
        recordId = recordId,
        kind = kind,
        source = source,
        tier = tier,
        goalId = goalId,
        body = if (untrusted) null else body,
        bodyWithheld = untrusted && body != null,
        code = code,
        countValue = countValue,
        createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
    )
}

/** A non-blank query or body value; null when absent or blank (no condition). */
private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The filter from the raw values. An absent value is no condition. A present value that does not parse makes the whole filter
 * null (the caller answers 400). Nothing here echoes a value.
 */
private fun filterOf(
    kind: String?,
    source: String?,
    tier: String?,
    goalId: String?,
    createdAfter: String?,
    createdBefore: String?,
): MemoryFilter? {
    val parsedKind = kind.present()?.let { v -> MemoryKind.entries.firstOrNull { it.name == v } ?: return null }
    val parsedSource = source.present()?.let { v -> MemorySource.entries.firstOrNull { it.name == v } ?: return null }
    val parsedTier = tier.present()?.let { v -> MemoryTier.entries.firstOrNull { it.name == v } ?: return null }
    val parsedGoal = goalId.present()?.let { v -> if (MEMORY_ID_SHAPE.matches(v)) v else return null }
    val after = createdAfter.present()?.let { v -> parseTime(v) ?: return null }
    val before = createdBefore.present()?.let { v -> parseTime(v) ?: return null }
    return MemoryFilter(
        goalId = parsedGoal,
        kind = parsedKind,
        source = parsedSource,
        tier = parsedTier,
        createdAfter = after,
        createdBefore = before,
    )
}

private fun parseTime(raw: String): LocalDateTime? = try {
    LocalDateTime.parse(raw)
} catch (_: Exception) {
    null
}

/** An integer query value in [range]; the default when absent. Null when present and out of range or not a number. */
private fun intParam(raw: String?, default: Int, range: IntRange): Int? =
    if (raw == null) default else raw.toIntOrNull()?.takeIf { it in range }

/** The reason, trimmed, when it is 1-500 characters without control characters; null otherwise. */
private fun validReason(raw: String?): String? {
    val text = raw?.trim() ?: return null
    val n = text.codePointCount(0, text.length)
    return text.takeIf { n in 1..MEMORY_REASON_MAX && !MEMORY_CONTROL_CHARS.containsMatchIn(it) }
}

/**
 * Authenticates the caller: 401 without a valid operator token, 403 for an agent token. Returns the principal otherwise.
 * It does not look at the memory switch first, so an unauthenticated caller learns nothing about it.
 */
private suspend fun SelfDevMemoryRouteDeps.requireOperator(call: ApplicationCall): MemoryCaller? =
    when (val auth = operators.authenticate(call.request.headers)) {
        // The authenticator accepts operators only, so the caller is an operator by today's rule (legacyAdmin).
        is OperatorAuth.Authenticated -> MemoryCaller(auth.principal, guard.resolve(auth.principal, legacyAdmin = true))
        OperatorAuth.Unauthenticated -> {
            call.respond(HttpStatusCode.Unauthorized, MemoryError("unauthorized"))
            null
        }
        OperatorAuth.AgentRefused -> {
            call.respond(HttpStatusCode.Forbidden, MemoryError(FORBIDDEN))
            null
        }
    }

/** 503 while the memory switch is off. Returns the repository, or null after responding. */
private suspend fun SelfDevMemoryRouteDeps.requireEnabled(call: ApplicationCall): SelfDevMemoryRepository? {
    memory?.let { return it }
    call.respond(HttpStatusCode.ServiceUnavailable, MemoryError(MEMORY_DISABLED))
    return null
}

/**
 * The permission check (ADR 0020, PR 3b). Responds 403 `forbidden` and returns false when refused. The refusal is logged with the action name
 * and a fixed code only; for the AI that is the line it has always written (`actor=AI result=forbidden`).
 */
private suspend fun SelfDevMemoryRouteDeps.permit(
    call: ApplicationCall,
    caller: MemoryCaller,
    permission: Permission,
    route: String,
    action: String,
): Boolean {
    val result = guard.check(caller.access, permission, route)
    if (result is GuardResult.Allow) return true
    val code = (result as GuardResult.Deny).code
    memoryLog.info("selfdev memory operator refused: action={} actor={} result={}", action, if (caller.access.isAi) "AI" else "OPERATOR", if (caller.access.isAi) FORBIDDEN else code)
    call.respond(HttpStatusCode.Forbidden, MemoryError(FORBIDDEN))
    return false
}

private class MemoryCaller(val principal: String, val access: GuardAccess)

/** Hash the operator principal for audit logs (first 6 bytes of SHA-256). */
internal fun operatorTag(principal: String): String =
    MessageDigest.getInstance("SHA-256").digest(principal.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
