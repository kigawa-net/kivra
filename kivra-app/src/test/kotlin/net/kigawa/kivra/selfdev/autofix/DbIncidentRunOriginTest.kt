package net.kigawa.kivra.selfdev.autofix

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

class DbIncidentRunOriginTest {

    @Test
    fun `a run of a fix's task names the incident issue, and every other run names nothing`() = TestDb().use { db ->
        val fixRun = (db.runs.submitTask("operator", "fix-run", "Fix", selfDev = true, maxActiveSelfDev = null) as TaskSubmitResult.Created).run
        val otherRun = (db.runs.submitTask("operator", "other-run", "Other", selfDev = true, maxActiveSelfDev = null) as TaskSubmitResult.Created).run
        val root = (
            SelfDevOperatorRepository(db.database)
                .submitTask("kivra-incident", "incident:root", "[kivra-incident] RUN_FAILED MERGE_CONFLICT", "body", "kigawa-net/kivra")
                as SelfDevSubmitResult.Created
            ).task
        val now = LocalDateTime.ofInstant(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC)
        transaction(db.database) {
            execUpdate(
                "INSERT INTO selfdev_incident_fixes (fingerprint, incident_code, reason_code, issue_number, state, root_task_id, attempts, " +
                    "active_slot, created_at, updated_at) VALUES (?, 'RUN_FAILED', 'MERGE_CONFLICT', 7, 'SUBMITTED', ?, 1, NULL, ?, ?)",
                "%064x".format(1), root.taskId, now, now,
            )
            // The child the planner writes under the root, with the run it was dispatched as.
            execUpdate(
                "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, target_repo, state, dependencies, requires_approval, " +
                    "run_id, parent_task_id, revision, attempts, version, created_at, updated_at) " +
                    "VALUES (?, 'kivra-incident', 'plan:child', 'child', 'kigawa-net/kivra', 'SUBMITTED', '[]', TRUE, ?, ?, 0, 1, 0, ?, ?)",
                "CHILD".padEnd(26, '0'), fixRun.runId.value, root.taskId, now, now,
            )
        }
        val origin = DbIncidentRunOrigin(db.database)
        assertEquals(7L, origin.issueOf(fixRun.runId.value))
        assertNull(origin.issueOf(otherRun.runId.value))
    }
}
