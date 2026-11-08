package net.kigawa.kivra.selfdev.automerge

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunsTable
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.selfdev.llmwiring.isDuplicateKey
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The database side of the automatic merge (V48 `selfdev_automerge_attempts`). The table's primary key is the idempotency key (one attempt per
 * head SHA), and its unique `active_slot` is the single slot (one attempt in flight, across Pods). The audit event is appended under the
 * Run's row lock, as the shadow judgement does. The halt is the existing per-feature pause, so the operator resumes it through the existing
 * endpoint.
 */
class DbAutoMergeActiveStore(
    private val database: Database,
    private val control: SelfDevAutopilotControlRepository,
    private val audit: AuditRepository,
    private val clock: Clock = Clock.systemUTC(),
) : AutoMergeActiveStore {

    override fun attemptState(repo: String, number: Int, headSha: String): String? = transaction(database) {
        execQuery(
            "SELECT state FROM selfdev_automerge_attempts WHERE repo_key = ? AND pr_number = ? AND head_sha = ?",
            repo, number.toLong(), headSha,
        ) { it.getString(1) }.firstOrNull()
    }

    override fun claim(repo: String, number: Int, headSha: String, runId: RunId): Boolean = try {
        transaction(database) {
            val now = utcNow(clock)
            execUpdate(
                "INSERT INTO selfdev_automerge_attempts (repo_key, pr_number, head_sha, run_id, state, active_slot, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'CLAIMED', 'A', ?, ?)",
                repo, number.toLong(), headSha, runId.value, now, now,
            )
        }
        true
    } catch (e: Exception) {
        if (!e.isDuplicateKey()) throw e
        false
    }

    override fun finish(repo: String, number: Int, headSha: String, state: String) {
        transaction(database) {
            execUpdate(
                "UPDATE selfdev_automerge_attempts SET state = ?, active_slot = NULL, updated_at = ? " +
                    "WHERE repo_key = ? AND pr_number = ? AND head_sha = ?",
                state, utcNow(clock), repo, number.toLong(), headSha,
            )
        }
    }

    override fun recentStates(limit: Int): List<String> {
        require(limit in 1..10) { "limit must be 1 to 10" }
        return transaction(database) {
            execQuery("SELECT state FROM selfdev_automerge_attempts ORDER BY created_at DESC, head_sha DESC LIMIT $limit") { it.getString(1) }
        }
    }

    override fun staleClaim(olderThan: Instant): RunId? = transaction(database) {
        execQuery(
            "SELECT run_id FROM selfdev_automerge_attempts WHERE active_slot IS NOT NULL AND updated_at < ? ORDER BY updated_at LIMIT 1",
            LocalDateTime.ofInstant(olderThan, ZoneOffset.UTC),
        ) { it.getString(1) }.firstOrNull()?.let { RunId(it) }
    }

    override fun audit(runId: RunId, action: String, result: String, payload: JsonObject) {
        transaction(database) {
            DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull()
                ?: return@transaction
            audit.append(this, runId, AuditEntry(actor = ACTOR, action = action, result = result, payload = payload.toString()))
        }
    }

    override fun halt(runId: RunId, reasonCode: String) {
        control.setAutoMergePaused(true)
        audit(runId, AUTO_MERGE_HALTED, "PAUSED", buildJsonObject { put("reasonCode", reasonCode) })
    }

    private companion object {
        const val ACTOR = "system:automerge-active"
    }
}
