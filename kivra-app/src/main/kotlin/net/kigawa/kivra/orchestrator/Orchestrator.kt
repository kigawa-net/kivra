package net.kigawa.kivra.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.AdmissionEvaluator
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.AdmissionResult
import net.kigawa.kivra.core.RunAdmission
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.fusha.poller.PollResult
import net.kigawa.kivra.fusha.poller.TaskChanged
import net.kigawa.kivra.fusha.poller.TaskDiscovered
import net.kigawa.kivra.fusha.poller.TaskEventSink
import net.kigawa.kivra.fusha.poller.TaskPollEvent
import net.kigawa.kivra.fusha.poller.TaskPoller
import net.kigawa.kivra.fusha.poller.TaskPossiblyGone
import net.kigawa.kivra.agent.AgentManager
import net.kigawa.kivra.agent.AgentProfile
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.LeaderLease
import net.kigawa.kivra.db.LeaderLeaseRepository
import net.kigawa.kivra.fusha.outbox.FushaTaskOutboxPublisher
import net.kigawa.kivra.planner.PlannerPipeline
import net.kigawa.kivra.recl.ReclContextSearch
import net.kigawa.kivra.recl.ReclSummaryWriter
import net.kigawa.kivra.reviewer.ReviewerPipeline
import net.kigawa.kivra.scheduler.ScheduleTrigger
import net.kigawa.kivra.scheduler.ScheduleTriggerKind
import net.kigawa.kivra.scheduler.ScheduleTriggerSink
import org.slf4j.LoggerFactory
import java.time.Clock

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
    private val leaderLeaseRepo: LeaderLeaseRepository,
    private val runRepo: DevelopmentRunRepository,
    private val config: OrchestratorConfig,
    private val policy: AdmissionPolicy?,
    private val outboxPublisher: FushaTaskOutboxPublisher?,
    private val agentManager: AgentManager?,
    private val reviewerPipeline: ReviewerPipeline?,
    private val plannerPipeline: PlannerPipeline?,
    private val reclContextSearch: ReclContextSearch?,
    private val reclSummaryWriter: ReclSummaryWriter?,
    private val orchestratorMetrics: OrchestratorMetrics?,
    private val killSwitch: KillSwitch?,
    private val clock: Clock = Clock.systemUTC(),
) : TaskEventSink, ScheduleTriggerSink {

    private val logger = LoggerFactory.getLogger(Orchestrator::class.java)

    // Leader lease state (cached in-memory, backed by DB)
    private var leaderOwner: String? = null
    private var currentLease: LeaderLease? = null

    /** True when this instance holds the leader lease. */
    val isLeader: Boolean
        get() = currentLease != null && currentLease!!.expiresAt.isAfter(net.kigawa.kivra.db.utcNow(clock))

    /**
     * One pass. Suspending (database calls). A failure is contained and the next tick still runs.
     */
    suspend fun tick() {
        // Kill Switch check (design #3 section 9, Phase C6)
        if (killSwitch?.shouldBlockAdmission == true) {
            orchestratorMetrics?.tickSkippedKillSwitch()
            logger.info("orchestrator tick: kill switch is ON ({}), skipping", killSwitch.currentState)
            return
        }
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

            // 6. Publish outbox events to fusha-task (design #3 section 7.2, A6)
            outboxPublisher?.let {
                try {
                    val published = it.publishDue()
                    if (published > 0) {
                        logger.info("orchestrator tick: published {} outbox events to fusha-task", published)
                        orchestratorMetrics?.outboxPublished()
                    }
                } catch (e: Exception) {
                    logger.warn("orchestrator tick: outbox publish failed, exception={}", e.javaClass.simpleName)
                    orchestratorMetrics?.outboxFailed()
                }
            }

            // Record tick completion metric (design #3 section 12, Phase C6)
            orchestratorMetrics?.tickCompleted()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("orchestrator tick: failed, exception={}", e.javaClass.simpleName)
        }
    }

    /**
     * Process task events from the poller. Only admits tasks that pass all guards.
     */
    private suspend fun processEvents(events: List<TaskPollEvent>) {
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
    private suspend fun admitTask(task: TaskSnapshot) {
        // Kill Switch check for admission (design #3 section 9, Phase C6)
        if (killSwitch?.shouldBlockAdmission == true) {
            logger.info("orchestrator admission: blocked by kill switch ({})", killSwitch.currentState)
            orchestratorMetrics?.admissionDenied("kill_switch")
            return
        }

        if (!poller.admissionAllowed()) {
            logger.info("orchestrator admission: blocked, poller not healthy, taskId={}", task.id.value)
            orchestratorMetrics?.admissionDenied("poller_unhealthy")
            return
        }

        val blockReason = AdmissionEvaluator.evaluate(task, policy)
        if (blockReason != null) {
            logger.info("orchestrator admission: blocked, reason={}, taskId={}",
                blockReason.name, task.id.value)
            orchestratorMetrics?.admissionDenied(blockReason.name)
            return
        }

        if (activeRunsReachedLimit()) {
            logger.info("orchestrator admission: blocked, active run limit reached, taskId={}",
                task.id.value)
            orchestratorMetrics?.admissionDenied("concurrency_limit")
            return
        }

        val requestId = "orchestrator:${task.id.value}:${task.updatedAt}"
        logger.info("orchestrator admission: admitting taskId={}, requestId={}",
            task.id.value, requestId)
        val result = admission.admit(task, requestId)
        when (result) {
            is AdmissionResult.Accepted -> {
                logger.info("orchestrator admission: accepted, runId={}, taskId={}",
                    result.runId.value, task.id.value)
                orchestratorMetrics?.admissionAccepted()
                orchestratorMetrics?.runStateChanged(result.runId.value, "running")
                // Dispatch the newly admitted run to the Agent Manager (design #3 section 5.1)
                agentManager?.let {
                    try {
                        val outcome = it.run(result.runId, profile)
                        logger.info("orchestrator: agentManager.run outcome={}, runId={}",
                            outcome::class.simpleName, result.runId.value)
                    } catch (e: Exception) {
                        logger.warn("orchestrator: agentManager.run failed, runId={} exception={}",
                            result.runId.value, e.javaClass.simpleName)
                    }
                }
            }
            is AdmissionResult.AlreadyActive -> {
                logger.info("orchestrator admission: already active, runId={}, taskId={}",
                    result.runId.value, task.id.value)
                orchestratorMetrics?.admissionDenied("already_active")
            }
            is AdmissionResult.AlreadyTerminal -> {
                logger.info("orchestrator admission: already terminal, runId={}, taskId={}",
                    result.runId.value, task.id.value)
                orchestratorMetrics?.admissionDenied("already_terminal")
            }
            is AdmissionResult.Blocked -> {
                logger.info("orchestrator admission: blocked, reason={}, taskId={}",
                    result.reason.name, task.id.value)
                orchestratorMetrics?.admissionDenied(result.reason.name)
            }
        }
    }

    /** Reconcile existing runs (design #3 section 7.3). */
    private suspend fun reconcile() {
        // Check for orphaned runs: active runs whose task no longer exists in fusha-task.
        // For now, log a warning if there are runs in CREATED state that have been stuck.
        val activeRuns = runRepo.countActiveRuns()
        if (activeRuns > config.maxConcurrentRuns) {
            logger.warn("orchestrator reconcile: {} active runs exceeds limit {}", 
                activeRuns, config.maxConcurrentRuns)
        }

        // Check for runs that should enter review phase (design #3 section 4.3, C2)
        reviewerPipeline?.let { pipeline ->
            try {
                val activeRunIds = runRepo.findActiveRuns().map { it.runId.value }
                for (runId in activeRunIds) {
                    if (pipeline.shouldEnterReview(net.kigawa.kivra.core.RunId(runId))) {
                        logger.info("orchestrator reconcile: runId={} should enter review phase", runId)
                    }
                }
            } catch (e: Exception) {
                logger.warn("orchestrator reconcile: review check failed, exception={}", e.javaClass.simpleName)
            }
        }

        // Check for tasks that should enter planning phase (design #3 section 5.4, C3)
        plannerPipeline?.let { pipeline ->
            try {
                val activeRunIds = runRepo.findActiveRuns().map { it.taskId }
                for (taskId in activeRunIds) {
                    if (pipeline.shouldEnterPlanning(taskId)) {
                        logger.info("orchestrator reconcile: taskId={} should enter planning phase", taskId)
                    }
                }
                // Create child tasks for approved plans
                for (taskId in activeRunIds) {
                    val approvedPlan = pipeline.getApprovedPlan(taskId)
                    if (approvedPlan != null) {
                        val created = pipeline.createChildTasks(taskId)
                        if (created.isNotEmpty()) {
                            logger.info("orchestrator reconcile: taskId={}, created {} child tasks", taskId, created.size)
                        }
                    }
                }
                for (taskId in activeRunIds) {
                    if (pipeline.areAllChildrenComplete(taskId)) {
                        logger.info("orchestrator reconcile: taskId={} all children complete, ready for join", taskId)
                    }
                }
            } catch (e: Exception) {
                logger.warn("orchestrator reconcile: planner check failed, exception={}", e.javaClass.simpleName)
            }
        }
    }

    private fun activeRunsReachedLimit(): Boolean {
        val count = runRepo.countActiveRuns()
        return count >= config.maxConcurrentRuns
    }

    private fun tryAcquireLeader(): Boolean {
        if (isLeader) {
            // Renew lease if approaching expiry
            val lease = currentLease ?: return false
            val now = net.kigawa.kivra.db.utcNow(clock)
            val remaining = java.time.Duration.between(now, lease.expiresAt)
            if (remaining.compareTo(config.leaderLeaseRenewInterval) > 0) {
                return true
            }
            // Renew
            if (leaderLeaseRepo.renew(leaderOwner!!)) {
                currentLease = leaderLeaseRepo.getCurrent()
                logger.info("orchestrator: renewed leader lease, owner={}", leaderOwner)
                return currentLease != null
            } else {
                logger.warn("orchestrator: lease renewal failed, lease lost")
                currentLease = null
                leaderOwner = null
                orchestratorMetrics?.leaderLeaseLost()
                return false
            }
        }

        val owner = "orchestrator:${java.util.UUID.randomUUID()}"
        val lease = leaderLeaseRepo.tryAcquire(owner)
        if (lease != null) {
            leaderOwner = owner
            currentLease = lease
            logger.info("orchestrator: acquired leader lease, owner={}, generation={}",
                leaderOwner, lease.generation)
            orchestratorMetrics?.leaderLeaseAcquired()
            return true
        }
        logger.debug("orchestrator: another instance holds the leader lease")
        return false
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

    /**
     * Handle external wake-up from ai-scheduler (design #3 section 11, Phase C5).
     *
     * ai-scheduler delivers at-least-once; the caller (ScheduleClient) is responsible for
     * deduplication using TriggerDedupStore. This method simply runs [tick] — the tick itself
     * is fully idempotent.
     *
     * - [TICK]: Run a full orchestrator tick (poll, admit, reconcile).
     * - [DEADLINE_RESUME]: Run a tick to pick up expired approvals.
     * - [TIMED_TASK]: Run a tick for time-specified task activation.
     */
    override suspend fun onTrigger(trigger: ScheduleTrigger) {
        logger.info("orchestrator trigger: kind={}, job={}, scheduledAt={}",
            trigger.kind.name, trigger.job.id, trigger.scheduledAt)
        tick()
    }
}
