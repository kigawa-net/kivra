-- M3 Approval Gate (#33). V1/V2 are unchanged.
-- One row per approval request, bound to one exact operation digest (design #1 section 6.3).
-- Lifecycle: PENDING -> APPROVED -> CONSUMED (once) ; PENDING/APPROVED -> REJECTED / EXPIRED.
-- Every transition is a conditional UPDATE on (status, expires_at), so consumption is atomic and single-use.
CREATE TABLE approvals (
    approval_id        CHAR(26)     NOT NULL,
    run_id             CHAR(26)     NOT NULL,
    requester_agent_id VARCHAR(64)  NOT NULL,
    digest             CHAR(64)     NOT NULL,           -- SHA-256 hex of the OperationDigest
    action             VARCHAR(64)  NOT NULL,
    resource           VARCHAR(255) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    requested_at       DATETIME(6)  NOT NULL,
    expires_at         DATETIME(6)  NOT NULL,           -- compared against the DB clock only
    decided_by         VARCHAR(128) NULL,               -- human approver / rejecter subject
    decided_at         DATETIME(6)  NULL,
    decision_reason    VARCHAR(255) NULL,
    consumed_at        DATETIME(6)  NULL,
    PRIMARY KEY (approval_id),
    CONSTRAINT fk_approval_run FOREIGN KEY (run_id) REFERENCES development_runs (run_id),
    -- four-eyes also enforced by the schema: the decider is never the requesting agent
    CONSTRAINT ck_approval_four_eyes CHECK (decided_by IS NULL OR decided_by <> requester_agent_id)
);

CREATE INDEX idx_approval_run_digest ON approvals (run_id, digest, status);

-- Orphan-intent detection looks up the terminal record of a decision by decision_id.
CREATE INDEX idx_audit_decision ON audit_events (decision_id);
