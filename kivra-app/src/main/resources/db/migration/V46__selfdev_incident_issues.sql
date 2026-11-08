-- V46: the record of the incident issues the self-development failure detector files (issue #225, the first stage of self-healing).
-- Expand only: one new table, nothing existing is changed. Nothing else reads it except the detector.
--
-- One row per fingerprint (the SHA-256 of the fixed codes and the repository). The primary key is the idempotency key: the row is
-- claimed before the issue is created, so one cause has at most one open report at a time. A row is claimed again only after its
-- window has passed (a compare-and-set on created_at), so a recurring cause is reported once per window. Fixed fields only.
CREATE TABLE selfdev_incident_issues (
    fingerprint    CHAR(64)     NOT NULL,                       -- SHA-256 hex of (incident code, reason code, repository)
    incident_code  VARCHAR(32)  NOT NULL,                       -- RUN_FAILED, RUN_UNKNOWN or BREAKER_OPEN
    reason_code    VARCHAR(64)  NOT NULL,                       -- the fixed reason code of the Runs, or NONE / OTHER
    state          VARCHAR(16)  NOT NULL,                       -- CLAIMED, OPENED, FAILED, UNKNOWN
    issue_number   BIGINT       NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (fingerprint),
    CONSTRAINT ck_selfdev_incident_issues_code CHECK (incident_code IN ('RUN_FAILED', 'RUN_UNKNOWN', 'BREAKER_OPEN')),
    CONSTRAINT ck_selfdev_incident_issues_state CHECK (state IN ('CLAIMED', 'OPENED', 'FAILED', 'UNKNOWN'))
);
CREATE INDEX ix_selfdev_incident_issues_created ON selfdev_incident_issues (created_at);
