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
     * The request provably was NOT sent, so the caller MAY repeat it (and the OperationExecutor may record a
     * verified NOT_SENT evidence). Returned ONLY when nothing was sent: connection refused, connect timeout, DNS resolution
     * failure, token acquisition failure (classified from the Ktor/JDK engine exceptions in ExkesAdapter).
     * A 429 on a mutating call is [Unknown]: exkes has seen the request, so "no effect" is not proven.
     * Read-only GETs (repeating them has no effect) may also return Transient for a 429/5xx/timeout.
     */
    data class Transient(val message: String) : PortResult<Nothing>

    /**
     * A mutating call whose effect on exkes is unknown (timeout, connection loss, 5xx, undecodable 2xx).
     * It is NEVER resent automatically. The caller records the Operation as UNKNOWN and reconciles
     * (findWorkspaceByName / findRuntimesByLabel); when reconciliation is impossible it goes to
     * RECONCILIATION_REQUIRED (M0 Operation Journal contract).
     */
    data class Unknown(val message: String) : PortResult<Nothing> {
        /**
         * Raw Throwable instances must never cross this adapter boundary: they can retain provider response bodies,
         * bearer tokens, request URLs and credentials even if toString() is redacted. Callers get a machine code only.
         * Do not render message here either: other Port implementations may not sanitize it.
         */
        override fun toString(): String = "PortResult.Unknown(kind=UNKNOWN)"
    }

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
 * Kind-only, machine-readable description of a result, safe for journal / audit / logs / UI. It never includes the
 * message, cause or provider body of any variant; use it instead of `toString()` or string templates on a PortResult.
 */
fun PortResult<*>.kindCode(): String = when (this) {
    is PortResult.Ok -> "OK"
    PortResult.NotFound -> "NOT_FOUND"
    is PortResult.Conflict -> "CONFLICT"
    is PortResult.Rejected -> "REJECTED_HTTP_$httpStatus"
    is PortResult.Transient -> "TRANSIENT"
    is PortResult.Unknown -> "UNKNOWN"
    is PortResult.IdentityMismatch -> "IDENTITY_MISMATCH"
    is PortResult.Unsupported -> "UNSUPPORTED"
}

/**
 * Operation Journal status that a finished call of a MUTATING operation maps to. UNKNOWN is returned for
 * every ambiguous outcome; leaving UNKNOWN requires verified reconciliation evidence (see [OperationStatus]).
 * [PortResult.Conflict] maps to null: the caller must look the resource up before deciding.
 */
fun PortResult<*>.toOperationStatus(): OperationStatus? = when (this) {
    is PortResult.Ok -> OperationStatus.CONFIRMED
    is PortResult.Rejected, PortResult.NotFound, is PortResult.Unsupported -> OperationStatus.FAILED
    is PortResult.Transient -> OperationStatus.RETRYABLE
    is PortResult.Unknown, is PortResult.IdentityMismatch -> OperationStatus.UNKNOWN
    is PortResult.Conflict -> null
}
