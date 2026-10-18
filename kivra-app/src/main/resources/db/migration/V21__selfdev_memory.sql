-- V21: self-development memory (ADR 0016, Issue #277, phase P0). Expand-only: three new tables, no change to an existing table.
-- V1 .. V20 are not edited. The number is V21 in this branch; if another migration lands first, renumber this file in merge order.
--
-- Two kinds of row share one table (ADR 0016 section 1): facts (fixed codes and counts, no text) and notes (text written by the
-- operator, by the Planner, or by the system). The validator (SelfDevMemoryValidator) is the source of truth; the CHECKs below are
-- the backstop for a path that skipped it. Times are UTC DATETIME(6).
--
-- Nothing here stores a secret, a prompt, model output, a diff, a log line or a PR body. Notes are the only column that holds text,
-- and only the validated text. A deleted note keeps its row as a tombstone (status DELETED) with body, body_sha256 and body_chars NULL.

-- selfdev_memory_records: one row per fact or note. Append-only from the application; the only INSERT is in SelfDevMemoryRepository.
CREATE TABLE selfdev_memory_records (
    record_id           CHAR(26)     NOT NULL,                  -- ULID
    kind                VARCHAR(32)  NOT NULL,                  -- the MemoryKind list (ADR 0016 section 4.1)
    source_kind         VARCHAR(16)  NOT NULL,                  -- OPERATOR / PLANNER / SYSTEM
    trust_tier          VARCHAR(20)  NOT NULL,                  -- TRUSTED_OPERATOR / UNTRUSTED (facts are UNTRUSTED)
    goal_id             CHAR(26)     NULL,
    plan_id             CHAR(26)     NULL,
    run_id              CHAR(26)     NULL,
    task_ordinal        INT          NULL,                      -- with plan_id: the plan task (selfdev_plan_tasks has no ULID)
    body                TEXT         NULL,                      -- the validated note text; NULL for a fact and for a tombstone
    body_sha256         CHAR(64)     NULL,                      -- SHA-256 of the NFC text (V11 idempotency)
    body_chars          INT          NULL,                      -- code points after NFC
    code                VARCHAR(64)  NULL,                      -- fixed code of a fact (e.g. a state_reason)
    count_value         INT          NULL,                      -- count of a fact (0 or more)
    identity_sha256     CHAR(64)     NULL,                      -- task identity: SHA-256 of the sorted target path set (ADR 0016 4.3)
    status              VARCHAR(12)  NOT NULL,                  -- ACTIVE / DELETED (tombstone)
    created_by_hash     CHAR(12)     NULL,                      -- first 12 hex of SHA-256 of the operator id (never the raw id)
    created_at          DATETIME(6)  NOT NULL,
    deleted_at          DATETIME(6)  NULL,
    delete_reason_code  VARCHAR(32)  NULL,                      -- OPERATOR_DELETE / CONDITION_DELETE / RETENTION (fixed list)
    CONSTRAINT pk_selfdev_memory_records PRIMARY KEY (record_id),
    CONSTRAINT fk_selfdev_memory_records_goal FOREIGN KEY (goal_id) REFERENCES selfdev_goals (goal_id),
    CONSTRAINT fk_selfdev_memory_records_plan FOREIGN KEY (plan_id) REFERENCES selfdev_plans (plan_id),
    CONSTRAINT fk_selfdev_memory_records_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id),
    CONSTRAINT fk_selfdev_memory_records_plan_task FOREIGN KEY (plan_id, task_ordinal) REFERENCES selfdev_plan_tasks (plan_id, ordinal),
    CONSTRAINT ck_selfdev_memory_records_kind CHECK (kind IN (
        'OPERATOR_NOTE', 'GOAL_TITLE', 'REJECTION_REASON', 'PLAN_RATIONALE', 'FAILURE_EXPLANATION', 'CYCLE_REVIEW',
        'PLAN_FACT', 'TASK_FACT', 'RUN_FACT', 'ATTEMPT_FACT', 'COST_FACT', 'GOAL_FACT')),
    CONSTRAINT ck_selfdev_memory_records_source CHECK (source_kind IN ('OPERATOR', 'PLANNER', 'DEVELOPER', 'SYSTEM')),
    CONSTRAINT ck_selfdev_memory_records_tier CHECK (trust_tier IN ('TRUSTED_OPERATOR', 'UNTRUSTED')),
    -- The kind decides the source and the tier (V2). Only the operator's HTTP path may create TRUSTED_OPERATOR.
    CONSTRAINT ck_selfdev_memory_records_kind_tier CHECK (
        (kind IN ('OPERATOR_NOTE', 'GOAL_TITLE', 'REJECTION_REASON') AND source_kind = 'OPERATOR' AND trust_tier = 'TRUSTED_OPERATOR')
        OR (kind = 'PLAN_RATIONALE' AND source_kind = 'PLANNER' AND trust_tier = 'UNTRUSTED')
        OR (kind IN ('FAILURE_EXPLANATION', 'CYCLE_REVIEW') AND source_kind = 'SYSTEM' AND trust_tier = 'UNTRUSTED')
        OR (kind IN ('PLAN_FACT', 'TASK_FACT', 'RUN_FACT', 'ATTEMPT_FACT', 'COST_FACT', 'GOAL_FACT') AND source_kind = 'SYSTEM' AND trust_tier = 'UNTRUSTED')
    ),
    CONSTRAINT ck_selfdev_memory_records_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT ck_selfdev_memory_records_deleted CHECK (
        (status = 'ACTIVE' AND deleted_at IS NULL AND delete_reason_code IS NULL)
        OR (status = 'DELETED' AND deleted_at IS NOT NULL AND delete_reason_code IN ('OPERATOR_DELETE', 'CONDITION_DELETE', 'RETENTION'))
    ),
    -- A note has text and no fact fields; a fact has a code or a count and no text; a tombstone has neither.
    CONSTRAINT ck_selfdev_memory_records_shape CHECK (
        (kind IN ('OPERATOR_NOTE', 'GOAL_TITLE', 'REJECTION_REASON', 'PLAN_RATIONALE', 'FAILURE_EXPLANATION', 'CYCLE_REVIEW')
            AND ((status = 'ACTIVE' AND body IS NOT NULL AND body_sha256 IS NOT NULL AND body_chars BETWEEN 1 AND 500
                AND code IS NULL AND count_value IS NULL AND identity_sha256 IS NULL)
              OR (status = 'DELETED' AND body IS NULL AND body_sha256 IS NULL AND body_chars IS NULL
                AND code IS NULL AND count_value IS NULL AND identity_sha256 IS NULL)))
        OR (kind IN ('PLAN_FACT', 'TASK_FACT', 'RUN_FACT', 'ATTEMPT_FACT', 'COST_FACT', 'GOAL_FACT')
            AND status = 'ACTIVE' AND body IS NULL AND body_sha256 IS NULL AND body_chars IS NULL
            AND (code IS NOT NULL OR count_value IS NOT NULL) AND (count_value IS NULL OR count_value >= 0))
    ),
    CONSTRAINT ck_selfdev_memory_records_task CHECK (task_ordinal IS NULL OR plan_id IS NOT NULL)
);
CREATE INDEX ix_selfdev_memory_records_goal_kind ON selfdev_memory_records (goal_id, kind, status);
CREATE INDEX ix_selfdev_memory_records_sha ON selfdev_memory_records (goal_id, kind, body_sha256);
CREATE INDEX ix_selfdev_memory_records_status_created ON selfdev_memory_records (status, created_at);
CREATE INDEX ix_selfdev_memory_records_status_deleted ON selfdev_memory_records (status, deleted_at);

-- selfdev_memory_events: append-only audit of the memory. It never holds note text, a fragment of it or a hash of it.
-- record_id has no foreign key: the event outlives the record it names (tombstones are purged, the event stays for 365 days).
CREATE TABLE selfdev_memory_events (
    event_id      CHAR(26)     NOT NULL,                        -- ULID
    record_id     CHAR(26)     NULL,                            -- NULL for a rejection (no record is created)
    kind          VARCHAR(16)  NOT NULL,
    code          VARCHAR(32)  NULL,                            -- a rejection code, a delete reason, or a read code
    scope         VARCHAR(64)  NULL,                            -- for DELETED: ID / CONDITION:<field> / RETENTION:<class>
    detail        VARCHAR(255) NULL,                            -- fixed names only, e.g. the SecretKind names of a secret rejection
    count_value   INT          NULL,                            -- deleted rows, or the length (code points) of a rejected text
    actor_hash    CHAR(12)     NULL,
    at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_selfdev_memory_events PRIMARY KEY (event_id),
    CONSTRAINT ck_selfdev_memory_events_kind CHECK (kind IN (
        'WRITTEN', 'REJECTED', 'DUPLICATE', 'READ_SKIPPED', 'DELETED', 'PURGED', 'READ_FAILED')),
    CONSTRAINT ck_selfdev_memory_events_count CHECK (count_value IS NULL OR count_value >= 0)
);
CREATE INDEX ix_selfdev_memory_events_at ON selfdev_memory_events (at);
CREATE INDEX ix_selfdev_memory_events_record ON selfdev_memory_events (record_id);

-- selfdev_plan_memory_refs: the memory records a plan's prompt used (ADR 0016 section 4.7). Only IDs. Created now so that a
-- deletion can remove the IDs with the record; nothing writes it in P0.
CREATE TABLE selfdev_plan_memory_refs (
    plan_id     CHAR(26)    NOT NULL,
    record_id   CHAR(26)    NOT NULL,
    CONSTRAINT pk_selfdev_plan_memory_refs PRIMARY KEY (plan_id, record_id),
    CONSTRAINT fk_selfdev_plan_memory_refs_plan FOREIGN KEY (plan_id) REFERENCES selfdev_plans (plan_id)
);
CREATE INDEX ix_selfdev_plan_memory_refs_record ON selfdev_plan_memory_refs (record_id);
