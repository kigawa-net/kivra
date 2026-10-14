-- V11: TaskBinding for Task Intake & Run Admission (Issue #26). V1 .. V10 are not edited. Expand-only: a new table only.
-- One row per (provider, environment, task id). active_run_id points at the run admitted for the current generation; it stays
-- set after the run finishes (so a resend is answered AlreadyTerminal) and is cleared only by an explicit rerun (generation + 1).
-- observed_hash / observed_updated_at record the newest snapshot seen; they are never moved backwards.
-- The binding row and the run it points at are written in one transaction (DbRunAdmission).

CREATE TABLE task_bindings (
    provider            VARCHAR(32)  NOT NULL,
    environment         VARCHAR(64)  NOT NULL,
    task_id             VARCHAR(128) NOT NULL,
    active_run_id       CHAR(26)     NULL,
    generation          INT          NOT NULL DEFAULT 1,
    observed_hash       VARCHAR(128) NULL,
    observed_updated_at DATETIME(6)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_task_bindings PRIMARY KEY (provider, environment, task_id),
    CONSTRAINT fk_task_bindings_run FOREIGN KEY (active_run_id) REFERENCES development_runs (run_id)
);
