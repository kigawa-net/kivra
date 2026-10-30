package net.kigawa.kivra.selfdev.automerge

import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.DevelopmentRunsTable
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
// SelfDevPlanRepository and SelfDevPlanTask removed in V40
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The database side of the shadow judgement. The reads are read only; the one write is the audit event, appended in the Run's chain under
 * the Run's row lock (the same lock as [net.kigawa.kivra.db.DbAuditSink]).
 */
/** Stubbed pending Task model migration (V40). */
class DbAutoMergeShadowStore(
    private val database: Database,
    private val runs: DevelopmentRunRepository,
    private val control: SelfDevAutopilotControlRepository,
    private val audit: AuditRepository,
) : AutoMergeShadowStore {

    override fun pullRequestRunIds(limit: Int): List<RunId> = runs.selfDevRunIdsInState(DevelopmentRunState.PULL_REQUEST, limit)

    override fun runSnapshot(runId: RunId): ShadowRunSnapshot? {
        val run = runs.get(runId) ?: return null
        val binding = runs.loadPullRequestBinding(runId)?.let { b ->
            b.prNumber.takeIf { it in 1..Int.MAX_VALUE }?.let { number ->
                AutoMergeBinding(repo = b.repository, prNumber = number.toInt(), nodeId = b.prNodeId, headSha = b.headSha)
            }
        }
        // approval stubbed pending Task model migration (V40)
        return ShadowRunSnapshot(state = run.state, binding = binding, approval = null)
    }

    override fun globalSnapshot(): ShadowGlobalSnapshot {
        val c = control.read()
        return ShadowGlobalSnapshot(selfDevPaused = c.paused, breakerOpen = c.breakerOpen, autoMergePaused = c.autoMergePaused)
    }

    override fun recordJudgement(runId: RunId, verdict: String, payload: String) {
        transaction(database) {
            DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull()
                ?: return@transaction
            audit.append(this, runId, AuditEntry(actor = ACTOR, action = AUTO_MERGE_JUDGED, result = verdict, payload = payload))
        }
    }

    override fun recordPaused(runId: RunId) {
        transaction(database) {
            DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull()
                ?: return@transaction
            audit.append(this, runId, AuditEntry(actor = ACTOR, action = AUTO_MERGE_SHADOW_PAUSED, result = "PAUSED"))
        }
    }

    private companion object {
        const val ACTOR = "system:automerge-shadow"
    }
}
