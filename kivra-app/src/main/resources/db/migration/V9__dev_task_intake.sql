-- V9: API-submitted Tasks (Issue #112). V1 .. V8 are not edited.
-- Expand-only: nullable columns, no backfill. Runs created before V9 keep NULL and are never returned by the Task API,
-- because the API filters on principal.
-- principal is the authenticated identity (email, or user when no email) from the oauth2-proxy headers.
-- (principal, idempotency_key) is UNIQUE: concurrent identical submissions converge on one row. MariaDB and H2 allow
-- several NULL rows in a unique index, so pre-V9 runs do not conflict.
ALTER TABLE development_runs ADD COLUMN task_title VARCHAR(200) NULL;
ALTER TABLE development_runs ADD COLUMN principal VARCHAR(256) NULL;
ALTER TABLE development_runs ADD COLUMN idempotency_key VARCHAR(128) NULL;
CREATE UNIQUE INDEX uk_dev_runs_principal_idem ON development_runs (principal, idempotency_key);
