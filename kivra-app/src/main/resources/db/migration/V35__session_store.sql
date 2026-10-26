-- V35: Server-side session store for OIDC login session persistence.
-- Sessions are keyed by a random session ID stored in an HttpOnly cookie.
-- Times are UTC DATETIME(6).
CREATE TABLE session_store (
    session_id   CHAR(32)    NOT NULL,                                  -- random hex (16 bytes)
    principal    VARCHAR(255) NOT NULL,                                 -- OIDC owner key (iss:sub)
    created_at   DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT ck_session_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_session_expires ON session_store (expires_at);
