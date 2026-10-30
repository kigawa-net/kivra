package net.kigawa.kivra.orchestrator

import net.kigawa.kivra.observability.MetricsRegistry
import net.kigawa.kivra.observability.MetricCatalog

/**
 * Wraps MetricsRegistry calls for the Orchestrator (design #3 section 12, Phase C6).
 *
 * Records tick count, admission outcomes, run state transitions, and leader lease events.
 * All methods are fire-and-forget: a metrics failure must never interrupt the Orchestrator loop.
 */
class OrchestratorMetrics(
    private val registry: MetricsRegistry?,
) {
    /** Incremented once per successful [Orchestrator.tick] cycle. */
    fun tickCompleted() {
        registry?.counter("kivra_orchestrator_tick_total")?.increment()
    }

    /** Incremented when a tick is skipped because the Kill Switch is ON. */
    fun tickSkippedKillSwitch() {
        registry?.counter("kivra_orchestrator_tick_total")?.increment(
            mapOf("outcome" to "killed")
        )
    }

    /** Incremented when admission accepts a task. */
    fun admissionAccepted() {
        registry?.counter(MetricCatalog.RUN_TOTAL.name)?.increment(
            mapOf("outcome" to "admitted")
        )
    }

    /** Incremented when admission denies a task. */
    fun admissionDenied(reason: String) {
        registry?.counter(MetricCatalog.PROVIDER_ADMISSION_DENIED_TOTAL.name)?.increment(
            mapOf("provider" to "orchestrator", "reason" to reason)
        )
    }

    /** Incremented when a run state transitions. */
    fun runStateChanged(runId: String, newState: String) {
        // Run state is high-cardinality; use outcome label only.
        registry?.counter("kivra_orchestrator_run_state_total")?.increment(
            mapOf("outcome" to newState.lowercase())
        )
    }

    /** Incremented when the leader lease is acquired. */
    fun leaderLeaseAcquired() {
        registry?.counter("kivra_orchestrator_leader_lease_total")?.increment(
            mapOf("outcome" to "acquired")
        )
    }

    /** Incremented when the leader lease is lost. */
    fun leaderLeaseLost() {
        registry?.counter("kivra_orchestrator_leader_lease_total")?.increment(
            mapOf("outcome" to "lost")
        )
    }

    /** Incremented when fusha-task polling completes. */
    fun pollCompleted(taskCount: Int) {
        registry?.gauge("kivra_orchestrator_poll_tasks")?.set(taskCount.toDouble())
    }

    /** Incremented when reconciliation detects a stale run. */
    fun reconcileStaleRun() {
        registry?.counter("kivra_orchestrator_reconcile_stale_total")?.increment()
    }

    /** Incremented when Outbox publishing succeeds. */
    fun outboxPublished() {
        registry?.counter("kivra_orchestrator_outbox_published_total")?.increment()
    }

    /** Incremented when Outbox publishing fails. */
    fun outboxFailed() {
        registry?.counter("kivra_orchestrator_outbox_failed_total")?.increment()
    }
}
