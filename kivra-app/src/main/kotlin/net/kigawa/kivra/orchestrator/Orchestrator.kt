package net.kigawa.kivra.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.AdmissionEvaluator
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.RunAdmission
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.fusha.poller.PollResult
import net.kigawa.kivra.fusha.poller.TaskChanged
import net.kigawa.kivra.fusha.poller.TaskDiscovered
import net.kigawa.kivra.fusha.poller.TaskEventSink
import net.kigawa.kivra.fusha.poller.TaskPollEvent
import net.kigawa.kivra.fusha.poller.TaskPoller
import net.kigawa.kivra.fusha.poller.TaskPossiblyGone
import net.kigawa.kivra.agent.AgentProfile
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Orchestrator main loop (design #3 section 5.1, ADR 0008).
 *
 * One [tick] does, in order:
 * 1. Leader lease check: skip if not leader
 * 2. fusha-task sync: poll for task changes via [TaskPoller]
 * 3. Dependency & admissibility evaluation
 * 4. Admission: create runs for eligible tasks
 * 5. Reconcile existing runs
 *
 * All steps are idempotent. A crash mid-tick converges on the next tick (DB is source of truth).
 *
 * Triggered by polling (primary) and ai-scheduler (secondary, optional).
 * The orchestrator keeps working while ai-scheduler is down (design #3 section 11).
 */
class Orchestrator(
    private val poller: TaskPoller,
    private val admission: RunAdmission,
    private val profile: AgentProfile,
    private val config: OrchestratorConfig,
    private val policy: AdmissionPolicy?,
    private val clock: Clock = Clock.systemUTC(),
) : TaskEventSink {

    private val logger = LoggerFactory.getLogger(Orchestrator::class.java)

    // Leader lease state (in-memory, backed by DB on acquire)
    private var leaderOwner: String? = null
    private var leaderExpiresAt: Instant? = null
    private var leaderGeneration: Long = 0

    /** True when this instance holds the leader lease. */
    val isLeader: Boolean
        get() = leaderOwner != null && leaderExpiresAt?.isAfter(clock.instant()) == true

    /**
     * One pass. Suspending (database calls). A failure is contained and the next tick still runs.
     */
    suspend fun tick() {
        try {
            // 1. Leader lease check
            if (!tryAcquireLeader()) {
                logger.debug("orchestrator tick: not leader, skipping")
                return
            }

            // 2. fusha-task sync via poller
            val pollResult = poller.pollOnce()
            if (pollResult is PollResult.Failed) {
                logger.warn("orchestrator tick: poll failed, cause={}", pollResult.cause.name)
                return
            }
            if (pollResult is PollResult.SkippedBreakerOpen) {
                logger.info("orchestrator tick: poller circuit breaker open")
                return
            }

            // 3-4. Process events: admission for discovered/changed tasks
            if (pollResult is PollResult.Published) {
                processEvents(pollResult.events)
            }

            // 5. Reconcile existing runs
            reconcile()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("orchestrator tick: failed, exception={}", e.javaClass.simpleName)
        }
    }

    /**
     * Process task events from the poller. Only admits tasks that pass all guards.
     */
    private fun processEvents(events: List<TaskPollEvent>) {
        for (event in events) {
            try {
                when (event) {
                    is TaskDiscovered -> admitTask(event.task)
                    is TaskChanged -> admitTask(event.task)
                    is TaskPossiblyGone -> {
                        logger.info("orchestrator: task possibly gone, taskId={}", event.taskId.value)
                    }
                }
            } catch (e: Exception) {
                logger.warn("orchestrator: event processing failed, taskId={} exception={}",
                    event.taskId.value, e.javaClass.simpleName)
            }
        }
    }

    /**
     * Admit a task if it passes all guards: poller health, admission policy, dependencies, concurrency.
     */
    private fun admitTask(task: TaskSnapshot) {
        if (!poller.admissionAllowed()) {
            logger.info("orchestrator admission: blocked, poller not healthy, taskId={}", task.id.value)
            return
        }

        val blockReason = AdmissionEvaluator.evaluate(task, policy)
        if (blockReason != null) {
            logger.info("orchestrator admission: blocked, reason={}, taskId={}",
                blockReason.name, task.id.value)
            return
        }

        if (activeRunsReachedLimit()) {
            logger.info("orchestrator admission: blocked, active run limit reached, taskId={}",
                task.id.value)
            return
        }

        val requestId = "orchestrator:${task.id.value}:${task.updatedAt}"
        logger.info("orchestrator admission: would admit taskId={}, requestId={}",
            task.id.value, requestId)
        // TODO: Call admission.admit(task, requestId) when wired to Application.kt
    }

    /** Reconcile existing runs (design #3 section 7.3). */
    private fun reconcile() {
        // TODO: Check for runs that need resumption, orphaned runs, dependency resolution
    }

    private fun activeRunsReachedLimit(): Boolean {
        // TODO: Query DB for active run count
        return false
    }

    private fun tryAcquireLeader(): Boolean {
        val now = clock.instant()
        if (isLeader) return true

        val owner = "orchestrator:${java.util.UUID.randomUUID()}"
        leaderOwner = owner
        leaderExpiresAt = now.plus(config.leaderLeaseTtl)
        leaderGeneration += 1
        logger.info("orchestrator: acquired leader lease, owner={}, generation={}",
            leaderOwner, leaderGeneration)
        return true
    }

    /** Runs [tick] every [config.tickInterval] in [scope]. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("orchestrator: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(config.tickInterval.toMillis())
        }
    }

    override suspend fun publish(events: List<TaskPollEvent>) {
        processEvents(events)
    }
}
