package net.kigawa.kivra.selfdev.autofix

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import org.slf4j.LoggerFactory

/**
 * Submits one autopilot task per opened incident issue (issue #226, the second stage of self-healing). The task is the same kind of task
 * an operator submits: it enters at TASK_SUBMITTED, the Planner plans it, and the approval setting (required or auto), the AutoScopeGuard,
 * the budget, the breaker and the run limits decide the rest. This watch skips none of them.
 *
 * Rules, all fixed in code:
 * - One fix per cause, ever: the fingerprint is the primary key of `selfdev_incident_fixes` (V47). A recurrence after a fix is a human's call.
 * - One fix at a time: the row that holds the active slot is the only one that may submit (a unique index, so two Pods agree). The slot is held
 *   until the plan root ends (DONE, STOPPED or SKIPPED). A root that waits for the operator holds it too.
 * - Only incidents of the last [CANDIDATE_AGE] are candidates; a row the detector did not open on GitHub (state other than OPENED, or no issue
 *   number) is never a candidate. The issue body is never read: the task text comes from the fixed codes and the number ([renderIncidentFixTask]).
 * - A submission that fails is retried up to [MAX_ATTEMPTS] times in all, then the fix is FAILED and never retried. The task itself is keyed
 *   by (created_by, `incident:<fingerprint>`), so a retry after a lost response returns the same task.
 *
 * It only writes its own table and submits a plan root through [IncidentTasks]. It never talks to GitHub, starts a Run or changes one. The
 * pull request of the fix is opened by the runner, as a draft, through [IncidentRunOrigin]. Logs carry fixed codes, numbers and the
 * fingerprint prefix only.
 */
class IncidentFixWatch(
    private val settings: IncidentFixSettings,
    private val store: IncidentFixStore,
    private val tasks: IncidentTasks,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = INTERVAL,
) {
    /** One pass. Disabled, it reads and writes nothing. Database errors propagate to [start], which logs them and goes on. */
    fun tick() {
        if (!settings.enabled) return
        settleHolder()
        // The active slot is taken (a fix is still running, or waits for the operator): nothing else starts.
        if (store.holder() != null) return
        if (store.pending() == null) {
            val candidate = store.nextCandidate(clock.instant().minus(CANDIDATE_AGE)) ?: return
            // False when another Pod claimed it first; the next pass reads what it wrote.
            store.claim(candidate)
        }
        val next = store.pending() ?: return
        if (!store.claimSlot(next.fingerprint)) return
        submit(next, attempts = next.attempts + 1)
    }

    /** Runs [tick] every [interval] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("incident fix: pass failed, exception={}", e.javaClass.simpleName)
            }
            delay(interval.toMillis())
        }
    }

    /** The row that holds the slot: an interrupted submission is submitted again (same key, same task), a submitted one waits for its root. */
    private fun settleHolder() {
        val held = store.holder() ?: return
        when (held.state) {
            FixState.SUBMITTING -> submit(held, attempts = held.attempts)
            FixState.SUBMITTED -> settleSubmitted(held)
            else -> Unit
        }
    }

    private fun settleSubmitted(held: FixRow) {
        val state: TaskState? = held.rootTaskId?.let { tasks.rootState(it) }
        if (state == null) {
            store.release(held.fingerprint, FixState.FAILED)
            logger.warn("incident fix: root task missing, fix failed, issue={}", held.issueNumber)
        } else if (state in ENDED_ROOT_STATES) {
            store.release(held.fingerprint, FixState.ENDED)
            logger.info("incident fix: root ended, slot released, issue={}, rootState={}", held.issueNumber, state.name)
        }
    }

    private fun submit(row: FixRow, attempts: Int) {
        val text = renderIncidentFixTask(row.incidentCode, row.reasonCode, row.issueNumber, row.fingerprint)
        val rootId = try {
            tasks.submit(row.fingerprint, text.title, text.body)
        } catch (e: Exception) {
            // The task may or may not exist. CLAIMED keeps the slot free for the retry; the key makes the retry return the same task.
            val to = if (attempts >= MAX_ATTEMPTS) FixState.FAILED else FixState.CLAIMED
            store.release(row.fingerprint, to)
            logger.warn("incident fix: submission failed, issue={}, attempts={}, state={}, exception={}", row.issueNumber, attempts, to.name, e.javaClass.simpleName)
            return
        }
        if (rootId == null) {
            // The key was used for other content. It is not retried.
            store.release(row.fingerprint, FixState.FAILED)
            logger.warn("incident fix: key conflict, fix failed, issue={}", row.issueNumber)
            return
        }
        // A failure here leaves SUBMITTING with the slot; the next pass submits again with the same key and gets the same root.
        store.markSubmitted(row.fingerprint, rootId)
        logger.info("incident fix: task submitted, issue={}, fingerprint={}", row.issueNumber, row.fingerprint.take(FINGERPRINT_SHOWN))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IncidentFixWatch::class.java)

        /** One pass per minute. */
        val INTERVAL: Duration = Duration.ofSeconds(60)

        /** Submissions in all for one fix, the first included. */
        const val MAX_ATTEMPTS = 3

        /** Only incidents opened this recently are candidates. An old issue, from before the fix was switched on, is not picked up. */
        val CANDIDATE_AGE: Duration = Duration.ofHours(24)

        /** Root states after which the root will not run again: its planned tasks are settled (ADR 0011 section 2.8b) or it was stopped. */
        val ENDED_ROOT_STATES: Set<TaskState> = setOf(TaskState.DONE, TaskState.STOPPED, TaskState.SKIPPED)

        private const val FINGERPRINT_SHOWN = 12
    }
}

/** The tasks a fix submits and reads. The production port is [OperatorIncidentTasks]; tests give their own. */
interface IncidentTasks {
    /** Submits (or finds, by the key) the plan root of a fix. Its id, or null when the key was used for other content. Throws on failure. */
    fun submit(fingerprint: String, title: String, body: String): String?

    /** The state of the task [taskId], or null when it does not exist. */
    fun rootState(taskId: String): TaskState?
}

/** The `created_by` of the tasks a fix submits. It is a fixed name, never an operator's. */
const val INCIDENT_FIX_PRINCIPAL = "kivra-incident"

/** The production port: the operator's own task submission, with the fix's principal and key. The approval rules are not bypassed. */
class OperatorIncidentTasks(
    private val operations: SelfDevOperatorRepository,
    private val targetRepo: String,
) : IncidentTasks {
    override fun submit(fingerprint: String, title: String, body: String): String? =
        when (val result = operations.submitTask(INCIDENT_FIX_PRINCIPAL, "incident:$fingerprint", title, body, targetRepo)) {
            is SelfDevSubmitResult.Created -> result.task.taskId
            is SelfDevSubmitResult.Replayed -> result.task.taskId
            SelfDevSubmitResult.KeyConflict -> null
        }

    override fun rootState(taskId: String): TaskState? = operations.task(taskId)?.state
}
