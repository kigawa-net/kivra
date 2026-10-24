-- V33: assignees and roles (a role is held by an assignee; an owner key is bound to one assignee). Expand-only: three new tables, no
-- change to an existing one. V1 .. V32 are not edited. V30 stays unused on purpose. Nothing reads these tables yet (the permission
-- check comes in a later change), so this migration alone changes no behaviour.
-- Times are UTC DATETIME(6). No row holds a secret. The display name is the operator's text and is never logged or audited; the owner
-- key lives only in selfdev_assignee_members and is never logged or audited either (events carry the hashed reference only).

-- selfdev_assignees: one row per assignee (a name and a role). It is not a Keycloak user: owner keys are bound to it separately.
-- state is ACTIVE or DISABLED; a DISABLED assignee keeps its row and its members but is not allowed to act. version is the optimistic
-- lock; +1 on every role or state change. created_by is actor_ref (12 hex, the first digits of the SHA-256 of the creator's owner key)
-- or 'bootstrap' for the row the startup seeding makes.
CREATE TABLE selfdev_assignees (
    assignee_id   CHAR(26)     NOT NULL,                                -- ULID
    display_name  VARCHAR(64)  NOT NULL,                                -- NFC, trimmed, 1..64 code points; shown as text only
    role          VARCHAR(16)  NOT NULL,
    state         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_by    VARCHAR(12)  NOT NULL,                                -- actor_ref (12 hex) or 'bootstrap'
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (assignee_id),
    CONSTRAINT ck_selfdev_assignees_role CHECK (role IN ('ADMIN', 'OPERATOR', 'APPROVER', 'AI', 'VIEWER')),
    CONSTRAINT ck_selfdev_assignees_state CHECK (state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_selfdev_assignees_name CHECK (CHAR_LENGTH(display_name) BETWEEN 1 AND 64),
    CONSTRAINT ck_selfdev_assignees_created_by CHECK (created_by = 'bootstrap' OR CHAR_LENGTH(created_by) = 12)
);
CREATE INDEX ix_selfdev_assignees_role ON selfdev_assignees (role, state);

-- selfdev_assignee_members: which owner keys belong to which assignee. One owner key belongs to one assignee only (UNIQUE), so one
-- principal cannot hold two roles (for example AI and APPROVER). owner_key is compared as binary text: two keys that differ only in
-- case are two principals. member_ref (the first 16 hex digits of the SHA-256 of the owner key) is UNIQUE too; it is computed from the
-- exact bytes, so it is case-sensitive on every engine and backs the same rule if the column collation ever differs.
CREATE TABLE selfdev_assignee_members (
    owner_key     VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    member_ref    CHAR(16)     NOT NULL,
    assignee_id   CHAR(26)     NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (owner_key),
    CONSTRAINT uk_selfdev_assignee_members_ref UNIQUE (member_ref),
    CONSTRAINT fk_selfdev_assignee_members_assignee FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id)
);
CREATE INDEX ix_selfdev_assignee_members_assignee ON selfdev_assignee_members (assignee_id);

-- selfdev_assignee_events: the append-only record of assignee changes. The application only INSERTs. actor_ref is the first 12 hex
-- digits of the SHA-256 of the acting owner key (never the key); it is NULL only for the startup seeding. member_ref is the hashed
-- reference of a bound or unbound owner key. The display name and the owner key are never stored here.
CREATE TABLE selfdev_assignee_events (
    event_id      CHAR(26)    NOT NULL,                                 -- ULID
    assignee_id   CHAR(26)    NOT NULL,
    kind          VARCHAR(16) NOT NULL,
    actor_ref     CHAR(12)    NULL,
    from_role     VARCHAR(16) NULL,
    to_role       VARCHAR(16) NULL,
    member_ref    CHAR(16)    NULL,
    occurred_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_selfdev_assignee_events_assignee FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id),
    CONSTRAINT ck_selfdev_assignee_events_kind CHECK (
        kind IN ('CREATED', 'ROLE_CHANGED', 'DISABLED', 'ENABLED', 'MEMBER_BOUND', 'MEMBER_UNBOUND')
    )
);
CREATE INDEX ix_selfdev_assignee_events_assignee ON selfdev_assignee_events (assignee_id, occurred_at);
