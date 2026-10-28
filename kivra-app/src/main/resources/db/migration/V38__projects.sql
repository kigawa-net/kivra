-- V38: projects table and project_id on development_runs. Allows grouping Runs by project.
-- The migration is the source of truth for DDL. project_id is nullable so that existing Runs
-- have no project (unassigned). A deleted project does not cascade: its Runs become unassigned.

CREATE TABLE projects (
    project_id   CHAR(26)     NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512) NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (project_id)
);

-- development_runs gets an optional project_id. FK with SET NULL so that deleting a project
-- unassigns its Runs instead of failing.
ALTER TABLE development_runs ADD COLUMN project_id CHAR(26) NULL;
ALTER TABLE development_runs ADD CONSTRAINT fk_runs_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE SET NULL;
CREATE INDEX ix_dev_runs_project ON development_runs (project_id);
