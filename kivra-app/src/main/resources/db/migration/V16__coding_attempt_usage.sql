-- V16: usage of a coding attempt, reported by the local agent or estimated when no report exists (ADR 0010 addendum 4, CLI cost ledger).
-- V1 .. V15 are not edited. Expand-only: one new table, no change to an existing table.
--
-- One row per (attempt, lease generation): a second report for the same generation is never counted twice. The money itself is
-- booked once in budget_reservations under the reservation key `coding:<attempt_id>:<lease_generation>` (DbBudgetStore.recordSpend);
-- this table is the usage record next to it. source is REPORTED (the agent's usage, numbers only) or ESTIMATED_RESERVE (the
-- per-attempt reserve, charged because the cost was not reported: fail without usage, ORPHANED, EXPIRED, or a result without usage).
-- cost_usd is the USD cost as reported, or the reserve for ESTIMATED_RESERVE. Model and token columns are NULL for ESTIMATED_RESERVE.
-- Holds no prompt, output, path or secret. Amounts in yen are not stored here (budget_reservations holds them, in micro-yen).

CREATE TABLE coding_attempt_usage (
    attempt_id           CHAR(26)      NOT NULL,
    lease_generation     BIGINT        NOT NULL,
    cost_usd             DECIMAL(12,6) NOT NULL,
    model                VARCHAR(64)   NULL,
    num_turns            INT           NULL,
    input_tokens         BIGINT        NULL,
    output_tokens        BIGINT        NULL,
    cache_creation_tokens BIGINT       NULL,
    cache_read_tokens    BIGINT        NULL,
    source               ENUM('REPORTED', 'ESTIMATED_RESERVE') NOT NULL,
    reported_at          DATETIME(6)   NOT NULL,
    CONSTRAINT pk_coding_attempt_usage PRIMARY KEY (attempt_id, lease_generation),
    CONSTRAINT fk_coding_attempt_usage_attempt FOREIGN KEY (attempt_id) REFERENCES coding_attempts (attempt_id)
);
