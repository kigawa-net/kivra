-- V4: binds Operation Journal evidence to the Trusted Evidence Authority (Issue #82).
-- V1 / V2 / V3 are not edited: V3 is used by another open PR, so this migration is the next version on this branch.
-- Evidence is persisted as audit data only: the evidence ref (opaque id), request digest, authority principal, purpose,
-- capability and expiry. No credential, token or raw payload is stored.

-- The subject an evidence must be bound to (EvidenceSubject): the tenant and the provider contract version are fixed at
-- prepare. NULL on rows written before V4: such rows have no subject and cannot settle through evidence (fail-closed).
ALTER TABLE external_operations ADD COLUMN tenant_id                VARCHAR(64) NULL;
ALTER TABLE external_operations ADD COLUMN provider_contract_version VARCHAR(64) NULL;

-- Audit of the verified evidence that justified the last evidence-gated transition. evidence_ref holds the
-- VerifiedEvidenceRef (Evidence ID), evidence_issuer the authority principal, provider_capability the verified capability.
ALTER TABLE external_operations ADD COLUMN evidence_purpose    VARCHAR(32) NULL;   -- NOT_SENT / REJECTED / RECONCILE_*
ALTER TABLE external_operations ADD COLUMN evidence_digest     CHAR(64)    NULL;   -- request digest the evidence was bound to
ALTER TABLE external_operations ADD COLUMN evidence_expires_at DATETIME(6) NULL;
