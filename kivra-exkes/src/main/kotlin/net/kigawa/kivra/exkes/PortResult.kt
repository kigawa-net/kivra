package net.kigawa.kivra.exkes

import net.kigawa.kivra.core.OperationStatus

/**
 * Result of an ExecutionPort call. Deliberately NOT exception based: the caller (Agent Manager, #30)
 * must handle "outcome unknown" explicitly instead of letting it surface as a retried failure.
 */
sealed interface PortResult<out T> {
    data class Ok<T>(val value: T) : PortResult<T>

    /** 404. For a mutating call this is authoritative: the target does not exist. */
    data object NotFound : PortResult<Nothing>

    /** 409 (e.g. duplicate workspace name) or a pre-POST guard refused the call. Nothing new was created. */
    data class Conflict(val message: String) : PortResult<Nothing>

    /** Definite 4xx rejection (validation, auth, ...). The request had no effect. */
    data class Rejected(val httpStatus: Int, val code: String?, val message: String) : PortResult<Nothing>

    /**
     * The request provably was NOT sent (token provider failed, TCP connect refused / connect timeout), so it MAY be
     * repeated by the caller. Never returned for a mutating request that could have reached exkes: an HTTP 429/503,
     * a response timeout or a reset after the request was written is [Unknown] for mutating calls.
     * (Read-only calls may use it for any transient failure, since repeating a GET has no effect.)
     */
    data class Transient(val message: String) : PortResult<Nothing>

    /**
     * A mutating call whose effect on exkes is unknown (timeout, connection loss, 5xx, undecodable 2xx).
     * It is NEVER resent automatically. The caller records the Operation as UNKNOWN and reconciles
     * (findWorkspaceByName / findRuntimesByLabel); when reconciliation is impossible it goes to
     * RECONCILIATION_REQUIRED (M0 Operation Journal contract).
     */
    data class Unknown(val message: String, val cause: Throwable? = null) : PortResult<Nothing>

    /**
     * The response contradicts what was asked (response id / name / parent differs from the request).
     * The response is not trusted and not returned; an effect may or may not exist -> treat like [Unknown].
     */
    data class IdentityMismatch(val expected: String, val actual: String) : PortResult<Nothing>

    /** The server lacks this feature; see [ExecutionCapabilities]. */
    data class Unsupported(val capability: String, val workaround: String? = null) : PortResult<Nothing>
}

fun <T> PortResult<T>.getOrNull(): T? = (this as? PortResult.Ok)?.value

/**
 * Operation Journal status that a finished call of a MUTATING operation maps to. UNKNOWN is returned for
 * every ambiguous outcome; leaving UNKNOWN requires ReconciliationEvidence (see [OperationStatus]).
 * [PortResult.Conflict] maps to null: the caller must look the resource up before deciding.
 */
fun PortResult<*>.toOperationStatus(): OperationStatus? = when (this) {
    is PortResult.Ok -> OperationStatus.CONFIRMED
    is PortResult.Rejected, PortResult.NotFound, is PortResult.Unsupported -> OperationStatus.FAILED
    is PortResult.Transient -> OperationStatus.RETRYABLE
    is PortResult.Unknown, is PortResult.IdentityMismatch -> OperationStatus.UNKNOWN
    is PortResult.Conflict -> null
}
