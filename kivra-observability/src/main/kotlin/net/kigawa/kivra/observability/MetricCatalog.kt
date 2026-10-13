package net.kigawa.kivra.observability

/**
 * Names, types and units of the quality, safety and cost metrics (design #1 section 8, #63 metrics).
 * Keep docs/observability.md in sync with this list.
 *
 * Label policy: only low-cardinality enums (outcome, provider, model, purpose, ...). Never put
 * runId, executionId, agentId, taskId, traceId, tenant, prompt or token values in a label; those belong
 * in span attributes (see [AttributeFilter.SPAN_ATTRIBUTE_ALLOWLIST]).
 *
 * Cardinality bound: every family has a [MetricSpec.maxSeries] cap (default
 * [MetricSpec.DEFAULT_MAX_SERIES]). Label value enumerations (`labelValues`) are not declared yet
 * because the value domains (outcome, provider, model, ...) are not fixed in code; until then the cap
 * is the only bound on those keys.
 */
object MetricCatalog {
    private val LONG_SECONDS = listOf(1.0, 5.0, 30.0, 60.0, 300.0, 900.0, 3600.0, 14400.0, 86400.0)

    // Run / quality
    val RUN_TOTAL = MetricSpec(
        "kivra_run_total", MetricType.COUNTER, "1",
        "Finished DevelopmentRuns by outcome (success rate = completed / all)", setOf("outcome"),
    )
    val RUN_DURATION = MetricSpec(
        "kivra_run_duration_seconds", MetricType.HISTOGRAM, "s",
        "Wall time of a finished DevelopmentRun", setOf("outcome"), LONG_SECONDS,
    )
    val RUN_RETRY_TOTAL = MetricSpec(
        "kivra_run_retry_total", MetricType.COUNTER, "1",
        "Retries of a Stage or Attempt (retry rate)", setOf("operation_class"),
    )
    val CI_FIRST_ATTEMPT_TOTAL = MetricSpec(
        "kivra_ci_first_attempt_total", MetricType.COUNTER, "1",
        "CI result of the first attempt per PR (first-attempt success rate)", setOf("result"),
    )
    val REVIEW_FINDINGS_TOTAL = MetricSpec(
        "kivra_review_findings_total", MetricType.COUNTER, "1",
        "Review findings raised on Kivra PRs", setOf("severity"),
    )
    val ROLLBACK_TOTAL = MetricSpec(
        "kivra_rollback_total", MetricType.COUNTER, "1",
        "Rollbacks performed (rollback rate)", emptySet(),
    )
    val EXECUTION_DURATION = MetricSpec(
        "kivra_execution_duration_seconds", MetricType.HISTOGRAM, "s",
        "Execution wall time", setOf("outcome"), LONG_SECONDS,
    )

    // Approval / safety
    val APPROVAL_WAIT = MetricSpec(
        "kivra_approval_wait_seconds", MetricType.HISTOGRAM, "s",
        "Time from approval request until decision", setOf("kind"), LONG_SECONDS,
    )

    // Operation / recovery (UNKNOWN and Reaper, design #1 section 9.2 and #63)
    val OPERATION_UNKNOWN_TOTAL = MetricSpec(
        "kivra_operation_unknown_total", MetricType.COUNTER, "1",
        "Cumulative count of operations that entered UNKNOWN (fail-closed); not the current unresolved count, see kivra_recovery_queue_depth", setOf("operation_class"),
    )
    val REAPER_DETECTED_TOTAL = MetricSpec(
        "kivra_reaper_detected_total", MetricType.COUNTER, "1",
        "Orphaned or stale items detected by the Reaper", setOf("reason"),
    )
    val RECOVERY_QUEUE_DEPTH = MetricSpec(
        "kivra_recovery_queue_depth", MetricType.GAUGE, "1",
        "Operations waiting for reconciliation", emptySet(),
    )

    // Provider health (#63)
    val PROVIDER_CIRCUIT_STATE = MetricSpec(
        "kivra_provider_circuit_state", MetricType.GAUGE, "enum (0=closed,1=half_open,2=open)",
        "Circuit breaker state per provider", setOf("provider"),
    )
    val PROVIDER_REQUEST_TOTAL = MetricSpec(
        "kivra_provider_request_total", MetricType.COUNTER, "1",
        "Provider requests by outcome", setOf("provider", "outcome"),
    )
    val PROVIDER_PROBE_TOTAL = MetricSpec(
        "kivra_provider_probe_total", MetricType.COUNTER, "1",
        "Half-open probe requests by outcome", setOf("provider", "outcome"),
    )
    val PROVIDER_ADMISSION_DENIED_TOTAL = MetricSpec(
        "kivra_provider_admission_denied_total", MetricType.COUNTER, "1",
        "Requests refused before reaching the provider", setOf("provider", "reason"),
    )

    // Model usage and cost (Model Gateway; persistence is out of scope)
    val MODEL_TOKENS_TOTAL = MetricSpec(
        "kivra_model_tokens_total", MetricType.COUNTER, "1",
        "LLM tokens by direction", setOf("provider", "model", "direction"),
    )
    val MODEL_COST_MICROS_TOTAL = MetricSpec(
        "kivra_model_cost_micros_total", MetricType.COUNTER, "1e-6 billing currency (currency: TBD)",
        "Known LLM cost; unpriced calls are counted separately", setOf("provider", "model"),
    )
    val MODEL_UNPRICED_CALLS_TOTAL = MetricSpec(
        "kivra_model_unpriced_calls_total", MetricType.COUNTER, "1",
        "Model calls whose cost could not be determined", setOf("provider", "model"),
    )

    /**
     * Label keys that must never be used as metric labels (high-cardinality identifiers or bodies).
     * Checked by MetricsRegistryTest against [ALL].
     */
    val FORBIDDEN_LABEL_KEYS: Set<String> = setOf(
        "runId", "run_id", "executionId", "execution_id", "agentId", "agent_id", "taskId", "task_id",
        "traceId", "trace_id", "tenant", "tenantId", "prompt", "token", "reason_message",
    )

    /** Every metric declared above. Registering a new one means adding it here. */
    val ALL: List<MetricSpec> = listOf(
        RUN_TOTAL, RUN_DURATION, RUN_RETRY_TOTAL, CI_FIRST_ATTEMPT_TOTAL, REVIEW_FINDINGS_TOTAL,
        ROLLBACK_TOTAL, EXECUTION_DURATION, APPROVAL_WAIT,
        OPERATION_UNKNOWN_TOTAL, REAPER_DETECTED_TOTAL, RECOVERY_QUEUE_DEPTH,
        PROVIDER_CIRCUIT_STATE, PROVIDER_REQUEST_TOTAL, PROVIDER_PROBE_TOTAL, PROVIDER_ADMISSION_DENIED_TOTAL,
        MODEL_TOKENS_TOTAL, MODEL_COST_MICROS_TOTAL, MODEL_UNPRICED_CALLS_TOTAL,
    )
}
