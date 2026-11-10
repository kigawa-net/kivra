-- V53: the audit of an order extension (ADR 0015 addendum 2026-11-10, section 2.6). An EXTENDED event records the max_cycles and expires_at
-- before and after the change, and no existing column of selfdev_order_events can hold them. payload_json is a small fixed-field JSON of
-- those values (numbers and timestamps only, never free text). Expand only: one nullable column, and the existing rows keep NULL.
ALTER TABLE selfdev_order_events ADD COLUMN payload_json VARCHAR(255) NULL;
