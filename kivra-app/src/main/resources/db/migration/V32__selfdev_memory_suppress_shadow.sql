-- V32: the suppression shadow of the self-development memory (ADR 0016 section 11, P3). Expand-only: one more kind in the event table's
-- CHECK. V1 .. V31 are not edited.
--
-- The kind SHADOW_SUPPRESS records how many tasks of a stored plan a deterministic rule WOULD have refused (code = the rule's name,
-- count_value = the number of tasks). It changes no plan, refuses nothing and retries nothing. It holds no text, as the other kinds.
ALTER TABLE selfdev_memory_events DROP CONSTRAINT ck_selfdev_memory_events_kind;
ALTER TABLE selfdev_memory_events ADD CONSTRAINT ck_selfdev_memory_events_kind CHECK (kind IN (
    'WRITTEN', 'REJECTED', 'DUPLICATE', 'READ_SKIPPED', 'DELETED', 'PURGED', 'READ_FAILED', 'SHADOW_SUPPRESS'));
