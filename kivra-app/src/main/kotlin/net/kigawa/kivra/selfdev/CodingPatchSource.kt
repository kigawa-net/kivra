package net.kigawa.kivra.selfdev

import net.kigawa.kivra.coding.CodingEnqueueOutcome
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.DevelopmentRun

/** What the DEVELOPING step learns from the coding runtime. Only fixed codes, never text from the agent. */
sealed interface CodingProposal {
    /** The attempt is queued or running. The Run stays where it is. */
    data object Pending : CodingProposal

    /** A result is stored and ready to be checked by the runner's PatchPolicy and committed. */
    data class Ready(val patch: PatchSet) : CodingProposal

    /** The attempt ended without a usable result. [to] is the Run state to move to, [reasonCode] a fixed code. */
    data class Failed(val to: DevelopmentRunState, val reasonCode: String) : CodingProposal
}

/** Supplies change sets for the CLI runtime. The runner depends on this port, not on the coding service. */
fun interface CodingPatchSource {
    fun proposal(run: DevelopmentRun, baseSha: String): CodingProposal
}

/**
 * Maps the coding service's attempt states onto the Run (design section 2.7, ADR 0010):
 * - a DEVELOPING Run gets an attempt queued (idempotent); a settled attempt is read instead of queuing again;
 * - RESULT_RECEIVED gives [CodingProposal.Ready] with the stored change set (a missing body is UNKNOWN);
 * - FAILED with AUTH_REQUIRED or NEEDS_HUMAN, ORPHANED and EXPIRED give UNKNOWN (an operator decides; no automatic retry);
 * - other FAILED codes give FAILED with `CODING_<code>`; CANCELLED gives CANCELLED.
 * A PATCH_READY Run (the resume after a crash) never goes back to queueing: it only reads the stored result, and any failure is UNKNOWN.
 */
class CodingAttemptPatchSource(private val service: CodingRuntimeService) : CodingPatchSource {

    override fun proposal(run: DevelopmentRun, baseSha: String): CodingProposal {
        val resuming = run.state == DevelopmentRunState.PATCH_READY
        val runId = run.runId.value
        val attempt: CodingAttempt = when {
            resuming -> service.latest(runId) ?: return unknown("CODING_NO_ATTEMPT")
            run.state == DevelopmentRunState.DEVELOPING -> when (val queued = service.enqueue(runId, baseSha)) {
                is CodingEnqueueOutcome.Queued -> return CodingProposal.Pending
                is CodingEnqueueOutcome.Active -> queued.attempt
                is CodingEnqueueOutcome.Settled -> queued.latest
                CodingEnqueueOutcome.RunNotFound, CodingEnqueueOutcome.RunNotDeveloping, CodingEnqueueOutcome.InvalidInput ->
                    return unknown("CODING_ENQUEUE_REFUSED")
            }
            else -> return unknown("CODING_NOT_DEVELOPING")
        }
        return when (attempt.state) {
            CodingAttemptState.QUEUED, CodingAttemptState.CLAIMED, CodingAttemptState.RUNNING -> CodingProposal.Pending
            CodingAttemptState.RESULT_RECEIVED -> service.loadResult(attempt)?.let { CodingProposal.Ready(it) }
                ?: unknown("CODING_RESULT_MISSING")
            CodingAttemptState.FAILED -> failedWith(attempt.errorCode, resuming)
            CodingAttemptState.CANCELLED -> if (resuming) unknown("CODING_CANCELLED") else CodingProposal.Failed(DevelopmentRunState.CANCELLED, "CODING_CANCELLED")
            CodingAttemptState.ORPHANED -> unknown(CodingRuntimeService.REASON_ORPHANED)
            CodingAttemptState.EXPIRED -> unknown(CodingRuntimeService.REASON_QUEUE_EXPIRED)
        }
    }

    private fun failedWith(errorCode: String?, resuming: Boolean): CodingProposal {
        val code = errorCode ?: return unknown("CODING_FAILED")
        if (resuming || code == "AUTH_REQUIRED" || code == "NEEDS_HUMAN") return unknown("CODING_$code")
        return CodingProposal.Failed(DevelopmentRunState.FAILED, "CODING_$code")
    }

    private fun unknown(reasonCode: String): CodingProposal = CodingProposal.Failed(DevelopmentRunState.UNKNOWN, reasonCode)
}
