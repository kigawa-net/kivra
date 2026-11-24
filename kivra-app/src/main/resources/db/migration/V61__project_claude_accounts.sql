-- A project without a row uses the default Claude account.
CREATE TABLE project_claude_accounts (
    project_id  CHAR(26)     NOT NULL,
    account_id  VARCHAR(63)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (project_id),
    CONSTRAINT ck_project_claude_accounts_id CHECK (
        account_id REGEXP '(?-i)^[a-z0-9]' AND account_id NOT REGEXP '(?-i)[^a-z0-9-]'
    ),
    CONSTRAINT fk_project_claude_accounts_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE RESTRICT
);
