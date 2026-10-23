-- V28: the automatic child stop, stage 2 PR 3e of ADR 0015 (section 4.4). Expand-only: one nullable column, one FK, and CHECKs. V23 to V27 are
-- not edited. The two dropped CHECKs are replaced with the same name, and each new one accepts every value the old one accepted (a value is
-- added, none is removed). An old Writer never writes SYSTEM or ORDER, so every row it writes passes the new CHECKs as it did the old ones.
--
-- decider_order_id: the parent order that decided an automatic stop (decider_kind = 'ORDER'). NULL for a human decision and for a request
-- that is not decided yet. The FK ties it to a real order; the application checks that it is the target's direct parent.
ALTER TABLE selfdev_order_stop_requests ADD COLUMN decider_order_id CHAR(26) NULL;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT fk_selfdev_order_stop_decider_order FOREIGN KEY (decider_order_id) REFERENCES selfdev_orders (order_id);

-- The decider is a human (the root approver) or a parent order (the automatic decision, section 4.4). Replaces the V23 CHECK.
ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT ck_selfdev_order_stop_decider;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_decider CHECK (decider_kind IS NULL OR decider_kind IN ('HUMAN', 'ORDER'));
-- An automatic decision names the parent that made it.
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_decider_ref CHECK (
    decider_kind IS NULL OR decider_kind = 'HUMAN' OR decider_order_id IS NOT NULL
);
-- The root is never stopped by an automatic decision (section 4.4 (6)). The database refuses it, whatever the application checks.
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_auto_root CHECK (
    decider_kind IS NULL OR decider_kind = 'HUMAN' OR target_is_root = 0
);

-- The automatic request's requester is the fixed system principal, not a person or the AI principal. Replaces the V23 CHECK.
ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT ck_selfdev_order_stop_requester;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_requester CHECK (requester_kind IN ('HUMAN', 'AI', 'SYSTEM'));
