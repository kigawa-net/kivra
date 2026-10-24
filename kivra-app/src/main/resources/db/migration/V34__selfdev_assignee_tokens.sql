-- V34: API tokens of assignees (the AI and VIEWER roles; Keycloak is not involved). Expand-only: one new table, one nullable column and
-- two more kinds in the event table's CHECK. V1 .. V33 are not edited. Nothing calls the repository yet, so this migration alone changes no
-- behaviour.
-- The plaintext token (`kvt_` + 43 base64url characters) is never stored: token_hash is the lowercase hex SHA-256 of it (lookup by this
-- UNIQUE index), token_ref is the first 16 hex digits of the hash (safe to show and log; it is not the token). Times are UTC DATETIME(6).
-- created_by and revoked_by are actor_ref (12 hex, the first digits of the SHA-256 of the acting owner key), never the key.
CREATE TABLE selfdev_assignee_tokens (
    token_id      CHAR(26)    NOT NULL,                                 -- ULID
    assignee_id   CHAR(26)    NOT NULL,
    token_hash    CHAR(64)    NOT NULL,
    token_ref     CHAR(16)    NOT NULL,
    created_by    VARCHAR(12) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    revoked_at    DATETIME(6) NULL,
    revoked_by    VARCHAR(12) NULL,
    PRIMARY KEY (token_id),
    CONSTRAINT uk_selfdev_assignee_tokens_hash UNIQUE (token_hash),
    CONSTRAINT uk_selfdev_assignee_tokens_ref UNIQUE (token_ref),
    CONSTRAINT fk_selfdev_assignee_tokens_assignee FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id),
    CONSTRAINT ck_selfdev_assignee_tokens_hash CHECK (CHAR_LENGTH(token_hash) = 64),
    CONSTRAINT ck_selfdev_assignee_tokens_ref CHECK (CHAR_LENGTH(token_ref) = 16),
    CONSTRAINT ck_selfdev_assignee_tokens_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_selfdev_assignee_tokens_assignee ON selfdev_assignee_tokens (assignee_id, revoked_at, expires_at);

-- The token events carry the token_ref (never the token or its hash).
ALTER TABLE selfdev_assignee_events ADD COLUMN token_ref CHAR(16) NULL;

ALTER TABLE selfdev_assignee_events DROP CONSTRAINT ck_selfdev_assignee_events_kind;
ALTER TABLE selfdev_assignee_events ADD CONSTRAINT ck_selfdev_assignee_events_kind CHECK (
    kind IN ('CREATED', 'ROLE_CHANGED', 'DISABLED', 'ENABLED', 'MEMBER_BOUND', 'MEMBER_UNBOUND', 'TOKEN_ISSUED', 'TOKEN_REVOKED')
);
