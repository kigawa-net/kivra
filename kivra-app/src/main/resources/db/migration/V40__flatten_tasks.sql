-- V40: flatten self-development structure (remove Goal and Plan, introduce flat Task with direct dependencies).
-- Removes: selfdev_goals, selfdev_plans, selfdev_plan_tasks
-- Creates: selfdev_tasks (flat task model with direct task-to-task dependencies)
--
-- A task now carries both the goal-level properties (title, body, target_repo, goal-state)
-- and the task-level properties (dependencies, execution state). Tasks reference other tasks
-- by task_id for dependencies, enabling cross-project relationships.

-- Drop dependent tables first (foreign key order)
DROP TABLE IF EXISTS selfdev_plan_tasks;
DROP TABLE IF EXISTS selfdev_plans;
DROP TABLE IF EXISTS selfdev_goals;

-- selfdev_tasks: flat task model. Each task is independent and can depend on other tasks by task_id.
-- State combines the former GoalState and TaskState into a single lifecycle:
--   Submission: TASK_SUBMITTED (operator submits a task)
--   Planning: PLANNING (AI planner is working on it)
--   Approval: PENDING_APPROVAL (waiting for approval to execute)
--   Execution: QUEUED, SUBMITTING, SUBMITTED (execution pipeline)
--   Outcome: COMPLETED, FAILED, CANCELLED, SKIPPED
--   Control: BLOCKED (dispatch guard), PAUSED (operator pause), DONE (all done), STOPPED (operator stop)
CREATE TABLE selfdev_tasks (
    task_id            CHAR(26)     NOT NULL,                  -- ULID
    created_by         VARCHAR(255) NOT NULL,                  -- the authenticated operator
    idempotency_key    VARCHAR(128) NOT NULL,
    title              VARCHAR(200) NOT NULL,
    body               TEXT         NULL,                      -- at most 8000 characters
    target_repo        VARCHAR(200) NOT NULL,                  -- fixed at submission
    state              VARCHAR(32)  NOT NULL,
    state_reason       VARCHAR(64)  NULL,                      -- fixed code, never free text
    resume_state       VARCHAR(32)  NULL,                      -- state to resume to from PAUSED
    resume_reason      VARCHAR(64)  NULL,
    revision           INT          NOT NULL DEFAULT 0,        -- plan revisions created so far (at most 3)
    claim_token        VARCHAR(64)  NULL,                      -- Planner claim; the fencing token with version
    claimed_until      DATETIME(6)  NULL,
    dependencies       TEXT         NOT NULL,                  -- JSON array of task_ids this task depends on
    requires_approval  BOOLEAN      NOT NULL DEFAULT TRUE,     -- whether operator approval is needed before execution
    run_id             CHAR(26)     NULL,                      -- the Run created for this task (when SUBMITTED)
    attempts           INT          NOT NULL DEFAULT 0,        -- number of SUBMITTING attempts
    version            BIGINT       NOT NULL DEFAULT 0,        -- optimistic lock; +1 on every update
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    approved_by        VARCHAR(255) NULL,                      -- the operator who approved (V19)
    PRIMARY KEY (task_id),
    CONSTRAINT uk_selfdev_tasks_idem UNIQUE (created_by, idempotency_key),
    CONSTRAINT fk_selfdev_tasks_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id),
    CONSTRAINT ck_selfdev_tasks_state CHECK (state IN (
        'TASK_SUBMITTED', 'PLANNING', 'PENDING_APPROVAL', 'QUEUED', 'SUBMITTING', 'SUBMITTED',
        'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED', 'BLOCKED', 'PAUSED', 'DONE', 'STOPPED'
    )),
    CONSTRAINT ck_selfdev_tasks_resume CHECK (resume_state IS NULL OR resume_state IN ('QUEUED', 'SUBMITTING', 'SUBMITTED', 'BLOCKED')),
    CONSTRAINT ck_selfdev_tasks_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_selfdev_tasks_body CHECK (body IS NULL OR CHAR_LENGTH(body) <= 8000),
    CONSTRAINT ck_selfdev_tasks_revision CHECK (revision BETWEEN 0 AND 3),
    CONSTRAINT ck_selfdev_tasks_repo CHECK (CHAR_LENGTH(target_repo) BETWEEN 1 AND 200)
);
CREATE INDEX ix_selfdev_tasks_state ON selfdev_tasks (state, updated_at);
CREATE INDEX ix_selfdev_tasks_run ON selfdev_tasks (run_id);
CREATE INDEX ix_selfdev_tasks_created ON selfdev_tasks (created_by, created_at);
