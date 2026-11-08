package net.kigawa.kivra.selfdev.automerge

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.AuditEventsTable
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.DevelopmentRunsTable
import net.kigawa.kivra.db.PR_REBASE_REBOUND
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.selfdev.autofix.DbIncidentRunOrigin
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The database side of the shadow judgement, over the V40 Task model (`selfdev_tasks`, linked to a Run by `run_id`). The reads are read
 * only; the one write is the audit event, appended in the Run's chain under the Run's row lock (the same lock as
 * [net.kigawa.kivra.db.DbAuditSink]).
 */
class DbAutoMergeShadowStore(
    private val database: Database,
    private val runs: DevelopmentRunRepository,
    private val operators: SelfDevOperatorRepository,
    private val control: SelfDevAutopilotControlRepository,
    private val audit: AuditRepository,
) : AutoMergeShadowStore {

    override fun pullRequestRunIds(limit: Int): List<RunId> = runs.selfDevRunIdsInState(DevelopmentRunState.PULL_REQUEST, limit)

    override fun runSnapshot(runId: RunId): ShadowRunSnapshot? {
        val run = runs.get(runId) ?: return null
        val stored = runs.loadPullRequestBinding(runId)
        val binding = stored?.let { b ->
            b.prNumber.takeIf { it in 1..Int.MAX_VALUE }?.let { number ->
                AutoMergeBinding(repo = b.repository, prNumber = number.toInt(), nodeId = b.prNodeId, headSha = b.headSha)
            }
        }
        return ShadowRunSnapshot(
            state = run.state,
            binding = binding,
            approval = approvalOf(operators.taskOfRun(runId.value)),
            rebound = hasRebound(runId),
            incidentFix = DbIncidentRunOrigin(database).issueOf(runId.value) != null,
            policyRevision = stored?.policyRevision,
        )
    }

    override fun globalSnapshot(): ShadowGlobalSnapshot {
        val c = control.read()
        return ShadowGlobalSnapshot(selfDevPaused = c.paused, breakerOpen = c.breakerOpen, autoMergePaused = c.autoMergePaused)
    }

    override fun reviewOf(repo: String, number: Int, headSha: String): AutoMergeReviewFact? = transaction(database) {
        execQuery(
            "SELECT state, verdict FROM selfdev_pr_reviews WHERE repo_key = ? AND pr_number = ? AND head_sha = ?",
            repo, number.toLong(), headSha,
        ) { AutoMergeReviewFact(state = it.getString(1), verdict = it.getString(2)) }.firstOrNull()
    }

    override fun attemptsSince(since: Instant): Int = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM selfdev_automerge_attempts WHERE created_at >= ?",
            LocalDateTime.ofInstant(since, ZoneOffset.UTC),
        ) { it.getLong(1) }.firstOrNull()?.toInt() ?: 0
    }

    override fun inFlightAttempts(): Int = transaction(database) {
        execQuery("SELECT COUNT(*) FROM selfdev_automerge_attempts WHERE active_slot IS NOT NULL") { it.getLong(1) }
            .firstOrNull()?.toInt() ?: 0
    }

    /** True when the Run's head was re-bound at least once (issue #351). Read from the audit rows of the Run. */
    private fun hasRebound(runId: RunId): Boolean = transaction(database) {
        AuditEventsTable.selectAll()
            .where { (AuditEventsTable.runId eq runId.value) and (AuditEventsTable.action eq PR_REBASE_REBOUND) }
            .limit(1)
            .any()
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

        /**
         * The approval kind of a Run's task (ADR 0011 section 2.8d). An operator's approval is `approved_by`; a task that does not need an
         * approval and has none recorded is AUTO_APPROVED. Anything else is null, which the judgement refuses (fail-closed).
         */
        fun approvalOf(task: SelfDevTask?): AutoMergeTaskApproval? = when {
            task == null -> null
            task.approvedBy != null -> AutoMergeTaskApproval.OPERATOR_APPROVED
            !task.requiresApproval -> AutoMergeTaskApproval.AUTO_APPROVED
            else -> null
        }
    }
}
