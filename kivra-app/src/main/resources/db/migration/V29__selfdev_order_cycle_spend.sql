-- V29: the per-cycle spend record, ADR 0015 addendum 2026-10-23 (order cost aggregation, section C.2), stage 3f. Expand-only: one new table.
-- V1 .. V28 are not edited. Nothing writes this table yet: the tick that adds the cost to the order (stage 3g) is the first writer, so the
-- table stays empty in this change. order_id and cycle_no are the key of selfdev_goals.uk_selfdev_goals_order_cycle.
--
-- ledger_micro_yen: the ledger total of the cycle (micro-yen) as last read. counted_jpy: the whole yen already added to the ancestors
-- (ceil of the total, see section C.2). final: TRUE once the cycle can no longer change (section C.2). Amounts follow V10 and V26.
CREATE TABLE selfdev_order_cycle_spend (
    order_id          CHAR(26)     NOT NULL,                    -- selfdev_orders.order_id of the cycle
    cycle_no          INT          NOT NULL,                    -- selfdev_goals.cycle_no of the cycle
    goal_id           CHAR(26)     NOT NULL,                    -- selfdev_goals.goal_id of the cycle
    ledger_micro_yen  BIGINT       NOT NULL DEFAULT 0,          -- the ledger total of the cycle when last read (micro-yen)
    counted_jpy       DECIMAL(12,0) NOT NULL DEFAULT 0,         -- the whole yen already added to the path (section C.2)
    final             BOOLEAN      NOT NULL DEFAULT FALSE,      -- TRUE when the cycle can no longer change
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_selfdev_order_cycle_spend PRIMARY KEY (order_id, cycle_no),
    CONSTRAINT fk_selfdev_order_cycle_spend_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id),
    CONSTRAINT fk_selfdev_order_cycle_spend_goal FOREIGN KEY (goal_id) REFERENCES selfdev_goals (goal_id),
    CONSTRAINT ck_selfdev_order_cycle_spend_ledger CHECK (ledger_micro_yen >= 0),
    CONSTRAINT ck_selfdev_order_cycle_spend_counted CHECK (counted_jpy >= 0)
);
