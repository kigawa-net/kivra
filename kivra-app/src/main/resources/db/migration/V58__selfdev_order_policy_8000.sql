-- V58: an order's policy may hold up to 8000 characters (ADR 0023 追補 7). The project chat's AI writes the plan agreed with the operator, in
-- full, into a child order's policy, and 2000 characters do not hold a plan with its purpose, files, steps, acceptance, constraints and the
-- operator's quoted request. 8000 is the limit of the Task body the policy becomes (selfdev_tasks.body, V40), so a cycle's Task still fits.
-- The CHECK is replaced by a wider one: every row an old Writer writes (1 to 2000) passes the new CHECK as it passed the old one. No Contract
-- step is needed. MigrationExpandOnlyTest lists the DROP with this reason. The column is TEXT already: no type change.
ALTER TABLE selfdev_orders DROP CONSTRAINT ck_selfdev_orders_policy;
ALTER TABLE selfdev_orders ADD CONSTRAINT ck_selfdev_orders_policy CHECK (CHAR_LENGTH(policy) BETWEEN 1 AND 8000);
