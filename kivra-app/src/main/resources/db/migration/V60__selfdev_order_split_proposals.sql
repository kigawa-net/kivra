-- V60: the child orders a Planner proposes (ADR 0015 addendum 2026-11-22, ADR 0011 addendum 2026-11-22). Expand only: one new table, and
-- nothing else is changed. V1 .. V59 are not edited.
-- A proposal is a suggestion for a human operator. Writing a row creates no order: the child order is created only by an operator's act
-- (U-19), in a later change. state PROPOSED is the only state this change writes; APPROVED, REJECTED and EXPIRED are for that later change.
-- plan_id is "{plan_task_id}:r{revision}" (the plan of one commit), so a re-planned root writes new rows and never collides with the old ones.
-- The title and the instruction are the Planner's output: data, never executed. They are never logged (the planner logs the digest only).
-- Times are UTC DATETIME(6).
CREATE TABLE selfdev_order_split_proposals (
    proposal_id              CHAR(26)     NOT NULL,                 -- ULID, one row per proposal
    plan_id                  VARCHAR(64)  NOT NULL,                 -- "{plan_task_id}:r{revision}": one committed plan
    plan_task_id             CHAR(26)     NOT NULL,                 -- the plan root (selfdev_tasks.task_id)
    order_id                 CHAR(26)     NOT NULL,                 -- the order whose plan proposed it (a root order; see ADR 0015 addendum)
    ordinal                  INT          NOT NULL,                 -- 1 .. 3 within the plan
    title                    VARCHAR(200) NOT NULL,
    instruction              TEXT         NOT NULL,                 -- the child order's policy: at most 8000 characters
    max_cycles               INT          NOT NULL,
    state                    VARCHAR(16)  NOT NULL DEFAULT 'PROPOSED',
    created_at               DATETIME(6)  NOT NULL,
    created_child_order_id   CHAR(26)     NULL,                     -- set only by the later change that creates the child
    PRIMARY KEY (proposal_id),
    CONSTRAINT uk_selfdev_order_split_proposals_plan_ordinal UNIQUE (plan_id, ordinal),
    CONSTRAINT ck_selfdev_order_split_proposals_ordinal CHECK (ordinal BETWEEN 1 AND 3),
    CONSTRAINT ck_selfdev_order_split_proposals_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_selfdev_order_split_proposals_instruction CHECK (CHAR_LENGTH(instruction) BETWEEN 1 AND 8000),
    CONSTRAINT ck_selfdev_order_split_proposals_cycles CHECK (max_cycles BETWEEN 1 AND 10),
    CONSTRAINT ck_selfdev_order_split_proposals_state CHECK (state IN ('PROPOSED', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT fk_selfdev_order_split_proposals_task FOREIGN KEY (plan_task_id) REFERENCES selfdev_tasks (task_id),
    CONSTRAINT fk_selfdev_order_split_proposals_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id),
    CONSTRAINT fk_selfdev_order_split_proposals_child FOREIGN KEY (created_child_order_id) REFERENCES selfdev_orders (order_id)
);

CREATE INDEX ix_selfdev_order_split_proposals_order ON selfdev_order_split_proposals (order_id, created_at);
