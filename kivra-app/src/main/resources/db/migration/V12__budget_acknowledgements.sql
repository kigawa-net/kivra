-- V12: operator acknowledgements of unpriced budget reservations (ADR 0003, Issue #109). V1 .. V11 are not edited.
-- Expand-only: one new table, no change to an existing table, column, constraint or index.
-- Append-only by design: the application only INSERTs here. One row per reservation (the primary key), so a reservation
-- is released at most once. The row is the audit record: who (operator_id), why (reason), on what evidence (evidence), and
-- the amounts before and after. Evidence is kept here and never written to logs.
-- Amounts are BIGINT micro-yen (1 JPY = 1,000,000), as in V10.

CREATE TABLE budget_acknowledgements (
    reservation_key            VARCHAR(128) NOT NULL,              -- budget_reservations.reservation_key that was released
    scope_id                   VARCHAR(128) NOT NULL,              -- the scope the operator named (on the reservation path)
    period                     VARCHAR(16)  NOT NULL,              -- the period the reservation was charged to (JST month, or ALL)
    operator_id                VARCHAR(256) NOT NULL,              -- from the authenticated caller, never from the request body
    reason                     VARCHAR(500) NOT NULL,
    evidence                   VARCHAR(500) NOT NULL,              -- how the cost was checked; audit only, never logged
    charged_before_micro_yen   BIGINT       NOT NULL,              -- the amount charged to the scopes before this release
    actual_micro_yen           BIGINT       NOT NULL,              -- the amount charged after this release (= before when actual is unknown)
    actual_known               BOOLEAN      NOT NULL,              -- false: the charged estimate was kept as the settled cost
    acknowledged_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_budget_acknowledgements PRIMARY KEY (reservation_key),
    CONSTRAINT fk_budget_acknowledgements_key FOREIGN KEY (reservation_key) REFERENCES budget_reservations (reservation_key)
);

CREATE INDEX ix_budget_acknowledgements_scope_period ON budget_acknowledgements (scope_id, period);
CREATE INDEX ix_budget_acknowledgements_operator ON budget_acknowledgements (operator_id, acknowledged_at);
