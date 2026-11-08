-- V48: an order's cycle is a root Task (selfdev_tasks), not a goal (V40 removed selfdev_goals). Expand only: two nullable columns, an index
-- and a foreign key from a new column. Nothing is backfilled: no order cycle existed on the Task model before this change.
--
-- A cycle is one Task whose order_id is the order and whose cycle_no is the cycle's number. Its parent_task_id stays NULL: it is a plan
-- root (V44), the Planner plans it, and its planned tasks carry the work. selfdev_orders.current_goal_id holds that Task's ID (no FK,
-- as before). A child task has no order_id; it reaches its order through parent_task_id.
ALTER TABLE selfdev_tasks ADD COLUMN order_id CHAR(26) NULL;
ALTER TABLE selfdev_tasks ADD COLUMN cycle_no INT NULL;
-- One cycle per (order, number). NULLs are distinct, so the tasks that are not cycles do not collide.
CREATE UNIQUE INDEX ux_selfdev_tasks_order_cycle ON selfdev_tasks (order_id, cycle_no);
CREATE INDEX ix_selfdev_tasks_order ON selfdev_tasks (order_id, created_at);
ALTER TABLE selfdev_tasks ADD CONSTRAINT fk_selfdev_tasks_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id);
