package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.AdmissionBlockReason
import net.kigawa.kivra.core.AdmissionEvaluator
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.AdmissionResult
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RerunRejectReason
import net.kigawa.kivra.core.RerunResult
import net.kigawa.kivra.core.RunAdmission
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskKey
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.core.TaskSnapshotDigest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * MariaDB (and H2 in tests) implementation of [RunAdmission] (Issue #26).
 *
 * [admit] runs in one transaction: the binding row is created if missing (`ON DUPLICATE KEY UPDATE` queues concurrent creators on
 * the row lock), the row is locked with `SELECT ... FOR UPDATE`, and only then is the decision taken. A new run is created in the
 * same transaction as the binding update, so a failure leaves neither of them behind.
 *
 * The run is created with the least-privileged completion mode (PR_ONLY) and owner [SYSTEM_OWNER]; it has no `principal`, so the
 * Task API never lists it. Nothing here calls an external system.
 *
 * [policy] null means no admission policy is confirmed: every task is blocked (fail closed). Not wired into production (#26).
 */
class DbRunAdmission(
    private val database: Database,
    private val runs: DevelopmentRunRepository,
    private val audit: AuditRepository,
    private val policy: AdmissionPolicy?,
    private val clock: Clock = Clock.systemUTC(),
) : RunAdmission {

    /** TEST ONLY fault injection: runs after the run row is inserted and before the binding is updated. Throwing rolls back all of it. */
    internal var afterRunInsert: (() -> Unit)? = null

    override suspend fun admit(task: TaskSnapshot, requestId: String): AdmissionResult {
        val key = TaskKey.parse(task.id) ?: return AdmissionResult.Blocked(AdmissionBlockReason.INVALID_TASK_ID)
        if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID) return AdmissionResult.Blocked(AdmissionBlockReason.INVALID_REQUEST_ID)
        AdmissionEvaluator.evaluate(task, policy)?.let { return AdmissionResult.Blocked(it) }

        val observedAt = task.updatedAt.truncatedTo(ChronoUnit.MICROS)
        val observedLocal = LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC)
        val digest = TaskSnapshotDigest.of(task)
        val taskId = task.id.value

        return transaction(database) {
            ensureBindingRow(key)
            val binding = lockBinding(key) ?: error("task binding missing after insert")
            // A snapshot older than what was already observed must not move the binding or create a run.
            val stale = binding.observedUpdatedAt?.let { observedLocal < it } ?: false
            val activeRunId = binding.activeRunId

            if (activeRunId != null) {
                val state = runStateOrFail(activeRunId)
                if (!stale) recordObservation(key, binding, digest, observedLocal)
                return@transaction if (state.isTerminal) AdmissionResult.AlreadyTerminal(RunId(activeRunId)) else AdmissionResult.AlreadyActive(RunId(activeRunId))
            }
            if (stale) return@transaction AdmissionResult.Blocked(AdmissionBlockReason.STALE_SNAPSHOT)

            val runId = RunId(Ulid.generate(clock))
            runs.insertCreatedRun(this, runId, taskId, SYSTEM_OWNER, RunCompletionMode.PR_ONLY, traceId = null)
            afterRunInsert?.invoke()
            val idempotencyKey = "admission:${key.provider}:${key.environment}:${key.taskId}:${binding.generation}"
            audit.append(
                this, runId,
                AuditEntry(
                    actor = SYSTEM_OWNER,
                    action = "TASK_ADMITTED",
                    result = "OK",
                    resource = "task:$taskId",
                    payload = buildJsonObject {
                        put("idempotencyKey", idempotencyKey)
                        put("requestId", requestId)
                        put("generation", binding.generation)
                    }.toString(),
                ),
            )
            val now = utcNow(clock)
            execUpdate(
                """
                UPDATE task_bindings
                   SET active_run_id = ?, observed_hash = ?, observed_updated_at = ?, version = version + 1, updated_at = ?
                 WHERE provider = ? AND environment = ? AND task_id = ? AND version = ?
                """.trimIndent(),
                runId.value, digest, observedLocal, now, key.provider, key.environment, key.taskId, binding.version,
            ).also { check(it == 1) { "task binding was changed concurrently under lock" } }
            AdmissionResult.Accepted(runId)
        }
    }

    override suspend fun requestRerun(task: TaskId, operatorId: String, reason: String): RerunResult {
        val key = TaskKey.parse(task) ?: return RerunResult.Rejected(RerunRejectReason.INVALID_TASK_ID)
        if (operatorId.isBlank() || operatorId.length > MAX_OPERATOR) return RerunResult.Rejected(RerunRejectReason.INVALID_OPERATOR)
        val boundedReason = reason.trim().take(MAX_RERUN_REASON)
        return transaction(database) {
            val binding = lockBinding(key) ?: return@transaction RerunResult.Rejected(RerunRejectReason.NOT_BOUND)
            val activeRunId = binding.activeRunId ?: return@transaction RerunResult.Rejected(RerunRejectReason.NOTHING_TO_RERUN)
            if (!runStateOrFail(activeRunId).isTerminal) return@transaction RerunResult.Rejected(RerunRejectReason.RUN_NOT_TERMINAL)

            val newGeneration = binding.generation + 1
            execUpdate(
                """
                UPDATE task_bindings
                   SET generation = ?, active_run_id = NULL, version = version + 1, updated_at = ?
                 WHERE provider = ? AND environment = ? AND task_id = ? AND version = ?
                """.trimIndent(),
                newGeneration, utcNow(clock), key.provider, key.environment, key.taskId, binding.version,
            ).also { check(it == 1) { "task binding was changed concurrently under lock" } }
            audit.append(
                this, RunId(activeRunId),
                AuditEntry(
                    actor = operatorId,
                    action = "TASK_RERUN_REQUESTED",
                    result = "OK",
                    resource = "task:${task.value}",
                    payload = buildJsonObject {
                        put("reason", boundedReason)
                        put("generation", newGeneration)
                    }.toString(),
                ),
            )
            RerunResult.Requested(newGeneration)
        }
    }

    private data class BindingRow(
        val activeRunId: String?,
        val generation: Int,
        val observedHash: String?,
        val observedUpdatedAt: LocalDateTime?,
        val version: Long,
    )

    /**
     * Creates the binding row if it does not exist. The no-op `ON DUPLICATE KEY UPDATE` takes the row's exclusive lock when the
     * row already exists, so concurrent creators queue on it. A plain duplicate-key INSERT (or INSERT IGNORE) takes a shared lock
     * instead, and then the `SELECT ... FOR UPDATE` that follows deadlocks under concurrency (observed on MariaDB 11 with 8 admissions).
     */
    private fun Transaction.ensureBindingRow(key: TaskKey) {
        val now = utcNow(clock)
        execUpdate(
            """
            INSERT INTO task_bindings (provider, environment, task_id, active_run_id, generation, observed_hash, observed_updated_at, version, created_at, updated_at)
            VALUES (?, ?, ?, NULL, 1, NULL, NULL, 0, ?, ?)
            ON DUPLICATE KEY UPDATE version = version
            """.trimIndent(),
            key.provider, key.environment, key.taskId, now, now,
        )
    }

    private fun Transaction.lockBinding(key: TaskKey): BindingRow? =
        TaskBindingsTable.selectAll()
            .where { (TaskBindingsTable.provider eq key.provider) and (TaskBindingsTable.environment eq key.environment) and (TaskBindingsTable.taskId eq key.taskId) }
            .forUpdate()
            .firstOrNull()
            ?.toBindingRow()

    private fun Transaction.runStateOrFail(runId: String): DevelopmentRunState {
        val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId }.firstOrNull()
            ?: error("task binding points at a missing run")
        return DevelopmentRunState.valueOf(row[DevelopmentRunsTable.state])
    }

    /** Records the newest observation. Never moves [BindingRow.observedUpdatedAt] backwards (the caller checks staleness). */
    private fun Transaction.recordObservation(key: TaskKey, binding: BindingRow, digest: String, observedAt: LocalDateTime) {
        if (observedAt == binding.observedUpdatedAt && digest == binding.observedHash) return
        execUpdate(
            """
            UPDATE task_bindings
               SET observed_hash = ?, observed_updated_at = ?, version = version + 1, updated_at = ?
             WHERE provider = ? AND environment = ? AND task_id = ? AND version = ?
            """.trimIndent(),
            digest, observedAt, utcNow(clock), key.provider, key.environment, key.taskId, binding.version,
        ).also { check(it == 1) { "task binding was changed concurrently under lock" } }
    }

    private fun ResultRow.toBindingRow() = BindingRow(
        activeRunId = this[TaskBindingsTable.activeRunId],
        generation = this[TaskBindingsTable.generation],
        observedHash = this[TaskBindingsTable.observedHash],
        observedUpdatedAt = this[TaskBindingsTable.observedUpdatedAt],
        version = this[TaskBindingsTable.version],
    )

    companion object {
        /** Owner of runs created by admission. Not a user identity: no `principal`, so it never matches an OIDC subject. */
        const val SYSTEM_OWNER = "system:fusha-poller"
        private const val MAX_REQUEST_ID = 128
        private const val MAX_OPERATOR = 256
        private const val MAX_RERUN_REASON = 200
    }
}
