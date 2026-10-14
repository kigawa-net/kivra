package net.kigawa.kivra.agent

import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidenceRef
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
 *    it is only looked up. EXISTS adopts (CONFIRMED) once the Authority verified that observation. Anything else goes
 *    to RECONCILIATION_REQUIRED and stops. exkes lookups are LOOKUP_BY_STABLE_REF only, so an absence never justifies
 *    RETRYABLE (create ops are never resent).
 *  - only [PortResult.Transient] (adapter-proven: the request was never sent) is retried, and only with a
 *    [VerifiedEvidenceRef] for NOT_SENT that the [TrustedEvidenceAuthority] issued. If no such ref can be obtained the
 *    row goes to UNKNOWN instead. [PortResult.Retryable] (a read failure that may have been sent) is never a NOT_SENT
 *    proof and is never mapped to RETRYABLE here (ADR 0005). Every other failure stays UNKNOWN and is reconciled: nothing here marks a
 *    mutating call FAILED, because no trusted REJECTED_NO_EFFECT contract exists yet (M0 #43). A 409 may be our own
 *    earlier POST, and a 4xx or 404 on a POST is not proof that nothing happened.
 *  - the attempt budget is enforced by the journal: RETRYABLE -> IN_FLIGHT may come back as FAILED
 *    (MAX_ATTEMPTS_EXCEEDED), and nothing is sent unless the returned status is IN_FLIGHT.
 */
internal class OperationExecutor(
    private val journal: OperationJournalRepository,
    private val authority: TrustedEvidenceAuthority,
    private val tenantId: String,
    private val providerContractVersion: String,
    private val maxAttempts: Int,
    private val backoff: java.time.Duration,
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
            PrepareOperation(op.opType, op.key, "EXKES", op.stableRef, op.requestHash, tenantId, providerContractVersion, maxAttempts),
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
                        PortResult.Unknown("OPERATION_CALL_EXCEPTION")
                    }
                    val settled = settle(lease, op, inFlight, result, allowCancel)
                    if (settled.stop != null) return settled.stop
                    entry = settled.entry!!
                }
            }
        }
    }

    private class Settled(val entry: JournalEntry?, val stop: OpOutcome?)

    /**
     * Asks the Authority for evidence of [purpose] about this operation, observed under [observation]. Returns null when
     * no evidence is obtained (refused, unavailable, or the row has no subject). Nothing is persisted here.
     */
    private suspend fun verified(e: JournalEntry, purpose: EvidencePurpose, observation: String): VerifiedEvidenceRef? {
        val tenant = e.tenantId ?: return null
        val contract = e.providerContractVersion ?: return null
        val subject = EvidenceSubject(
            tenantId = tenant,
            runId = e.runId.value,
            operationId = e.opId,
            providerId = e.externalSystem,
            actionId = e.opType,
            requestDigest = e.requestHash,
            providerContractVersion = contract,
        )
        return try {
            when (val v = authority.verify(subject, EvidenceObservationRef(observation.take(255)), purpose)) {
                is EvidenceVerification.Verified -> v.ref
                is EvidenceVerification.Refused -> null
            }
        } catch (unavailable: EvidenceAuthorityUnavailableException) {
            null
        }
    }

    private suspend fun <T> settle(lease: Lease, op: Op<T>, e: JournalEntry, r: PortResult<T>, allowCancel: Boolean): Settled {
        fun move(to: OperationStatus, ref: String? = null, cls: String? = null, msg: String? = null) =
            journal.transition(lease, e.opId, e.version, to, externalRef = ref, errorClass = cls, error = msg, allowCancelRequested = allowCancel)
        // Retry only with NOT_SENT evidence from the Authority; without it the call is recorded UNKNOWN (never resent).
        suspend fun notSent(msg: String): JournalEntry {
            val ref = verified(e, EvidencePurpose.NOT_SENT, "not-sent:${op.stableRef}")
                ?: return move(OperationStatus.UNKNOWN, cls = "NOT_SENT_UNVERIFIED", msg = msg)
            return journal.markNotSent(lease, e.opId, e.version, ref, allowCancelRequested = allowCancel)
        }
        return when (r) {
            is PortResult.Ok -> Settled(move(OperationStatus.CONFIRMED, op.refOf(r.value)), null)
            PortResult.NotFound ->
                if (op.notFoundIsSuccess) Settled(move(OperationStatus.CONFIRMED, cls = null, msg = "already gone (404)"), null)
                // A 404 on a create is not proof of "no effect": reconcile by lookup (absent -> RECONCILIATION_REQUIRED).
                else Settled(move(OperationStatus.UNKNOWN, cls = "NOT_FOUND", msg = "PROVIDER_NOT_FOUND"), null)
            // 409 may be our own earlier POST (name / label already taken by us): reconcile, never FAILED.
            is PortResult.Conflict -> Settled(move(OperationStatus.UNKNOWN, cls = "CONFLICT", msg = "PROVIDER_CONFLICT"), null)
            // Not a proven no-effect answer (no REJECTED_NO_EFFECT contract): reconcile, never FAILED.
            is PortResult.Rejected -> Settled(
                move(OperationStatus.UNKNOWN, cls = "REJECTED", msg = "PROVIDER_REJECTED_HTTP_${r.httpStatus}"), null,
            )
            // Adapter-proven: the request never left the process (connect refused / connect timeout / DNS / token failure).
            is PortResult.Transient -> Settled(notSent("PROVIDER_PRE_DISPATCH_FAILURE"), null)
            // A read-only failure (GET) after the request may have been sent. It is NOT a NotSentProof, so no markNotSent
            // and no RETRYABLE: a mutating Op never produces it (the adapter answers Unknown), so it fails closed here.
            is PortResult.Retryable -> Settled(move(OperationStatus.UNKNOWN, cls = "RETRYABLE_NOT_PROVEN", msg = "PROVIDER_RETRYABLE_NOT_PROVEN"), null)
            is PortResult.Unknown -> Settled(move(OperationStatus.UNKNOWN, cls = "UNKNOWN", msg = "PROVIDER_OUTCOME_UNKNOWN"), null)
            is PortResult.IdentityMismatch ->
                Settled(move(OperationStatus.UNKNOWN, cls = "IDENTITY_MISMATCH", msg = "PROVIDER_IDENTITY_MISMATCH"), null)
            // Answered locally by the adapter before any request is built, so nothing was sent.
            is PortResult.Unsupported -> {
                val settled = notSent("PROVIDER_UNSUPPORTED")
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
            Observed.Undetermined("LOOKUP_EXCEPTION")
        }
        // exkes list / label lookups are never AUTHORITATIVE_ABSENCE, so an absence cannot justify RETRYABLE (M0 rule).
        // Only an observed EXISTS is evidence: it adopts the effect and is never re-created.
        val existsRef = (observed as? Observed.Exists)?.let { verified(entry, EvidencePurpose.RECONCILE_EXISTS, "lookup:${op.stableRef}") }
        return when {
            observed is Observed.Exists && existsRef != null -> Reconciled.Settled(
                journal.reconcile(
                    lease, entry.opId, entry.version, OperationStatus.CONFIRMED, existsRef,
                    externalRef = (observed as Observed.Exists).ref,
                    allowCancelRequested = allowCancel,
                ),
            )
            else -> {
                if (entry.status == OperationStatus.UNKNOWN) {
                    journal.transition(
                        lease, entry.opId, entry.version, OperationStatus.RECONCILIATION_REQUIRED,
                        errorClass = "NEEDS_RECONCILIATION", error = "PROVIDER_LOOKUP_UNDETERMINED", allowCancelRequested = allowCancel,
                    )
                }
                Reconciled.Stop(OpOutcome.ReconciliationRequired(entry.opId, op.opType))
            }
        }
    }
}
