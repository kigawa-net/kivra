-- V44: link the tasks a Planner writes to the task it planned (ADR 0011, Task model). Expand only: a nullable column and an index.
-- A plan root (the task the operator submitted) keeps parent_task_id NULL; each planned task names its root here. Dispatch and
-- the stop of a root read this link. Nothing else changes.
ALTER TABLE selfdev_tasks ADD COLUMN parent_task_id CHAR(26) NULL;
CREATE INDEX ix_selfdev_tasks_parent ON selfdev_tasks (parent_task_id);
