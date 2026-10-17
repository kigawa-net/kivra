-- V14: coding attempts and their progress events for the local-agent coding runtime (design: Issue #187 design-coding-runtime.md
-- section 2, Issue #56 / #157). V1 .. V13 are not edited. Expand-only: two new tables, no change to an existing table.
-- A Run keeps state DEVELOPING while its attempts run. The attempt rows are the work queue that the local agent claims from.
-- Times are UTC DATETIME(6). Lease and queue expiry are judged by the DB clock (NOW(6)) only, as for development_runs.
--
-- coding_attempts: one row per attempt of a Run. attempt_no counts from 1 within the Run.
-- States: QUEUED -> CLAIMED -> RUNNING -> RESULT_RECEIVED (complete), or -> FAILED / CANCELLED (fail), or -> ORPHANED (lease
-- expired), or QUEUED -> EXPIRED (queued_expires_at passed). Only QUEUED, CLAIMED and RUNNING are active; the rest are terminal.
-- lease_generation is the fencing token: claim bumps it, and heartbeat / complete / fail are accepted only for the current value.
-- The table never holds prompts, model output, logs or secrets. result_digest is the SHA-256 hex of the result, not the result.
-- At most one active attempt per Run: active_flag is 1 for QUEUED / CLAIMED / RUNNING and NULL otherwise, and UNIQUE(run_id,
-- active_flag) allows one row with value 1 per Run (NULLs do not conflict, on MariaDB and H2 alike).

CREATE TABLE coding_attempts (
    attempt_id        CHAR(26)     NOT NULL,                  -- ULID
    run_id            CHAR(26)     NOT NULL,
    attempt_no        INT          NOT NULL,                  -- per Run, from 1
    state             VARCHAR(16)  NOT NULL,
    claimed_by        VARCHAR(64)  NULL,                      -- local agent identifier (not a credential)
    lease_generation  BIGINT       NOT NULL DEFAULT 0,       -- fencing token; bumped only by claim
    lease_expires_at  DATETIME(6)  NULL,
    queued_expires_at DATETIME(6)  NOT NULL,                  -- a QUEUED attempt past this time is EXPIRED
    base_sha          VARCHAR(64)  NOT NULL,                  -- fixed when the attempt is queued, not the branch head
    result_digest     CHAR(64)     NULL,                      -- SHA-256 hex of the result; the result itself is stored elsewhere
    exit_status       INT          NULL,
    error_code        VARCHAR(64)  NULL,                      -- fixed reason code (for example TIMEOUT), never free text
    session_id        VARCHAR(128) NULL,                      -- Claude CLI session identifier only
    active_flag       TINYINT GENERATED ALWAYS AS (CASE WHEN state IN ('QUEUED', 'CLAIMED', 'RUNNING') THEN 1 ELSE NULL END),
    version           BIGINT       NOT NULL DEFAULT 0,        -- optimistic lock; +1 on every update
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (attempt_id),
    CONSTRAINT uk_coding_attempts_run_no UNIQUE (run_id, attempt_no),
    CONSTRAINT uk_coding_attempts_run_active UNIQUE (run_id, active_flag),
    CONSTRAINT ck_coding_attempts_state CHECK (state IN ('QUEUED', 'CLAIMED', 'RUNNING', 'RESULT_RECEIVED', 'FAILED', 'CANCELLED', 'EXPIRED', 'ORPHANED')),
    CONSTRAINT fk_coding_attempts_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id)
);
CREATE INDEX ix_coding_attempts_state_lease ON coding_attempts (state, lease_expires_at);
CREATE INDEX ix_coding_attempts_state_queued ON coding_attempts (state, queued_expires_at);

-- coding_events: append-only progress log of one attempt. seq counts from 1 within the attempt.
-- type is a fixed state or event code. payload_json is at most 512 characters of small JSON with fixed fields; it never holds
-- a prompt, model output, stdout / stderr, or a secret.
CREATE TABLE coding_events (
    attempt_id   CHAR(26)     NOT NULL,
    seq          INT          NOT NULL,
    type         VARCHAR(32)  NOT NULL,
    payload_json VARCHAR(512) NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (attempt_id, seq),
    CONSTRAINT fk_coding_events_attempt FOREIGN KEY (attempt_id) REFERENCES coding_attempts (attempt_id)
);
