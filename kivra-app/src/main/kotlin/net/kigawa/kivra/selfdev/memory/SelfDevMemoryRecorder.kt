package net.kigawa.kivra.selfdev.memory

import java.security.MessageDigest
import java.text.Normalizer
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.SelfDevMemoryRepository
// SelfDevPlanTask removed in V40
import org.slf4j.LoggerFactory

/**
 * The best-effort writer that the self-development flows call (ADR 0016 section 10, stage P1). It turns a fact of the flow into a
 * [MemoryCandidate] and hands it to the one write function, [SelfDevMemoryRepository.save].
 *
 * - Shadow only: nothing here is read back. The Planner's prompt, the PlanValidator and the approval and submission decisions never see
 *   a record.
 * - Never throws. A failure, a refusal, or a missing row is logged with a fixed code and the caller goes on. The write is not part of
 *   the caller's transaction, so a failed write cannot roll back a state change, and a state change cannot fail because of a write.
 * - Off ([OFF], the default) means no candidate is built and nothing is called: the write function is not reached.
 * - Facts only, except for the Goal's title (U-6). A text, a plan, a pull request body and a model's output are never passed here.
 * - Logs carry the kind and the fixed refusal code only. Never a title, a text, a path or a message of an exception.
 */
class SelfDevMemoryRecorder private constructor(private val store: ((MemoryCandidate) -> MemorySaveResult)?) {
    /** True when the write function is attached. */
    val enabled: Boolean get() = store != null

    /** The Goal's title, recorded once when the operator submits the Goal (U-6). The body is never recorded. */
    fun goalTitle(goalId: String, title: String, operatorId: String) = write {
        MemoryCandidate(
            kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operatorId,
            goalId = goalId, body = title,
        )
    }

    /** The Planner proposed a plan with [taskCount] tasks. */
    fun planProposed(goalId: String, planId: String, taskCount: Int) = write {
        MemoryCandidate(kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = goalId, planId = planId, code = CODE_PLAN_PROPOSED, countValue = taskCount)
    }

    /** Stubbed pending Task model migration (V40). */
    // fun taskProposed removed

    /** The Planner blocked the goal with a planning-stage [reason] (no plan was stored). */
    fun planBlocked(goalId: String, reason: AutopilotReason) = write {
        MemoryCandidate(kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = goalId, code = reason.name)
    }

    /** Stubbed pending Task model migration (V40). */
    // fun taskFact removed

    /** Stubbed pending Task model migration (V40). */
    // fun runFact removed

    /** The Goal ended with [reason] (DONE or STOPPED). Other reasons are not recorded. */
    fun goalFact(goalId: String, reason: AutopilotReason?) = write {
        if (reason == null || reason !in GOAL_REASONS) return@write null
        MemoryCandidate(kind = "GOAL_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = goalId, code = reason.name)
    }

    /**
     * Builds the candidate with [build] and writes it. [build] runs only when the write is on, inside the try, so a failure while building
     * is contained too. A null candidate means "nothing to record".
     */
    private fun write(build: () -> MemoryCandidate?) {
        val save = store ?: return
        try {
            val candidate = build() ?: return
            when (val result = save(candidate)) {
                is MemorySaveResult.Refused -> logger.info("selfdev memory refused: kind={} code={}", candidate.kind, result.code.name)
                else -> Unit
            }
        } catch (e: Exception) {
            logger.warn("selfdev memory write failed: exception={}", e.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SelfDevMemoryRecorder::class.java)

        /** The recorder of a switched-off feature: writes nothing, reads nothing. */
        val OFF = SelfDevMemoryRecorder(null)

        /** Records through [repository]. The repository itself checks its own switch first. */
        fun of(repository: SelfDevMemoryRepository): SelfDevMemoryRecorder = SelfDevMemoryRecorder { repository.save(it) }

        /** For the tests: a recorder whose writes go to [store]. */
        internal fun withStore(store: (MemoryCandidate) -> MemorySaveResult): SelfDevMemoryRecorder = SelfDevMemoryRecorder(store)

        const val CODE_PLAN_PROPOSED = "PLAN_PROPOSED"

        /** The task reasons recorded as TASK_FACT: approvals, the rejection and the stop, and the outcome of the Run. */
        val TASK_REASONS: Set<AutopilotReason> = setOf(
            AutopilotReason.OPERATOR_APPROVED, AutopilotReason.AUTO_APPROVED, AutopilotReason.OPERATOR_SKIPPED,
            AutopilotReason.TASK_STOPPED, AutopilotReason.PR_MERGED, AutopilotReason.RUN_FAILED, AutopilotReason.POLICY_REJECTED,
            AutopilotReason.PR_ABANDONED, AutopilotReason.RUN_RELEASED, AutopilotReason.AUTO_SCOPE_EXCEEDED,
        )

        /** The goal reasons recorded as GOAL_FACT: the end of a goal (completed, stopped by the tasks, stopped by the operator). */
        val GOAL_REASONS: Set<AutopilotReason> = setOf(
            AutopilotReason.ALL_TASKS_COMPLETED, AutopilotReason.TASKS_INCOMPLETE, AutopilotReason.OPERATOR_STOPPED,
        )

        /**
         * The identity of a task's paths (ADR 0016 section 4.3): the SHA-256 of the sorted, distinct, NFC-normalized paths. The paths
         * themselves are not stored; the hash is the key that matches a task across plans.
         */
        fun identityOf(paths: List<String>): String {
            val canonical = paths.map { Normalizer.normalize(it, Normalizer.Form.NFC) }.toSortedSet().joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
