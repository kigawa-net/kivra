-- V17: the task body and the reference a self-development run was resolved from (Issue #229, addendum: task references).
-- V1 .. V16 are not edited. Expand-only: two nullable columns on development_runs, no default, no change to an existing row.
--
-- task_body: the text the coding agent receives after the task title. It is the manual body the operator submitted, and, when the title
-- is a reference (for example an Issue URL), the resolved text after a separator. It is UNTRUSTED input. It is never logged or audited.
-- task_source: the fixed reference the body came from, as `owner/repo#n`. Not a secret. NULL for a manual body, or when nothing was resolved.
-- Existing rows keep NULL in both columns, which means: the title only, as before.
ALTER TABLE development_runs ADD COLUMN task_body TEXT NULL;
ALTER TABLE development_runs ADD COLUMN task_source VARCHAR(255) NULL;
