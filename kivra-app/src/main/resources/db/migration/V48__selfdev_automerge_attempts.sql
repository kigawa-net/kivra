-- V48: the attempts of the automatic merge of a self-development pull request (ADR 0014, active addendum). Expand only: one new table,
-- nothing existing is changed.
--
-- One row per (pull request, head SHA). The primary key is the idempotency key: a head is attempted at most once, whatever its outcome, across
-- restarts and Pods. The row is claimed before the merge request is sent, so a crash in between leaves CLAIMED behind, and that is never
-- retried (the watch halts on it).
--
-- active_slot is the single slot: 'A' while an attempt is CLAIMED, NULL otherwise. The unique index lets one row hold 'A' at a time (NULL in
-- every other row; MySQL and H2 allow many NULLs). So two merges cannot be in flight at once, even across Pods (ADR 0014 C9).
-- Fixed fields only: no title, no body, no path, no content.
CREATE TABLE selfdev_automerge_attempts (
    repo_key      VARCHAR(200) NOT NULL,                       -- owner/name, as the run was bound
    pr_number     BIGINT       NOT NULL,
    head_sha      CHAR(40)     NOT NULL,
    run_id        VARCHAR(64)  NOT NULL,
    state         VARCHAR(16)  NOT NULL,                       -- CLAIMED, MERGED, REFUSED, UNKNOWN
    active_slot   CHAR(1)      NULL,                           -- 'A' while CLAIMED, NULL otherwise
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (repo_key, pr_number, head_sha),
    CONSTRAINT uk_selfdev_automerge_slot UNIQUE (active_slot),
    CONSTRAINT ck_selfdev_automerge_state CHECK (state IN ('CLAIMED', 'MERGED', 'REFUSED', 'UNKNOWN')),
    CONSTRAINT ck_selfdev_automerge_slot CHECK (active_slot IS NULL OR active_slot = 'A')
);
CREATE INDEX ix_selfdev_automerge_created ON selfdev_automerge_attempts (created_at);
