package net.kigawa.kivra.core

import java.security.MessageDigest

/*
 * Task -> Run admission (Issue #26). The Task (fusha-task) body is untrusted input written by people and AI: nothing in
 * this file copies it into a reason, a log line or an audit payload. Reasons are fixed codes.
 */

/** Identity of a task in Kivra: `fusha:{environment}:{id}` (see [TaskId]). Each part is a bounded, plain token. */
data class TaskKey(val provider: String, val environment: String, val taskId: String) {
    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9._-]{1,64}")

        /** Null when the id is not in the `provider:environment:id` shape. Never throws on untrusted input. */
        fun parse(id: TaskId): TaskKey? {
            val parts = id.value.split(':')
            if (parts.size != 3) return null
            val (provider, environment, taskId) = parts
            if (!SEGMENT.matches(provider) || !SEGMENT.matches(environment) || !SEGMENT.matches(taskId)) return null
            return TaskKey(provider, environment, taskId)
        }
    }
}

/** Why a task is not admitted. Fixed codes only. */
enum class AdmissionBlockReason {
    INVALID_TASK_ID,
    INVALID_REQUEST_ID,
    /** No admission policy is confirmed for this deployment (fail closed). */
    POLICY_UNCONFIRMED,
    STATUS_UNKNOWN,
    STATUS_NOT_ADMITTABLE,
    CANCELLED,
    DEPENDENCIES_UNKNOWN,
    DEPENDENCIES_UNRESOLVED,
    TARGET_UNKNOWN,
    REPOSITORY_UNKNOWN,
    /** The snapshot is older than what the binding already observed. Nothing is created from it. */
    STALE_SNAPSHOT,
}

sealed interface AdmissionResult {
    /** A new run was created for the task and its binding now points at it. */
    data class Accepted(val runId: RunId) : AdmissionResult

    /** The binding points at a run that has not finished: no new run. */
    data class AlreadyActive(val runId: RunId) : AdmissionResult

    /** The binding points at a finished run and no explicit rerun was requested: no new run. */
    data class AlreadyTerminal(val runId: RunId) : AdmissionResult

    /** Admission was refused. Nothing (no binding, no run) was written. */
    data class Blocked(val reason: AdmissionBlockReason) : AdmissionResult
}

sealed interface RerunResult {
    /** The binding is reset: the next admission of the task creates a run under [generation]. */
    data class Requested(val generation: Int) : RerunResult

    data class Rejected(val reason: RerunRejectReason) : RerunResult
}

enum class RerunRejectReason {
    INVALID_TASK_ID,
    INVALID_OPERATOR,
    NOT_BOUND,
    /** Nothing to rerun: the binding has no run (the next admission creates one). */
    NOTHING_TO_RERUN,
    /** A rerun is only allowed after the current run has finished. */
    RUN_NOT_TERMINAL,
}

interface RunAdmission {
    /**
     * Admits [task] as a run, at most one active run per task (per provider, environment and task id). Idempotent: a
     * resend of the same snapshot returns the same run. Never called from a bare polling loop as a rerun.
     */
    suspend fun admit(task: TaskSnapshot, requestId: String): AdmissionResult

    /** Explicit operator rerun. Allowed only when the bound run has finished. Audited with [operatorId] and [reason]. */
    suspend fun requestRerun(task: TaskId, operatorId: String, reason: String): RerunResult
}

/**
 * The policy an admission is judged against. It names the fusha-task fields and values the deployment has confirmed.
 * Without a policy (null to [AdmissionEvaluator.evaluate]) every task is blocked.
 */
data class AdmissionPolicy(
    /** Key in [TaskSnapshot.fields] that holds the status (raw JSON string). */
    val statusField: String,
    val admittableStatuses: Set<String>,
    val cancelledStatuses: Set<String>,
    /** Key in [TaskSnapshot.fields] that holds the dependency list (raw JSON array). Must be `[]` to be satisfied. */
    val dependenciesField: String,
    /** Key in [TaskSnapshot.fields] that holds the target (A / B). */
    val targetField: String,
    val allowedTargets: Set<String>,
    /** Key in [TaskSnapshot.fields] that holds the repository. */
    val repositoryField: String,
) {
    init {
        require(admittableStatuses.isNotEmpty() && allowedTargets.isNotEmpty()) { "policy sets must not be empty" }
    }
}

/** Pure admission judgement. Fail closed: anything that cannot be read as a known value is blocked. */
object AdmissionEvaluator {
    /** Returns the block reason, or null when the snapshot may be admitted. */
    fun evaluate(task: TaskSnapshot, policy: AdmissionPolicy?): AdmissionBlockReason? {
        if (TaskKey.parse(task.id) == null) return AdmissionBlockReason.INVALID_TASK_ID
        if (policy == null) return AdmissionBlockReason.POLICY_UNCONFIRMED
        val status = stringField(task, policy.statusField) ?: return AdmissionBlockReason.STATUS_UNKNOWN
        if (status in policy.cancelledStatuses) return AdmissionBlockReason.CANCELLED
        if (status !in policy.admittableStatuses) return AdmissionBlockReason.STATUS_NOT_ADMITTABLE
        when (dependencyState(task, policy.dependenciesField)) {
            DependencyState.SATISFIED -> Unit
            DependencyState.UNKNOWN -> return AdmissionBlockReason.DEPENDENCIES_UNKNOWN
            DependencyState.UNRESOLVED -> return AdmissionBlockReason.DEPENDENCIES_UNRESOLVED
        }
        val target = stringField(task, policy.targetField) ?: return AdmissionBlockReason.TARGET_UNKNOWN
        if (target !in policy.allowedTargets) return AdmissionBlockReason.TARGET_UNKNOWN
        stringField(task, policy.repositoryField) ?: return AdmissionBlockReason.REPOSITORY_UNKNOWN
        return null
    }

    private enum class DependencyState { SATISFIED, UNKNOWN, UNRESOLVED }

    /** A JSON string literal without escapes, unquoted. Anything else is unknown (null). */
    private fun stringField(task: TaskSnapshot, key: String): String? {
        val raw = task.fields[key]?.trim() ?: return null
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return null
        val inner = raw.substring(1, raw.length - 1)
        if (inner.isBlank() || inner.contains('\\') || inner.contains('"')) return null
        return inner
    }

    /** A dependency list is satisfied only when it is an empty JSON array. Completion of listed tasks is not checked here. */
    private fun dependencyState(task: TaskSnapshot, key: String): DependencyState {
        val raw = task.fields[key]?.trim() ?: return DependencyState.UNKNOWN
        if (raw.length < 2 || raw.first() != '[' || raw.last() != ']') return DependencyState.UNKNOWN
        return if (raw.substring(1, raw.length - 1).isBlank()) DependencyState.SATISFIED else DependencyState.UNRESOLVED
    }
}

/** Digest of a snapshot's content (everything except fetchedAt). Used only to record what was observed, never to decide a run. */
object TaskSnapshotDigest {
    fun of(task: TaskSnapshot): String {
        val sb = StringBuilder()
        fun put(value: String?) {
            if (value == null) sb.append("N;") else sb.append('S').append(value.length).append(':').append(value).append(';')
        }
        put(task.id.value)
        put(task.title)
        put(task.description)
        put(task.assigneeId)
        put(task.dueDate)
        task.fields.toSortedMap().forEach { (k, v) -> put(k); put(v) }
        put(task.nextTaskIds.size.toString()); task.nextTaskIds.forEach { put(it.value) }
        put(task.relatedTaskIds.size.toString()); task.relatedTaskIds.forEach { put(it.value) }
        return MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
