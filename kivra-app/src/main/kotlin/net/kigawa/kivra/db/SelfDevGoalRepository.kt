package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalContext
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TransitionDecision
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.core.autopilot.checkGoalTransition
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Persistence of self-development goals, their Plans and the Plan's tasks (V18, ADR 0011 sections 2.1 to 2.3). Every state change
 * is one conditional UPDATE. Its affected-row count decides the outcome (CAS). The state change itself is checked by the pure state
 * machine (kivra-core autopilot) before the UPDATE, so a transition that the table does not allow is never written.
 *
 * Planner writes are fenced by the pair (version, claim_token) read from the claim. A write with a stale pair changes nothing and
 * returns [CommitResult.StaleClaim]; the caller records it as PLAN_DISCARDED_STALE (ADR 0011 section 2.7).
 *
 * Expiry of a claim, and "paused" in the claim guard, are judged on the DB clock (NOW(6)), as for coding attempts.
 * Goal text is stored as the operator typed it and is never logged here.
 */

/** Exposed mirror of V18 `selfdev_goals` (the migration is the source of truth). */
internal object SelfDevGoalsTable : Table("selfdev_goals") {
    val goalId = char("goal_id", 26)
    val createdBy = varchar("created_by", 255)
    val idempotencyKey = varchar("idempotency_key", 128)
    val title = varchar("title", 200)
    val body = text("body").nullable()
    val targetRepo = varchar("target_repo", 200)
    val state = varchar("state", 32)
    val stateReason = varchar("state_reason", 64).nullable()
    val resumeState = varchar("resume_state", 32).nullable()
    val resumeReason = varchar("resume_reason", 64).nullable()
    val revision = integer("revision").default(0)
    val claimToken = varchar("claim_token", 64).nullable()
    val claimedUntil = datetime("claimed_until").nullable()
    val version = long("version").default(0)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val approvedBy = varchar("approved_by", 255).nullable()
    override val primaryKey = PrimaryKey(goalId)
}

/** One goal. [body] is null when the operator gave none. */
data class SelfDevGoal(
    val goalId: String,
    val createdBy: String,
    val idempotencyKey: String,
    val title: String,
    val body: String?,
    val targetRepo: String,
    val state: GoalState,
    val stateReason: AutopilotReason?,
    val resumeState: GoalState?,
    val resumeReason: AutopilotReason?,
    val revision: Int,
    val claimToken: String?,
    val claimedUntil: LocalDateTime?,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    /** The operator who approved the Plan (`approved_by`, V19). Null until the Plan is approved. */
    val approvedBy: String? = null,
)

/** The outcome of a conditional change of one row. */
sealed interface Transition<out T> {
    data class Moved<out T>(val row: T) : Transition<T>
    data object NotFound : Transition<Nothing>
    /** The expected version is not the current one. Nothing changed. */
    data object StaleVersion : Transition<Nothing>
    /** The state machine does not allow the change. Nothing changed. */
    data class Refused(val reason: TransitionRefusal) : Transition<Nothing>
}

sealed interface SubmitResult {
    data class Created(val goal: SelfDevGoal) : SubmitResult
    /** The same operator, key and content: the existing goal is returned and nothing is created. */
    data class Replayed(val goal: SelfDevGoal) : SubmitResult
    /** The same operator and key with other content. Nothing is created. */
    data object KeyConflict : SubmitResult
}

/** Why a claim was refused. Fixed codes. */
enum class ClaimRefusal {
    NOT_FOUND,
    /** The goal is not GOAL_SUBMITTED (already claimed, or in another state). */
    NOT_CLAIMABLE,
    /** The autopilot is paused for everything (selfdev_autopilot_control.paused). */
    PAUSED,
}

sealed interface ClaimResult {
    /** The goal is PLANNING. [SelfDevGoal.claimToken] and [SelfDevGoal.version] are the fence for the Planner's writes. */
    data class Claimed(val goal: SelfDevGoal) : ClaimResult
    data class Refused(val reason: ClaimRefusal) : ClaimResult
}

/** The fence a Planner holds: the goal's version and claim token, as read from the claim. */
data class GoalClaim(val goalId: String, val version: Long, val claimToken: String)

/** A validated Plan to store. [summary] is at most 500 characters, [outputSha256] is the digest of the raw model output. */
data class NewPlan(
    val summary: String?,
    val plannerModel: String,
    val outputSha256: String,
    val requestLedgerId: String?,
)

sealed interface CommitResult {
    data class Committed(val plan: SelfDevPlan, val tasks: List<SelfDevPlanTask>) : CommitResult
    /** The claim is no longer the current one (expired, taken again, or the goal was moved). Nothing was written. */
    data object StaleClaim : CommitResult
}

class SelfDevGoalRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val plans: SelfDevPlanRepository = SelfDevPlanRepository(database, clock),
) {

    /**
     * Stores a goal. Idempotent per (operator, key): a repeat with the same content returns the stored goal, and a repeat with
     * other content is refused. The unique constraint decides a race between two submissions.
     */
    fun submit(createdBy: String, idempotencyKey: String, title: String, body: String?, targetRepo: String): SubmitResult {
        require(createdBy.isNotEmpty() && createdBy.length <= 255) { "created_by must be 1 to 255 characters" }
        require(idempotencyKey.isNotEmpty() && idempotencyKey.length <= 128) { "idempotency key must be 1 to 128 characters" }
        require(title.isNotEmpty() && title.codePointCount(0, title.length) <= 200) { "title must be 1 to 200 characters" }
        require(targetRepo.isNotEmpty() && targetRepo.length <= 200) { "target repository must be 1 to 200 characters" }
        val normalizedBody = body?.takeIf { it.isNotEmpty() }
        require(normalizedBody == null || normalizedBody.codePointCount(0, normalizedBody.length) <= 8000) {
            "body must be at most 8000 characters"
        }
        return transaction(database) {
            fun replayOrConflict(existing: SelfDevGoal): SubmitResult =
                if (existing.title == title && existing.body == normalizedBody && existing.targetRepo == targetRepo) {
                    SubmitResult.Replayed(existing)
                } else {
                    SubmitResult.KeyConflict
                }
            findByKey(createdBy, idempotencyKey)?.let { return@transaction replayOrConflict(it) }

            val goalId = Ulid.generate(clock)
            val now = utcNow(clock)
            try {
                SelfDevGoalsTable.insert {
                    it[SelfDevGoalsTable.goalId] = goalId
                    it[SelfDevGoalsTable.createdBy] = createdBy
                    it[SelfDevGoalsTable.idempotencyKey] = idempotencyKey
                    it[SelfDevGoalsTable.title] = title
                    it[SelfDevGoalsTable.body] = normalizedBody
                    it[SelfDevGoalsTable.targetRepo] = targetRepo
                    it[SelfDevGoalsTable.state] = GoalState.GOAL_SUBMITTED.name
                    it[SelfDevGoalsTable.revision] = 0
                    it[SelfDevGoalsTable.version] = 0
                    it[SelfDevGoalsTable.createdAt] = now
                    it[SelfDevGoalsTable.updatedAt] = now
                }
            } catch (e: ExposedSQLException) {
                if (!e.isUniqueViolation()) throw e
                // Lost a race with a submission of the same key: the winner's row is the answer.
                return@transaction replayOrConflict(findByKey(createdBy, idempotencyKey) ?: throw e)
            }
            SubmitResult.Created(getIn(goalId) ?: error("goal vanished after insert"))
        }
    }

    fun get(goalId: String): SelfDevGoal? = transaction(database) { getIn(goalId) }

    /**
     * GOAL_SUBMITTED -> PLANNING with a fresh claim token for [ttl] (DB clock). Refused while the autopilot is paused for
     * everything. Of several concurrent claims, exactly one wins: the others see the goal already PLANNING.
     */
    fun claim(goalId: String, ttl: Duration): ClaimResult {
        require(!ttl.isNegative && !ttl.isZero) { "claim ttl must be positive" }
        return transaction(database) {
            val current = getIn(goalId) ?: return@transaction ClaimResult.Refused(ClaimRefusal.NOT_FOUND)
            if (current.state != GoalState.GOAL_SUBMITTED) return@transaction ClaimResult.Refused(ClaimRefusal.NOT_CLAIMABLE)
            requireAllowed(checkGoalTransition(current.state, GoalState.PLANNING, null, contextOf(current)))
            val token = UUID.randomUUID().toString()
            val now = utcNow(clock)
            val updated = execUpdate(
                """
                UPDATE selfdev_goals
                   SET state = 'PLANNING', claim_token = ?, claimed_until = ${DbTime.nowPlusSeconds()}, state_reason = NULL,
                       version = version + 1, updated_at = ?
                 WHERE goal_id = ? AND state = 'GOAL_SUBMITTED'
                   AND NOT EXISTS (SELECT 1 FROM selfdev_autopilot_control WHERE control_id = 1 AND paused = TRUE)
                """.trimIndent(),
                token, ttl.seconds, now, goalId,
            )
            if (updated == 1) return@transaction ClaimResult.Claimed(getIn(goalId) ?: error("goal vanished after claim"))
            val after = getIn(goalId) ?: return@transaction ClaimResult.Refused(ClaimRefusal.NOT_FOUND)
            val paused = execQuery("SELECT paused FROM selfdev_autopilot_control WHERE control_id = 1") { it.getBoolean(1) }.single()
            ClaimResult.Refused(if (paused && after.state == GoalState.GOAL_SUBMITTED) ClaimRefusal.PAUSED else ClaimRefusal.NOT_CLAIMABLE)
        }
    }

    /** Goals waiting for a Planner claim, oldest first. A read only: the claim itself is [claim]. */
    fun submittedGoalIds(limit: Int): List<String> = transaction(database) {
        execQuery(
            "SELECT goal_id FROM selfdev_goals WHERE state = 'GOAL_SUBMITTED' ORDER BY created_at, goal_id LIMIT ?",
            limit,
        ) { it.getString(1) }
    }

    /** Goals whose Planner claim has expired on the DB clock. A read only: the reset is [expireClaim]. */
    fun expiredClaimGoalIds(limit: Int): List<String> = transaction(database) {
        execQuery(
            "SELECT goal_id FROM selfdev_goals WHERE state = 'PLANNING' AND claimed_until <= ${DbTime.NOW} " +
                "ORDER BY claimed_until, goal_id LIMIT ?",
            limit,
        ) { it.getString(1) }
    }

    /**
     * U-16: an operator returns a planning-stage BLOCKED goal (PLAN_INVALID, PLAN_BUDGET_HOLD or PLAN_CALL_FAILED) to
     * GOAL_SUBMITTED, so the Planner makes a new Plan. Fenced by [expectedVersion]. The state machine refuses any other block.
     */
    fun recoverBlockedPlan(goalId: String, expectedVersion: Long): Transition<SelfDevGoal> =
        transition(goalId, expectedVersion, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED)

    /** PLANNING -> GOAL_SUBMITTED (CLAIM_EXPIRED) once the claim's deadline has passed on the DB clock. Returns false if not expired. */
    fun expireClaim(goalId: String): Boolean = transaction(database) {
        val current = getIn(goalId) ?: return@transaction false
        if (current.state != GoalState.PLANNING) return@transaction false
        requireAllowed(checkGoalTransition(current.state, GoalState.GOAL_SUBMITTED, AutopilotReason.CLAIM_EXPIRED, contextOf(current)))
        execUpdate(
            """
            UPDATE selfdev_goals
               SET state = 'GOAL_SUBMITTED', state_reason = 'CLAIM_EXPIRED', claim_token = NULL, claimed_until = NULL,
                   version = version + 1, updated_at = ?
             WHERE goal_id = ? AND state = 'PLANNING' AND claimed_until <= ${DbTime.NOW}
            """.trimIndent(),
            utcNow(clock), goalId,
        ) == 1
    }

    /**
     * Stores a validated Plan and its tasks, and moves the goal PLANNING -> PLAN_PROPOSED, in one transaction. The goal row is
     * updated first, fenced by [claim]; if it does not match, nothing else is written. The new revision is the goal's revision + 1,
     * and any PROPOSED plan of the goal becomes SUPERSEDED. Every task starts PENDING_APPROVAL (ADR section 6.1, stage 1).
     */
    fun commitPlan(claim: GoalClaim, plan: NewPlan, tasks: List<NewTask>): CommitResult {
        require(tasks.isNotEmpty()) { "a plan has at least one task" }
        return transaction(database) {
            val now = utcNow(clock)
            val current = getIn(claim.goalId)
            if (current == null || current.state != GoalState.PLANNING) return@transaction CommitResult.StaleClaim
            requireAllowed(checkGoalTransition(current.state, GoalState.PLAN_PROPOSED, null, contextOf(current)))
            val fenced = execUpdate(
                """
                UPDATE selfdev_goals
                   SET state = 'PLAN_PROPOSED', revision = revision + 1, state_reason = NULL, claim_token = NULL,
                       claimed_until = NULL, version = version + 1, updated_at = ?
                 WHERE goal_id = ? AND version = ? AND claim_token = ? AND state = 'PLANNING' AND claimed_until > ${DbTime.NOW}
                """.trimIndent(),
                now, claim.goalId, claim.version, claim.claimToken,
            )
            if (fenced != 1) return@transaction CommitResult.StaleClaim
            val revision = getIn(claim.goalId)?.revision ?: error("goal vanished after plan commit")
            execUpdate(
                "UPDATE selfdev_plans SET state = 'SUPERSEDED' WHERE goal_id = ? AND state = 'PROPOSED'",
                claim.goalId,
            )
            val (stored, storedTasks) = plans.insertPlan(this, claim.goalId, revision, plan, tasks, now)
            CommitResult.Committed(stored, storedTasks)
        }
    }

    /** PLANNING -> BLOCKED with a planning-stage reason, fenced by [claim]. Returns false when the fence does not match. */
    fun blockPlanning(claim: GoalClaim, reason: AutopilotReason): Boolean = transaction(database) {
        val current = getIn(claim.goalId) ?: return@transaction false
        if (current.state != GoalState.PLANNING) return@transaction false
        requireAllowed(checkGoalTransition(current.state, GoalState.BLOCKED, reason, contextOf(current)))
        execUpdate(
            """
            UPDATE selfdev_goals
               SET state = 'BLOCKED', state_reason = ?, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ?
             WHERE goal_id = ? AND version = ? AND claim_token = ? AND state = 'PLANNING'
            """.trimIndent(),
            reason.name, utcNow(clock), claim.goalId, claim.version, claim.claimToken,
        ) == 1
    }

    /**
     * A goal transition that is not a Planner write: approval of the first Plan, replanning, the dispatch guards, pause, resume,
     * completion and stop. The state machine decides whether it is allowed. The row must still be at [expectedVersion].
     */
    fun transition(goalId: String, expectedVersion: Long, to: GoalState, reason: AutopilotReason?): Transition<SelfDevGoal> =
        transaction(database) {
            val current = getIn(goalId) ?: return@transaction Transition.NotFound
            if (current.version != expectedVersion) return@transaction Transition.StaleVersion
            val decision = checkGoalTransition(current.state, to, reason, contextOf(current))
            if (decision is TransitionDecision.Refused) return@transaction Transition.Refused(decision.reason)

            // Resuming restores the reason the goal had before the pause; pausing keeps the reason it had for later resume.
            val resuming = current.state == GoalState.PAUSED
            val pausing = to == GoalState.PAUSED
            val newReason = if (resuming) current.resumeReason else reason
            val changed = execUpdate(
                """
                UPDATE selfdev_goals
                   SET state = ?, state_reason = ?, resume_state = ?, resume_reason = ?,
                       claim_token = CASE WHEN ? = 'PLANNING' THEN claim_token ELSE NULL END,
                       claimed_until = CASE WHEN ? = 'PLANNING' THEN claimed_until ELSE NULL END,
                       version = version + 1, updated_at = ?
                 WHERE goal_id = ? AND version = ?
                """.trimIndent(),
                to.name,
                newReason?.name,
                if (pausing) current.state.name else null,
                if (pausing) current.stateReason?.name else null,
                to.name,
                to.name,
                utcNow(clock),
                goalId,
                expectedVersion,
            )
            if (changed != 1) return@transaction Transition.StaleVersion
            Transition.Moved(getIn(goalId) ?: error("goal vanished after transition"))
        }

    private fun findByKey(createdBy: String, idempotencyKey: String): SelfDevGoal? =
        SelfDevGoalsTable.selectAll()
            .where { (SelfDevGoalsTable.createdBy eq createdBy) and (SelfDevGoalsTable.idempotencyKey eq idempotencyKey) }
            .firstOrNull()?.toGoal()

    private fun getIn(goalId: String): SelfDevGoal? =
        SelfDevGoalsTable.selectAll().where { SelfDevGoalsTable.goalId eq goalId }.firstOrNull()?.toGoal()

    private fun contextOf(goal: SelfDevGoal) = GoalContext(
        revision = goal.revision,
        blockedReason = if (goal.state == GoalState.BLOCKED) goal.stateReason else null,
        resumeState = goal.resumeState,
    )

    private fun requireAllowed(decision: TransitionDecision) {
        // The callers have already matched the state, so a refusal here is a bug in the caller, not an operator error.
        check(decision is TransitionDecision.Allowed) { "goal transition refused by the state machine" }
    }

    private fun ResultRow.toGoal() = SelfDevGoal(
        goalId = this[SelfDevGoalsTable.goalId],
        createdBy = this[SelfDevGoalsTable.createdBy],
        idempotencyKey = this[SelfDevGoalsTable.idempotencyKey],
        title = this[SelfDevGoalsTable.title],
        body = this[SelfDevGoalsTable.body],
        targetRepo = this[SelfDevGoalsTable.targetRepo],
        state = GoalState.valueOf(this[SelfDevGoalsTable.state]),
        stateReason = this[SelfDevGoalsTable.stateReason]?.let { AutopilotReason.valueOf(it) },
        resumeState = this[SelfDevGoalsTable.resumeState]?.let { GoalState.valueOf(it) },
        resumeReason = this[SelfDevGoalsTable.resumeReason]?.let { AutopilotReason.valueOf(it) },
        revision = this[SelfDevGoalsTable.revision],
        claimToken = this[SelfDevGoalsTable.claimToken],
        claimedUntil = this[SelfDevGoalsTable.claimedUntil],
        version = this[SelfDevGoalsTable.version],
        createdAt = this[SelfDevGoalsTable.createdAt],
        updatedAt = this[SelfDevGoalsTable.updatedAt],
        approvedBy = this[SelfDevGoalsTable.approvedBy],
    )
}
