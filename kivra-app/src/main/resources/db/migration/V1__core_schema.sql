-- Kivra core schema (M0). Sources: design #1 section 9, #10 section 4, #11 sections 3-5.
-- Must run on MariaDB and on H2 (MODE=MySQL): no ENUM / JSON / STORED / ON DUPLICATE KEY.
-- Times are UTC DATETIME(6). Lease expiry is judged by the DB clock (NOW(6)) only.
-- JSON payloads are stored as TEXT (kotlinx.serialization on the application side).

-- development_runs: the single source of truth of a DevelopmentRun (design #9 section 3 / #10 section 4; ADR 0001).
-- States: CREATED PLANNING PREPARING DEVELOPING TESTING REVIEWING PULL_REQUEST WAITING_APPROVAL RELEASING VERIFYING
-- RETRY_WAITING COMPLETED FAILED CANCELLED. task_id only references the fusha-task task; it is not a second state.
-- Lease columns live on this row so that state updates and lease checks are one atomic statement (#11 section 3.2).
CREATE TABLE development_runs (
    run_id           CHAR(26)     NOT NULL,                 -- ULID
    task_id          VARCHAR(128) NOT NULL,                 -- fusha-task task id (reference only)
    task_updated_at  DATETIME(6)  NULL,                     -- task updatedAt at intake
    state            VARCHAR(32)  NOT NULL,
    current_stage    VARCHAR(32)  NULL,                     -- design #9 section 3.3 Stage
    state_reason     VARCHAR(64)  NULL,
    attempt          INT          NOT NULL DEFAULT 0,         -- run-wide total
    plan_revision    INT          NOT NULL DEFAULT 0,
    lease_owner      VARCHAR(64)  NULL,
    lease_generation BIGINT       NOT NULL DEFAULT 0,       -- fencing token; bumped only by acquire
    lease_expires_at DATETIME(6)  NULL,
    hold_reason      VARCHAR(64)  NULL,                     -- non-NULL stops automatic progress
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    next_retry_at    DATETIME(6)  NULL,
    budget_json      TEXT         NULL,
    trace_id         VARCHAR(64)  NULL,
    version          BIGINT       NOT NULL DEFAULT 0,       -- optimistic lock; +1 on every update
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    -- NULL for terminal states, 1 otherwise: UNIQUE(task_id, active_flag) allows one active run per task.
    active_flag      TINYINT GENERATED ALWAYS AS (CASE WHEN state IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN NULL ELSE 1 END),
    PRIMARY KEY (run_id),
    CONSTRAINT uk_dev_runs_active_task UNIQUE (task_id, active_flag)
);
CREATE INDEX ix_dev_runs_recover ON development_runs (state, lease_expires_at);
CREATE INDEX ix_dev_runs_retry ON development_runs (state, next_retry_at);

-- stage_executions: one Attempt of one Stage (design #10 section 4). Unique per (run, stage, attempt_no).
CREATE TABLE stage_executions (
    stage_exec_id    CHAR(26)     NOT NULL,
    run_id           CHAR(26)     NOT NULL,
    stage            VARCHAR(32)  NOT NULL,
    attempt_no       INT          NOT NULL,                    -- per-stage, monotonic
    lease_generation BIGINT       NOT NULL,                    -- generation of the worker that ran it
    status           VARCHAR(16)  NOT NULL,                    -- RUNNING / SUCCEEDED / FAILED / ABANDONED
    error_class      VARCHAR(32)  NULL,
    error_detail     TEXT         NULL,
    started_at       DATETIME(6)  NOT NULL,
    finished_at      DATETIME(6)  NULL,
    PRIMARY KEY (stage_exec_id),
    CONSTRAINT uk_stage_attempt UNIQUE (run_id, stage, attempt_no),
    CONSTRAINT fk_stage_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id)
);

CREATE TABLE agents (
    agent_id   CHAR(26)     NOT NULL,
    run_id     CHAR(26)     NOT NULL,
    role       VARCHAR(64)  NOT NULL,
    state      VARCHAR(32)  NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (agent_id),
    CONSTRAINT fk_agents_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id)
);
CREATE INDEX ix_agents_run ON agents (run_id);

-- execution_sessions: Kivra-side binding to exkes Workspace/Runtime/Execution (design #1 section 5.3).
-- exkes_* columns are references / observed values only; exkes owns their state.
CREATE TABLE execution_sessions (
    session_id      CHAR(26)     NOT NULL,
    run_id          CHAR(26)     NOT NULL,
    agent_id        CHAR(26)     NULL,
    state           VARCHAR(32)  NOT NULL,
    workspace_id    VARCHAR(128) NULL,
    runtime_id      VARCHAR(128) NULL,
    execution_id    VARCHAR(128) NULL,
    observed_status VARCHAR(32)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_sessions_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id),
    CONSTRAINT fk_sessions_agent FOREIGN KEY (agent_id) REFERENCES agents (agent_id)
);
CREATE INDEX ix_sessions_run ON execution_sessions (run_id);
CREATE INDEX ix_sessions_state ON execution_sessions (state);

-- external_operations (the Operation Journal): two-phase record of external side effects (design #10 / #11 section 4).
-- idempotency_key is #11's logical_key: deterministic, same for retries and worker changes.
CREATE TABLE external_operations (
    op_id              CHAR(26)     NOT NULL,
    run_id             CHAR(26)     NOT NULL,
    op_type            VARCHAR(48)  NOT NULL,                -- e.g. EXKES_CREATE_WORKSPACE
    idempotency_key    VARCHAR(128) NOT NULL,
    external_system    VARCHAR(16)  NOT NULL,                -- EXKES / FUSHA_TASK / GITHUB / DEC
    stable_ref         VARCHAR(255) NOT NULL,                -- identifier Kivra decides before the call
    external_ref       VARCHAR(255) NULL,                    -- identifier assigned by the external system
    request_hash       CHAR(64)     NOT NULL,                -- detects a changed request under the same key
    status             VARCHAR(32)  NOT NULL,
    attempt            INT          NOT NULL DEFAULT 0,
    max_attempts       INT          NOT NULL DEFAULT 3,
    lease_generation   BIGINT       NOT NULL,                -- generation that last moved the row (audit only)
    next_action_at     DATETIME(6)  NULL,
    last_error_class   VARCHAR(32)  NULL,
    last_error         TEXT         NULL,
    reconcile_attempts INT          NOT NULL DEFAULT 0,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (op_id),
    CONSTRAINT uk_op_idem UNIQUE (run_id, op_type, idempotency_key),
    CONSTRAINT fk_op_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id)
);
CREATE INDEX ix_op_status_next ON external_operations (status, next_action_at);

-- outbox_events: notifications of committed state changes (design #11 section 5).
CREATE TABLE outbox_events (
    event_id          CHAR(26)     NOT NULL,                 -- ULID; consumers dedupe on it
    aggregate_type    VARCHAR(32)  NOT NULL,                 -- 'RUN'
    aggregate_id      CHAR(26)     NOT NULL,                 -- run id
    aggregate_version BIGINT       NOT NULL,                 -- development_runs.version after the change
    event_type        VARCHAR(64)  NOT NULL,
    schema_version    INT          NOT NULL DEFAULT 1,
    payload           TEXT         NOT NULL,
    trace_id          VARCHAR(64)  NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED / DEAD
    publish_attempts  INT          NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(6)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    published_at      DATETIME(6)  NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_outbox_agg_ver UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);
CREATE INDEX ix_outbox_pending ON outbox_events (status, next_attempt_at);

-- inbox_events: consumer-side deduplication (design #11 section 5.2).
CREATE TABLE inbox_events (
    consumer_name     VARCHAR(64)  NOT NULL,
    event_id          VARCHAR(128) NOT NULL,
    aggregate_id      VARCHAR(64)  NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    processed_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

-- audit_events: append-only, hash-chained per run (design #1 section 6.5).
-- hash = SHA-256 over prev_hash and the event fields; secrets are never stored, references only.
CREATE TABLE audit_events (
    event_id    CHAR(26)     NOT NULL,
    run_id      CHAR(26)     NULL,
    seq         BIGINT       NOT NULL,                       -- per-run monotonic sequence
    occurred_at DATETIME(6)  NOT NULL,
    actor       VARCHAR(128) NOT NULL,
    agent_id    CHAR(26)     NULL,
    action      VARCHAR(64)  NOT NULL,
    resource    VARCHAR(255) NULL,
    decision_id VARCHAR(64)  NULL,
    approval_id VARCHAR(64)  NULL,
    result      VARCHAR(32)  NOT NULL,
    trace_id    VARCHAR(64)  NULL,
    payload     TEXT         NULL,
    prev_hash   CHAR(64)     NULL,
    hash        CHAR(64)     NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_audit_run_seq UNIQUE (run_id, seq),
    CONSTRAINT fk_audit_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id)
);
