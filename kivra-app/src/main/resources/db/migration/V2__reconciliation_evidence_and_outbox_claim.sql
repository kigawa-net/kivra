-- V2: fail-closed reconciliation evidence for UNKNOWN external operations, and Outbox publisher claims.

-- An external_operations row may leave UNKNOWN / RECONCILIATION_REQUIRED only together with the observed external
-- state. The last such observation is persisted on the row as the transition reason (design #11 section 4.1).
ALTER TABLE external_operations ADD COLUMN evidence_observation VARCHAR(16) NULL;      -- EXISTS / ABSENT / FAILED_CONFIRMED
ALTER TABLE external_operations ADD COLUMN evidence_ref         VARCHAR(255) NULL;
ALTER TABLE external_operations ADD COLUMN evidence_observed_at DATETIME(6)  NULL;
ALTER TABLE external_operations ADD COLUMN provider_capability  VARCHAR(32)  NULL;     -- LOOKUP_BY_STABLE_REF / AUTHORITATIVE_IDEMPOTENCY / MANUAL_OPERATOR
ALTER TABLE external_operations ADD COLUMN transition_reason    VARCHAR(64)  NULL;     -- e.g. RECONCILED_EXISTS

-- outbox_events claim (lease): a publisher owns an event until claim_expires_at (DB clock). Delivery stays
-- at-least-once: an expired claim lets another publisher deliver again, so consumers dedupe on event_id.
ALTER TABLE outbox_events ADD COLUMN claimed_by       VARCHAR(64) NULL;
ALTER TABLE outbox_events ADD COLUMN claim_expires_at DATETIME(6) NULL;
