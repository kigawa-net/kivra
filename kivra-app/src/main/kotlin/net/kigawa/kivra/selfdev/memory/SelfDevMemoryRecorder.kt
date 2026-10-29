package net.kigawa.kivra.selfdev.memory

import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevTask
import org.slf4j.LoggerFactory

class SelfDevMemoryRecorder private constructor(private val store: ((MemoryCandidate) -> MemorySaveResult)?) {
    val enabled: Boolean get() = store != null
    fun taskTitle(taskId: String, title: String, operatorId: String) = write {
        MemoryCandidate(kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operatorId, goalId = taskId, body = title)
    }
    fun planProposed(taskId: String, taskCount: Int) = write {
        MemoryCandidate(kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = taskId, code = CODE_PLAN_PROPOSED, countValue = taskCount)
    }
    fun planBlocked(taskId: String, reason: AutopilotReason) = write {
        MemoryCandidate(kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = taskId, code = reason.name)
    }
    fun taskFact(task: SelfDevTask, reason: AutopilotReason?) = write {
        if (reason == null || reason !in TASK_REASONS) return@write null
        MemoryCandidate(kind = "TASK_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = task.taskId, code = reason.name)
    }
    fun runFact(task: SelfDevTask, runId: String, code: String) = write {
        MemoryCandidate(kind = "RUN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = task.taskId, runId = runId, code = code)
    }
    fun goalFact(taskId: String, reason: AutopilotReason?) = write {
        if (reason == null || reason !in GOAL_REASONS) return@write null
        MemoryCandidate(kind = "GOAL_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = taskId, code = reason.name)
    }
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
        val OFF = SelfDevMemoryRecorder(null)
        fun of(repository: SelfDevMemoryRepository): SelfDevMemoryRecorder = SelfDevMemoryRecorder { repository.save(it) }
        internal fun withStore(store: (MemoryCandidate) -> MemorySaveResult): SelfDevMemoryRecorder = SelfDevMemoryRecorder(store)
        const val CODE_PLAN_PROPOSED = "PLAN_PROPOSED"
        val TASK_REASONS: Set<AutopilotReason> = setOf(
            AutopilotReason.OPERATOR_APPROVED, AutopilotReason.AUTO_APPROVED, AutopilotReason.OPERATOR_SKIPPED,
            AutopilotReason.TASK_STOPPED, AutopilotReason.PR_MERGED, AutopilotReason.RUN_FAILED, AutopilotReason.POLICY_REJECTED,
            AutopilotReason.PR_ABANDONED, AutopilotReason.RUN_RELEASED, AutopilotReason.AUTO_SCOPE_EXCEEDED)
        val GOAL_REASONS: Set<AutopilotReason> = setOf(
            AutopilotReason.ALL_TASKS_COMPLETED, AutopilotReason.TASKS_INCOMPLETE, AutopilotReason.OPERATOR_STOPPED)
    }
}
