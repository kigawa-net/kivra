-- ADR 0021 L5: allow 'LLM' as a decider kind for the LLM stop approver.
-- The LLM is an internal decider (ADR 0021 P12), like ORDER; it does not set decider_order_id.
ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT ck_selfdev_order_stop_decider;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_decider CHECK (decider_kind IS NULL OR decider_kind IN ('HUMAN', 'ORDER', 'LLM'));

ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT ck_selfdev_order_stop_decider_ref;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_decider_ref CHECK (
    decider_kind IS NULL OR decider_kind IN ('HUMAN', 'LLM') OR decider_order_id IS NOT NULL
);

ALTER TABLE selfdev_order_stop_requests DROP CONSTRAINT ck_selfdev_order_stop_auto_root;
ALTER TABLE selfdev_order_stop_requests ADD CONSTRAINT ck_selfdev_order_stop_auto_root CHECK (
    decider_kind IS NULL OR decider_kind IN ('HUMAN', 'LLM') OR target_is_root = 0
);

-- Allow 'LLM' as an actor kind in order events (for LLM-approved stops).
ALTER TABLE selfdev_order_events DROP CONSTRAINT ck_selfdev_order_events_actor;
ALTER TABLE selfdev_order_events ADD CONSTRAINT ck_selfdev_order_events_actor CHECK (actor_kind IN ('HUMAN', 'AI', 'SYSTEM', 'LLM'));
