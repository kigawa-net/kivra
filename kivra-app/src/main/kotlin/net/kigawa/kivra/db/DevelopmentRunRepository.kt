package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.PrMergeObservationPolicy
import net.kigawa.kivra.core.PrMergeVerification
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.TrustedPrMergeAuthority
import net.kigawa.kivra.core.VerifiedPrMerge
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class DevelopmentRun(
    val runId: RunId,
    val taskId: String,
    val state: DevelopmentRunState,
    val stateReason: String?,
    val attempt: Int,
    val leaseOwner: String?,
    val leaseGeneration: Long,
    val leaseExpiresAt: LocalDateTime?,
    val holdReason: String?,
    val cancelRequested: Boolean,
    val version: Long,
    val traceId: String?,
    /** Fixed at Run Admission. Never changed by any update path. */
    val completionMode: RunCompletionMode,
    /** The PR repository and head SHA the run is bound to (set while in PULL_REQUEST, see [DevelopmentRunRepository.bindPullRequest]). */
    val prRepository: String?,
    val prHeadSha: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    /** Set only for Tasks submitted through the Task API (Issue #112, V9). */
    val taskTitle: String? = null,
    /** Authenticated owner of an API-submitted Task (V9). Null for runs created by the workflow path. */
    val principal: String? = null,
    val idempotencyKey: String? = null,
    /** The task body the coding agent receives (V17). Untrusted. Null when there is none. Never logged. */
    val taskBody: String? = null,
    /** The reference the body was resolved from, `owner/repo#n` (V17). Null when nothing was resolved. */
    val taskSource: String? = null,
    /** The stage the run is in (`current_stage`), null when none. Read-only here. */
    val currentStage: String? = null,
    /** Optional project grouping (V38). Null when the run has no project. */
    val projectId: String? = null,
)

/** Result of [DevelopmentRunRepository.submitTask]. */
sealed interface TaskSubmitResult {
    val run: DevelopmentRun

    /** A new run was persisted in CREATED. */
    data class Created(override val run: DevelopmentRun) : TaskSubmitResult

    /** The same (principal, idempotency key) was submitted before with the same title: the existing run is returned. */
    data class Replayed(override val run: DevelopmentRun) : TaskSubmitResult

    /** The same (principal, idempotency key) exists with a different title. Nothing is written. */
    data class ConflictingTitle(override val run: DevelopmentRun) : TaskSubmitResult
}

/** Another non-terminal run already exists for the task (`uk_dev_runs_active_task`). */
/** Fixed reason code for a pull request closed without merge (ADR 0008 item 6). */
const val PR_ABANDONED_REASON = "PR_ABANDONED"

/** Result of [DevelopmentRunRepository.releaseUnknownSelfDev]. */
sealed interface ReleaseResult {
    /** The run was UNKNOWN and is now CANCELLED. */
    data class Released(val run: DevelopmentRun) : ReleaseResult

    /** No self-development run with that id. A run of another kind is reported the same way. */
    data object NotFound : ReleaseResult

    /** The run is not UNKNOWN (or changed under this call). Nothing is written. */
    data object NotUnknown : ReleaseResult
}

/** Fixed state reason of a run released by an operator (ADR 0009). The free-text reason goes only to the audit payload. */
const val OPERATOR_RELEASED_REASON = "OPERATOR_RELEASED"

/**
 * Fixed state reason of a PULL_REQUEST self-development run without a binding, released by an operator (ADR 0009 addendum).
 * The free-text reason goes only to the audit payload.
 */
const val OPERATOR_RELEASED_UNBOUND_PR_REASON = "OPERATOR_RELEASED_UNBOUND_PR"

/** Fixed state reason of a DEVELOPING self-development run released by an operator because it stalled (ADR 0009 addendum 2026-11-11). */
const val OPERATOR_RELEASED_STUCK_REASON = "OPERATOR_RELEASED_STUCK"

/** How long a DEVELOPING run must have gone without any update before an operator may release it as stuck. */
val STUCK_DEVELOPING_MIN_IDLE: java.time.Duration = java.time.Duration.ofMinutes(10)

/** Result of [DevelopmentRunRepository.releaseStuckDevelopingSelfDev]. Every refusal writes nothing. */
sealed interface StuckReleaseResult {
    /** The run was a stalled DEVELOPING run and is now CANCELLED. */
    data class Released(val run: DevelopmentRun) : StuckReleaseResult

    /** No self-development run with that id. A run of another kind is reported the same way. */
    data object NotFound : StuckReleaseResult

    /** The run is not DEVELOPING (or changed under this call). */
    data object NotDeveloping : StuckReleaseResult

    /** A pull request is bound to the run. This path never touches a pull request, so it is refused. */
    data object PullRequestBound : StuckReleaseResult

    /** The run has a coding attempt in QUEUED, CLAIMED or RUNNING: an agent may be working on it. */
    data object ActiveAttempt : StuckReleaseResult

    /** A worker holds an unexpired lease on the run. */
    data object LeaseHeld : StuckReleaseResult

    /** The run was updated less than [STUCK_DEVELOPING_MIN_IDLE] ago. */
    data object TooRecent : StuckReleaseResult
}

/**
 * Fixed state reason of an UNKNOWN self-development run whose coding attempt was orphaned, released automatically (ADR 0009 追補
 * 2026-11-16). Distinct from [OPERATOR_RELEASED_REASON], so a manual and an automatic release can always be told apart.
 */
const val AUTO_RELEASED_ORPHAN_REASON = "AUTO_RELEASED_ORPHAN"

/**
 * The fixed actor of the automatic release, in the audit row. Not an operator: an operator's owner key never starts with `system:`
 * (the same principal as the budget's automatic release, ADR 0003 追補 2026-11-15).
 */
const val SELF_DEV_AUTO_RELEASE_ACTOR = "system:auto-release"

/** The fixed reason in the audit payload of an automatic release (there is no operator text). */
const val AUTO_RELEASE_GRACE_ELAPSED = "GRACE_ELAPSED"

/** An UNKNOWN reason the automatic release takes: the coding attempt's lease ran out (the agent stopped). */
const val CODING_ORPHANED_REASON = "CODING_ORPHANED"

/** An UNKNOWN reason the automatic release takes: the agent failed the attempt with NEEDS_HUMAN (ADR 0009 追補 2026-11-18). */
const val CODING_NEEDS_HUMAN_REASON = "CODING_NEEDS_HUMAN"

/** Fixed state reason of an UNKNOWN / [CODING_NEEDS_HUMAN_REASON] self-development run released automatically (ADR 0009 追補 2026-11-18). */
const val AUTO_RELEASED_NEEDS_HUMAN_REASON = "AUTO_RELEASED_NEEDS_HUMAN"

/**
 * The UNKNOWN reasons the automatic release takes, each with the state reason it leaves (ADR 0009 追補 2026-11-16 and 2026-11-18). Every
 * other UNKNOWN reason stays for an operator.
 */
val SELF_DEV_AUTO_RELEASE_REASONS: Map<String, String> = mapOf(
    CODING_ORPHANED_REASON to AUTO_RELEASED_ORPHAN_REASON,
    CODING_NEEDS_HUMAN_REASON to AUTO_RELEASED_NEEDS_HUMAN_REASON,
)

/** Result of [DevelopmentRunRepository.autoReleaseUnknownSelfDev]. Every refusal writes nothing. */
sealed interface UnknownAutoReleaseResult {
    /** The run was an UNKNOWN run of a reason in [SELF_DEV_AUTO_RELEASE_REASONS] and is now CANCELLED with the mapped reason. */
    data class Released(val run: DevelopmentRun) : UnknownAutoReleaseResult

    /**
     * The run is not (or no longer) eligible: not a self-development run, not UNKNOWN with a reason in [SELF_DEV_AUTO_RELEASE_REASONS] (for example already
     * released by another replica or an operator), bound to a pull request, past DEVELOPING once, with an active attempt or a held
     * lease, or updated within the grace.
     */
    data object NotEligible : UnknownAutoReleaseResult
}

/** Result of [DevelopmentRunRepository.releaseUnboundPullRequestSelfDev]. */
sealed interface UnboundPrReleaseResult {
    /** The run was an unbound PULL_REQUEST and is now CANCELLED. */
    data class Released(val run: DevelopmentRun) : UnboundPrReleaseResult

    /** No self-development run with that id. A run of another kind is reported the same way. */
    data object NotFound : UnboundPrReleaseResult

    /**
     * The run is not in PULL_REQUEST, or it is in PULL_REQUEST with a binding (it completes by itself when the pull request is
     * merged). Nothing is written.
     */
    data object NotEligible : UnboundPrReleaseResult
}

/** One audit event of a run, as the run's own views may show it (see [DevelopmentRunRepository.auditTrail]). */
data class AuditTrailEntry(
    val seq: Long,
    val occurredAt: LocalDateTime,
    val action: String,
    val result: String,
    /** The state a RUN_TRANSITION moved the run to (a fixed code), else null. */
    val toState: String?,
    /** The fixed reason code of a RUN_TRANSITION, else null. Never free text. */
    val reasonCode: String?,
)

/** One audit event of a project's Run, for the project dashboard. The same fixed fields as [AuditTrailEntry], plus the run. */
data class ProjectActivityEntry(
    val runId: String,
    val occurredAt: LocalDateTime,
    val action: String,
    val result: String,
    val toState: String?,
    val reasonCode: String?,
)

private val CODE_SHAPE = Regex("[A-Z][A-Z0-9_]{0,63}")

/** Thrown when a self-development submission would exceed the active-run cap. Nothing is written. */
class ActiveSelfDevLimitException : RuntimeException("active self-development run limit reached")

/** Audit actions of the pull request branch update (issue #276). They carry fixed codes and the first 12 characters of a head only. */
const val PR_REBASE_ATTEMPTED = "PR_REBASE_ATTEMPTED"
const val PR_REBASE_REBOUND = "PR_REBASE_REBOUND"
const val PR_REBASE_HELD = "PR_REBASE_HELD"

/**
 * Audit actions of the closing of a timed-out pull request (ADR 0008 追補 2026-11-18). They carry the pull request number and fixed codes
 * only: never a title, a body, the comment text or provider text.
 * - [PR_ABANDON_CLOSE_ATTEMPTED]: written before the writes of one attempt (the attempts are counted from these rows).
 * - [PR_ABANDON_COMMENTED]: the comment was posted, or its outcome is unknown (it is then never posted again).
 * - [PR_ABANDON_CLOSED]: the pull request was closed by kivra.
 * - [PR_ABANDON_CLOSE_GAVE_UP]: the attempts ran out; the run is abandoned with the pull request left as it is.
 */
const val PR_ABANDON_CLOSE_ATTEMPTED = "PR_ABANDON_CLOSE_ATTEMPTED"
const val PR_ABANDON_COMMENTED = "PR_ABANDON_COMMENTED"
const val PR_ABANDON_CLOSED = "PR_ABANDON_CLOSED"
const val PR_ABANDON_CLOSE_GAVE_UP = "PR_ABANDON_CLOSE_GAVE_UP"
private val PR_ABANDON_ACTIONS = setOf(PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_COMMENTED, PR_ABANDON_CLOSED, PR_ABANDON_CLOSE_GAVE_UP)

/** What the audit holds about the closing of a run's timed-out pull request: the attempts so far, and whether the comment went out. */
data class PrAbandonCloseTrail(val attempts: Int, val commented: Boolean)

/** How many characters of a commit id an audit payload keeps. */
const val HEAD_PREFIX_CHARS = 12

/** A payload with one commit id, shortened to [HEAD_PREFIX_CHARS]. */
internal fun headPayload(key: String, headSha: String): String =
    buildJsonObject { put(key, headSha.take(HEAD_PREFIX_CHARS)) }.toString()

/** Task id prefixes. A self-development run has [SELF_DEV_TASK_PREFIX]; the Task API keeps [API_TASK_PREFIX]. */
const val SELF_DEV_TASK_PREFIX = "selfdev:"
const val API_TASK_PREFIX = "api:"

private val TERMINAL_STATE_NAMES: List<String> = DevelopmentRunState.entries.filter { it.isTerminal }.map { it.name }

class DuplicateActiveRunException(val taskId: String) : RuntimeException("an active run already exists for task $taskId")

/**
 * DevelopmentRun persistence. Every state change is one transaction that (1) CASes the row on
 * `lease_owner / lease_generation / lease_expires_at > NOW / version / state`, (2) appends an audit event and
 * (3) appends an outbox event (design #1 section 5.4, #11 section 5.3).
 */
class DevelopmentRunRepository(
    private val database: Database,
    private val outbox: OutboxRepository,
    private val audit: AuditRepository,
    /** The merge Authority. Required (no default): a repository cannot be built without one, and it never receives evidence from callers. */
    private val prMergeAuthority: TrustedPrMergeAuthority,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Creates a run in CREATED (version 0) with its audit and outbox events. [completionMode] is fixed for the run's lifetime. */
    fun create(taskId: String, actor: String, completionMode: RunCompletionMode, traceId: String? = null): DevelopmentRun {
        val runId = RunId(Ulid.generate(clock))
        try {
            transaction(database) { insertCreatedRun(this, runId, taskId, actor, completionMode, traceId) }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) throw DuplicateActiveRunException(taskId) else throw e
        }
        return get(runId)!!
    }

    /**
     * The CREATED row with its RUN_CREATED audit and outbox event, inside the caller's transaction [tx]. Used by [create] and by
     * [DbRunAdmission], so that a binding and its run are written together (Issue #26). Does not read back the row.
     */
    internal fun insertCreatedRun(
        tx: Transaction,
        runId: RunId,
        taskId: String,
        actor: String,
        completionMode: RunCompletionMode,
        traceId: String?,
    ) = with(tx) {
        val now = utcNow(clock)
        DevelopmentRunsTable.insert {
            it[DevelopmentRunsTable.runId] = runId.value
            it[DevelopmentRunsTable.taskId] = taskId
            it[state] = DevelopmentRunState.CREATED.name
            it[DevelopmentRunsTable.completionMode] = completionMode.name
            it[DevelopmentRunsTable.traceId] = traceId
            it[createdAt] = now
            it[updatedAt] = now
        }
        audit.append(this, runId, AuditEntry(actor = actor, action = "RUN_CREATED", result = "OK", resource = "task:$taskId", traceId = traceId))
        outbox.append(this, runId, 0, "RunCreated", transitionPayload(taskId, null, DevelopmentRunState.CREATED), traceId)
    }

    fun get(runId: RunId): DevelopmentRun? = transaction(database) {
        DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.firstOrNull()?.toDevelopmentRunOrNull()
    }

    /**
     * Persists a Task submitted through the Task API (Issue #112). The run is created in CREATED with the same audit and
     * outbox events as [create], and it is never executed here: the mode is SIMULATED and no external system is called.
     * Idempotent per (principal, idempotencyKey). Concurrent identical submissions converge on one row: the loser of the
     * unique-index race re-reads the winner's row.
     */
    fun submitTask(
        principal: String,
        idempotencyKey: String,
        title: String,
        selfDev: Boolean = false,
        maxActiveSelfDev: Int? = null,
        /** The manual task body (V17), or null. It is part of the idempotency comparison: the same key with another body conflicts. */
        body: String? = null,
        /** The project of the Run (V38, ADR 0022). A self-development Run takes its task's project. It is part of the replay comparison. */
        projectId: String? = null,
    ): TaskSubmitResult {
        findTask(principal, idempotencyKey)?.let { return replayOrConflict(it, title, body, selfDev, projectId) }
        val runId = RunId(Ulid.generate(clock))
        val insert = {
            transaction(database) {
                if (selfDev && maxActiveSelfDev != null && countActiveSelfDev(this) >= maxActiveSelfDev) {
                    // Rolls back nothing yet written: the count is checked before any insert in this transaction.
                    throw ActiveSelfDevLimitException()
                }
                val now = utcNow(clock)
                // A self-development run is marked by its task id prefix; the Task API keeps "api:".
                val taskId = (if (selfDev) SELF_DEV_TASK_PREFIX else API_TASK_PREFIX) + runId.value
                DevelopmentRunsTable.insert {
                    it[DevelopmentRunsTable.runId] = runId.value
                    it[DevelopmentRunsTable.taskId] = taskId
                    it[state] = DevelopmentRunState.CREATED.name
                    // PR_ONLY is the least-privileged completion mode: an API Task can never reach RELEASE.
                    it[completionMode] = RunCompletionMode.PR_ONLY.name
                    it[taskTitle] = title
                    it[taskBody] = body
                    it[DevelopmentRunsTable.projectId] = projectId
                    it[DevelopmentRunsTable.principal] = principal
                    it[DevelopmentRunsTable.idempotencyKey] = idempotencyKey
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                audit.append(this, runId, AuditEntry(actor = principal, action = "RUN_CREATED", result = "OK", resource = "task:$taskId"))
                outbox.append(this, runId, 0, "RunCreated", transitionPayload(taskId, null, DevelopmentRunState.CREATED), null)
            }
        }
        try {
            // The count and the insert are serialized in this process. Two replicas can still race (see the self-development runbook).
            if (selfDev) synchronized(selfDevAdmissionLock) { insert() } else insert()
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
            // Lost the race (or a replay): the winner's row is committed, so a fresh transaction sees it.
            val existing = findTask(principal, idempotencyKey) ?: throw e
            return replayOrConflict(existing, title, body, selfDev, projectId)
        }
        return TaskSubmitResult.Created(get(runId)!!)
    }

    private val selfDevAdmissionLock = Any()

    private fun countActiveSelfDev(tx: Transaction): Long = with(tx) {
        DevelopmentRunsTable.selectAll()
            .where { (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%") and (DevelopmentRunsTable.state notInList TERMINAL_STATE_NAMES) }
            .count()
    }

    /**
     * Ids of the non-terminal self-development runs, oldest first. Used by the self-development worker, which takes every run ([project]
     * All). The status count narrows it to the caller's projects (ADR 0022 PR-D3).
     */
    fun activeSelfDevRunIds(limit: Int = 100, project: ProjectFilter = ProjectFilter.All): List<RunId> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where {
                val active = (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%") and (DevelopmentRunsTable.state notInList TERMINAL_STATE_NAMES)
                project.condition(DevelopmentRunsTable.projectId)?.let { active and it } ?: active
            }
            .orderBy(DevelopmentRunsTable.createdAt to SortOrder.ASC, DevelopmentRunsTable.runId to SortOrder.ASC)
            .limit(limit)
            .map { RunId(it[DevelopmentRunsTable.runId]) }
    }

    /**
     * The number of self-development runs in [state], manual and autopilot runs alike. Read by the autopilot's pending-pull-request
     * guard (ADR 0011 section 5.2). Read-only.
     */
    fun countSelfDevInState(state: DevelopmentRunState): Long = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%") and (DevelopmentRunsTable.state eq state.name) }
            .count()
    }

    /** Ids of the self-development runs in [state], oldest first, at most [limit]. Read-only (ADR 0014 shadow judgement). */
    fun selfDevRunIdsInState(state: DevelopmentRunState, limit: Int): List<RunId> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%") and (DevelopmentRunsTable.state eq state.name) }
            .orderBy(DevelopmentRunsTable.createdAt to SortOrder.ASC, DevelopmentRunsTable.runId to SortOrder.ASC)
            .limit(limit)
            .map { RunId(it[DevelopmentRunsTable.runId]) }
    }

    /**
     * Count all active (non-terminal) runs across all tasks. Used by the orchestrator admission guard
     * to enforce the concurrent-run limit (design #3 section 5.5). Read-only.
     */
    fun countActiveRuns(): Long = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { DevelopmentRunsTable.state notInList TERMINAL_STATE_NAMES }
            .count()
    }

    /**
     * Find all active (non-terminal) runs. Used by the orchestrator to check for runs
     * that should enter review phase (design #3 section 4.3, C2). Read-only.
     */
    fun findActiveRuns(): List<DevelopmentRun> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { DevelopmentRunsTable.state notInList TERMINAL_STATE_NAMES }
            .mapNotNull { it.toDevelopmentRunOrNull() }
    }

    /**
     * A kind mismatch under the same (principal, idempotency key) is a conflict, never a replay of another kind of run. A different
     * body is a conflict too: the body changes what the agent is asked to do. An empty body and no body are the same.
     */
    private fun replayOrConflict(existing: DevelopmentRun, title: String, body: String?, selfDev: Boolean, projectId: String?): TaskSubmitResult =
        if (existing.taskTitle == title && existing.taskBody.orEmpty() == body.orEmpty() &&
            existing.taskId.startsWith(SELF_DEV_TASK_PREFIX) == selfDev && existing.projectId == projectId
        ) {
            TaskSubmitResult.Replayed(existing)
        } else {
            TaskSubmitResult.ConflictingTitle(existing)
        }

    private fun findTask(principal: String, idempotencyKey: String): DevelopmentRun? = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { (DevelopmentRunsTable.principal eq principal) and (DevelopmentRunsTable.idempotencyKey eq idempotencyKey) }
            .firstOrNull()?.toDevelopmentRunOrNull()
    }

    /** A run owned by [principal]. Another principal's run is indistinguishable from a missing one (null). */
    fun getTask(runId: RunId, principal: String): DevelopmentRun? = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { (DevelopmentRunsTable.runId eq runId.value) and (DevelopmentRunsTable.principal eq principal) }
            .firstOrNull()?.toDevelopmentRunOrNull()
    }

    /** Runs submitted by [principal], newest first. */
    fun listTasks(
        principal: String,
        limit: Int = 50,
        selfDev: Boolean? = null,
        project: ProjectFilter = ProjectFilter.All,
    ): List<DevelopmentRun> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where {
                val byPrincipal = DevelopmentRunsTable.principal eq principal
                val scoped = when (selfDev) {
                    null -> byPrincipal
                    true -> byPrincipal and (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%")
                    false -> byPrincipal and (DevelopmentRunsTable.taskId notLike "$SELF_DEV_TASK_PREFIX%")
                }
                project.condition(DevelopmentRunsTable.projectId)?.let { scoped and it } ?: scoped
            }
            .orderBy(DevelopmentRunsTable.createdAt to SortOrder.DESC, DevelopmentRunsTable.runId to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { it.toDevelopmentRunOrNull() }
    }

    /**
     * One page of [principal]'s self-development runs, newest first, after [after] (the last run of the previous page, which the caller
     * has already checked to be owned by [principal]). Keyset paging on (createdAt, runId), so a run is never skipped or repeated.
     */
    fun listSelfDevPage(
        principal: String,
        limit: Int,
        after: DevelopmentRun?,
        project: ProjectFilter = ProjectFilter.All,
    ): List<DevelopmentRun> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where {
                val owned = (DevelopmentRunsTable.principal eq principal) and (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%")
                val paged = if (after == null) {
                    owned
                } else {
                    owned and (
                        (DevelopmentRunsTable.createdAt less after.createdAt) or
                            ((DevelopmentRunsTable.createdAt eq after.createdAt) and (DevelopmentRunsTable.runId less after.runId.value))
                        )
                }
                project.condition(DevelopmentRunsTable.projectId)?.let { paged and it } ?: paged
            }
            .orderBy(DevelopmentRunsTable.createdAt to SortOrder.DESC, DevelopmentRunsTable.runId to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { it.toDevelopmentRunOrNull() }
    }

    /**
     * Every Task API run of any principal (the operator view, Issue #56 UI), newest first. Workflow runs (no principal) are
     * never listed. [selfDev] null lists both kinds, true only self-development runs, false only the simulated ones.
     */
    fun listAllTasks(limit: Int, selfDev: Boolean? = null, project: ProjectFilter = ProjectFilter.All): List<DevelopmentRun> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where {
                val owned = DevelopmentRunsTable.principal.isNotNull()
                val scoped = when (selfDev) {
                    null -> owned
                    true -> owned and (DevelopmentRunsTable.taskId like "$SELF_DEV_TASK_PREFIX%")
                    false -> owned and (DevelopmentRunsTable.taskId notLike "$SELF_DEV_TASK_PREFIX%")
                }
                project.condition(DevelopmentRunsTable.projectId)?.let { scoped and it } ?: scoped
            }
            .orderBy(DevelopmentRunsTable.createdAt to SortOrder.DESC, DevelopmentRunsTable.runId to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { it.toDevelopmentRunOrNull() }
    }

    /** Any Task API run, for the operator view. Null for a missing run or a workflow run (no principal). */
    fun getAnyTask(runId: RunId): DevelopmentRun? = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { (DevelopmentRunsTable.runId eq runId.value) and DevelopmentRunsTable.principal.isNotNull() }
            .firstOrNull()?.toDevelopmentRunOrNull()
    }

    /**
     * The audit events of one run, oldest first. Only the time, the action, the result and the fixed codes of a state
     * transition are returned. The actor and the free-text payload (for example an operator's release reason) are never read out.
     */
    fun auditTrail(runId: RunId, limit: Int = 100): List<AuditTrailEntry> = transaction(database) {
        AuditEventsTable.selectAll()
            .where { AuditEventsTable.runId eq runId.value }
            .orderBy(AuditEventsTable.seq to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                val action = row[AuditEventsTable.action]
                val codes = if (action == "RUN_TRANSITION") transitionCodes(row[AuditEventsTable.payload]) else TransitionCodes(null, null)
                AuditTrailEntry(
                    seq = row[AuditEventsTable.seq],
                    occurredAt = row[AuditEventsTable.occurredAt],
                    action = action,
                    result = row[AuditEventsTable.result],
                    toState = codes.toState,
                    reasonCode = codes.reasonCode,
                )
            }
    }

    /**
     * The Runs of [projectId], newest first (the project dashboard). [principal] null: every principal's Run (the operator view);
     * otherwise only that principal's Runs.
     */
    fun listProjectRuns(projectId: String, principal: String?, limit: Int): List<DevelopmentRun> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where { projectRunsScope(projectId, principal) }
            .orderBy(DevelopmentRunsTable.createdAt to SortOrder.DESC, DevelopmentRunsTable.runId to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { it.toDevelopmentRunOrNull() }
    }

    /** The number of [projectId]'s Runs per state, in the same scope as [listProjectRuns]. Only states that occur are listed. */
    fun countProjectRunsByState(projectId: String, principal: String?): Map<String, Long> = transaction(database) {
        val total = DevelopmentRunsTable.runId.count()
        DevelopmentRunsTable.select(DevelopmentRunsTable.state, total)
            .where { projectRunsScope(projectId, principal) }
            .groupBy(DevelopmentRunsTable.state)
            .associate { it[DevelopmentRunsTable.state] to it[total] }
    }

    /**
     * The audit events of [projectId]'s Runs, newest first, in the same scope as [listProjectRuns]. Only the fixed fields
     * [auditTrail] reads are returned (never the actor or the free-text payload).
     */
    fun projectActivity(projectId: String, principal: String?, limit: Int): List<ProjectActivityEntry> = transaction(database) {
        (AuditEventsTable innerJoin DevelopmentRunsTable)
            .selectAll()
            .where { projectRunsScope(projectId, principal) }
            .orderBy(AuditEventsTable.occurredAt to SortOrder.DESC, AuditEventsTable.eventId to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                val action = row[AuditEventsTable.action]
                val codes = if (action == "RUN_TRANSITION") transitionCodes(row[AuditEventsTable.payload]) else TransitionCodes(null, null)
                ProjectActivityEntry(
                    runId = row[DevelopmentRunsTable.runId],
                    occurredAt = row[AuditEventsTable.occurredAt],
                    action = action,
                    result = row[AuditEventsTable.result],
                    toState = codes.toState,
                    reasonCode = codes.reasonCode,
                )
            }
    }

    private fun projectRunsScope(projectId: String, principal: String?): Op<Boolean> {
        val inProject = DevelopmentRunsTable.projectId eq projectId
        return if (principal == null) inProject else inProject and (DevelopmentRunsTable.principal eq principal)
    }

    private data class TransitionCodes(val toState: String?, val reasonCode: String?)

    /** Only the fixed state and reason codes of a transition payload are kept. Anything else (or a malformed payload) is dropped. */
    private fun transitionCodes(payload: String?): TransitionCodes {
        if (payload == null) return TransitionCodes(null, null)
        val obj = try { Json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return TransitionCodes(null, null) }
        fun code(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { CODE_SHAPE.matches(it) }
        return TransitionCodes(code("to"), code("reasonCode"))
    }

    /**
     * Fenced state transition. Throws [LeaseLostException] if the lease is no longer valid on the DB clock,
     * [VersionConflictException] if [expectedVersion] is stale, [InvalidTransitionException] for a transition
     * the state machine forbids. Nothing is written in any failure case.
     */
    fun transition(
        lease: Lease,
        expectedVersion: Long,
        to: DevelopmentRunState,
        actor: String,
        reason: String? = null,
        /** When set, the stored state must be exactly this one (checked under the row lock). */
        expectedFrom: DevelopmentRunState? = null,
        /** A fixed machine reason code, written to the audit payload. Never free text. */
        auditReasonCode: String? = null,
    ): DevelopmentRun {
        transaction(database) {
            // Locks the row; throws LeaseLostException / RunHaltedException (hold, or cancel request except the CANCELLED ack).
            requireLease(lease, allowCancelRequested = to == DevelopmentRunState.CANCELLED)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            val from = storedStateOrRefuse(lease.runId.value, row[DevelopmentRunsTable.state])
            if (row[DevelopmentRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[DevelopmentRunsTable.version]}, expected $expectedVersion")
            }
            if (expectedFrom != null && from != expectedFrom) {
                throw InvalidTransitionException("run ${lease.runId}: expected $expectedFrom, the run is in $from")
            }
            if (to in from.evidenceGated) {
                throw InvalidTransitionException("run ${lease.runId}: $from -> $to requires verified evidence; no reconciliation path exists yet")
            }
            if (!from.canTransitionTo(to)) throw InvalidTransitionException("run ${lease.runId}: $from -> $to is not allowed")
            val mode = RunCompletionMode.valueOf(row[DevelopmentRunsTable.completionMode])
            if (to == DevelopmentRunState.COMPLETED && !(mode == RunCompletionMode.RELEASE && from == DevelopmentRunState.VERIFYING)) {
                throw InvalidTransitionException(
                    "run ${lease.runId}: COMPLETED is reached only by completePrOnlyRun (PR_ONLY) or after VERIFYING (RELEASE); $from -> COMPLETED ($mode) refused",
                )
            }
            if (mode == RunCompletionMode.PR_ONLY && to in setOf(DevelopmentRunState.RELEASING, DevelopmentRunState.VERIFYING)) {
                throw InvalidTransitionException("run ${lease.runId}: PR_ONLY runs never release ($from -> $to refused)")
            }
            if (to == DevelopmentRunState.PATCH_READY && mode != RunCompletionMode.PR_ONLY) {
                throw InvalidTransitionException("run ${lease.runId}: PATCH_READY is only for PR_ONLY runs ($from -> $to, $mode refused)")
            }

            val updated = execUpdate(
                """
                UPDATE development_runs
                   SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ?
                   AND lease_expires_at > ${DbTime.NOW}
                   AND hold_reason IS NULL
                   AND version = ? AND state = ?
                """.trimIndent(),
                to.name, reason, utcNow(clock), lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name,
            )
            if (updated != 1) throw LeaseLostException("fenced update rejected for run ${lease.runId}")

            val taskId = row[DevelopmentRunsTable.taskId]
            val traceId = row[DevelopmentRunsTable.traceId]
            audit.append(
                this, lease.runId,
                AuditEntry(actor = actor, action = "RUN_TRANSITION", result = "OK", resource = "task:$taskId", traceId = traceId, payload = transitionPayload(taskId, from, to, auditReasonCode)),
            )
            outbox.append(this, lease.runId, expectedVersion + 1, "RunStateChanged", transitionPayload(taskId, from, to), traceId)
        }
        return get(lease.runId)!!
    }

    /**
     * Closes a PULL_REQUEST run whose pull request was closed without merge, or timed out (ADR 0008 item 6). This is a plain
     * PULL_REQUEST -> CANCELLED transition: it frees the one-active-run key, and the audit row carries the fixed reason code
     * [PR_ABANDONED_REASON]. No pull request or task text is written.
     */
    fun closeAbandonedPullRequest(lease: Lease, expectedVersion: Long, actor: String): DevelopmentRun =
        transition(
            lease, expectedVersion, DevelopmentRunState.CANCELLED, actor,
            reason = PR_ABANDONED_REASON,
            expectedFrom = DevelopmentRunState.PULL_REQUEST,
            auditReasonCode = PR_ABANDONED_REASON,
        )

    /**
     * Records the pull request identity the run works on (Issue #53). Allowed only in PULL_REQUEST with the lease and version.
     * A new bind (a push, or a changed policy / manifest) overwrites every binding column, so a merge verified against an older
     * binding is refused at completion and the policy / manifest must be supplied again.
     */
    fun bindPullRequest(lease: Lease, expectedVersion: Long, binding: PullRequestBinding, actor: String): DevelopmentRun {
        require(binding.runId == lease.runId) { "binding belongs to another run" }
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            val from = storedStateOrRefuse(lease.runId.value, row[DevelopmentRunsTable.state])
            if (row[DevelopmentRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[DevelopmentRunsTable.version]}, expected $expectedVersion")
            }
            if (from != DevelopmentRunState.PULL_REQUEST) throw InvalidTransitionException("run ${lease.runId} is in $from, not PULL_REQUEST")
            val updated = execUpdate(
                """
                UPDATE development_runs
                   SET pr_repository = ?, pr_head_sha = ?, pr_number = ?, pr_node_id = ?, pr_repository_id = ?, pr_base_ref = ?,
                       pr_policy_revision = ?, pr_review_manifest_digest = ?, version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ? AND lease_expires_at > ${DbTime.NOW}
                   AND hold_reason IS NULL AND version = ? AND state = ?
                """.trimIndent(),
                binding.repository, binding.headSha, binding.prNumber, binding.prNodeId, binding.repositoryId, binding.baseRef,
                binding.policyRevision, binding.reviewManifestDigest, utcNow(clock),
                lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name,
            )
            if (updated != 1) throw LeaseLostException("fenced update rejected for run ${lease.runId}")
            val taskId = row[DevelopmentRunsTable.taskId]
            val traceId = row[DevelopmentRunsTable.traceId]
            audit.append(this, lease.runId, AuditEntry(actor = actor, action = "RUN_PR_BOUND", result = "OK", resource = "task:$taskId", traceId = traceId))
            outbox.append(
                this, lease.runId, expectedVersion + 1, "RunPullRequestBound",
                buildJsonObject {
                    put("taskId", taskId)
                    put("repository", binding.repository)
                    put("headSha", binding.headSha)
                    put("prNumber", binding.prNumber)
                }.toString(),
                traceId,
            )
        }
        return get(lease.runId)!!
    }

    /**
     * Stores the resolved task body and its reference (V17) while the run is DEVELOPING. Fenced like every write: the lease must be held
     * and [expectedVersion] must match. Writes no state change. The audit row carries the fixed action and no content.
     */
    fun saveTaskReference(lease: Lease, expectedVersion: Long, body: String, source: String, actor: String): DevelopmentRun {
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            val from = storedStateOrRefuse(lease.runId.value, row[DevelopmentRunsTable.state])
            if (row[DevelopmentRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[DevelopmentRunsTable.version]}, expected $expectedVersion")
            }
            if (from != DevelopmentRunState.DEVELOPING) throw InvalidTransitionException("run ${lease.runId} is in $from, not DEVELOPING")
            if (row[DevelopmentRunsTable.taskSource] != null) throw InvalidTransitionException("run ${lease.runId} already has a task reference")
            val updated = execUpdate(
                """
                UPDATE development_runs
                   SET task_body = ?, task_source = ?, version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ? AND lease_expires_at > ${DbTime.NOW}
                   AND hold_reason IS NULL AND version = ? AND state = ? AND task_source IS NULL
                """.trimIndent(),
                body, source, utcNow(clock),
                lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name,
            )
            if (updated != 1) throw LeaseLostException("fenced update rejected for run ${lease.runId}")
            audit.append(
                this,
                lease.runId,
                AuditEntry(actor = actor, action = "RUN_TASK_REFERENCE_RESOLVED", result = "OK", resource = "task:${row[DevelopmentRunsTable.taskId]}"),
            )
        }
        return get(lease.runId)!!
    }

    /** The binding the run currently records, or null when any binding column is missing (for example a row written before V7). */
    /**
     * Writes the audit row of a branch update attempt (issue #276) before the update is sent. Fenced by [lease]. The row is the only
     * record that counts toward the update limit, so it is written first, in its own transaction. It carries the fixed action and the
     * first 12 characters of the head only.
     */
    fun recordRebaseAttempt(lease: Lease, headSha: String, actor: String) {
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            audit.append(
                this, lease.runId,
                AuditEntry(
                    actor = actor, action = PR_REBASE_ATTEMPTED, result = "OK", resource = "task:${row[DevelopmentRunsTable.taskId]}",
                    traceId = row[DevelopmentRunsTable.traceId], payload = headPayload("head", headSha),
                ),
            )
        }
    }

    /**
     * Writes the audit row of a branch update that was not completed (issue #276): the run stays for a person. Fenced by [lease].
     * [reasonCode] is a fixed code; the row carries the first 12 characters of [fromHead] and nothing else.
     */
    fun recordRebaseHold(lease: Lease, fromHead: String, reasonCode: String, actor: String) {
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            audit.append(
                this, lease.runId,
                AuditEntry(
                    actor = actor, action = PR_REBASE_HELD, result = "HELD", resource = "task:${row[DevelopmentRunsTable.taskId]}",
                    traceId = row[DevelopmentRunsTable.traceId],
                    payload = buildJsonObject {
                        put("from", fromHead.take(HEAD_PREFIX_CHARS))
                        put("reasonCode", reasonCode)
                    }.toString(),
                ),
            )
        }
    }

    /**
     * Writes one audit row of the closing of a timed-out pull request (ADR 0008 追補 2026-11-18). Fenced by [lease]. Audit only: the run
     * row is not touched (its version and `updated_at` stay, so the review timeout keeps counting from the same point). [action] is one of
     * the PR_ABANDON_ actions; [code] is a fixed code or null.
     */
    fun recordPrAbandonClose(lease: Lease, action: String, prNumber: Long, code: String?, actor: String) {
        require(action in PR_ABANDON_ACTIONS) { "not a pull request close action" }
        require(code == null || CODE_SHAPE.matches(code)) { "code must be a fixed code" }
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            audit.append(
                this, lease.runId,
                AuditEntry(
                    actor = actor, action = action, result = "OK", resource = "task:${row[DevelopmentRunsTable.taskId]}",
                    traceId = row[DevelopmentRunsTable.traceId],
                    payload = buildJsonObject {
                        put("prNumber", prNumber)
                        if (code != null) put("code", code)
                    }.toString(),
                ),
            )
        }
    }

    /** The audit trail of the closing of the run's timed-out pull request (see [recordPrAbandonClose]). Read-only. */
    fun prAbandonCloseTrail(runId: RunId): PrAbandonCloseTrail = transaction(database) {
        val actions = AuditEventsTable.selectAll()
            .where { (AuditEventsTable.runId eq runId.value) and (AuditEventsTable.action inList listOf(PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_COMMENTED)) }
            .map { it[AuditEventsTable.action] }
        PrAbandonCloseTrail(attempts = actions.count { it == PR_ABANDON_CLOSE_ATTEMPTED }, commented = PR_ABANDON_COMMENTED in actions)
    }

    /**
     * The branch update attempts of a run: (all attempts, attempts at or after [since]). Read from the audit rows written by
     * [recordRebaseAttempt]. Read-only.
     */
    fun countRebaseAttempts(runId: RunId, since: java.time.Instant): Pair<Int, Int> = transaction(database) {
        val times = AuditEventsTable.selectAll()
            .where { (AuditEventsTable.runId eq runId.value) and (AuditEventsTable.action eq PR_REBASE_ATTEMPTED) }
            .map { it[AuditEventsTable.occurredAt] }
        val sinceLocal = LocalDateTime.ofInstant(since, ZoneOffset.UTC)
        times.size to times.count { !it.isBefore(sinceLocal) }
    }

    /**
     * Moves the run's recorded pull request head from [fromHead] to [toHead] and writes the audit row in the same transaction (issue
     * #276). If either write fails, neither is kept, so the binding stays at [fromHead]. Fenced by [lease]; [fromHead] must still be the
     * recorded head, and [reasonCode] is a fixed code. Never called for a head that the caller has not verified.
     */
    fun rebindPullRequestHead(lease: Lease, expectedVersion: Long, fromHead: String, toHead: String, reasonCode: String, actor: String) {
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            val from = storedStateOrRefuse(lease.runId.value, row[DevelopmentRunsTable.state])
            if (row[DevelopmentRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[DevelopmentRunsTable.version]}, expected $expectedVersion")
            }
            if (from != DevelopmentRunState.PULL_REQUEST) throw InvalidTransitionException("run ${lease.runId} is in $from, not PULL_REQUEST")
            if (row[DevelopmentRunsTable.prHeadSha] != fromHead) throw LeaseLostException("recorded head changed for run ${lease.runId}")
            val updated = execUpdate(
                """
                UPDATE development_runs
                   SET pr_head_sha = ?, version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ? AND lease_expires_at > ${DbTime.NOW}
                   AND hold_reason IS NULL AND version = ? AND state = ? AND pr_head_sha = ?
                """.trimIndent(),
                toHead, utcNow(clock),
                lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name, fromHead,
            )
            if (updated != 1) throw LeaseLostException("fenced head update rejected for run ${lease.runId}")
            audit.append(
                this, lease.runId,
                AuditEntry(
                    actor = actor, action = PR_REBASE_REBOUND, result = "OK", resource = "task:${row[DevelopmentRunsTable.taskId]}",
                    traceId = row[DevelopmentRunsTable.traceId],
                    payload = buildJsonObject {
                        put("from", fromHead.take(HEAD_PREFIX_CHARS))
                        put("to", toHead.take(HEAD_PREFIX_CHARS))
                        put("reasonCode", reasonCode)
                    }.toString(),
                ),
            )
        }
    }

    fun loadPullRequestBinding(runId: RunId): PullRequestBinding? = transaction(database) {
        val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.firstOrNull() ?: return@transaction null
        row.toPullRequestBindingOrNull()
    }

    /**
     * PR_ONLY completion: PULL_REQUEST or WAITING_APPROVAL -> COMPLETED (Issue #53). The caller supplies no merge evidence.
     *
     * 1. The run's current [PullRequestBinding] is read (no transaction lock is held while the Authority is asked).
     * 2. The injected [TrustedPrMergeAuthority] verifies the merge. A refusal throws [PrMergeNotVerifiedException] (an
     *    [InvalidTransitionException] that carries the fixed reason code) and an unavailable Authority or any other exception
     *    propagates. In all these cases nothing has been written.
     * 3. [commitPrOnlyCompletion] re-checks the verified merge against the binding in the database inside the transaction
     *    and performs the compare-and-set. A re-bind (for example a push) in between makes the merge refused.
     */
    suspend fun completePrOnlyRun(lease: Lease, expectedVersion: Long, actor: String): DevelopmentRun {
        val binding = loadPullRequestBinding(lease.runId)
            ?: throw InvalidTransitionException("run ${lease.runId}: PR_ONLY completion refused: the run has no complete pull request binding")
        val merge = when (val verification = prMergeAuthority.verifyMerge(binding)) {
            is PrMergeVerification.Verified -> verification.merge
            is PrMergeVerification.Refused -> throw PrMergeNotVerifiedException(verification.reason)
        }
        return commitPrOnlyCompletion(lease, expectedVersion, merge, actor)
    }

    /**
     * The compare-and-set half of [completePrOnlyRun]. Private: a [VerifiedPrMerge] can only come from the Authority.
     * Completion is refused unless the merge matches the run's binding in the database at this point. Other outcomes write nothing:
     * - run is not PR_ONLY, or not in a PR state,
     * - the run has no complete binding (legacy or unbound row), or the merge belongs to another run,
     * - repository id / name, PR number, node id, base ref, head SHA, policy revision or review manifest digest differ from the binding,
     * - the observation is in the future or older than [PrMergeObservationPolicy.MAX_OBSERVATION_AGE],
     * - the lease is lost or the run is on hold / cancel-requested ([LeaseLostException] / [RunHaltedException]).
     * A second call fails (the run is already COMPLETED and terminal), so `RunCompleted` is appended exactly once.
     */
    private fun commitPrOnlyCompletion(lease: Lease, expectedVersion: Long, merge: VerifiedPrMerge, actor: String): DevelopmentRun {
        transaction(database) {
            requireLease(lease)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            val from = storedStateOrRefuse(lease.runId.value, row[DevelopmentRunsTable.state])
            if (row[DevelopmentRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[DevelopmentRunsTable.version]}, expected $expectedVersion")
            }
            if (RunCompletionMode.valueOf(row[DevelopmentRunsTable.completionMode]) != RunCompletionMode.PR_ONLY) {
                throw InvalidTransitionException("run ${lease.runId} is not a PR_ONLY run")
            }
            if (from !in DevelopmentRunState.prOnlyCompletionSources) {
                throw InvalidTransitionException("run ${lease.runId}: $from cannot complete as PR_ONLY")
            }
            verifyMerge(lease, row, merge)

            val observedAt = LocalDateTime.ofInstant(merge.observedAt, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
            val updated = execUpdate(
                """
                UPDATE development_runs
                   SET state = ?, state_reason = ?, completion_merge_sha = ?, completion_evidence_ref = ?, completion_observed_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ? AND lease_expires_at > ${DbTime.NOW}
                   AND hold_reason IS NULL AND version = ? AND state = ?
                """.trimIndent(),
                DevelopmentRunState.COMPLETED.name, "PR_MERGED", merge.mergeCommitSha, "github-pr:${merge.prNodeId}", observedAt,
                utcNow(clock), lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name,
            )
            if (updated != 1) throw LeaseLostException("fenced update rejected for run ${lease.runId}")

            val taskId = row[DevelopmentRunsTable.taskId]
            val traceId = row[DevelopmentRunsTable.traceId]
            val payload = buildJsonObject {
                put("taskId", taskId)
                put("from", from.name)
                put("to", DevelopmentRunState.COMPLETED.name)
                put("mergeSha", merge.mergeCommitSha)
            }.toString()
            audit.append(this, lease.runId, AuditEntry(actor = actor, action = "RUN_TRANSITION", result = "OK", resource = "task:$taskId", traceId = traceId, payload = payload))
            outbox.append(this, lease.runId, expectedVersion + 1, "RunCompleted", payload, traceId)
        }
        return get(lease.runId)!!
    }

    /** Checks the verified merge against the run's current binding. Throws [InvalidTransitionException] on any mismatch. */
    private fun verifyMerge(lease: Lease, row: ResultRow, m: VerifiedPrMerge) {
        fun refuse(why: String): Nothing = throw InvalidTransitionException("run ${lease.runId}: PR_ONLY completion refused: $why")
        if (m.runId != lease.runId) refuse("the verified merge belongs to another run")
        if (!m.merged) refuse("the pull request is not merged")
        val now = utcNow(clock)
        val observedAt = LocalDateTime.ofInstant(m.observedAt, ZoneOffset.UTC)
        if (observedAt.isAfter(now)) refuse("observedAt is in the future")
        if (observedAt.isBefore(now.minus(PrMergeObservationPolicy.MAX_OBSERVATION_AGE))) refuse("the observation is too old")
        val bound = row.toPullRequestBindingOrNull()
            ?: refuse("the run has no complete pull request binding (legacy or unbound row is not completable)")
        if (!m.repository.equals(bound.repository, ignoreCase = true)) refuse("the merged repository is not the bound repository")
        if (m.repositoryId != bound.repositoryId) refuse("the merged repository id is not the bound repository id")
        if (m.prNumber != bound.prNumber) refuse("the merged pull request number is not the bound number")
        if (m.prNodeId != bound.prNodeId) refuse("the merged pull request node id is not the bound node id")
        if (m.baseRef != bound.baseRef) refuse("the merged base is not the bound base")
        if (m.headSha != bound.headSha) refuse("the merged head is not the bound head")
        if (m.policyRevision != bound.policyRevision) refuse("the policy revision is not the bound revision")
        if (m.reviewManifestDigest != bound.reviewManifestDigest) refuse("the review manifest digest is not the bound digest")
    }

    private fun ResultRow.toPullRequestBindingOrNull(): PullRequestBinding? {
        val repository = this[DevelopmentRunsTable.prRepository] ?: return null
        val headSha = this[DevelopmentRunsTable.prHeadSha] ?: return null
        val prNumber = this[DevelopmentRunsTable.prNumber] ?: return null
        val prNodeId = this[DevelopmentRunsTable.prNodeId] ?: return null
        val repositoryId = this[DevelopmentRunsTable.prRepositoryId] ?: return null
        val baseRef = this[DevelopmentRunsTable.prBaseRef] ?: return null
        val policyRevision = this[DevelopmentRunsTable.prPolicyRevision] ?: return null
        val manifestDigest = this[DevelopmentRunsTable.prReviewManifestDigest] ?: return null
        return PullRequestBinding(
            runId = RunId(this[DevelopmentRunsTable.runId]),
            repository = repository,
            repositoryId = repositoryId,
            prNumber = prNumber,
            prNodeId = prNodeId,
            baseRef = baseRef,
            headSha = headSha,
            policyRevision = policyRevision,
            reviewManifestDigest = manifestDigest,
        )
    }

    /**
     * Administrator Hold. Applies to a worker that already holds the lease: its next renew, fenced transition,
     * journal / outbox write fails with [net.kigawa.kivra.core.RunHaltedException] (fail-closed), not only new acquires.
     * Needs no lease. Idempotent for the same reason; a different reason overwrites.
     */
    fun hold(runId: RunId, reason: String, actor: String): DevelopmentRun {
        adminUpdate(runId, actor, "RUN_HOLD", "RunHeld", "hold_reason = ?", reason)
        return get(runId)!!
    }

    fun clearHold(runId: RunId, actor: String): DevelopmentRun {
        adminUpdate(runId, actor, "RUN_HOLD_CLEARED", "RunHoldCleared", "hold_reason = NULL")
        return get(runId)!!
    }

    /**
     * Requests cancellation. The current worker may then only acknowledge it with a transition to CANCELLED;
     * every other write fails. Rejected for states that cannot reach CANCELLED (RELEASING / VERIFYING: use DEC rollback).
     */
    fun requestCancel(runId: RunId, actor: String): DevelopmentRun {
        adminUpdate(runId, actor, "RUN_CANCEL_REQUESTED", "RunCancelRequested", "cancel_requested = TRUE") { from ->
            if (!from.canTransitionTo(DevelopmentRunState.CANCELLED)) throw InvalidTransitionException("run $runId: $from cannot be cancelled")
        }
        return get(runId)!!
    }

    /** Completes a requested cancel when no worker owns the run any more (no lease, or it expired). Returns false if not applicable. */
    fun cancelUnowned(runId: RunId, actor: String): Boolean = transaction(database) {
        val row = lockRun(runId) ?: return@transaction false
        val from = storedStateOrRefuse(runId.value, row[DevelopmentRunsTable.state])
        if (!row[DevelopmentRunsTable.cancelRequested] || !from.canTransitionTo(DevelopmentRunState.CANCELLED)) return@transaction false
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET state = ?, state_reason = ?, version = version + 1, updated_at = ?,
                   lease_owner = NULL, lease_expires_at = NULL
             WHERE run_id = ? AND version = ? AND cancel_requested = TRUE
               AND (lease_owner IS NULL OR lease_expires_at <= ${DbTime.NOW})
            """.trimIndent(),
            DevelopmentRunState.CANCELLED.name, "CANCEL_REQUESTED", utcNow(clock), runId.value, row[DevelopmentRunsTable.version],
        )
        if (updated != 1) return@transaction false
        val taskId = row[DevelopmentRunsTable.taskId]
        val traceId = row[DevelopmentRunsTable.traceId]
        val payload = transitionPayload(taskId, from, DevelopmentRunState.CANCELLED)
        audit.append(this, runId, AuditEntry(actor = actor, action = "RUN_TRANSITION", result = "OK", resource = "task:$taskId", traceId = traceId, payload = payload))
        outbox.append(this, runId, row[DevelopmentRunsTable.version] + 1, "RunStateChanged", payload, traceId)
        true
    }

    /**
     * Operator release of a self-development run that is UNKNOWN (ADR 0009): UNKNOWN -> CANCELLED. This is the only way out of
     * UNKNOWN without evidence, and it exists only here, not in [transition]. The run row, its RUN_RELEASED audit event (which
     * carries [reason] in its payload) and its RunStateChanged outbox event are written in one transaction, or not at all.
     * The row is locked and the update is guarded on state = UNKNOWN, so a second release is NotUnknown.
     */
    fun releaseUnknownSelfDev(runId: RunId, operator: String, reason: String): ReleaseResult = transaction(database) {
        val row = lockRun(runId)
        if (row == null || !row[DevelopmentRunsTable.taskId].startsWith(SELF_DEV_TASK_PREFIX)) return@transaction ReleaseResult.NotFound
        val from = DevelopmentRunState.fromStorage(row[DevelopmentRunsTable.state])
        if (from != DevelopmentRunState.UNKNOWN) return@transaction ReleaseResult.NotUnknown
        val version = row[DevelopmentRunsTable.version]
        val taskId = row[DevelopmentRunsTable.taskId]
        val traceId = row[DevelopmentRunsTable.traceId]
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
             WHERE run_id = ? AND version = ? AND state = ?
            """.trimIndent(),
            DevelopmentRunState.CANCELLED.name, OPERATOR_RELEASED_REASON, utcNow(clock), runId.value, version, DevelopmentRunState.UNKNOWN.name,
        )
        if (updated != 1) throw VersionConflictException("run $runId changed concurrently")
        audit.append(
            this, runId,
            AuditEntry(
                actor = operator, action = "RUN_RELEASED", result = "OK", resource = "task:$taskId", traceId = traceId,
                payload = buildJsonObject {
                    put("taskId", taskId)
                    put("from", DevelopmentRunState.UNKNOWN.name)
                    put("to", DevelopmentRunState.CANCELLED.name)
                    put("reasonCode", OPERATOR_RELEASED_REASON)
                    put("reason", reason)
                }.toString(),
            ),
        )
        outbox.append(this, runId, version + 1, "RunStateChanged", transitionPayload(taskId, DevelopmentRunState.UNKNOWN, DevelopmentRunState.CANCELLED), traceId)
        // Read in this transaction: a nested transaction would not see the update above.
        val released = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.single().toDevelopmentRunOrNull()
        ReleaseResult.Released(checkNotNull(released) { "released run $runId has an unknown state" })
    }

    /**
     * Operator release of a self-development run that waits in PULL_REQUEST with no pull request binding (ADR 0009 addendum):
     * PULL_REQUEST -> CANCELLED, only when the binding is absent. Such a run was opened before the binding was recorded, so it
     * could never complete on merge (BINDING_MISSING). A run with a binding is NotEligible: it completes by itself on merge.
     * This path is separate from [transition] and from [releaseUnknownSelfDev]; the generic transition table is unchanged.
     *
     * The row is locked, so a concurrent bind cannot slip in between the binding check and the update. The update is guarded on
     * state and version. The run row, its RUN_RELEASED audit event (the reason only in its payload) and its RunStateChanged outbox
     * event are written in one transaction, or not at all. Nothing is written to GitHub: the pull request stays open and unmerged.
     */
    fun releaseUnboundPullRequestSelfDev(runId: RunId, operator: String, reason: String): UnboundPrReleaseResult = transaction(database) {
        val row = lockRun(runId)
        if (row == null || !row[DevelopmentRunsTable.taskId].startsWith(SELF_DEV_TASK_PREFIX)) return@transaction UnboundPrReleaseResult.NotFound
        val from = DevelopmentRunState.fromStorage(row[DevelopmentRunsTable.state])
        if (from != DevelopmentRunState.PULL_REQUEST || row.toPullRequestBindingOrNull() != null) {
            return@transaction UnboundPrReleaseResult.NotEligible
        }
        val version = row[DevelopmentRunsTable.version]
        val taskId = row[DevelopmentRunsTable.taskId]
        val traceId = row[DevelopmentRunsTable.traceId]
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
             WHERE run_id = ? AND version = ? AND state = ?
            """.trimIndent(),
            DevelopmentRunState.CANCELLED.name, OPERATOR_RELEASED_UNBOUND_PR_REASON, utcNow(clock), runId.value, version,
            DevelopmentRunState.PULL_REQUEST.name,
        )
        if (updated != 1) throw VersionConflictException("run $runId changed concurrently")
        audit.append(
            this, runId,
            AuditEntry(
                actor = operator, action = "RUN_RELEASED", result = "OK", resource = "task:$taskId", traceId = traceId,
                payload = buildJsonObject {
                    put("taskId", taskId)
                    put("from", DevelopmentRunState.PULL_REQUEST.name)
                    put("to", DevelopmentRunState.CANCELLED.name)
                    put("reasonCode", OPERATOR_RELEASED_UNBOUND_PR_REASON)
                    put("reason", reason)
                }.toString(),
            ),
        )
        outbox.append(
            this, runId, version + 1, "RunStateChanged",
            transitionPayload(taskId, DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.CANCELLED, OPERATOR_RELEASED_UNBOUND_PR_REASON),
            traceId,
        )
        // Read in this transaction: a nested transaction would not see the update above.
        val released = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.single().toDevelopmentRunOrNull()
        UnboundPrReleaseResult.Released(checkNotNull(released) { "released run $runId has an unknown state" })
    }

    /**
     * Operator release of a stalled DEVELOPING self-development run (ADR 0009 addendum 2026-11-11): DEVELOPING -> CANCELLED with
     * [OPERATOR_RELEASED_STUCK_REASON]. Refused (nothing written) unless all of these hold, checked under the row lock in this order:
     * the run is DEVELOPING, no pull request is bound, no coding attempt is QUEUED / CLAIMED / RUNNING, no worker holds an unexpired
     * lease (DB clock, like the fences), and the run was last updated at least [minIdle] ago.
     *
     * The update is guarded on state and version. The run row, its RUN_RELEASED audit event (the reason only in its payload) and its
     * RunStateChanged outbox event are written in one transaction. Budget reservations and the Order's cycle are left alone, exactly as
     * for [releaseUnknownSelfDev]: a CANCELLED run no longer counts against the active-run cap, and the cycle sees it as a failure.
     */
    fun releaseStuckDevelopingSelfDev(
        runId: RunId,
        operator: String,
        reason: String,
        minIdle: java.time.Duration = STUCK_DEVELOPING_MIN_IDLE,
    ): StuckReleaseResult = transaction(database) {
        val row = lockRun(runId)
        if (row == null || !row[DevelopmentRunsTable.taskId].startsWith(SELF_DEV_TASK_PREFIX)) return@transaction StuckReleaseResult.NotFound
        if (DevelopmentRunState.fromStorage(row[DevelopmentRunsTable.state]) != DevelopmentRunState.DEVELOPING) {
            return@transaction StuckReleaseResult.NotDeveloping
        }
        if (row[DevelopmentRunsTable.prNumber] != null || row[DevelopmentRunsTable.prRepository] != null || row[DevelopmentRunsTable.prHeadSha] != null) {
            return@transaction StuckReleaseResult.PullRequestBound
        }
        val activeAttempts = execQuery(
            "SELECT COUNT(*) FROM coding_attempts WHERE run_id = ? AND state IN ('QUEUED', 'CLAIMED', 'RUNNING')", runId.value,
        ) { it.getInt(1) }.single()
        if (activeAttempts > 0) return@transaction StuckReleaseResult.ActiveAttempt
        val leased = execQuery(
            "SELECT COUNT(*) FROM development_runs WHERE run_id = ? AND lease_owner IS NOT NULL AND lease_expires_at > ${DbTime.NOW}", runId.value,
        ) { it.getInt(1) }.single()
        if (leased > 0) return@transaction StuckReleaseResult.LeaseHeld
        if (java.time.Duration.between(row[DevelopmentRunsTable.updatedAt], utcNow(clock)) < minIdle) return@transaction StuckReleaseResult.TooRecent
        val version = row[DevelopmentRunsTable.version]
        val taskId = row[DevelopmentRunsTable.taskId]
        val traceId = row[DevelopmentRunsTable.traceId]
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
             WHERE run_id = ? AND version = ? AND state = ?
            """.trimIndent(),
            DevelopmentRunState.CANCELLED.name, OPERATOR_RELEASED_STUCK_REASON, utcNow(clock), runId.value, version,
            DevelopmentRunState.DEVELOPING.name,
        )
        if (updated != 1) throw VersionConflictException("run $runId changed concurrently")
        audit.append(
            this, runId,
            AuditEntry(
                actor = operator, action = "RUN_RELEASED", result = "OK", resource = "task:$taskId", traceId = traceId,
                payload = buildJsonObject {
                    put("taskId", taskId)
                    put("from", DevelopmentRunState.DEVELOPING.name)
                    put("to", DevelopmentRunState.CANCELLED.name)
                    put("reasonCode", OPERATOR_RELEASED_STUCK_REASON)
                    put("reason", reason)
                }.toString(),
            ),
        )
        outbox.append(
            this, runId, version + 1, "RunStateChanged",
            transitionPayload(taskId, DevelopmentRunState.DEVELOPING, DevelopmentRunState.CANCELLED, OPERATOR_RELEASED_STUCK_REASON),
            traceId,
        )
        // Read in this transaction: a nested transaction would not see the update above.
        val released = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.single().toDevelopmentRunOrNull()
        StuckReleaseResult.Released(checkNotNull(released) { "released run $runId has an unknown state" })
    }

    /**
     * The candidates of the automatic release (ADR 0009 追補 2026-11-16, 2026-11-18): self-development runs in UNKNOWN with a reason in
     * [SELF_DEV_AUTO_RELEASE_REASONS],
     * no pull request column set, last updated at least [grace] ago. Oldest first, at most [limit]. A candidate is only a hint: every
     * condition is checked again under the row lock by [autoReleaseUnknownSelfDev].
     */
    fun dueAutoReleasableSelfDev(grace: java.time.Duration, limit: Int): List<RunId> = transaction(database) {
        val cutoff = utcNow(clock).minus(grace)
        val reasons = SELF_DEV_AUTO_RELEASE_REASONS.keys.toList()
        execQuery(
            """
            SELECT run_id FROM development_runs
             WHERE state = ? AND state_reason IN (${reasons.joinToString(", ") { "?" }}) AND task_id LIKE ?
               AND pr_number IS NULL AND pr_repository IS NULL AND pr_head_sha IS NULL AND pr_node_id IS NULL
               AND updated_at <= ?
             ORDER BY updated_at, run_id
             LIMIT ?
            """.trimIndent(),
            DevelopmentRunState.UNKNOWN.name, *reasons.toTypedArray(), "$SELF_DEV_TASK_PREFIX%", cutoff, limit,
        ) { RunId(it.getString(1)) }
    }

    /**
     * The automatic release of one UNKNOWN self-development run (ADR 0009 追補 2026-11-16, 2026-11-18): UNKNOWN -> CANCELLED with the
     * state reason that [SELF_DEV_AUTO_RELEASE_REASONS] maps its reason to, by the fixed actor [SELF_DEV_AUTO_RELEASE_ACTOR]. Refused
     * (nothing written) unless all of these hold,
     * checked under the row lock:
     * - a self-development run, in UNKNOWN with a state reason in [SELF_DEV_AUTO_RELEASE_REASONS] ([CODING_ORPHANED_REASON] or
     *   [CODING_NEEDS_HUMAN_REASON]; any other UNKNOWN stays for an operator);
     * - no pull request column set (no binding, not even a partial one);
     * - the run never reached PATCH_READY or PULL_REQUEST (its audit has no transition to either). Every GitHub write of a run (branch,
     *   commit, pull request) happens from PATCH_READY on, and the coding agent holds no GitHub credential (ADR 0010), so such a run has
     *   written nothing to GitHub;
     * - no coding attempt in QUEUED, CLAIMED or RUNNING, and no worker holds an unexpired lease (DB clock);
     * - the run was last updated at least [grace] ago.
     *
     * The update is guarded on state and version. The run row, its RUN_RELEASED audit event and its RunStateChanged outbox event are
     * written in one transaction, exactly as for [releaseUnknownSelfDev]. A second call (another replica) finds the run CANCELLED and is
     * NotEligible. Budget reservations and the Order's cycle are left alone, as for the operator's release.
     */
    fun autoReleaseUnknownSelfDev(runId: RunId, grace: java.time.Duration): UnknownAutoReleaseResult = transaction(database) {
        val row = lockRun(runId)
        if (row == null || !row[DevelopmentRunsTable.taskId].startsWith(SELF_DEV_TASK_PREFIX)) return@transaction UnknownAutoReleaseResult.NotEligible
        if (DevelopmentRunState.fromStorage(row[DevelopmentRunsTable.state]) != DevelopmentRunState.UNKNOWN) {
            return@transaction UnknownAutoReleaseResult.NotEligible
        }
        val fromReason = row[DevelopmentRunsTable.stateReason] ?: return@transaction UnknownAutoReleaseResult.NotEligible
        val releasedReason = SELF_DEV_AUTO_RELEASE_REASONS[fromReason] ?: return@transaction UnknownAutoReleaseResult.NotEligible
        if (row[DevelopmentRunsTable.prNumber] != null || row[DevelopmentRunsTable.prRepository] != null ||
            row[DevelopmentRunsTable.prHeadSha] != null || row[DevelopmentRunsTable.prNodeId] != null
        ) {
            return@transaction UnknownAutoReleaseResult.NotEligible
        }
        val reachedGitHubStep = execQuery(
            "SELECT COUNT(*) FROM audit_events WHERE run_id = ? AND (payload LIKE ? OR payload LIKE ?)",
            runId.value, "%\"to\":\"${DevelopmentRunState.PATCH_READY.name}\"%", "%\"to\":\"${DevelopmentRunState.PULL_REQUEST.name}\"%",
        ) { it.getInt(1) }.single()
        if (reachedGitHubStep > 0) return@transaction UnknownAutoReleaseResult.NotEligible
        val activeAttempts = execQuery(
            "SELECT COUNT(*) FROM coding_attempts WHERE run_id = ? AND state IN ('QUEUED', 'CLAIMED', 'RUNNING')", runId.value,
        ) { it.getInt(1) }.single()
        if (activeAttempts > 0) return@transaction UnknownAutoReleaseResult.NotEligible
        val leased = execQuery(
            "SELECT COUNT(*) FROM development_runs WHERE run_id = ? AND lease_owner IS NOT NULL AND lease_expires_at > ${DbTime.NOW}", runId.value,
        ) { it.getInt(1) }.single()
        if (leased > 0) return@transaction UnknownAutoReleaseResult.NotEligible
        if (java.time.Duration.between(row[DevelopmentRunsTable.updatedAt], utcNow(clock)) < grace) return@transaction UnknownAutoReleaseResult.NotEligible
        val version = row[DevelopmentRunsTable.version]
        val taskId = row[DevelopmentRunsTable.taskId]
        val traceId = row[DevelopmentRunsTable.traceId]
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
             WHERE run_id = ? AND version = ? AND state = ? AND state_reason = ?
            """.trimIndent(),
            DevelopmentRunState.CANCELLED.name, releasedReason, utcNow(clock), runId.value, version,
            DevelopmentRunState.UNKNOWN.name, fromReason,
        )
        if (updated != 1) throw VersionConflictException("run $runId changed concurrently")
        audit.append(
            this, runId,
            AuditEntry(
                actor = SELF_DEV_AUTO_RELEASE_ACTOR, action = "RUN_RELEASED", result = "OK", resource = "task:$taskId", traceId = traceId,
                payload = buildJsonObject {
                    put("taskId", taskId)
                    put("from", DevelopmentRunState.UNKNOWN.name)
                    put("to", DevelopmentRunState.CANCELLED.name)
                    put("reasonCode", releasedReason)
                    put("reason", AUTO_RELEASE_GRACE_ELAPSED)
                }.toString(),
            ),
        )
        outbox.append(
            this, runId, version + 1, "RunStateChanged",
            transitionPayload(taskId, DevelopmentRunState.UNKNOWN, DevelopmentRunState.CANCELLED, releasedReason),
            traceId,
        )
        // Read in this transaction: a nested transaction would not see the update above.
        val released = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.single().toDevelopmentRunOrNull()
        UnknownAutoReleaseResult.Released(checkNotNull(released) { "released run $runId has an unknown state" })
    }

    /**
     * One pass of the automatic release (ADR 0009 追補 2026-11-16, 2026-11-18): at most [limit] candidates of [dueAutoReleasableSelfDev], each released in
     * its own transaction by [autoReleaseUnknownSelfDev]. A candidate that is no longer eligible (another replica or an operator released
     * it first, or it changed) counts as `alreadyReleased`. A candidate that throws counts as `failed` and is retried on the next pass;
     * the exception never stops the others.
     */
    fun autoReleaseUnknownRuns(grace: java.time.Duration, limit: Int): AutoReleaseReport {
        var released = 0
        var already = 0
        var failed = 0
        val reasons = mutableMapOf<String, Int>()
        for (runId in dueAutoReleasableSelfDev(grace, limit)) {
            try {
                when (val result = autoReleaseUnknownSelfDev(runId, grace)) {
                    is UnknownAutoReleaseResult.Released -> {
                        released++
                        result.run.stateReason?.let { reasons.merge(it, 1, Int::plus) }
                    }
                    UnknownAutoReleaseResult.NotEligible -> already++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return AutoReleaseReport(released, already, failed, reasons)
    }

    private fun lockRun(runId: RunId): ResultRow? =
        DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull()

    /**
     * Enqueues a comment for the run's task. The comment is delivered to fusha-task by the outbox publisher.
     * Returns the outbox event id, or null when the run is not found.
     */
    fun enqueueComment(runId: RunId, actor: String, body: String): String? = transaction(database) {
        val row = lockRun(runId) ?: return@transaction null
        val taskId = row[DevelopmentRunsTable.taskId]
        val version = row[DevelopmentRunsTable.version]
        val traceId = row[DevelopmentRunsTable.traceId]
        val payload = buildJsonObject {
            put("taskId", taskId)
            put("body", body)
        }.toString()
        audit.append(this, runId, AuditEntry(actor = actor, action = "TASK_COMMENT", result = "OK", resource = "task:$taskId", traceId = traceId, payload = payload))
        outbox.append(this, runId, version + 1, "TaskComment", payload, traceId)
    }

    private fun adminUpdate(
        runId: RunId, actor: String, action: String, eventType: String, setClause: String, vararg setParams: Any?,
        check: (DevelopmentRunState) -> Unit = {},
    ) {
        transaction(database) {
            val row = lockRun(runId) ?: throw NoSuchElementException("run $runId not found")
            val from = storedStateOrRefuse(runId.value, row[DevelopmentRunsTable.state])
            if (from.isTerminal) throw InvalidTransitionException("run $runId is already $from")
            check(from)
            val version = row[DevelopmentRunsTable.version]
            val updated = execUpdate(
                "UPDATE development_runs SET $setClause, version = version + 1, updated_at = ? WHERE run_id = ? AND version = ?",
                *setParams, utcNow(clock), runId.value, version,
            )
            if (updated != 1) throw VersionConflictException("run $runId changed concurrently")
            val taskId = row[DevelopmentRunsTable.taskId]
            val traceId = row[DevelopmentRunsTable.traceId]
            audit.append(this, runId, AuditEntry(actor = actor, action = action, result = "OK", resource = "task:$taskId", traceId = traceId))
            outbox.append(this, runId, version + 1, eventType, buildJsonObject { put("taskId", taskId); put("state", from.name) }.toString(), traceId)
        }
    }

    private fun transitionPayload(taskId: String, from: DevelopmentRunState?, to: DevelopmentRunState, reasonCode: String? = null): String =
        buildJsonObject {
            put("taskId", taskId)
            if (from != null) put("from", from.name)
            put("to", to.name)
            if (reasonCode != null) put("reasonCode", reasonCode)
        }.toString()

    /** The stored state, or a refusal for a name this binary does not know. Writers never act on such a run (fail-closed). */
    private fun storedStateOrRefuse(runId: String, raw: String): DevelopmentRunState =
        DevelopmentRunState.fromStorage(raw)
            ?: throw InvalidTransitionException("run $runId is in a state this version does not know; refused")

    /** Null for a state name this binary does not know: such a run is not shown and never acted on (fail-closed). */
    private fun ResultRow.toDevelopmentRunOrNull(): DevelopmentRun? {
        val state = DevelopmentRunState.fromStorage(this[DevelopmentRunsTable.state]) ?: return null
        return toDevelopmentRun(state)
    }

    /**
     * Runs in RETRY_WAITING that have been waiting since before [cutoff], oldest first.
     * Used by the auto-recovery worker to find runs eligible for automatic retry.
     * Read-only, needs no lease.
     */
    fun getRetryWaitingRuns(limit: Int, cutoff: LocalDateTime): List<DevelopmentRun> = transaction(database) {
        DevelopmentRunsTable.selectAll()
            .where {
                (DevelopmentRunsTable.state eq DevelopmentRunState.RETRY_WAITING.name) and
                    (DevelopmentRunsTable.updatedAt less cutoff)
            }
            .orderBy(DevelopmentRunsTable.updatedAt to SortOrder.ASC, DevelopmentRunsTable.runId to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { it.toDevelopmentRunOrNull() }
    }

    private fun ResultRow.toDevelopmentRun(state: DevelopmentRunState) = DevelopmentRun(
        completionMode = RunCompletionMode.valueOf(this[DevelopmentRunsTable.completionMode]),
        prRepository = this[DevelopmentRunsTable.prRepository],
        prHeadSha = this[DevelopmentRunsTable.prHeadSha],
        runId = RunId(this[DevelopmentRunsTable.runId]),
        taskId = this[DevelopmentRunsTable.taskId],
        state = state,
        stateReason = this[DevelopmentRunsTable.stateReason],
        attempt = this[DevelopmentRunsTable.attempt],
        leaseOwner = this[DevelopmentRunsTable.leaseOwner],
        leaseGeneration = this[DevelopmentRunsTable.leaseGeneration],
        leaseExpiresAt = this[DevelopmentRunsTable.leaseExpiresAt],
        holdReason = this[DevelopmentRunsTable.holdReason],
        cancelRequested = this[DevelopmentRunsTable.cancelRequested],
        version = this[DevelopmentRunsTable.version],
        traceId = this[DevelopmentRunsTable.traceId],
        createdAt = this[DevelopmentRunsTable.createdAt],
        updatedAt = this[DevelopmentRunsTable.updatedAt],
        taskTitle = this[DevelopmentRunsTable.taskTitle],
        principal = this[DevelopmentRunsTable.principal],
        idempotencyKey = this[DevelopmentRunsTable.idempotencyKey],
        taskBody = this[DevelopmentRunsTable.taskBody],
        taskSource = this[DevelopmentRunsTable.taskSource],
        currentStage = this[DevelopmentRunsTable.currentStage],
        projectId = this[DevelopmentRunsTable.projectId],
    )
}

/**
 * The merge Authority refused to verify the merge of the bound pull request. [reason] is a fixed code from the core verifier
 * (for example HEAD_SHA_MISMATCH) or from the Authority, never provider text. The run is left as it is.
 */
class PrMergeNotVerifiedException(val reason: String) :
    InvalidTransitionException("PR_ONLY completion refused by the merge authority: $reason")
