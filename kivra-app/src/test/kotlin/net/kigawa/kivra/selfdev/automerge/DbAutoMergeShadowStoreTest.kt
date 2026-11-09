package net.kigawa.kivra.selfdev.automerge

import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The shadow store reads the approval kind of a Run's task from the V40 Task model (`selfdev_tasks.run_id`). Runs on H2 with the real
 * repositories; the store is the only thing under test.
 */
class DbAutoMergeShadowStoreTest {
    private val db = TestDb()
    private val store = DbAutoMergeShadowStore(
        db.database,
        db.runs,
        SelfDevOperatorRepository(db.database),
        SelfDevAutopilotControlRepository(db.database),
        AuditRepository(),
    )

    @AfterTest
    fun close() = db.close()

    private fun newRun(): RunId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

    /** Inserts a V40 task row linked to [runId]. [approvedBy] null and [requiresApproval] are the two inputs of the approval kind. */
    private val project: String = net.kigawa.kivra.db.ProjectRepository(db.database, java.time.Clock.systemUTC()).create("Shadow", null).projectId

    private fun insertTask(runId: RunId, requiresApproval: Boolean, approvedBy: String?, id: String) = transaction(db.database) {
        val now = LocalDateTime.of(2026, 1, 1, 0, 0)
        execUpdate(
            "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, target_repo, state, dependencies, requires_approval, " +
                "run_id, approved_by, revision, attempts, version, created_at, updated_at, project_id) " +
                "VALUES (?, 'test', ?, 'title', 'kigawa-net/kivra', 'SUBMITTED', '[]', ?, ?, ?, 0, 1, 0, ?, ?, ?)",
            id, "key-$id", requiresApproval, runId.value, approvedBy, now, now, project,
        )
    }

    @Test
    fun `a Run with no task is refused as not approved, and the snapshot still reads its state`() = runBlocking {
        val runId = newRun()
        val snapshot = assertNotNull(store.runSnapshot(runId))
        assertNull(snapshot.approval)
        assertNull(store.runSnapshot(RunId("01ZZZZZZZZZZZZZZZZZZZZZZZZ")))
    }

    @Test
    fun `an operator approval is OPERATOR_APPROVED, whatever the approval requirement`() {
        val runId = newRun()
        insertTask(runId, requiresApproval = true, approvedBy = "operator", id = "T".padEnd(26, '1'))
        assertEquals(AutoMergeTaskApproval.OPERATOR_APPROVED, store.runSnapshot(runId)!!.approval)
    }

    @Test
    fun `a task that needs no approval and has none recorded is AUTO_APPROVED`() {
        val runId = newRun()
        insertTask(runId, requiresApproval = false, approvedBy = null, id = "T".padEnd(26, '2'))
        assertEquals(AutoMergeTaskApproval.AUTO_APPROVED, store.runSnapshot(runId)!!.approval)
    }

    @Test
    fun `a task that still needs an approval and has none is null, which the judgement refuses`() {
        val runId = newRun()
        insertTask(runId, requiresApproval = true, approvedBy = null, id = "T".padEnd(26, '3'))
        assertNull(store.runSnapshot(runId)!!.approval)
    }
}
