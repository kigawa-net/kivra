-- V42: project_id on selfdev_orders and selfdev_assignees. Allows managing orders and assignees per project.
-- The migration is the source of truth for DDL. project_id is nullable so that existing rows
-- have no project (unassigned). A deleted project does not cascade: its rows become unassigned.

ALTER TABLE selfdev_orders ADD COLUMN project_id CHAR(26) NULL;
ALTER TABLE selfdev_orders ADD CONSTRAINT fk_orders_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE SET NULL;
CREATE INDEX ix_selfdev_orders_project ON selfdev_orders (project_id);

ALTER TABLE selfdev_assignees ADD COLUMN project_id CHAR(26) NULL;
ALTER TABLE selfdev_assignees ADD CONSTRAINT fk_assignees_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE SET NULL;
CREATE INDEX ix_selfdev_assignees_project ON selfdev_assignees (project_id);
