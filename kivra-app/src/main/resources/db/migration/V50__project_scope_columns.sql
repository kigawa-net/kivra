-- V50: project_id on selfdev_tasks and selfdev_memory_records (the first step of "every entity belongs to a project", ADR 0022).
-- Expand only: two nullable columns, two indexes and two foreign keys. Nothing is backfilled and no existing INSERT changes: a row written
-- before this migration (or by a Writer that does not know the column) keeps project_id NULL, which means "not assigned yet".
-- The backfill from the project_repos mapping is a later change (PR-B). There is no default project, so no is_default column.
--
-- The foreign keys use ON DELETE RESTRICT: a project that still has tasks or memory cannot be deleted, so no row is ever silently
-- unassigned. development_runs, selfdev_orders and selfdev_assignees already carry project_id (V38, V42) with SET NULL; that is left as is.
ALTER TABLE selfdev_tasks ADD COLUMN project_id CHAR(26) NULL;
ALTER TABLE selfdev_tasks ADD CONSTRAINT fk_selfdev_tasks_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT;
CREATE INDEX ix_selfdev_tasks_project ON selfdev_tasks (project_id);

ALTER TABLE selfdev_memory_records ADD COLUMN project_id CHAR(26) NULL;
ALTER TABLE selfdev_memory_records ADD CONSTRAINT fk_selfdev_memory_records_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT;
CREATE INDEX ix_selfdev_memory_records_project ON selfdev_memory_records (project_id);
