-- V47: the record of the incident fixes the self-healing stage 2 submits (issue #226). Expand only: one new table, nothing existing changes.
--
-- One row per incident fingerprint (the SHA-256 of the fixed codes and the repository, as in V46). The primary key is the idempotency
-- key: a cause gets at most one fix, ever. A recurrence after a fix is a human's decision, never a new automatic loop. The row is
-- claimed before the task is submitted; the task itself is keyed by (created_by, idempotency_key) in selfdev_tasks, so a retry returns
-- the same task.
--
-- active_slot is the single active fix: the unique index lets one row hold 'A' at a time (NULL in every other row; MySQL and H2 allow
-- many NULLs). It is held from SUBMITTING until the plan root ends, so two Pods cannot run two fixes at once.
CREATE TABLE selfdev_incident_fixes (
    fingerprint    CHAR(64)     NOT NULL,                       -- as selfdev_incident_issues.fingerprint
    incident_code  VARCHAR(32)  NOT NULL,                       -- RUN_FAILED, RUN_UNKNOWN or BREAKER_OPEN
    reason_code    VARCHAR(64)  NOT NULL,                       -- the fixed reason code, or NONE / OTHER
    issue_number   BIGINT       NOT NULL,                       -- the incident issue this fix answers (created by the detector)
    state          VARCHAR(16)  NOT NULL,                       -- CLAIMED, SUBMITTING, SUBMITTED, ENDED, FAILED
    root_task_id   CHAR(26)     NULL,                           -- the plan root task submitted for this fix (selfdev_tasks.task_id)
    attempts       INT          NOT NULL DEFAULT 0,             -- submissions started (at most 3)
    active_slot    CHAR(1)      NULL,                           -- 'A' while this fix holds the single active slot
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (fingerprint),
    CONSTRAINT uk_selfdev_incident_fixes_slot UNIQUE (active_slot),
    CONSTRAINT ck_selfdev_incident_fixes_state CHECK (state IN ('CLAIMED', 'SUBMITTING', 'SUBMITTED', 'ENDED', 'FAILED')),
    CONSTRAINT ck_selfdev_incident_fixes_slot CHECK (active_slot IS NULL OR active_slot = 'A')
);
CREATE INDEX ix_selfdev_incident_fixes_root ON selfdev_incident_fixes (root_task_id);
