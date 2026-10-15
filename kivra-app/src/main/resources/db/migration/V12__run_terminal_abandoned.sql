-- V12: ABANDONED (ADR 0008 item 6) is a terminal Run state, so it must not hold the one-active-run-per-task key.
-- active_flag (V1) is a derived generated column with no stored data. It is recomputed from `state` with ABANDONED added to the
-- terminal list, and the unique key is rebuilt on it. Writers never name active_flag (Tables.kt), so Writers built against V11
-- keep working. No row is deleted or rewritten, and no column type or NOT NULL changes.
ALTER TABLE development_runs DROP CONSTRAINT uk_dev_runs_active_task;
ALTER TABLE development_runs DROP COLUMN active_flag;
ALTER TABLE development_runs ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'ABANDONED') THEN NULL ELSE 1 END);
ALTER TABLE development_runs ADD CONSTRAINT uk_dev_runs_active_task UNIQUE (task_id, active_flag);
