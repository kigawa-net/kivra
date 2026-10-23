-- V31: the per-feature pause of the automatic merge, ADR 0014 section 4 (U-10). Expand-only: one new column on the control row.
-- V1 .. V30 are not edited. The default FALSE keeps the existing row unpaused. The shadow watch reads it on every tick and skips the
-- judgement while it is TRUE; it is changed only by the operator's API (the AI principal is refused there).
ALTER TABLE selfdev_autopilot_control
    ADD COLUMN auto_merge_paused BOOLEAN NOT NULL DEFAULT FALSE;
