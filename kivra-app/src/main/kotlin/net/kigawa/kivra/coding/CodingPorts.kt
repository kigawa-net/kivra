package net.kigawa.kivra.coding

import java.time.Duration
import java.time.LocalDateTime
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository

/** The Run operations the coding service needs. Narrow on purpose: the service never changes a Run except through these. */
interface CodingRunPort {
    fun find(runId: RunId): DevelopmentRun?

    /** Sets the cancel request on the Run. Returns false when the Run cannot reach CANCELLED. */
    fun requestCancel(runId: RunId): Boolean

    /** DEVELOPING -> UNKNOWN with a fixed reason code, for an operator to decide. Fenced like every Run write. */
    fun holdForOperator(runId: RunId, reasonCode: String): HoldOutcome
}

enum class HoldOutcome { MOVED_TO_UNKNOWN, SKIPPED }

/**
 * Adapter over [DevelopmentRunRepository] and [LeaseStore]. [holdForOperator] takes the Run lease for the one transition and releases
 * it at once. If another worker holds the lease, or the Run moved on, nothing changes and the outcome is SKIPPED.
 */
class DbCodingRunPort(
    private val runs: DevelopmentRunRepository,
    private val leases: LeaseStore,
    private val actor: String = ACTOR,
) : CodingRunPort {
    override fun find(runId: RunId): DevelopmentRun? = runs.get(runId)

    override fun requestCancel(runId: RunId): Boolean = try {
        runs.requestCancel(runId, actor)
        true
    } catch (_: InvalidTransitionException) {
        false
    }

    override fun holdForOperator(runId: RunId, reasonCode: String): HoldOutcome {
        val lease = leases.acquire(runId, OWNER, LEASE_TTL, Duration.ZERO) ?: return HoldOutcome.SKIPPED
        return try {
            val run = runs.get(runId) ?: return HoldOutcome.SKIPPED
            runs.transition(
                lease, run.version, DevelopmentRunState.UNKNOWN, actor,
                reason = reasonCode, expectedFrom = DevelopmentRunState.DEVELOPING, auditReasonCode = reasonCode,
            )
            HoldOutcome.MOVED_TO_UNKNOWN
        } catch (_: LeaseLostException) {
            HoldOutcome.SKIPPED
        } catch (_: VersionConflictException) {
            HoldOutcome.SKIPPED
        } catch (_: InvalidTransitionException) {
            HoldOutcome.SKIPPED
        } finally {
            leases.release(lease)
        }
    }

    companion object {
        const val ACTOR = "coding-runtime"
        private const val OWNER = "coding-runtime"
        private val LEASE_TTL = Duration.ofMinutes(1)
    }
}

/**
 * Where an accepted change set is kept until the runner commits it. Keyed by (attempt, digest): a result that was refused, or a stale
 * one, can leave a row under another digest, and [load] never returns it for the row that was accepted.
 *
 * The MariaDB / H2 implementation is `DbCodingResultStore` (V15 `coding_results`). It is wired into the runtime only when the agent API
 * is enabled (`KIVRA_AGENT_API_ENABLED`, PR 4a). The self-development runner still refuses the CLI runtime at startup (see SelfDevSettings).
 */
interface CodingResultStore {
    /** Stores [patch] for ([attemptId], [digest]). Repeating the same pair is allowed. Throws when the write fails. */
    fun save(attemptId: String, digest: String, patch: PatchSet)

    /** The change set stored for ([attemptId], [digest]), or null. */
    fun load(attemptId: String, digest: String): PatchSet?
}

/** Deletes the stored change sets that the retention allows to go (design 15 section 6.5.3, ADR 0010 addendum 6). */
fun interface CodingResultPurge {
    /**
     * Deletes at most [limit] stored change sets created before [before] that nothing can read any more: the attempt is not active, and
     * either the Run is terminal or the row is not the attempt's accepted result. Returns the number deleted. The attempt and usage rows stay.
     */
    fun purgeBefore(before: LocalDateTime, limit: Int): Int
}

/** Deletes the run-execution monitor events that the retention allows to go (ADR 0012 section 3). The attempt rows stay. */
fun interface CodingMonitorPurge {
    /**
     * Deletes the events of at most [maxAttempts] attempts that are not active, whose Run is terminal, and whose last change is before
     * [before]. Returns the number of event rows deleted.
     */
    fun purgeBefore(before: LocalDateTime, maxAttempts: Int): Int
}
