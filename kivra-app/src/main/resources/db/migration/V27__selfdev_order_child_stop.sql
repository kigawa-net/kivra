-- V27: the child stop, stage 2 PR 3d of ADR 0015 (section 4.2 (a), section 2.7). Expand-only: one new column with a default, and the
-- separation CHECK is narrowed to the root (the existing rows are all root requests, so they keep the same rule). V23 to V26 are not edited.
-- target_is_root: 1 for a request to stop the root (the default, so every row that exists is a root request), 0 for a child. A root
-- request keeps the two-principal rule: the requester and the decider must be different owner keys. A child request is decided by its
-- direct parent, which is the root approver (a human), so the requester may be the decider (ADR 0015 section 4.2 (a)).
ALTER TABLE selfdev_order_stop_requests ADD COLUMN target_is_root TINYINT NOT NULL DEFAULT 1;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_target CHECK (target_is_root IN (0, 1));

-- Replaces the V23 separation CHECK (the same name, now only for the root). The root rule is the same as before: a root request's
-- decided_by is never its requested_by. The database enforces it, whatever the application checks.
ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT ck_selfdev_order_stop_separation;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_separation CHECK (
    target_is_root = 0 OR decided_by IS NULL OR decided_by <> requested_by
);
