-- V20: the run-execution monitor's event log (ADR 0012 section 2.2, Issue #223 PR 1). Expand-only: one new table, no change to an
-- existing table. V1 .. V19 are not edited.
--
-- One row is one fixed-code event that an agent reported for an attempt (kind, plus the fixed fields of that kind). The table never
-- holds a prompt, model output, stdout / stderr, a path, a tool input or a secret: every column is a fixed code, a count or a cost.
-- seq is numbered per (attempt, lease_generation) from 1. Seq 0 is reserved for the server's TRUNCATED mark (one per attempt).
-- received_at is the server's receive time (DB clock is not used: the application's UTC clock, like the other coding tables).
-- Times are UTC DATETIME(6).
--
-- The kind list is checked here as a backstop; the agent API checks the same list and the per-kind fields in code (the source of truth).
-- No ENUM type: VARCHAR with CHECK is the same on MariaDB and H2.

CREATE TABLE coding_monitor_events (
    attempt_id       CHAR(26)     NOT NULL,
    lease_generation BIGINT       NOT NULL,
    seq              INT          NOT NULL,
    kind             VARCHAR(32)  NOT NULL,
    phase            VARCHAR(32)  NULL,
    tool_name        VARCHAR(32)  NULL,
    code             VARCHAR(32)  NULL,
    count_value      INT          NULL,
    cost_usd         DECIMAL(12,6) NULL,
    received_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (attempt_id, lease_generation, seq),
    CONSTRAINT fk_coding_monitor_events_attempt FOREIGN KEY (attempt_id) REFERENCES coding_attempts (attempt_id),
    CONSTRAINT ck_coding_monitor_events_seq CHECK (seq >= 0),
    CONSTRAINT ck_coding_monitor_events_kind CHECK (kind IN (
        'ATTEMPT_STARTED', 'CLI_INIT', 'PHASE', 'TOOL_USE', 'TOOL_RESULT', 'TURN', 'PERMISSION_DENIED',
        'API_RETRY', 'COST', 'STOP_REQUESTED', 'PROCESS_EXIT_CONFIRMED', 'WARNING', 'DROPPED', 'TRUNCATED'))
);
