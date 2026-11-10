-- V54: one PENDING stop request per tree, not per database (ADR 0015 addendum 2026-11-11). V23 made active_key UNIQUE over the whole table,
-- so with V43's several roots a stop request of one tree was refused while another tree had a pending one. The rule is per tree, as the
-- ADR states ("木に未決の要求は 1 件まで"). Expand, then one Contract step, as V43 did for the root:
--   1. tree_root_id: the root of the request's tree (NULL for the rows written before this change; the application's check under the root
--      lock covers those, see SelfDevOrderRepository.requestStop).
--   2. UNIQUE (tree_root_id, active_key): at most one PENDING row (active_key = 1) per tree. NULL keys do not clash, so a finished request
--      (active_key NULL) never blocks a new one.
--   3. The global UNIQUE uk_selfdev_order_stop_active is dropped, so a second tree's request can be PENDING at the same time.
ALTER TABLE selfdev_order_stop_requests ADD COLUMN tree_root_id CHAR(26) NULL;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT uk_selfdev_order_stop_tree_active UNIQUE (tree_root_id, active_key);
ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT uk_selfdev_order_stop_active;
