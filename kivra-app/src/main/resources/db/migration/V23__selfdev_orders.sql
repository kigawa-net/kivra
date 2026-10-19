-- V23: self-development orders, stage 1 of ADR 0015 (Issue #273). Expand-only: three new tables and two nullable columns on
-- selfdev_goals. V1 .. V21 are not edited. V22 is left unused on purpose (another change may take it).
-- Stage 1 is the root order only: no child rows are written (the parent column exists for stage 2), max_cycles is 1 by default.
-- Times are UTC DATETIME(6). No order row holds a secret, a model output, a diff or a log line. Title and policy are the operator's
-- text; they are never logged or audited (ADR 0015 section 2.3, and the stage 1 rule against free text in audit).

-- selfdev_orders: one row per order. The root is the row with parent_order_id NULL and root_slot 1. root_slot is UNIQUE, so the
-- database refuses a second root (ADR 0015 section 2.7). state values are OrderState; state_reason is a fixed code (OrderReason).
CREATE TABLE selfdev_orders (
    order_id                   CHAR(26)     NOT NULL,                 -- ULID
    parent_order_id            CHAR(26)     NULL,                     -- NULL only for the root (children are stage 2)
    depth                      INT          NOT NULL DEFAULT 0,
    root_slot                  TINYINT      NULL,                     -- 1 for the root only; UNIQUE, so there is at most one root
    title                      VARCHAR(200) NOT NULL,
    policy                     TEXT         NOT NULL,                 -- the operator's direction; untrusted input for the Planner
    target_repo                VARCHAR(200) NOT NULL,                 -- fixed at creation
    max_cycles                 INT          NOT NULL DEFAULT 1,
    cycles_started             INT          NOT NULL DEFAULT 0,
    current_goal_id            CHAR(26)     NULL,                     -- the goal of the cycle in progress
    consecutive_failed_cycles  INT          NOT NULL DEFAULT 0,
    state                      VARCHAR(24)  NOT NULL,
    state_reason               VARCHAR(64)  NULL,
    expires_at                 DATETIME(6)  NOT NULL,
    created_by                 VARCHAR(255) NOT NULL,                 -- owner key of the operator who created it
    version                    BIGINT       NOT NULL DEFAULT 0,       -- optimistic lock; +1 on every change
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (order_id),
    CONSTRAINT uk_selfdev_orders_root UNIQUE (root_slot),
    CONSTRAINT fk_selfdev_orders_parent FOREIGN KEY (parent_order_id) REFERENCES selfdev_orders (order_id),
    CONSTRAINT fk_selfdev_orders_goal FOREIGN KEY (current_goal_id) REFERENCES selfdev_goals (goal_id),
    CONSTRAINT ck_selfdev_orders_state CHECK (state IN ('ACTIVE', 'PAUSED', 'STOP_REQUESTED', 'STOPPED')),
    CONSTRAINT ck_selfdev_orders_root CHECK (
        (parent_order_id IS NULL AND depth = 0 AND root_slot = 1) OR (parent_order_id IS NOT NULL AND depth >= 1 AND root_slot IS NULL)
    ),
    CONSTRAINT ck_selfdev_orders_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_selfdev_orders_policy CHECK (CHAR_LENGTH(policy) BETWEEN 1 AND 2000),
    CONSTRAINT ck_selfdev_orders_cycles CHECK (max_cycles BETWEEN 1 AND 10 AND cycles_started BETWEEN 0 AND max_cycles),
    CONSTRAINT ck_selfdev_orders_failures CHECK (consecutive_failed_cycles >= 0)
);
CREATE INDEX ix_selfdev_orders_state ON selfdev_orders (state, updated_at);

-- selfdev_order_stop_requests: a request to stop an order, and its decision. At most one PENDING request per tree: active_key is 1
-- while PENDING and NULL otherwise, and it is UNIQUE. The requester and the decider must be different owner keys (CHECK below), so a
-- request cannot be approved by the principal that made it. The application checks the same rule before the write. A cancelled or
-- expired request keeps decided_by NULL (the canceller is recorded in selfdev_order_events).
CREATE TABLE selfdev_order_stop_requests (
    request_id       CHAR(26)     NOT NULL,                             -- ULID
    order_id         CHAR(26)     NOT NULL,
    requested_by     VARCHAR(255) NOT NULL,                             -- owner key of the requester (a human or the AI principal)
    requester_kind   VARCHAR(8)   NOT NULL,                             -- HUMAN or AI
    reason_code      VARCHAR(32)  NOT NULL,                             -- StopRequestReason (fixed list, never free text)
    order_version    BIGINT       NOT NULL,                             -- the order's version after STOP_REQUESTED (the binding)
    digest           CHAR(64)     NOT NULL,                             -- SHA-256 of the canonical request (the approver echoes it)
    state            VARCHAR(16)  NOT NULL,                             -- PENDING, CONSUMED, REJECTED, CANCELLED, EXPIRED
    active_key       TINYINT      NULL,                                 -- 1 while PENDING, NULL otherwise
    decided_by       VARCHAR(255) NULL,                                 -- owner key of the approver or the rejecter (a cancelled request keeps it NULL)
    decider_kind     VARCHAR(8)   NULL,                                 -- HUMAN only (the AI principal never decides)
    decided_at       DATETIME(6)  NULL,
    expires_at       DATETIME(6)  NOT NULL,                             -- created_at + 24 hours
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_selfdev_order_stop_active UNIQUE (active_key),
    CONSTRAINT fk_selfdev_order_stop_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id),
    CONSTRAINT ck_selfdev_order_stop_state CHECK (state IN ('PENDING', 'CONSUMED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_selfdev_order_stop_requester CHECK (requester_kind IN ('HUMAN', 'AI')),
    CONSTRAINT ck_selfdev_order_stop_decider CHECK (decider_kind IS NULL OR decider_kind = 'HUMAN'),
    CONSTRAINT ck_selfdev_order_stop_active CHECK (
        (state = 'PENDING' AND active_key = 1) OR (state <> 'PENDING' AND active_key IS NULL)
    ),
    CONSTRAINT ck_selfdev_order_stop_decided CHECK (state IN ('PENDING', 'CANCELLED', 'EXPIRED') OR decided_by IS NOT NULL),
    CONSTRAINT ck_selfdev_order_stop_separation CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
CREATE INDEX ix_selfdev_order_stop_order ON selfdev_order_stop_requests (order_id, state);

-- selfdev_order_events: the append-only record of order changes. The application only INSERTs. actor_ref is the first 12 hex digits of
-- the SHA-256 of the owner key (never the key). No free text is stored here.
CREATE TABLE selfdev_order_events (
    event_id     CHAR(26)    NOT NULL,                                  -- ULID
    order_id     CHAR(26)    NOT NULL,
    cycle_no     INT         NULL,
    kind         VARCHAR(32) NOT NULL,
    from_state   VARCHAR(24) NULL,
    to_state     VARCHAR(24) NULL,
    reason_code  VARCHAR(64) NULL,
    actor_kind   VARCHAR(8)  NOT NULL,                                  -- HUMAN, AI or SYSTEM
    actor_ref    CHAR(12)    NULL,
    request_id   CHAR(26)    NULL,
    occurred_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_selfdev_order_events_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id),
    CONSTRAINT ck_selfdev_order_events_actor CHECK (actor_kind IN ('HUMAN', 'AI', 'SYSTEM'))
);
CREATE INDEX ix_selfdev_order_events_order ON selfdev_order_events (order_id, occurred_at);

-- A goal may belong to one order cycle. Both columns are NULL for every goal that existed before, and for every goal the autopilot
-- creates without an order, so the existing goal flow is unchanged.
ALTER TABLE selfdev_goals ADD COLUMN order_id CHAR(26) NULL;
ALTER TABLE selfdev_goals ADD COLUMN cycle_no INT NULL;
ALTER TABLE selfdev_goals ADD CONSTRAINT fk_selfdev_goals_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id);
ALTER TABLE selfdev_goals ADD CONSTRAINT uk_selfdev_goals_order_cycle UNIQUE (order_id, cycle_no);
