-- V52: the project scope contract (ADR 0022 PR-F). Contract step: selfdev_tasks.project_id and selfdev_memory_records.project_id become NOT NULL.
--
-- This runs only when no row of either table has a NULL project. The gate in runMigrations (Database.kt) keeps V52 pending while such a row
-- exists and logs the counts, so this statement never meets a NULL row and the Pod does not stop on one. No row is deleted or assigned here.
-- The rows are assigned by the backfill (POST /api/projects/backfill, runbook 4.16). Writers from before PR-F must be drained first: such a
-- Writer may insert a NULL project, and would then fail once the column is NOT NULL.
-- MODIFY is the one non-expand form here. MigrationExpandOnlyTest lists it with this reason.
ALTER TABLE selfdev_tasks MODIFY COLUMN project_id CHAR(26) NOT NULL;
ALTER TABLE selfdev_memory_records MODIFY COLUMN project_id CHAR(26) NOT NULL;
