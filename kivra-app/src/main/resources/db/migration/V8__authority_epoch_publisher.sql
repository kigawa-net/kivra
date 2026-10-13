-- V8: records who published each Authority Epoch copy and when (Issue #100). V1 .. V7 are not edited.
-- Expand-only: nullable columns, no backfill. A row written before V8 keeps NULL here and is treated as not fresh
-- (fail-closed) by AuthorityEpochRepository.requireFresh. published_by is the authenticated issuer identifier (no secret).
-- published_at is the application UTC time of the last accepted publish, which is the freshness reference.
ALTER TABLE evidence_authority_epochs ADD COLUMN published_by VARCHAR(128) NULL;
ALTER TABLE evidence_authority_epochs ADD COLUMN published_at DATETIME(6)  NULL;
