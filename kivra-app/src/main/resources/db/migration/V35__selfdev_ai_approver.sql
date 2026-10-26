-- V35: the AI_APPROVER role and the record of the server-side stop decisions (ADR 0021, stage L2). Expand-only: one CHECK widened, one
-- new table. V1 .. V34 are not edited. Nothing reads or writes the new table yet and the role has no token and no bound person, so this
-- migration alone changes no behaviour.

-- The role CHECK accepts every value it did and adds AI_APPROVER. An old Writer never writes AI_APPROVER, so its rows pass the new CHECK
-- as they passed the old one. (Same DROP + ADD form as V34, which MariaDB and H2 both run.)
ALTER TABLE selfdev_assignees DROP CONSTRAINT ck_selfdev_assignees_role;
ALTER TABLE selfdev_assignees ADD CONSTRAINT ck_selfdev_assignees_role CHECK (
    role IN ('ADMIN', 'OPERATOR', 'APPROVER', 'AI', 'VIEWER', 'AI_APPROVER')
);

-- selfdev_llm_stop_decisions: append-only, one row per decision. Fixed fields only: no free text (neither the input nor the output of the
-- model), no owner key, no display name. input_hash is the SHA-256 (hex) of the input, never the input. request_id and order_id are plain
-- ids (no foreign key: a decision row must survive whatever happens to the order, and it is written by a later stage).
CREATE TABLE selfdev_llm_stop_decisions (
    decision_id   CHAR(26)     NOT NULL,                                -- ULID
    request_id    CHAR(26)     NOT NULL,
    order_id      CHAR(26)     NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    reason_code   VARCHAR(32)  NOT NULL,                                -- a fixed code, never free text
    model         VARCHAR(64)  NOT NULL,                                -- the model name
    input_hash    CHAR(64)     NOT NULL,
    mode          VARCHAR(8)   NOT NULL,
    tokens_in     INT          NOT NULL DEFAULT 0,
    tokens_out    INT          NOT NULL DEFAULT 0,
    cost_millijpy BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (decision_id),
    CONSTRAINT ck_selfdev_llm_stop_decisions_outcome CHECK (outcome IN ('APPROVED', 'ABSTAINED', 'SKIPPED', 'LOST_RACE', 'ERROR')),
    CONSTRAINT ck_selfdev_llm_stop_decisions_mode CHECK (mode IN ('shadow', 'active')),
    CONSTRAINT ck_selfdev_llm_stop_decisions_hash CHECK (CHAR_LENGTH(input_hash) = 64),
    CONSTRAINT ck_selfdev_llm_stop_decisions_nums CHECK (tokens_in >= 0 AND tokens_out >= 0 AND cost_millijpy >= 0)
);
CREATE INDEX ix_selfdev_llm_stop_decisions_created ON selfdev_llm_stop_decisions (created_at);
CREATE INDEX ix_selfdev_llm_stop_decisions_request ON selfdev_llm_stop_decisions (request_id);
