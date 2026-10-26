package net.kigawa.kivra.db

import java.time.LocalDateTime
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Binds [ownerKey] to an AI_APPROVER assignee by SQL. The repository refuses to bind any key to an AI_APPROVER (ADR 0021 P12); the
 * server's own key arrives in a later stage. A test that needs an AI_APPROVER principal for the permission check uses this.
 */
fun bindToAiApproverForTest(db: TestDb, assigneeId: String, ownerKey: String) {
    transaction(db.database) {
        exec(
            "INSERT INTO selfdev_assignee_members (owner_key, member_ref, assignee_id, created_at) VALUES ('" + ownerKey.replace("'", "''") + "', '" +
                SelfDevAssigneeRepository.memberRef(ownerKey) + "', '" + assigneeId + "', '" + LocalDateTime.of(2026, 1, 1, 0, 0) + "')",
        )
    }
}
