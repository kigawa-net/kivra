-- V7: binds a PR_ONLY run to the pull request identity that a verified GitHub merge must match (Issue #53).
-- V1 .. V6 are not edited. Expand-only: nullable columns, no backfill. A row written before V7 keeps NULL here and is
-- never completable (fail-closed), because its identity was never recorded.
-- pr_repository / pr_head_sha (V2) remain the repository full name and the head SHA of the latest bind.
ALTER TABLE development_runs ADD COLUMN pr_number                 BIGINT       NULL;   -- GitHub PR number
ALTER TABLE development_runs ADD COLUMN pr_node_id                VARCHAR(255) NULL;   -- GitHub GraphQL node id of the PR
ALTER TABLE development_runs ADD COLUMN pr_repository_id          BIGINT       NULL;   -- GitHub immutable repository id (base repo)
ALTER TABLE development_runs ADD COLUMN pr_base_ref               VARCHAR(255) NULL;   -- base branch name at bind time
ALTER TABLE development_runs ADD COLUMN pr_policy_revision        VARCHAR(255) NULL;   -- policy revision supplied at bind (#50)
ALTER TABLE development_runs ADD COLUMN pr_review_manifest_digest VARCHAR(255) NULL;   -- review manifest digest supplied at bind (#49)
