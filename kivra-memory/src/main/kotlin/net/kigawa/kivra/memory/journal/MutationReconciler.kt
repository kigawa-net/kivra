package net.kigawa.kivra.memory.journal

import kotlinx.coroutines.CancellationException
import net.kigawa.kivra.memory.MemoryAudit
import net.kigawa.kivra.memory.MemoryAuditEvent
import net.kigawa.kivra.memory.MemoryAuditType

sealed interface LookupResult {
    /** A bound, read-only lookup observed that the request was applied. Settles UNKNOWN_HELD -> CONFIRMED. */
    data class Applied(val evidence: MutationEvidence) : LookupResult

    /**
     * Read-only observation found nothing. Eventually-consistent "not found" is NOT absence (design #11 / #37):
     * it never authorises a re-send, so the row stays UNKNOWN_HELD and is escalated.
     */
    data object NotFound : LookupResult

    /** Lookup not supported, forbidden, timed out or failed. [kind] is a machine code. */
    data class Unavailable(val kind: String) : LookupResult
}

/**
 * Read-only ResultLookup. Implementations MUST be bound to the identity passed in (tenant + principal) and must refuse
 * any other identity. They must never issue POST / PATCH / DELETE.
 */
fun interface MutationResultLookup {
    suspend fun lookup(identity: MutationIdentity, requestId: String, payloadDigest: String): LookupResult
}

/** Operator Escalation sink. Receives ids and machine codes only, never content. */
fun interface OperatorEscalation {
    fun escalate(operationId: String, reasonCode: String)
}

data class ReconcileReport(val examined: Int, val confirmed: Int, val escalated: Int)

/**
 * Reconciles UNKNOWN_HELD mutations of ONE writer identity (tenant + principal). It only reads (through
 * [MutationResultLookup]) and only settles on bound evidence. It never re-sends: a row that cannot be settled stays
 * UNKNOWN_HELD and is escalated to an operator.
 */
class MemoryMutationReconciler(
    private val journal: MemoryMutationJournalPort,
    private val lookup: MutationResultLookup,
    private val escalation: OperatorEscalation,
    private val audit: MemoryAudit = MemoryAudit.NONE,
) {
    suspend fun reconcile(writer: MutationWriter): ReconcileReport {
        var examined = 0
        var confirmed = 0
        var escalated = 0
        for (row in journal.unknownHeld(writer.identity)) {
            examined++
            val i = row.intent
            val result = try {
                lookup.lookup(i.identity, i.requestId, i.payloadDigest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LookupResult.Unavailable("ERROR")
            }
            val code: String? = when (result) {
                is LookupResult.Applied -> {
                    val settled = try {
                        journal.settleFromLookup(i.operationId, row.version, result.evidence)
                        true
                    } catch (e: MutationJournalRejectedException) {
                        false
                    }
                    if (settled) {
                        confirmed++
                        audit(MemoryAuditType.MUTATION_SETTLED, i.operationId, "LOOKUP_APPLIED")
                        null
                    } else {
                        "LOOKUP_EVIDENCE_REJECTED"
                    }
                }
                LookupResult.NotFound -> "LOOKUP_NOT_FOUND_NOT_AUTHORITATIVE"
                is LookupResult.Unavailable -> "LOOKUP_${result.kind.filter { it.isLetterOrDigit() || it == '_' }.uppercase()}"
            }
            if (code != null) {
                escalated++
                escalation.escalate(i.operationId, code)
                audit(MemoryAuditType.MUTATION_ESCALATED, i.operationId, code)
            }
        }
        return ReconcileReport(examined, confirmed, escalated)
    }

    private fun audit(type: MemoryAuditType, operationId: String, code: String) {
        audit.record(MemoryAuditEvent(type, executionId = null, traceId = null, detail = mapOf("operationId" to operationId, "code" to code)))
    }
}
