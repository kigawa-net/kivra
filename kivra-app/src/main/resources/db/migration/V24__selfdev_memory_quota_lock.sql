-- V24: the quota lock of the self-development memory (ADR 0016 section 4.4, V12, Issue #277). Expand-only: one new table with one row.
-- SelfDevMemoryRepository.save takes `SELECT ... FOR UPDATE` on this row before it counts the quotas and inserts, so every writer
-- counts and inserts one at a time. A count followed by an insert, without a lock, lets two writers both pass a limit.
-- The row is only ever locked, never updated, and it holds no data.
CREATE TABLE selfdev_memory_quota_lock (
    guard_id  CHAR(16)  NOT NULL,
    CONSTRAINT pk_selfdev_memory_quota_lock PRIMARY KEY (guard_id)
);
INSERT INTO selfdev_memory_quota_lock (guard_id) VALUES ('QUOTA');
