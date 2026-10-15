-- V13: durable requestId ledger for the Model Gateway (ADR 0008 step 6a, Issue #56). V1 .. V12 are not edited.
-- Expand-only: one new table, no change to an existing table, column, constraint or index.
-- One row per requestId, never deleted: a COMPLETED or UNKNOWN requestId stays sealed for the life of the store.
-- state mirrors the in-memory ledger: RESERVED, SENDING, RELEASED, COMPLETED, UNKNOWN (see DbRequestLedger).
-- digest is the SHA-256 hex of the request fingerprint (RequestDigest); the request body is never stored.
-- response_json holds the completed response (at most 256 KB of UTF-8). Above that, or for a streamed call, it is NULL
-- and response_replayable is FALSE: the requestId is still sealed, but the response cannot be replayed.

CREATE TABLE request_ledger (
    request_id           VARCHAR(128) NOT NULL,
    digest               CHAR(64)     NOT NULL,
    state                VARCHAR(16)  NOT NULL,
    response_json        MEDIUMTEXT   NULL,
    response_replayable  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT pk_request_ledger PRIMARY KEY (request_id)
);

CREATE INDEX ix_request_ledger_state_updated ON request_ledger (state, updated_at);
