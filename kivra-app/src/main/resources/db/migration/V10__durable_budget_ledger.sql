-- V10: durable budget ledger for the Model Gateway (Issue #109, B1). V1 .. V9 are not edited. Expand-only: new tables.
-- Amounts are BIGINT micro-yen (1 JPY = 1,000,000). The tenant scope is limited per JST calendar month (period YYYY-MM);
-- run scopes have period ALL (lifetime of the run). A reservation's periods are fixed at reserve time (see DbBudgetStore).
-- Reserved amounts are not stored on budget_periods: they are the sum of RESERVED rows, so a state change cannot drift.

CREATE TABLE budget_periods (
    scope_id             VARCHAR(128) NOT NULL,                  -- tenant scope id, or run:<id>
    period               VARCHAR(16)  NOT NULL,                  -- YYYY-MM (JST) for the tenant scope, ALL for run scopes
    hard_micro_yen       BIGINT       NOT NULL,                  -- the limit when the row was created (the configured limit governs)
    committed_micro_yen  BIGINT       NOT NULL DEFAULT 0,        -- charged spend, including lapsed reservations
    unpriced_calls       BIGINT       NOT NULL DEFAULT 0,        -- calls charged at the estimate without an established cost
    created_at           DATETIME(6)  NOT NULL,
    CONSTRAINT pk_budget_periods PRIMARY KEY (scope_id, period)
);

-- state: RESERVED, LAPSED (TTL elapsed, charged conservatively), COMMITTED, RELEASED.
CREATE TABLE budget_reservations (
    reservation_key   VARCHAR(128) NOT NULL,                     -- requestId / attemptId; unique, so a key is never reserved twice
    amount_micro_yen  BIGINT       NOT NULL,                     -- the estimate reserved in every scope of the path
    state             VARCHAR(16)  NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    settled_at        DATETIME(6)  NULL,
    CONSTRAINT pk_budget_reservations PRIMARY KEY (reservation_key)
);

CREATE INDEX ix_budget_reservations_state_expiry ON budget_reservations (state, expires_at);

-- One row per scope of a reservation's path, with the period it was charged to.
CREATE TABLE budget_reservation_scopes (
    reservation_key  VARCHAR(128) NOT NULL,
    scope_id         VARCHAR(128) NOT NULL,
    period           VARCHAR(16)  NOT NULL,
    CONSTRAINT pk_budget_reservation_scopes PRIMARY KEY (reservation_key, scope_id),
    CONSTRAINT fk_budget_reservation_scopes_key FOREIGN KEY (reservation_key) REFERENCES budget_reservations (reservation_key)
);

CREATE INDEX ix_budget_reservation_scopes_period ON budget_reservation_scopes (scope_id, period);
