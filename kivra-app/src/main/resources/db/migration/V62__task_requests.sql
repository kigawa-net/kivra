-- V60: the approval request for a Task (ADR 0025). A request asks "may this title be run?"; approving it creates one Run through the Task API's
-- own submission (the run key is "request:<request_id>"). Every request belongs to one project (project_id, FK). Expand only: two new tables, nothing existing is touched.
--   1. The requester and the decider are different owner keys: CHECK below, as V23's stop request does. The repository checks the same rule first.
--   2. A decided row is not changed except run_id, which is set once after the approval (ADR 0025 section 3). CHECK run_id only with APPROVED.
--   3. task_request_events is append-only: the application only INSERTs. Its actor_ref is the first 12 hex digits of the SHA-256 of the owner key.
CREATE TABLE task_requests (
    request_id       CHAR(26)     NOT NULL,                              -- ULID
    project_id       CHAR(26)     NOT NULL,                              -- the project the request belongs to (the route is /api/projects/{projectId}/requests)
    requested_by     VARCHAR(255) NOT NULL,                              -- owner key of the requester (never the decider)
    idempotency_key  VARCHAR(128) NOT NULL,                              -- the requester's key: the same key with the same title is a replay
    title            VARCHAR(200) NOT NULL,                              -- the task title (1 to 200 characters, validated by the route)
    state            VARCHAR(16)  NOT NULL,                              -- PENDING, APPROVED, REJECTED, CANCELLED
    decided_by       VARCHAR(255) NULL,                                  -- owner key of the approver or the rejecter (never shown to the requester)
    decided_at       DATETIME(6)  NULL,
    decision_note    VARCHAR(500) NULL,                                  -- the rejecter's optional reason. Never logged or audited.
    run_id           CHAR(26)     NULL,                                  -- the Run the approval created, set once (NULL until then)
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_task_request_key UNIQUE (requested_by, idempotency_key),
    CONSTRAINT fk_task_request_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_request_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id),
    CONSTRAINT ck_task_request_state CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_task_request_decided CHECK (state IN ('PENDING', 'CANCELLED') OR decided_by IS NOT NULL),
    CONSTRAINT ck_task_request_separation CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_task_request_run CHECK (run_id IS NULL OR state = 'APPROVED')
);

CREATE TABLE task_request_events (
    event_id     CHAR(26)    NOT NULL,                                   -- ULID
    request_id   CHAR(26)    NOT NULL,
    kind         VARCHAR(16) NOT NULL,                                   -- REQUESTED, APPROVED, REJECTED, CANCELLED, RUN_LINKED
    actor_ref    CHAR(12)    NOT NULL,                                   -- the first 12 hex digits of the SHA-256 of the owner key (never the key)
    run_id       CHAR(26)    NULL,                                       -- set only for RUN_LINKED
    occurred_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_task_request_events_request FOREIGN KEY (request_id) REFERENCES task_requests (request_id),
    CONSTRAINT ck_task_request_events_kind CHECK (kind IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'RUN_LINKED'))
);
CREATE INDEX ix_task_request_events_request ON task_request_events (request_id, occurred_at);
CREATE INDEX ix_task_requests_project_state ON task_requests (project_id, state, created_at);
