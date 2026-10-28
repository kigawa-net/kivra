-- Orchestrator leader lease table (design #3 section 5.5, ADR 0008).
-- Ensures a single orchestrator leader at any time. The DB clock (NOW(6)) is the sole expiry judge.
-- Only one row exists; the orchestrator updates it with the owner and expiry on each acquisition/renewal.
CREATE TABLE orchestrator_leader_lease (
    id               INT          NOT NULL DEFAULT 0,          -- always 0; single-row table
    owner            VARCHAR(128) NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    generation       BIGINT       NOT NULL DEFAULT 0,          -- monotonic; bumps on each acquisition
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);
