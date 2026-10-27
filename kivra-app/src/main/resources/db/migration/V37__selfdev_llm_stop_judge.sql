-- V37: the shadow judgement of the LLM stop approval (ADR 0021, stage L4). Expand-only: one CHECK widened, two new tables. V1 .. V36 are not
-- edited. The tables are read and written only while KIVRA_SELFDEV_LLM_STOP_APPROVAL_ENABLED is true, so this migration alone changes no
-- behaviour.

-- A shadow APPROVE is recorded as WOULD_APPROVE ("would have approved"): the shadow never approves, so APPROVED stays for L5. The CHECK
-- accepts every value it did and adds WOULD_APPROVE (same DROP + ADD form as V34 and V35, which MariaDB and H2 both run). An old Writer
-- never writes WOULD_APPROVE, so its rows pass the new CHECK as they passed the old one.
ALTER TABLE selfdev_llm_stop_decisions DROP CONSTRAINT ck_selfdev_llm_stop_decisions_outcome;
ALTER TABLE selfdev_llm_stop_decisions ADD CONSTRAINT ck_selfdev_llm_stop_decisions_outcome CHECK (
    outcome IN ('APPROVED', 'ABSTAINED', 'SKIPPED', 'LOST_RACE', 'ERROR', 'WOULD_APPROVE')
);

-- selfdev_llm_stop_claims: at most one judgement of a request per mode, across Pods and restarts. The primary key is the guard: the job
-- INSERTs the claim before it calls the model, and only the one that inserted goes on. Fixed fields only (an id and a mode).
CREATE TABLE selfdev_llm_stop_claims (
    request_id CHAR(26)    NOT NULL,
    mode       VARCHAR(8)  NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id, mode),
    CONSTRAINT ck_selfdev_llm_stop_claims_mode CHECK (mode IN ('shadow', 'active'))
);
CREATE INDEX ix_selfdev_llm_stop_claims_claimed ON selfdev_llm_stop_claims (claimed_at);

-- selfdev_llm_stop_limits: the counters of the limits (ADR 0021 P6). One row per counter and bucket (an hour or a month key). The store adds
-- with one conditional UPDATE (amount + ? <= max), so Pods together never pass a limit.
CREATE TABLE selfdev_llm_stop_limits (
    counter_name VARCHAR(16) NOT NULL,
    bucket_key   BIGINT      NOT NULL,
    amount       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (counter_name, bucket_key),
    CONSTRAINT ck_selfdev_llm_stop_limits_amount CHECK (amount >= 0)
);
