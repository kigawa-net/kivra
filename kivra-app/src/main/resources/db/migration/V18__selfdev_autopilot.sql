-- V18: self-development autopilot foundation (ADR 0011 section 2; V16 in the ADR is already used by coding_attempt_usage, so
-- the ADR's V16 is V18 here). V1 .. V17 are not edited. Expand-only: four new tables, no change to an existing table.
-- development_runs is not changed: a Run stays the single source of truth (ADR 0001). A task is a projection of its Run.
-- Times are UTC DATETIME(6). The tables hold goal text the operator typed and fixed codes; no model output, prompt or secret.

-- selfdev_goals: one row per goal submitted from the operator dashboard. state values are GoalState (kivra-core autopilot).
-- resume_state / resume_reason keep where a PAUSED goal returns to (ADR 0011 section 2.5: "the previous state"). They are set
-- only while PAUSED. This column pair is an addition to the ADR table; see the ADR section 2.1 note.
CREATE TABLE selfdev_goals (
    goal_id          CHAR(26)     NOT NULL,                  -- ULID
    created_by       VARCHAR(255) NOT NULL,                  -- the authenticated operator
    idempotency_key  VARCHAR(128) NOT NULL,
    title            VARCHAR(200) NOT NULL,
    body             TEXT         NULL,                      -- at most 8000 characters (checked below and in the application)
    target_repo      VARCHAR(200) NOT NULL,                  -- fixed at submission
    state            VARCHAR(32)  NOT NULL,
    state_reason     VARCHAR(64)  NULL,                      -- fixed code, never free text
    resume_state     VARCHAR(32)  NULL,
    resume_reason    VARCHAR(64)  NULL,
    revision         INT          NOT NULL DEFAULT 0,        -- Plans created so far (at most 3)
    claim_token      VARCHAR(64)  NULL,                      -- Planner claim; the fencing token with version
    claimed_until    DATETIME(6)  NULL,
    version          BIGINT       NOT NULL DEFAULT 0,        -- optimistic lock; +1 on every update
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (goal_id),
    CONSTRAINT uk_selfdev_goals_idem UNIQUE (created_by, idempotency_key),
    CONSTRAINT ck_selfdev_goals_state CHECK (state IN ('GOAL_SUBMITTED', 'PLANNING', 'PLAN_PROPOSED', 'TASKS_QUEUED', 'RUNNING', 'BLOCKED', 'PAUSED', 'DONE', 'STOPPED')),
    CONSTRAINT ck_selfdev_goals_resume CHECK (resume_state IS NULL OR resume_state IN ('TASKS_QUEUED', 'RUNNING', 'BLOCKED')),
    CONSTRAINT ck_selfdev_goals_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_selfdev_goals_body CHECK (body IS NULL OR CHAR_LENGTH(body) <= 8000),
    CONSTRAINT ck_selfdev_goals_revision CHECK (revision BETWEEN 0 AND 3)
);
CREATE INDEX ix_selfdev_goals_state ON selfdev_goals (state, updated_at);

-- selfdev_plans: one row per Plan revision of a goal. revision is 1-based and unique per goal.
CREATE TABLE selfdev_plans (
    plan_id            CHAR(26)    NOT NULL,                 -- ULID
    goal_id            CHAR(26)    NOT NULL,
    revision           INT         NOT NULL,
    state              VARCHAR(16) NOT NULL,                 -- PROPOSED / SUPERSEDED / REJECTED
    summary            VARCHAR(500) NULL,                    -- the validated summary, control characters removed
    planner_model      VARCHAR(64) NOT NULL,                 -- model id, for example claude-haiku-5-5
    output_sha256      CHAR(64)    NOT NULL,                 -- digest of the raw model output; the output itself is not stored
    request_ledger_id  VARCHAR(64) NULL,                     -- ModelGateway request ledger reference
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (plan_id),
    CONSTRAINT uk_selfdev_plans_goal_rev UNIQUE (goal_id, revision),
    CONSTRAINT fk_selfdev_plans_goal FOREIGN KEY (goal_id) REFERENCES selfdev_goals (goal_id),
    CONSTRAINT ck_selfdev_plans_state CHECK (state IN ('PROPOSED', 'SUPERSEDED', 'REJECTED')),
    CONSTRAINT ck_selfdev_plans_revision CHECK (revision >= 1)
);

-- selfdev_plan_tasks: the tasks of one Plan. ordinal is 1-based. idempotency_key is ap:{plan_id}:{ordinal} (ADR 0011 section 5.1)
-- and is unique, so a second submission of the same task is refused by the database. run_id is set only when the task is
-- SUBMITTED (the Run that was created for it).
CREATE TABLE selfdev_plan_tasks (
    plan_id            CHAR(26)     NOT NULL,
    ordinal            INT          NOT NULL,
    title              VARCHAR(200) NOT NULL,
    body               TEXT         NULL,                    -- the task text that the CLI receives (ADR 0011 section 2.3, #229)
    target_paths       TEXT         NOT NULL,                -- JSON array, at most 10 paths
    size_estimate      VARCHAR(2)   NOT NULL,                -- S / M / L (the planner's estimate, not trusted)
    depends_on         TEXT         NOT NULL,                -- JSON array of ordinals, each smaller than ordinal
    requires_approval  BOOLEAN      NOT NULL,
    state              VARCHAR(24)  NOT NULL,
    state_reason       VARCHAR(64)  NULL,
    idempotency_key    VARCHAR(128) NOT NULL,
    run_id             CHAR(26)     NULL,
    attempts           INT          NOT NULL DEFAULT 0,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (plan_id, ordinal),
    CONSTRAINT uk_selfdev_plan_tasks_idem UNIQUE (idempotency_key),
    CONSTRAINT fk_selfdev_plan_tasks_plan FOREIGN KEY (plan_id) REFERENCES selfdev_plans (plan_id),
    CONSTRAINT fk_selfdev_plan_tasks_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id),
    CONSTRAINT ck_selfdev_plan_tasks_state CHECK (state IN ('PENDING_APPROVAL', 'QUEUED', 'SUBMITTING', 'SUBMITTED', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_selfdev_plan_tasks_size CHECK (size_estimate IN ('S', 'M', 'L')),
    CONSTRAINT ck_selfdev_plan_tasks_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_selfdev_plan_tasks_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_selfdev_plan_tasks_body CHECK (body IS NULL OR CHAR_LENGTH(body) <= 8000)
);
CREATE INDEX ix_selfdev_plan_tasks_state ON selfdev_plan_tasks (state, updated_at);
CREATE INDEX ix_selfdev_plan_tasks_run ON selfdev_plan_tasks (run_id);

-- selfdev_autopilot_control: one row for the whole autopilot (ADR 0011 section 2.4). control_id is always 1.
-- The row is seeded here, so the dispatcher never has to insert it.
-- day_utc / runs_today count the Runs submitted on the current UTC day (provisional, ADR section U-3).
CREATE TABLE selfdev_autopilot_control (
    control_id           TINYINT      NOT NULL,
    paused               BOOLEAN      NOT NULL DEFAULT FALSE,
    paused_by            VARCHAR(255) NULL,                  -- the last operator who paused it
    paused_at            DATETIME(6)  NULL,
    consecutive_failures INT          NOT NULL DEFAULT 0,    -- reset by a COMPLETED task, and by an operator resume of the breaker
    breaker_open         BOOLEAN      NOT NULL DEFAULT FALSE,
    day_utc              DATE         NULL,
    runs_today           INT          NOT NULL DEFAULT 0,
    version              BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (control_id),
    CONSTRAINT ck_selfdev_autopilot_control_single CHECK (control_id = 1),
    CONSTRAINT ck_selfdev_autopilot_control_failures CHECK (consecutive_failures >= 0),
    CONSTRAINT ck_selfdev_autopilot_control_runs CHECK (runs_today >= 0)
);
INSERT INTO selfdev_autopilot_control (control_id, paused, breaker_open, consecutive_failures, runs_today, version)
    VALUES (1, FALSE, FALSE, 0, 0, 0);
