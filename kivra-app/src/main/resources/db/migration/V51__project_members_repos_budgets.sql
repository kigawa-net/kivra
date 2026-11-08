-- V51: the project scope tables (ADR 0022). Expand only: three new tables, nothing existing is changed.
--
-- project_members: which assignee works in which project, and with which role. The roles are the same set as selfdev_assignees.role
-- (V33). Nothing reads this table yet, so this migration alone changes no behaviour.
CREATE TABLE project_members (
    project_id    CHAR(26)     NOT NULL,
    assignee_id   CHAR(26)     NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (project_id, assignee_id),
    CONSTRAINT ck_project_members_role CHECK (role IN ('ADMIN', 'OPERATOR', 'APPROVER', 'AI', 'VIEWER')),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_members_assignee FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id)
);
CREATE INDEX ix_project_members_assignee ON project_members (assignee_id);

-- project_repos: the repositories a project owns (repo_key is owner/name, as in selfdev_tasks.target_repo). A repository belongs to one
-- project at most (UNIQUE on repo_key). PR-B uses this mapping to assign existing rows; a row whose repo maps to no project stays NULL.
CREATE TABLE project_repos (
    project_id    CHAR(26)     NOT NULL,
    repo_key      VARCHAR(200) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (project_id, repo_key),
    CONSTRAINT uk_project_repos_repo UNIQUE (repo_key),
    CONSTRAINT fk_project_repos_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT
);

-- project_budgets: the monthly budget of a project in JPY (DECIMAL(12,0), as selfdev_orders.order_budget_jpy). soft_ratio is the share of
-- the limit at which a warning is shown (0 to 1). One row per project; a project without a row has no limit.
CREATE TABLE project_budgets (
    project_id         CHAR(26)      NOT NULL,
    monthly_limit_jpy  DECIMAL(12,0) NOT NULL,
    soft_ratio         DECIMAL(4,3)  NOT NULL DEFAULT 0.800,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (project_id),
    CONSTRAINT ck_project_budgets_limit CHECK (monthly_limit_jpy >= 0),
    CONSTRAINT ck_project_budgets_ratio CHECK (soft_ratio BETWEEN 0 AND 1),
    CONSTRAINT fk_project_budgets_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT
);
