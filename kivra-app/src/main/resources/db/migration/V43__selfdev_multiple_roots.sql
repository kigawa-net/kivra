-- V43: allow multiple root orders. The root_slot UNIQUE constraint enforced a single root (ADR 0015 stage 1). Removing it lets the
-- operator create independent root orders, each with its own tree. The root is now identified by parent_order_id IS NULL and depth = 0,
-- the same invariant the CHECK constraint already enforces. root_slot is kept as a legacy column (NULL for every row) and will be
-- dropped in a future migration after the application code no longer references it.

-- Step 1: Remove the UNIQUE constraint that blocks a second root.
ALTER TABLE selfdev_orders DROP CONSTRAINT uk_selfdev_orders_root;

-- Step 2: Clear the legacy root_slot values. Every root now has NULL instead of 1.
UPDATE selfdev_orders SET root_slot = NULL WHERE root_slot = 1;
