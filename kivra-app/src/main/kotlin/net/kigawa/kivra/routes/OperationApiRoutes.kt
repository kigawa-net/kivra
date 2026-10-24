package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidence
import net.kigawa.kivra.core.VerifiedEvidenceRef
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.JournalEntry
import net.kigawa.kivra.db.OperationJournalRepository
import org.jetbrains.exposed.sql.Database

/**
 * Read-only Operation projection (Kivra #85): `GET /api/runs/{id}/operations`. Only the caller's own Runs are listed.
 * Another caller's Run and a missing Run get the same 404 body, so the response does not reveal which Run ids exist.
 *
 * Registered only when `KIVRA_AUTH_MODE` is set, like the Task API. There are no write endpoints.
 *
 * The state is passed through as the journal's own name. UNKNOWN and RECONCILIATION_REQUIRED are not mapped to a success
 * or completed value. Tokens, request hashes, external refs, raw provider errors and Evidence data are not included.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OperationListResponse(
    val schemaVersion: String = "ui.v1",
    val runId: String,
    val operations: List<OperationSummaryResponse>,
    /** The page size cap, [MAX_OPERATIONS_PER_RUN]. */
    val limit: Int,
    /** True when the Run has more operations than [limit]; the rest are not returned. */
    val truncated: Boolean,
    /**
     * Cursor for the next page, present only when [truncated] is true. Pass it back as `?after=`. Omitted otherwise
     * (EncodeDefault.NEVER keeps the key out of the JSON when it is null).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val nextAfter: String? = null,
)

@Serializable
data class OperationSummaryResponse(
    val operationId: String,
    val runId: String,
    val opType: String,
    val externalSystem: String,
    /** Journal state name, e.g. PREPARED, IN_FLIGHT, UNKNOWN, RECONCILIATION_REQUIRED, CONFIRMED, FAILED. */
    val state: String,
    val attempt: Int,
    val maxAttempts: Int,
    val lastErrorClass: String?,
)

@Serializable
private data class OperationApiError(val error: String)

/** Maximum operations returned per Run. Operations are ordered by operationId (ULID, so creation order). */
internal const val MAX_OPERATIONS_PER_RUN = 100

/** A cursor is an operationId: a 26-char ULID. Anything else is rejected before it reaches the database. */
private val CURSOR_PATTERN = Regex("[0-9A-Za-z]{26}")

internal fun JournalEntry.toOperationSummary(): OperationSummaryResponse = OperationSummaryResponse(
    operationId = opId,
    runId = runId.value,
    opType = opType,
    externalSystem = externalSystem,
    state = status.name,
    attempt = attempt,
    maxAttempts = maxAttempts,
    lastErrorClass = lastErrorClass,
)

/**
 * The BFF only reads the Journal. [OperationJournalRepository] takes a [TrustedEvidenceAuthority] for its write paths, and
 * no production Authority is wired yet. This one refuses every call, so no transition can be made through the BFF.
 * `listByRun` never calls it.
 */
private object ReadOnlyEvidenceAuthority : TrustedEvidenceAuthority {
    override suspend fun verify(
        subject: EvidenceSubject,
        observation: EvidenceObservationRef,
        requiredPurpose: EvidencePurpose,
    ): EvidenceVerification = throw EvidenceAuthorityUnavailableException("read-only BFF has no evidence authority")

    override suspend fun resolve(
        ref: VerifiedEvidenceRef,
        expected: EvidenceSubject,
        requiredPurpose: EvidencePurpose,
    ): VerifiedEvidence = throw EvidenceAuthorityUnavailableException("read-only BFF has no evidence authority")
}

/** A journal repository for read-only use. See [ReadOnlyEvidenceAuthority]. */
internal fun readOnlyOperationJournal(database: Database): OperationJournalRepository =
    OperationJournalRepository(database, ReadOnlyEvidenceAuthority)

internal fun Route.configureOperationApiRoutes(
    runs: DevelopmentRunRepository,
    operations: OperationJournalRepository,
    identity: TaskApiIdentity,
    guard: PermissionGuard = offPermissionGuard(),
) {
    get("/api/runs/{id}/operations") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, OperationApiError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "run_operations")) {
            call.respond(HttpStatusCode.Forbidden, OperationApiError("forbidden"))
            return@get
        }
        val runId = RunId(call.parameters["id"].orEmpty())
        // getTask filters by the caller, so a Run owned by someone else is indistinguishable from a missing one.
        if (runs.getTask(runId, principal) == null) {
            call.respond(HttpStatusCode.NotFound, OperationApiError("run not found"))
            return@get
        }
        // Checked after the owner check, so a bad cursor never tells a non-owner anything beyond the same 404.
        val after = call.request.queryParameters["after"]
        if (after != null && !CURSOR_PATTERN.matches(after)) {
            call.respond(HttpStatusCode.BadRequest, OperationApiError("invalid after"))
            return@get
        }
        val page = operations.listByRun(runId, limit = MAX_OPERATIONS_PER_RUN, afterOpId = after)
        call.respond(
            OperationListResponse(
                runId = runId.value,
                operations = page.items.map { it.toOperationSummary() },
                limit = MAX_OPERATIONS_PER_RUN,
                truncated = page.hasMore,
                nextAfter = if (page.hasMore) page.items.last().opId else null,
            ),
        )
    }
}
