-- V57: the project chat's AI proposals (ADR 0023 追補 5). The chat's AI may propose an order's stop request and a member's add or removal; the
-- operator confirms them as before. Expand, with one widening of a column:
--   1. target_id is widened from CHAR(26) to VARCHAR(64), so a target that is not an order or a Task (an assignee) fits whatever its ID's form.
--      Every value an old Writer writes (a 26-character ULID) still fits, and the UNIQUE on (project_id, action_code, target_id, active_key) is
--      kept. MODIFY is the one non-expand form here; MigrationExpandOnlyTest lists it with this reason.
--   2. target_kind: what target_id names (ORDER, TASK or ASSIGNEE). The default ORDER is what an old Writer's row is. The rows already written
--      for the Task codes (TASK_REPLAN, TASK_APPROVE, TASK_REJECT; ADR 0023 追補 and 段階 2) are set to TASK, the kind their code names.
--   3. origin: who proposed the row, RULE (the rule-based proposer) or AI (the chat's AI through a tool). An old Writer only writes rule
--      proposals, so the default RULE is right for it. An AI proposal is not expired by the rules (only by its target's change).
--   4. param_code: a fixed parameter of the action, never free text. Only MEMBER_ADD uses it (the member role, one of PROJECT_MEMBER_ROLES).
-- For an ASSIGNEE target, expected_version is the membership when proposed: 1 (a member) or 0 (not a member).
ALTER TABLE project_chat_actions MODIFY COLUMN target_id VARCHAR(64) NOT NULL;
ALTER TABLE project_chat_actions ADD COLUMN target_kind VARCHAR(16) NOT NULL DEFAULT 'ORDER';
ALTER TABLE project_chat_actions ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'RULE';
ALTER TABLE project_chat_actions ADD COLUMN param_code VARCHAR(32) NULL;
UPDATE project_chat_actions SET target_kind = 'TASK' WHERE action_code IN ('TASK_REPLAN', 'TASK_APPROVE', 'TASK_REJECT');
ALTER TABLE project_chat_actions ADD CONSTRAINT ck_chat_action_target_kind CHECK (target_kind IN ('ORDER', 'TASK', 'ASSIGNEE'));
ALTER TABLE project_chat_actions ADD CONSTRAINT ck_chat_action_origin CHECK (origin IN ('RULE', 'AI'));
