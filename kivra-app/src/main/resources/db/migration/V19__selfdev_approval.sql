-- V19: who approved, and when, a goal's Plan and a task (ADR 0011 sections 1 and 4; Issue #233 PR D).
-- Expand-only: nullable columns on the two V18 tables. Existing rows keep NULL (nothing was approved through the operator API).
-- approved_by is the authenticated operator's owner key (the same value as created_by), never a token or a claim value.
-- approved_at is UTC, DATETIME(6), like the other autopilot timestamps.

ALTER TABLE selfdev_goals ADD COLUMN approved_by VARCHAR(255) NULL;
ALTER TABLE selfdev_goals ADD COLUMN approved_at DATETIME(6) NULL;

ALTER TABLE selfdev_plan_tasks ADD COLUMN approved_by VARCHAR(255) NULL;
ALTER TABLE selfdev_plan_tasks ADD COLUMN approved_at DATETIME(6) NULL;
