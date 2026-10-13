-- V5: Memory Mutation Journal bindings on external_operations (Issue #91, M5 MemoryMutationJournalPort -> M0 Journal).
-- Expand-only: V1 / V2 / V3 / V4 are not edited (V3 is used by #55). No Memory canonical table is created: a memory mutation is a
-- row of external_operations (op_type MEMORY_MUTATION). Only identifiers, digests and machine codes are stored; no body or secret.

-- principal: the writing service principal (the M5 identity binds tenant + principal + provider + contract version).
ALTER TABLE external_operations ADD COLUMN principal_id      VARCHAR(128) NULL;
-- request_id: the provider request id (recl). Unique per tenant + provider, see uk_op_request below.
ALTER TABLE external_operations ADD COLUMN request_id        VARCHAR(64)  NULL;
-- expected_revision: the content revision the write was prepared against. Part of the immutable binding of an operation.
ALTER TABLE external_operations ADD COLUMN expected_revision BIGINT       NULL;
-- execution_id: the execution the write was prepared for. Part of the immutable binding of an operation.
ALTER TABLE external_operations ADD COLUMN execution_id      VARCHAR(128) NULL;
-- memory_kind: SAVE / UPDATE / SUPERSEDE / DELETE.
ALTER TABLE external_operations ADD COLUMN memory_kind       VARCHAR(16)  NULL;
-- memory_reason: machine reason code of the last memory transition (for example SWEPT_WORKER_LOST, PROVIDER_OUTCOME_UNKNOWN).
ALTER TABLE external_operations ADD COLUMN memory_reason     VARCHAR(64)  NULL;

-- A request id may be bound to one operation only, per tenant and provider. NULL (non-memory rows) never conflicts.
CREATE UNIQUE INDEX uk_op_request ON external_operations (tenant_id, external_system, request_id);
