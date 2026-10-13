-- V6: fences Trusted Evidence against the Authority Epoch inside the Operation Journal CAS (Issue #94).
-- V5 is reserved by PR #92 and is not created here; V1 / V2 / V4 are not edited. Expand-only: new table and nullable columns.

-- Kivra MariaDB copy of the Authority's current epoch for one (tenant, provider). The copy is the source of truth that the
-- Journal compares against inside the same transaction as the status CAS. It is written by the Authority sync (not part
-- of this slice); the Journal only reads it, locking the row (SELECT ... FOR UPDATE) so that an epoch change and a
-- transition are serialized on this row. This is NOT a cross-store atomic guarantee: an external Grant that changed
-- after the last sync is not seen here (see AuthorityEpochRepository KDoc).
CREATE TABLE evidence_authority_epochs (
    tenant_id                  VARCHAR(64)  NOT NULL,
    provider_id                VARCHAR(16)  NOT NULL,            -- same domain as external_operations.external_system
    provider_contract_version  VARCHAR(64)  NOT NULL,
    capability                 VARCHAR(64)  NOT NULL,            -- ProviderCapability name
    authority_epoch            BIGINT       NOT NULL,            -- bumped on any authority-wide change
    capability_revision        BIGINT       NOT NULL,            -- bumped when the capability or contract version changes
    grant_generation           BIGINT       NOT NULL,            -- bumped on Provider Grant revocation
    updated_at                 DATETIME(6)  NOT NULL,
    CONSTRAINT pk_evidence_authority_epochs PRIMARY KEY (tenant_id, provider_id)
);

-- Audit of the epoch values the last evidence-gated transition was checked against. The evidence_content_digest is the
-- Authority-bound digest of the evidence content (not a credential or payload).
ALTER TABLE external_operations ADD COLUMN authority_epoch         BIGINT    NULL;
ALTER TABLE external_operations ADD COLUMN capability_revision     BIGINT    NULL;
ALTER TABLE external_operations ADD COLUMN grant_generation        BIGINT    NULL;
ALTER TABLE external_operations ADD COLUMN evidence_content_digest CHAR(64)  NULL;
