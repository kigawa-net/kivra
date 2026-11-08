-- V45: the record of the AI review of a self-development pull request (ADR 0008 addendum, PR review). Expand only: one new table,
-- nothing existing is changed. Nothing else reads it except the review and its shadow reference.
--
-- One row per (pull request, head SHA). The primary key is the idempotency key: the row is inserted (claimed) before the model is called
-- and before the comment is posted, so the same head is reviewed at most once, across restarts and Pods. A row that stays CLAIMED
-- (a crash in between) is never reviewed again. Fixed fields only: no diff, no title, no body, no model output.
CREATE TABLE selfdev_pr_reviews (
    repo_key       VARCHAR(200) NOT NULL,                       -- owner/name, as the run was bound
    pr_number      BIGINT       NOT NULL,
    head_sha       CHAR(40)     NOT NULL,
    run_id         VARCHAR(64)  NOT NULL,
    state          VARCHAR(16)  NOT NULL,                       -- CLAIMED, POSTED, FAILED, UNKNOWN, INVALID, SKIPPED
    verdict        VARCHAR(16)  NULL,                           -- PASS or CONCERNS, when the model answered
    finding_count  INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (repo_key, pr_number, head_sha),
    CONSTRAINT ck_selfdev_pr_reviews_state CHECK (state IN ('CLAIMED', 'POSTED', 'FAILED', 'UNKNOWN', 'INVALID', 'SKIPPED')),
    CONSTRAINT ck_selfdev_pr_reviews_verdict CHECK (verdict IS NULL OR verdict IN ('PASS', 'CONCERNS')),
    CONSTRAINT ck_selfdev_pr_reviews_count CHECK (finding_count >= 0 AND finding_count <= 10)
);
CREATE INDEX ix_selfdev_pr_reviews_created ON selfdev_pr_reviews (created_at);
