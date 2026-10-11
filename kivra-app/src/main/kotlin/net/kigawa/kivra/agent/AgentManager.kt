package net.kigawa.kivra.agent

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.delay
import net.kigawa.kivra.core.AgentState
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.ExecutionSessionState
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import net.kigawa.kivra.db.AgentRecord
import net.kigawa.kivra.db.AgentRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.OperationJournalRepository
import net.kigawa.kivra.db.SessionRecord
import net.kigawa.kivra.exkes.Execution
import net.kigawa.kivra.exkes.ExecutionId
import net.kigawa.kivra.exkes.ExecutionPort
import net.kigawa.kivra.exkes.ExecutionSpec
import net.kigawa.kivra.exkes.ExecutionState
import net.kigawa.kivra.exkes.KivraNaming
import net.kigawa.kivra.exkes.PortResult
import net.kigawa.kivra.exkes.Runtime
import net.kigawa.kivra.exkes.RuntimeId
import net.kigawa.kivra.exkes.RuntimeSpec
import net.kigawa.kivra.exkes.RuntimeState
import net.kigawa.kivra.exkes.Workspace
import net.kigawa.kivra.exkes.WorkspaceId
import net.kigawa.kivra.exkes.WorkspaceSpec
import net.kigawa.kivra.exkes.WorkspaceState
import java.util.concurrent.Semaphore
import kotlin.time.TimeSource

/** Result of one [AgentManager.run] pass. Everything except Completed / Failed / Cancelled leaves the run to be resumed. */
sealed interface AgentRunOutcome {
    /** The Agent finished; the Run moved on to [runState] (TESTING). [cleanupPending]: stopping the Runtime is not confirmed. */
    data class Completed(val runState: DevelopmentRunState, val cleanupPending: Boolean) : AgentRunOutcome
    data class Failed(val reason: String, val willRetry: Boolean) : AgentRunOutcome
    data object Cancelled : AgentRunOutcome

    /** Cancel requested but an external side effect is still unresolved: no terminal state is forced (design #46). */
    data class CancelPending(val unresolvedOpIds: List<String>) : AgentRunOutcome

    /** An administrator Hold stopped this worker; nothing further was sent. */
    data class Halted(val reason: String) : AgentRunOutcome

    /** The lease was lost (expired / superseded). This worker stopped and wrote nothing more. */
    data class LeaseLost(val reason: String) : AgentRunOutcome
    data class WaitingDependency(val reason: String) : AgentRunOutcome
    data class ReconciliationRequired(val opId: String, val opType: String) : AgentRunOutcome
    data object NotAcquired : AgentRunOutcome
    data object Busy : AgentRunOutcome
    data class NotRunnable(val state: DevelopmentRunState) : AgentRunOutcome
}

/**
 * Minimal Agent Manager (M2): one Agent / one Workspace per Run, sequential (design #3 section 6, #46).
 *
 * Holds the Run Lease and drives Workspace -> Runtime -> Execution through [ExecutionPort]. Every external side effect
 * is journaled before it is sent ([OperationExecutor]); each step is idempotent through the journal, so after a crash or a
 * worker change [run] simply resumes: CONFIRMED steps are skipped, IN_FLIGHT / UNKNOWN ones are looked up and never resent
 * blindly. The Run state machine is the single source of truth (ADR 0001); this class only moves it
 * PREPARING -> DEVELOPING -> TESTING (or RETRY_WAITING / FAILED / CANCELLED) and keeps Agent / ExecutionSession in step.
 *
 * Fail-closed: [renew][LeaseStore.renew] before every external call and every poll, and every DB write is fenced. Lease loss or
 * Hold stops everything with no further call; Cancel runs a wind-down (stop Runtime, delete Workspace) and only then CANCELLED.
 * Not in scope: orphan reclamation (#31), Orchestrator / multi-agent pipelines (#36, M7).
 */
class AgentManager(
    private val workerId: String,
    private val port: ExecutionPort,
    private val leases: LeaseStore,
    private val runs: DevelopmentRunRepository,
    private val agents: AgentRepository,
    private val journal: OperationJournalRepository,
    private val config: AgentManagerConfig = AgentManagerConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val slots = Semaphore(config.maxConcurrentRuns)
    private val ops = OperationExecutor(journal, config.maxOperationAttempts, config.transientBackoff, clock)
    private val actor = "agent-manager:$workerId"

    /** Startup reconciliation (minimal): resumes every run that no live worker owns. Returns the outcomes by run. */
    suspend fun resumeAll(profile: AgentProfile): Map<RunId, AgentRunOutcome> =
        agents.resumableRuns(config.leaseGrace.seconds).associateWith { run(it, profile) }

    suspend fun run(runId: RunId, profile: AgentProfile): AgentRunOutcome {
        if (!slots.tryAcquire()) return AgentRunOutcome.Busy
        try {
            val lease = leases.acquire(runId, workerId, config.leaseTtl, config.leaseGrace) ?: return AgentRunOutcome.NotAcquired
            val ctx = Ctx(lease, profile)
            try {
                return try {
                    execute(ctx)
                } catch (e: LeaseLostException) {
                    onHalt(ctx, e)
                }
            } finally {
                runCatching { leases.release(lease) } // false when the lease is already gone: fine
            }
        } finally {
            slots.release()
        }
    }

    // ------------------------------------------------------------------ context

    private class Ctx(val lease: Lease, val profile: AgentProfile) {
        val runId: RunId get() = lease.runId
        lateinit var agent: AgentRecord
        var session: SessionRecord? = null
        var attempt: Int = 1
        var allowCancel: Boolean = false
        val hasAgent: Boolean get() = this::agent.isInitialized
    }

    private fun reload(ctx: Ctx) {
        val all = agents.agentsOf(ctx.runId)
        val latest = all.lastOrNull() ?: return
        ctx.agent = latest
        ctx.attempt = all.size
        ctx.session = agents.sessionOf(latest.agentId)
    }

    // ------------------------------------------------------------------ fencing

    /** Renews the lease; false from the store means expired, superseded, Hold or Cancel: classify and stop. */
    private fun guard(ctx: Ctx) {
        if (ctx.allowCancel) return // wind-down: every write is still fenced by requireLease (Hold / lease loss stop it)
        if (!leases.renew(ctx.lease, config.leaseTtl)) throw classify(ctx)
    }

    private fun classify(ctx: Ctx): LeaseLostException {
        val run = runs.get(ctx.runId)
        return when {
            run == null -> LeaseLostException("run ${ctx.runId} vanished")
            run.holdReason != null -> RunHaltedException("run ${ctx.runId} is on hold (${run.holdReason})")
            run.cancelRequested -> RunHaltedException("cancellation was requested for run ${ctx.runId}")
            else -> LeaseLostException("lease lost for run ${ctx.runId}")
        }
    }

    private suspend fun onHalt(ctx: Ctx, e: LeaseLostException): AgentRunOutcome {
        val run = runs.get(ctx.runId) ?: return AgentRunOutcome.LeaseLost(e.message ?: "run not found")
        if (run.holdReason != null) return AgentRunOutcome.Halted("hold: ${run.holdReason}")
        if (e is RunHaltedException && run.cancelRequested && run.state.canTransitionTo(DevelopmentRunState.CANCELLED)) {
            return try {
                windDownCancel(ctx)
            } catch (e2: LeaseLostException) {
                if (runs.get(ctx.runId)?.holdReason != null) AgentRunOutcome.Halted("hold during cancel wind-down")
                else AgentRunOutcome.LeaseLost(e2.message ?: "lease lost during cancel wind-down")
            }
        }
        return if (e is RunHaltedException) AgentRunOutcome.Halted(e.message ?: "halted") else AgentRunOutcome.LeaseLost(e.message ?: "lease lost")
    }

    // ------------------------------------------------------------------ main flow

    private suspend fun execute(ctx: Ctx): AgentRunOutcome {
        val run = runs.get(ctx.runId) ?: return AgentRunOutcome.NotRunnable(DevelopmentRunState.FAILED)
        if (run.state !in RUNNABLE) return AgentRunOutcome.NotRunnable(run.state)
        guard(ctx) // also stops at once on a Hold / pending Cancel
        reload(ctx)

        // Crash recovery: the previous worker may have died between the Agent and the Run state change.
        if (ctx.hasAgent) {
            when {
                ctx.agent.state == AgentState.COMPLETED && run.state == DevelopmentRunState.DEVELOPING -> return finishCompleted(ctx)
                ctx.agent.state == AgentState.FAILED && run.state in setOf(DevelopmentRunState.PREPARING, DevelopmentRunState.DEVELOPING) ->
                    return applyFailurePolicy(ctx, "previous attempt failed")
            }
        }
        val open = if (ctx.hasAgent && !ctx.agent.state.isTerminal) ctx.agent else null
        if (open == null) {
            if (agents.agentsOf(ctx.runId).size >= config.maxAgentAttempts) {
                moveRun(ctx, DevelopmentRunState.FAILED, "MAX_ATTEMPTS")
                return AgentRunOutcome.Failed("max agent attempts (${config.maxAgentAttempts}) used", willRetry = false)
            }
            ctx.agent = agents.createAgent(ctx.lease, ctx.profile.role)
            ctx.attempt = agents.agentsOf(ctx.runId).size
            ctx.session = null
        }
        if (ctx.session == null) ctx.session = agents.createSession(ctx.lease, ctx.agent.agentId)
        setAgent(ctx, AgentState.STARTING)
        if (run.state == DevelopmentRunState.RETRY_WAITING) moveRun(ctx, DevelopmentRunState.DEVELOPING, "RETRY_ATTEMPT_${ctx.attempt}")

        // ---- Workspace
        val wsName = KivraNaming.workspaceName(ctx.runId.value)
        val wsOutcome = ops.perform(ctx.lease, workspaceOp(ctx), ctx.allowCancel, true) { guard(ctx) }
        stopFor(ctx, "workspace", wsOutcome)?.let { return it }
        val wsId = WorkspaceId((wsOutcome as OpOutcome.Done).externalRef ?: return failAttempt(ctx, "workspace $wsName has no external id"))
        advance(ctx, ExecutionSessionState.PROVISIONING, workspaceId = wsId.value)
        when (val p = poll(ctx, config.workspaceReadyTimeout, { port.getWorkspace(wsId) }, { it.state == WorkspaceState.READY }, ::badWorkspace)) {
            is Polled.Reached -> Unit
            is Polled.TimedOut -> return failAttempt(ctx, "WORKSPACE_TIMEOUT")
            is Polled.Failed -> return failAttempt(ctx, "WORKSPACE_${p.message}")
        }

        // ---- Runtime
        val rtOutcome = ops.perform(ctx.lease, runtimeOp(ctx, wsId), ctx.allowCancel, true) { guard(ctx) }
        stopFor(ctx, "runtime", rtOutcome)?.let { return it }
        val rtId = RuntimeId(wsId, (rtOutcome as OpOutcome.Done).externalRef ?: return failAttempt(ctx, "runtime has no external id"))
        advance(ctx, null, runtimeId = rtId.value)
        when (val p = poll(ctx, config.runtimeReadyTimeout, { port.getRuntime(rtId) }, { it.state == RuntimeState.RUNNING }, ::badRuntime)) {
            is Polled.Reached -> Unit
            is Polled.TimedOut -> return failAttempt(ctx, "RUNTIME_TIMEOUT")
            is Polled.Failed -> return failAttempt(ctx, "RUNTIME_${p.message}")
        }
        advance(ctx, ExecutionSessionState.READY)
        ensureRun(ctx, DevelopmentRunState.DEVELOPING, "AGENT_${ctx.attempt}_STARTING")

        // ---- Execution
        val exOutcome = ops.perform(ctx.lease, executionOp(ctx, rtId), ctx.allowCancel, true) { guard(ctx) }
        stopFor(ctx, "execution", exOutcome)?.let { return it }
        val exId = ExecutionId(rtId, (exOutcome as OpOutcome.Done).externalRef ?: return failAttempt(ctx, "execution has no external id"))
        advance(ctx, ExecutionSessionState.EXECUTING, executionId = exId.value)
        setAgent(ctx, AgentState.RUNNING)

        val timeout = ctx.profile.timeout ?: config.executionTimeout
        return when (val p = poll(ctx, timeout, { port.getExecution(exId) }, { it.state.isFinal }, { null })) {
            is Polled.Reached -> {
                advance(ctx, null, observedStatus = p.value.state.name)
                if (p.value.state == ExecutionState.SUCCEEDED) finishCompleted(ctx)
                else failAttempt(ctx, "EXECUTION_${p.value.state.name}${p.value.exitCode?.let { " exit=$it" } ?: ""}")
            }
            is Polled.TimedOut -> failAttempt(ctx, "EXECUTION_TIMEOUT")
            is Polled.Failed -> failAttempt(ctx, "EXECUTION_${p.message}")
        }
    }

    /** Maps a non-Done step outcome to the run outcome (null = continue). */
    private suspend fun stopFor(ctx: Ctx, what: String, o: OpOutcome): AgentRunOutcome? = when (o) {
        is OpOutcome.Done -> null
        is OpOutcome.Failed -> failAttempt(ctx, "${what.uppercase()}_FAILED: ${o.message}")
        is OpOutcome.WaitingDependency -> AgentRunOutcome.WaitingDependency(o.message)
        is OpOutcome.ReconciliationRequired -> {
            // Agent / Run stay as they are: an unresolved side effect never forces a terminal state.
            ctx.session?.let { if (it.state == ExecutionSessionState.REQUESTED) advance(ctx, ExecutionSessionState.UNKNOWN) }
            AgentRunOutcome.ReconciliationRequired(o.opId, o.opType)
        }
        OpOutcome.NotDispatched -> AgentRunOutcome.WaitingDependency("$what was not dispatched")
    }

    // ------------------------------------------------------------------ operations

    private fun workspaceOp(ctx: Ctx): Op<Workspace> {
        val name = KivraNaming.workspaceName(ctx.runId.value)
        return Op(
            opType = "EXKES_CREATE_WORKSPACE",
            key = "run:${ctx.runId}:workspace",
            stableRef = name,
            requestHash = sha256Hex("workspace|$name"),
            call = { port.createWorkspace(WorkspaceSpec(name, KivraNaming.runLabels(ctx.runId.value))) },
            refOf = { it.id.value },
            observe = {
                when (val r = port.findWorkspaceByName(name)) {
                    is PortResult.Ok -> r.value?.let { Observed.Exists(it.id.value) } ?: Observed.Absent(replaySafe = true) // create = look up, then create
                    else -> Observed.Undetermined(r.toString())
                }
            },
        )
    }

    private fun runtimeLabels(ctx: Ctx) = KivraNaming.runLabels(ctx.runId.value) + ("kivra.attempt" to ctx.attempt.toString())

    private fun runtimeOp(ctx: Ctx, ws: WorkspaceId): Op<Runtime> {
        val labels = runtimeLabels(ctx)
        val profile = ctx.profile
        return Op(
            opType = "EXKES_CREATE_RUNTIME",
            key = "run:${ctx.runId}:a${ctx.attempt}:runtime",
            stableRef = "workspace/${ws.value}/labels/${labels.toSortedMap()}",
            requestHash = sha256Hex("runtime|${ws.value}|${profile.ref}|${profile.templateId}|${labels.toSortedMap()}"),
            call = { port.createRuntime(ws, RuntimeSpec(profile.templateId, labels)) },
            refOf = { it.id.value },
            observe = {
                when (val r = port.findRuntimesByLabel(ws, labels)) {
                    is PortResult.Ok -> when (r.value.size) {
                        0 -> Observed.Absent(replaySafe = true) // create = look up by label, then create
                        1 -> Observed.Exists(r.value.single().id.value)
                        else -> Observed.Undetermined("${r.value.size} runtimes carry labels $labels")
                    }
                    else -> Observed.Undetermined(r.toString())
                }
            },
        )
    }

    private fun executionOp(ctx: Ctx, rt: RuntimeId): Op<Execution> {
        val profile = ctx.profile
        val spec = ExecutionSpec(profile.type, profile.commandJson, profile.agentConfigJson, singleExecutionGuard = true)
        return Op(
            opType = "EXKES_START_EXECUTION",
            key = "run:${ctx.runId}:a${ctx.attempt}:execution",
            stableRef = "runtime/${rt.value}",
            requestHash = sha256Hex("execution|${rt.value}|${profile.ref}|${profile.type}|${profile.commandJson}|${profile.agentConfigJson}"),
            call = { port.startExecution(rt, spec) },
            refOf = { it.id.value },
            observe = {
                // exkes cannot tag or deduplicate an Execution. The Runtime is run+attempt scoped and single-execution, so one
                // Execution on it is ours. None found does NOT prove the POST is dead (it may still land): never resend.
                when (val r = port.listExecutions(rt)) {
                    is PortResult.Ok -> when (r.value.size) {
                        0 -> Observed.Absent(replaySafe = false)
                        1 -> Observed.Exists(r.value.single().id.value)
                        else -> Observed.Undetermined("${r.value.size} executions on runtime ${rt.value}")
                    }
                    else -> Observed.Undetermined(r.toString())
                }
            },
        )
    }

    private fun stopRuntimeOp(ctx: Ctx, rt: RuntimeId) = Op(
        opType = "EXKES_STOP_RUNTIME",
        key = "run:${ctx.runId}:a${ctx.attempt}:stop-runtime",
        stableRef = "runtime/${rt.value}",
        requestHash = sha256Hex("stop-runtime|${rt.value}"),
        call = { port.stopRuntime(rt) },
        refOf = { it.id.value },
        notFoundIsSuccess = true,
        observe = {
            when (val r = port.getRuntime(rt)) {
                PortResult.NotFound -> Observed.Exists(rt.value)
                is PortResult.Ok ->
                    if (r.value.state in setOf(RuntimeState.STOPPING, RuntimeState.STOPPED, RuntimeState.DELETING, RuntimeState.DELETED, RuntimeState.EXPIRED, RuntimeState.FAILED)) Observed.Exists(rt.value)
                    else Observed.Absent(replaySafe = true) // stopping twice is harmless
                else -> Observed.Undetermined(r.toString())
            }
        },
    )

    private fun deleteWorkspaceOp(ctx: Ctx, ws: WorkspaceId) = Op(
        opType = "EXKES_DELETE_WORKSPACE",
        key = "run:${ctx.runId}:delete-workspace",
        stableRef = KivraNaming.workspaceName(ctx.runId.value),
        requestHash = sha256Hex("delete-workspace|${ws.value}"),
        call = { port.deleteWorkspace(ws) },
        refOf = { it.id.value },
        notFoundIsSuccess = true,
        observe = {
            when (val r = port.getWorkspace(ws)) {
                PortResult.NotFound -> Observed.Exists(ws.value)
                is PortResult.Ok ->
                    if (r.value.state == WorkspaceState.DELETING || r.value.state == WorkspaceState.DELETED) Observed.Exists(ws.value)
                    else Observed.Absent(replaySafe = true)
                else -> Observed.Undetermined(r.toString())
            }
        },
    )

    // ------------------------------------------------------------------ polling

    private sealed interface Polled<out T> {
        data class Reached<T>(val value: T) : Polled<T>
        data object TimedOut : Polled<Nothing>
        data class Failed(val message: String) : Polled<Nothing>
    }

    /** Polls with a lease renewal per round. GET failures that may be transient (Unknown / Transient) are ridden out until the timeout. */
    private suspend fun <T> poll(
        ctx: Ctx,
        timeout: Duration,
        get: suspend () -> PortResult<T>,
        done: (T) -> Boolean,
        bad: (T) -> String?,
    ): Polled<T> {
        val mark = TimeSource.Monotonic.markNow()
        while (true) {
            guard(ctx)
            when (val r = get()) {
                is PortResult.Ok -> {
                    bad(r.value)?.let { return Polled.Failed(it) }
                    if (done(r.value)) return Polled.Reached(r.value)
                }
                is PortResult.Transient, is PortResult.Unknown -> Unit
                else -> return Polled.Failed("POLL_${r::class.simpleName}")
            }
            if (mark.elapsedNow().inWholeMilliseconds >= timeout.toMillis()) return Polled.TimedOut
            delay(config.pollInterval.toMillis().coerceAtLeast(1))
        }
    }

    private fun badWorkspace(w: Workspace): String? =
        if (w.state in setOf(WorkspaceState.ERROR, WorkspaceState.DELETING, WorkspaceState.DELETED, WorkspaceState.ARCHIVED)) "STATE_${w.state}" else null

    private fun badRuntime(r: Runtime): String? =
        if (r.state in setOf(RuntimeState.FAILED, RuntimeState.STOPPING, RuntimeState.STOPPED, RuntimeState.DELETING, RuntimeState.DELETED, RuntimeState.EXPIRED)) "STATE_${r.state}" else null

    // ------------------------------------------------------------------ completion, failure, cancel

    private suspend fun finishCompleted(ctx: Ctx): AgentRunOutcome {
        if (ctx.agent.state == AgentState.RUNNING) setAgent(ctx, AgentState.COMPLETED)
        val clean = teardown(ctx, deleteWorkspace = false) // the Workspace stays: the next stage (test / review) works on it
        if (clean) releaseSession(ctx)
        ensureRun(ctx, DevelopmentRunState.TESTING, "AGENT_${ctx.attempt}_COMPLETED")
        return AgentRunOutcome.Completed(DevelopmentRunState.TESTING, cleanupPending = !clean)
    }

    private suspend fun failAttempt(ctx: Ctx, reason: String): AgentRunOutcome {
        when (ctx.agent.state) {
            AgentState.CREATED -> { setAgent(ctx, AgentState.STARTING); setAgent(ctx, AgentState.FAILED) }
            AgentState.STARTING, AgentState.RUNNING -> setAgent(ctx, AgentState.FAILED)
            else -> Unit
        }
        val clean = teardown(ctx, config.deleteWorkspaceOnFailure)
        if (clean) releaseSession(ctx)
        return applyFailurePolicy(ctx, reason)
    }

    private fun applyFailurePolicy(ctx: Ctx, reason: String): AgentRunOutcome {
        val run = runs.get(ctx.runId)!!
        val failed = agents.agentsOf(ctx.runId).count { it.state == AgentState.FAILED }
        val retry = run.state == DevelopmentRunState.DEVELOPING && failed < config.maxAgentAttempts
        moveRun(ctx, if (retry) DevelopmentRunState.RETRY_WAITING else DevelopmentRunState.FAILED, reason.take(60))
        return AgentRunOutcome.Failed(reason, willRetry = retry)
    }

    /**
     * Stops the Runtime of the current attempt (and deletes the Workspace when asked), journaled like everything else.
     * Returns whether everything it needed to do is confirmed. A teardown that is UNKNOWN is left for reconciliation / the Reaper (#31).
     */
    private suspend fun teardown(ctx: Ctx, deleteWorkspace: Boolean): Boolean {
        val s = ctx.session ?: return true
        var clean = true
        val ws = s.workspaceId?.let(::WorkspaceId)
        val rt = s.runtimeId?.let { ws?.let { w -> RuntimeId(w, it) } }
        if (rt != null) clean = ops.perform(ctx.lease, stopRuntimeOp(ctx, rt), ctx.allowCancel, true) { guard(ctx) } is OpOutcome.Done && clean
        if (deleteWorkspace && ws != null) clean = ops.perform(ctx.lease, deleteWorkspaceOp(ctx, ws), ctx.allowCancel, true) { guard(ctx) } is OpOutcome.Done && clean
        return clean
    }

    private fun releaseSession(ctx: Ctx) {
        var s = ctx.session ?: return
        if (s.state == ExecutionSessionState.PROVISIONING) advance(ctx, ExecutionSessionState.ORPHANED)
        s = ctx.session!!
        if (s.state in setOf(ExecutionSessionState.READY, ExecutionSessionState.EXECUTING, ExecutionSessionState.ORPHANED)) advance(ctx, ExecutionSessionState.RELEASING)
        if (ctx.session!!.state == ExecutionSessionState.RELEASING) advance(ctx, ExecutionSessionState.RELEASED)
    }

    /** Cancel: first resolve what is unresolved (look-up only, never dispatch), then stop / delete, and only then CANCELLED. */
    private suspend fun windDownCancel(ctx: Ctx): AgentRunOutcome {
        ctx.allowCancel = true
        reload(ctx)
        if (ctx.hasAgent) {
            resolvePending(ctx)
            reload(ctx)
            teardown(ctx, config.deleteWorkspaceOnCancel)
        }
        val unresolved = journal.listByRun(ctx.runId).filter {
            it.status in setOf(OperationStatus.IN_FLIGHT, OperationStatus.UNKNOWN, OperationStatus.RECONCILIATION_REQUIRED)
        }
        if (unresolved.isNotEmpty()) return AgentRunOutcome.CancelPending(unresolved.map { it.opId })
        if (ctx.hasAgent) {
            when (ctx.agent.state) {
                AgentState.CREATED -> { setAgent(ctx, AgentState.STARTING); setAgent(ctx, AgentState.FAILED) }
                AgentState.STARTING -> setAgent(ctx, AgentState.FAILED)
                AgentState.SUSPENDED -> { setAgent(ctx, AgentState.RUNNING); setAgent(ctx, AgentState.STOPPING); setAgent(ctx, AgentState.TERMINATED) }
                AgentState.RUNNING -> { setAgent(ctx, AgentState.STOPPING); setAgent(ctx, AgentState.TERMINATED) }
                AgentState.STOPPING -> setAgent(ctx, AgentState.TERMINATED)
                else -> Unit
            }
            releaseSession(ctx)
        }
        moveRun(ctx, DevelopmentRunState.CANCELLED, "CANCEL_REQUESTED")
        return AgentRunOutcome.Cancelled
    }

    /** Looks up (never re-sends) creates whose result was lost, so that their exkes ids are known before the teardown. */
    private suspend fun resolvePending(ctx: Ctx) {
        suspend fun <T> resolve(op: Op<T>): String? {
            val existing = journal.findByKey(ctx.runId, op.opType, op.key) ?: return null
            if (existing.status == OperationStatus.CONFIRMED) return existing.externalRef
            val o = ops.perform(ctx.lease, op, allowCancel = true, allowDispatch = false) { }
            return (o as? OpOutcome.Done)?.externalRef
        }
        val ws = resolve(workspaceOp(ctx))?.also { advance(ctx, null, workspaceId = it) }?.let(::WorkspaceId) ?: return
        val rt = resolve(runtimeOp(ctx, ws))?.also { advance(ctx, null, runtimeId = it) }?.let { RuntimeId(ws, it) } ?: return
        resolve(executionOp(ctx, rt))?.also { advance(ctx, null, executionId = it) }
    }

    // ------------------------------------------------------------------ state helpers

    private fun setAgent(ctx: Ctx, to: AgentState) {
        if (ctx.agent.state == to) return
        ctx.agent = agents.transitionAgent(ctx.lease, ctx.agent.agentId, ctx.agent.version, to, ctx.allowCancel)
    }

    private fun rank(s: ExecutionSessionState) = when (s) {
        ExecutionSessionState.REQUESTED -> 0
        ExecutionSessionState.PROVISIONING -> 1
        ExecutionSessionState.READY -> 2
        ExecutionSessionState.EXECUTING -> 3
        ExecutionSessionState.RELEASING -> 4
        ExecutionSessionState.RELEASED -> 5
        ExecutionSessionState.ORPHANED, ExecutionSessionState.UNKNOWN -> -1
    }

    /** Moves the session forward (never backward, so resuming is idempotent) and records newly learned exkes references. */
    private fun advance(
        ctx: Ctx,
        to: ExecutionSessionState?,
        workspaceId: String? = null,
        runtimeId: String? = null,
        executionId: String? = null,
        observedStatus: String? = null,
    ) {
        val s = ctx.session ?: return
        val target = to?.takeIf { it == ExecutionSessionState.ORPHANED || it == ExecutionSessionState.UNKNOWN || rank(it) > rank(s.state) }?.takeIf { it != s.state }
        val ws = workspaceId?.takeIf { it != s.workspaceId }
        val rt = runtimeId?.takeIf { it != s.runtimeId }
        val ex = executionId?.takeIf { it != s.executionId }
        val ob = observedStatus?.takeIf { it != s.observedStatus }
        if (target == null && ws == null && rt == null && ex == null && ob == null) return
        ctx.session = agents.updateSession(ctx.lease, s.sessionId, s.version, target, ws, rt, ex, ob, ctx.allowCancel)
    }

    private fun ensureRun(ctx: Ctx, to: DevelopmentRunState, reason: String) {
        if (runs.get(ctx.runId)!!.state != to) moveRun(ctx, to, reason)
    }

    private fun moveRun(ctx: Ctx, to: DevelopmentRunState, reason: String) {
        repeat(2) { n ->
            val current = runs.get(ctx.runId)!!
            try {
                runs.transition(ctx.lease, current.version, to, actor, reason)
                return
            } catch (e: VersionConflictException) {
                if (n == 1) throw e
            }
        }
    }

    private companion object {
        val RUNNABLE = setOf(DevelopmentRunState.PREPARING, DevelopmentRunState.DEVELOPING, DevelopmentRunState.RETRY_WAITING)
    }
}
