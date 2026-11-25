CREATE TABLE selfdev_order_assignments (
    assignment_id CHAR(26) NOT NULL PRIMARY KEY,
    order_id CHAR(26) NOT NULL,
    assignee_id CHAR(26) NOT NULL,
    assigned_by CHAR(12) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,
    unassigned_at DATETIME(6) NULL,
    active_slot TINYINT NULL,
    CONSTRAINT fk_order_assignments_order FOREIGN KEY (order_id) REFERENCES selfdev_orders (order_id),
    CONSTRAINT fk_order_assignments_assignee FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id),
    CONSTRAINT uk_order_assignments_active UNIQUE (order_id, active_slot),
    CONSTRAINT ck_order_assignments_slot CHECK (
        (unassigned_at IS NULL AND active_slot IS NOT NULL AND active_slot = 1)
        OR (unassigned_at IS NOT NULL AND active_slot IS NULL)
    )
);
CREATE INDEX ix_order_assignments_assignee ON selfdev_order_assignments (assignee_id, unassigned_at);
