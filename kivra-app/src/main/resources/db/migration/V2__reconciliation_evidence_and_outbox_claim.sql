-- V2: fail-closed reconciliation evidence for UNKNOWN external operations, Outbox publisher claims with a claim token,
-- and the immutable RunCompletionMode with PR_ONLY completion evidence (M8).
-- V2 is unmerged and not applied anywhere; it is edited in place. Once merged, add V3+ instead of editing it (Flyway checksum).

-- An external_operations row may leave UNKNOWN / RECONCILIATION_REQUIRED only together with the observed external
-- state (or an IN_FLIGHT row with a NotSentProof / RejectedProof). The last such observation is persisted on the row.
ALTER TABLE external_operations ADD COLUMN evidence_observation VARCHAR(16) NULL;      -- EXISTS / ABSENT / FAILED_CONFIRMED (NULL for proofs)
ALTER TABLE external_operations ADD COLUMN evidence_ref         VARCHAR(255) NULL;
ALTER TABLE external_operations ADD COLUMN evidence_observed_at DATETIME(6)  NULL;
ALTER TABLE external_operations ADD COLUMN evidence_issuer      VARCHAR(64)  NULL;     -- trusted issuer that produced the evidence / proof
ALTER TABLE external_operations ADD COLUMN provider_capability  VARCHAR(32)  NULL;     -- LOOKUP_BY_STABLE_REF / AUTHORITATIVE_IDEMPOTENCY / AUTHORITATIVE_ABSENCE / MANUAL_OPERATOR
ALTER TABLE external_operations ADD COLUMN transition_reason    VARCHAR(64)  NULL;     -- e.g. RECONCILED_EXISTS, PROOF_NOT_SENT, MAX_ATTEMPTS_EXCEEDED, SWEPT_STALE_IN_FLIGHT

-- outbox_events claim (lease): a publisher owns an event until claim_expires_at (DB clock). Each claim gets a fresh
-- claim_token, so a stalled attempt of the SAME publisher_id cannot ack or nack a later claim (ABA). Delivery stays
-- at-least-once: an expired claim lets another publisher deliver again, so consumers dedupe on event_id.
ALTER TABLE outbox_events ADD COLUMN claimed_by       VARCHAR(64) NULL;
ALTER TABLE outbox_events ADD COLUMN claim_expires_at DATETIME(6) NULL;
ALTER TABLE outbox_events ADD COLUMN claim_token      VARCHAR(36) NULL;

-- development_runs: completion mode is chosen at Run Admission and never updated (no SQL path writes it).
-- PR_ONLY completion stores the verified GitHub merge result; the PR head is bound while the run is in PULL_REQUEST.
ALTER TABLE development_runs ADD COLUMN completion_mode         VARCHAR(16)  NOT NULL;   -- PR_ONLY / RELEASE
ALTER TABLE development_runs ADD COLUMN pr_repository           VARCHAR(255) NULL;
ALTER TABLE development_runs ADD COLUMN pr_head_sha             VARCHAR(64)  NULL;
ALTER TABLE development_runs ADD COLUMN completion_merge_sha    VARCHAR(64)  NULL;
ALTER TABLE development_runs ADD COLUMN completion_evidence_ref VARCHAR(255) NULL;
ALTER TABLE development_runs ADD COLUMN completion_observed_at  DATETIME(6)  NULL;
