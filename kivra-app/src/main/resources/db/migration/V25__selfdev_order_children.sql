-- V25: child orders, stage 2 PR 3a of ADR 0015 (section 2.7). Expand-only: new columns with defaults, new constraints, and nullable
-- event columns. V23 and V24 are not edited. Budgets are whole yen (DECIMAL(12,0), as ADR 0015 section 2.1).
-- order_budget_jpy: the order's budget (5,000 yen by default, so the root gets it too). allocated_jpy: the part given to its children.
-- child_count: the number of children (the limit is checked by the application). idempotency_key: the client's key for a child creation,
-- unique per parent, so a replay returns the same child. The root has no key and no parent, so the unique key does not touch it.
ALTER TABLE selfdev_orders ADD COLUMN order_budget_jpy DECIMAL(12,0) NOT NULL DEFAULT 5000;
ALTER TABLE selfdev_orders ADD COLUMN allocated_jpy DECIMAL(12,0) NOT NULL DEFAULT 0;
ALTER TABLE selfdev_orders ADD COLUMN child_count INT NOT NULL DEFAULT 0;
ALTER TABLE selfdev_orders ADD COLUMN idempotency_key VARCHAR(128) NULL;
ALTER TABLE selfdev_orders ADD CONSTRAINT ck_selfdev_orders_budget CHECK (
    order_budget_jpy >= 0 AND allocated_jpy BETWEEN 0 AND order_budget_jpy
);
ALTER TABLE selfdev_orders ADD CONSTRAINT ck_selfdev_orders_child_count CHECK (child_count >= 0);
ALTER TABLE selfdev_orders ADD CONSTRAINT uk_selfdev_orders_child_key UNIQUE (parent_order_id, idempotency_key);

-- The child creation's audit row: the parent, the depth and the allocated amount. NULL for every event that existed before.
ALTER TABLE selfdev_order_events ADD COLUMN parent_order_id CHAR(26) NULL;
ALTER TABLE selfdev_order_events ADD COLUMN depth INT NULL;
ALTER TABLE selfdev_order_events ADD COLUMN amount_jpy DECIMAL(12,0) NULL;
ALTER TABLE selfdev_order_events ADD CONSTRAINT fk_selfdev_order_events_parent FOREIGN KEY (parent_order_id) REFERENCES selfdev_orders (order_id);
