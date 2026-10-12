package net.kigawa.kivra.agent

import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.NotSentProof
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.ReconciliationEvidence
import net.kigawa.kivra.core.RejectedProof
import net.kigawa.kivra.db.JournalEntry
import net.kigawa.kivra.db.OperationJournalRepository
import net.kigawa.kivra.db.PrepareOperation
import net.kigawa.kivra.exkes.PortResult

/** What a reconciliation lookup saw on exkes. */
sealed interface Observed {
    /** The effect exists; [ref] is the exkes id to adopt. */
    data class Exists(val ref: String?) : Observed

    /**
     * Nothing there. [replaySafe] = a replay cannot create a second effect: only for teardown ops that are harmless to
     * repeat (stop / delete, where "already gone" is success). Create ops are NEVER replay-safe: "look up, then create"
     * is not atomic on exkes and a lost POST may still land (#44, #60). false = never resent.
     */
    data class Absent(val replaySafe: Boolean) : Observed

    /** The lookup failed or was ambiguous. */
    data class Undetermined(val why: String) : Observed
}

/** One journaled external side effect. */
class Op<T>(
    val opType: String,
    val key: String,
    val stableRef: String,
    val requestHash: String,
    val call: suspend () -> PortResult<T>,
    val refOf: (T) -> String?,
    val observe: suspend () -> Observed,
    /** 404 on a delete / stop means "already gone" = success. */
    val notFoundIsSuccess: Boolean = false,
)

sealed interface OpOutcome {
    data class Done(val externalRef: String?) : OpOutcome
    data class Failed(val message: String) : OpOutcome

    /** The capability is missing on the connected exkes (design: WAITING_DEPENDENCY, never faked). */
    data class WaitingDependency(val message: String) : OpOutcome
    data class ReconciliationRequired(val opId: String, val opType: String) : OpOutcome

    /** Wind-down only: the operation would have to be dispatched, which is not allowed there. */
    data object NotDispatched : OpOutcome
}

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Runs an [Op] through the Operation Journal (design #11 section 4):
 * PREPARED -> IN_FLIGHT (persisted BEFORE the call) -> CONFIRMED / FAILED / RETRYABLE / UNKNOWN.
 *
 * Fail-closed rules (M0 contract, docs/adr/0001):
 *  - an UNKNOWN result (or an IN_FLIGHT row left by a dead worker, which is UNKNOWN by definition) is never resent;
 *    it is only looked up. EXISTS adopts (CONFIRMED). Anything else goes to RECONCILIATION_REQUIRED and stops.
 *    exkes lookups are LOOKUP_BY_STABLE_REF only, so an absence never justifies RETRYABLE (create ops are never resent).
 *  - only [PortResult.Transient] (adapter-proven: the request was never sent) is retried, via `markNotSent` with a
 *    [NotSentProof] issued by [ISSUER]. Every other failure stays UNKNOWN or is rejected with a [RejectedProof].
 *  - the attempt budget is enforced by the journal: RETRYABLE -> IN_FLIGHT may come back as FAILED
 *    (MAX_ATTEMPTS_EXCEEDED), and nothing is sent unless the returned status is IN_FLIGHT.
 */
/** Issuer of the evidence and proofs this executor records; on the journal's trusted allowlist. */
internal const val ISSUER = "exkes-adapter"

internal class OperationExecutor(
    private val journal: OperationJournalRepository,
    private val maxAttempts: Int,
    private val backoff: java.time.Duration,
    private val clock: Clock,
) {
    suspend fun <T> perform(
        lease: Lease,
        op: Op<T>,
        allowCancel: Boolean,
        allowDispatch: Boolean,
        guard: () -> Unit,
    ): OpOutcome {
        var entry = journal.prepare(
            lease,
            PrepareOperation(op.opType, op.key, "EXKES", op.stableRef, op.requestHash, maxAttempts),
            allowCancel,
        )
        while (true) {
            when (entry.status) {
                OperationStatus.CONFIRMED -> return OpOutcome.Done(entry.externalRef)
                OperationStatus.FAILED -> return OpOutcome.Failed(entry.lastError ?: "operation ${op.opType} failed")
                OperationStatus.IN_FLIGHT -> entry = journal.transition(
                    lease, entry.opId, entry.version, OperationStatus.UNKNOWN,
                    errorClass = "STALE_IN_FLIGHT", error = "worker stopped while the call was in flight", allowCancelRequested = allowCancel,
                )
                OperationStatus.UNKNOWN, OperationStatus.RECONCILIATION_REQUIRED -> {
                    when (val r = reconcile(lease, op, entry, allowCancel)) {
                        is Reconciled.Settled -> entry = r.entry
                        is Reconciled.Stop -> return r.outcome
                    }
                }
                OperationStatus.PREPARED, OperationStatus.RETRYABLE -> {
                    if (!allowDispatch) return OpOutcome.NotDispatched
                    if (entry.status == OperationStatus.RETRYABLE && !backoff.isZero) delay(backoff.toMillis())
                    guard()
                    val inFlight = journal.transition(lease, entry.opId, entry.version, OperationStatus.IN_FLIGHT, allowCancelRequested = allowCancel)
                    if (inFlight.status != OperationStatus.IN_FLIGHT) {
                        // Budget spent (MAX_ATTEMPTS_EXCEEDED -> FAILED): the call was not sent, do not dispatch.
                        entry = inFlight
                        continue
                    }
                    val result = try {
                        op.call()
                    } catch (e: CancellationException) {
                        throw e // the row stays IN_FLIGHT: whoever resumes treats it as UNKNOWN
                    } catch (e: Exception) {
                        PortResult.Unknown("call threw ${e::class.simpleName}: ${e.message}", e)
                    }
                    val settled = settle(lease, op, inFlight, result, allowCancel)
                    if (settled.stop != null) return settled.stop
                    entry = settled.entry!!
                }
            }
        }
    }

    private class Settled(val entry: JournalEntry?, val stop: OpOutcome?)

    private fun <T> settle(lease: Lease, op: Op<T>, e: JournalEntry, r: PortResult<T>, allowCancel: Boolean): Settled {
        fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        fun proofRef(kind: String) = "$kind:${op.stableRef}".take(250)
        fun move(to: OperationStatus, ref: String? = null, cls: String? = null, msg: String? = null) =
            journal.transition(lease, e.opId, e.version, to, externalRef = ref, errorClass = cls, error = msg, allowCancelRequested = allowCancel)
        fun rejected(cls: String, msg: String?) = journal.markRejected(
            lease, e.opId, e.version,
            RejectedProof(lease.runId, e.opId, e.requestHash, ISSUER, proofRef("rejected"), now()),
            errorClass = cls, error = msg, allowCancelRequested = allowCancel,
        )
        fun notSent(msg: String) = journal.markNotSent(
            lease, e.opId, e.version,
            NotSentProof(lease.runId, e.opId, e.requestHash, ISSUER, proofRef("not-sent"), now()),
            allowCancelRequested = allowCancel,
        )
        return when (r) {
            is PortResult.Ok -> Settled(move(OperationStatus.CONFIRMED, op.refOf(r.value)), null)
            PortResult.NotFound ->
                if (op.notFoundIsSuccess) Settled(move(OperationStatus.CONFIRMED, cls = null, msg = "already gone (404)"), null)
                else Settled(rejected("NOT_FOUND", "target not found"), null)
            is PortResult.Conflict -> Settled(rejected("CONFLICT", r.message), null)
            is PortResult.Rejected -> Settled(rejected("REJECTED", "HTTP ${r.httpStatus} ${r.code ?: ""} ${r.message}".trim()), null)
            // Adapter-proven: the request never left the process (connect refused / connect timeout / DNS / token failure).
            is PortResult.Transient -> Settled(notSent(r.message), null)
            is PortResult.Unknown -> Settled(move(OperationStatus.UNKNOWN, cls = "UNKNOWN", msg = r.message), null)
            is PortResult.IdentityMismatch ->
                Settled(move(OperationStatus.UNKNOWN, cls = "IDENTITY_MISMATCH", msg = "expected ${r.expected}, got ${r.actual}"), null)
            // Answered locally by the adapter before any request is built, so nothing was sent.
            is PortResult.Unsupported -> {
                val settled = notSent("${r.capability}${r.workaround?.let { " (workaround: $it)" } ?: ""}")
                Settled(settled, OpOutcome.WaitingDependency("exkes does not support ${r.capability}"))
            }
        }
    }

    private sealed interface Reconciled {
        data class Settled(val entry: JournalEntry) : Reconciled
        data class Stop(val outcome: OpOutcome) : Reconciled
    }

    private suspend fun <T> reconcile(lease: Lease, op: Op<T>, entry: JournalEntry, allowCancel: Boolean): Reconciled {
        val observed = try {
            op.observe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Observed.Undetermined("lookup threw ${e::class.simpleName}: ${e.message}")
        }
        // exkes list / label lookups are never AUTHORITATIVE_ABSENCE, so an absence cannot justify RETRYABLE (M0 rule).
        // Only an observed EXISTS is evidence: it adopts the effect and is never re-created.
        return when {
            observed is Observed.Exists -> Reconciled.Settled(
                journal.reconcile(
                    lease, entry.opId, entry.version, OperationStatus.CONFIRMED,
                    ReconciliationEvidence(
                        runId = lease.runId,
                        opId = entry.opId,
                        requestHash = entry.requestHash,
                        issuer = ISSUER,
                        observation = ExternalObservation.EXISTS,
                        evidenceRef = "lookup:${op.stableRef}".take(250),
                        observedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        providerCapability = ProviderCapability.LOOKUP_BY_STABLE_REF,
                    ),
                    externalRef = observed.ref,
                    allowCancelRequested = allowCancel,
                ),
            )
            else -> {
                if (entry.status == OperationStatus.UNKNOWN) {
                    journal.transition(
                        lease, entry.opId, entry.version, OperationStatus.RECONCILIATION_REQUIRED,
                        errorClass = "NEEDS_RECONCILIATION", error = observed.toString().take(200), allowCancelRequested = allowCancel,
                    )
                }
                Reconciled.Stop(OpOutcome.ReconciliationRequired(entry.opId, op.opType))
            }
        }
    }
}
