-- V26: the cycle budget check, stage 2 PR 3b of ADR 0015 (section 2.7). Expand-only: two new columns with defaults and two CHECKs.
-- V23, V24 and V25 are not edited. Budgets are whole yen (DECIMAL(12,0), as V25).
-- spent_jpy: the cost counted against the order and its descendants. Nothing in this change writes it: the cost ledger that feeds it is
-- PR 3c, so until then it stays 0 (or is set by hand in a test). cycle_budget_jpy: the most one cycle of the order may cost (ADR 0015
-- section 3.2, 1,000 yen by default). A cycle start checks, for every order on the path to the root, that order_budget_jpy - spent_jpy
-- is at least that order's cycle_budget_jpy (the application does this under row locks).
ALTER TABLE selfdev_orders ADD COLUMN spent_jpy DECIMAL(12,0) NOT NULL DEFAULT 0;
ALTER TABLE selfdev_orders ADD COLUMN cycle_budget_jpy DECIMAL(12,0) NOT NULL DEFAULT 1000;
ALTER TABLE selfdev_orders ADD CONSTRAINT ck_selfdev_orders_spent CHECK (spent_jpy >= 0);
ALTER TABLE selfdev_orders ADD CONSTRAINT ck_selfdev_orders_cycle_budget CHECK (cycle_budget_jpy >= 0);
