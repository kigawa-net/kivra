package net.kigawa.kivra.selfdev.review

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.DevelopmentRunsTable
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.llmwiring.isDuplicateKey
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** The bound pull request of a Run: the repository as it was bound (`owner/name`) and the pull request number. */
data class PrReviewBinding(val repository: String, val prNumber: Int)

/** The states of one review row (`selfdev_pr_reviews`, V45). */
enum class PrReviewState { CLAIMED, POSTED, FAILED, UNKNOWN, INVALID, SKIPPED }

/** The database side of the review: the reads of the Runs, the rows that make a review idempotent, and the audit event. */
interface PrReviewStore {
    /** The ids of the self-development Runs in PULL_REQUEST, oldest first, at most [limit]. */
    fun pullRequestRunIds(limit: Int): List<RunId>

    /** The pull request the Run is bound to, or null when it has none. */
    fun binding(runId: RunId): PrReviewBinding?

    /** The operator's task text of the Run, or null when the Run has no task. */
    fun task(runId: RunId): PrReviewTask?

    /** How many reviews of [repo] pull request [number] used a model call (every head, SKIPPED rows excluded). */
    fun reviewsOfPr(repo: RepoRef, number: Int): Int

    /** How many reviews since [since] used a model call (SKIPPED rows excluded). */
    fun reviewsSince(since: Instant): Int

    /** Inserts the row of (repo, pull request, head). False when the head already has one: it is then never reviewed again. */
    fun claim(repo: RepoRef, number: Int, headSha: String, runId: RunId): Boolean

    /** Sets the state of the row of (repo, pull request, head). */
    fun finish(repo: RepoRef, number: Int, headSha: String, state: PrReviewState, verdict: PrReviewVerdict?, findingCount: Int)

    /** Appends one `PR_AI_REVIEW` event to the Run's audit chain. [payload] carries fixed codes and counts only. */
    fun audit(runId: RunId, result: String, payload: JsonObject)
}

/** The audit action of one review step (result: the row state). It never carries the diff, the title, the body or the model's text. */
const val PR_AI_REVIEW = "PR_AI_REVIEW"

/**
 * The database side of the review, over the Task model (V40 `selfdev_tasks`, linked to a Run by `run_id`) and the V45 table. The audit
 * event is appended under the Run's row lock, as the auto-merge shadow does.
 */
class DbPrReviewStore(
    private val database: Database,
    private val runs: DevelopmentRunRepository,
    private val operators: SelfDevOperatorRepository,
    private val audit: AuditRepository,
    private val clock: Clock = Clock.systemUTC(),
) : PrReviewStore {

    override fun pullRequestRunIds(limit: Int): List<RunId> = runs.selfDevRunIdsInState(DevelopmentRunState.PULL_REQUEST, limit)

    override fun binding(runId: RunId): PrReviewBinding? {
        val b = runs.loadPullRequestBinding(runId) ?: return null
        val number = b.prNumber.takeIf { it in 1..Int.MAX_VALUE } ?: return null
        return PrReviewBinding(b.repository, number.toInt())
    }

    override fun task(runId: RunId): PrReviewTask? = operators.taskOfRun(runId.value)?.let { PrReviewTask(it.title, it.body) }

    override fun reviewsOfPr(repo: RepoRef, number: Int): Int = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM selfdev_pr_reviews WHERE repo_key = ? AND pr_number = ? AND state <> 'SKIPPED'",
            repo.toString(), number.toLong(),
        ) { it.getLong(1) }.firstOrNull()?.toInt() ?: 0
    }

    override fun reviewsSince(since: Instant): Int = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM selfdev_pr_reviews WHERE created_at >= ? AND state <> 'SKIPPED'",
            LocalDateTime.ofInstant(since, ZoneOffset.UTC),
        ) { it.getLong(1) }.firstOrNull()?.toInt() ?: 0
    }

    override fun claim(repo: RepoRef, number: Int, headSha: String, runId: RunId): Boolean = try {
        transaction(database) {
            val now = utcNow(clock)
            execUpdate(
                "INSERT INTO selfdev_pr_reviews (repo_key, pr_number, head_sha, run_id, state, finding_count, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'CLAIMED', 0, ?, ?)",
                repo.toString(), number.toLong(), headSha, runId.value, now, now,
            )
        }
        true
    } catch (e: Exception) {
        if (!e.isDuplicateKey()) throw e
        false
    }

    override fun finish(repo: RepoRef, number: Int, headSha: String, state: PrReviewState, verdict: PrReviewVerdict?, findingCount: Int) {
        transaction(database) {
            execUpdate(
                "UPDATE selfdev_pr_reviews SET state = ?, verdict = ?, finding_count = ?, updated_at = ? " +
                    "WHERE repo_key = ? AND pr_number = ? AND head_sha = ?",
                state.name, verdict?.name, findingCount.coerceIn(0, PrReviewOutputParser.MAX_FINDINGS), utcNow(clock),
                repo.toString(), number.toLong(), headSha,
            )
        }
    }

    override fun audit(runId: RunId, result: String, payload: JsonObject) {
        transaction(database) {
            DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull()
                ?: return@transaction
            audit.append(this, runId, AuditEntry(actor = ACTOR, action = PR_AI_REVIEW, result = result, payload = payload.toString()))
        }
    }

    private companion object {
        const val ACTOR = "system:pr-review"
    }
}
