package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Exposed definitions mirroring V61__task_requests.sql (ADR 0025). The migration is the source of truth for DDL. */
object TaskRequestsTable : Table("task_requests") {
    val requestId = char("request_id", 26)
    val projectId = char("project_id", 26)
    val requestedBy = varchar("requested_by", 255)
    val idempotencyKey = varchar("idempotency_key", 128)
    val title = varchar("title", 200)
    val state = varchar("state", 16)
    val decidedBy = varchar("decided_by", 255).nullable()
    val decidedAt = datetime("decided_at").nullable()
    val decisionNote = varchar("decision_note", 500).nullable()
    val runId = char("run_id", 26).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(requestId)
}

object TaskRequestEventsTable : Table("task_request_events") {
    val eventId = char("event_id", 26)
    val requestId = char("request_id", 26)
    val kind = varchar("kind", 16)
    val actorRef = char("actor_ref", 12)
    val runId = char("run_id", 26).nullable()
    val occurredAt = datetime("occurred_at")
    override val primaryKey = PrimaryKey(eventId)
}

enum class TaskRequestState { PENDING, APPROVED, REJECTED, CANCELLED }

/** A row of `task_requests`. [decidedBy] is an owner key and is never shown to a caller (ADR 0025 section 1). */
data class TaskRequest(
    val requestId: String,
    val projectId: String,
    val requestedBy: String,
    val idempotencyKey: String,
    val title: String,
    val state: TaskRequestState,
    val decidedBy: String?,
    val decidedAt: LocalDateTime?,
    val decisionNote: String?,
    val runId: String?,
    val createdAt: LocalDateTime,
)

/** Result of [TaskRequestRepository.submit]. */
sealed interface TaskRequestSubmitResult {
    val request: TaskRequest

    /** A new request was persisted in PENDING. */
    data class Created(override val request: TaskRequest) : TaskRequestSubmitResult

    /** The same (requester, idempotency key) with the same title exists: that request is returned. */
    data class Replayed(override val request: TaskRequest) : TaskRequestSubmitResult

    /** The same (requester, idempotency key) exists with another title. Nothing is written. */
    data class ConflictingTitle(override val request: TaskRequest) : TaskRequestSubmitResult
}

/** Result of a transition ([TaskRequestRepository.decide], [TaskRequestRepository.cancel]). Nothing is written unless [Moved]. */
sealed interface TaskRequestTransition {
    /** The request moved to its new state. */
    data class Moved(val request: TaskRequest) : TaskRequestTransition

    /** No request with that id, or (for a cancel) not made by the caller. */
    data object NotFound : TaskRequestTransition

    /** The caller made the request, so it may not decide it (ADR 0025 section 1). */
    data object OwnRequest : TaskRequestTransition

    /** The request is not PENDING (or changed under this call). [request] is its current row. */
    data class NotPending(val request: TaskRequest) : TaskRequestTransition
}

/** The key a request's Run is submitted under. Task API keys must not start with [REQUEST_RUN_KEY_PREFIX] (ADR 0025 section 3). */
const val REQUEST_RUN_KEY_PREFIX = "request:"

fun requestRunKey(requestId: String): String = REQUEST_RUN_KEY_PREFIX + requestId

/**
 * Approval requests for a Task (ADR 0025). Every change is one conditional UPDATE in one transaction with its audit event, so two
 * deciders racing on the same request change it once. The run of an approved request is created by [DevelopmentRunRepository.submitTask]
 * (idempotent per requester and key) and linked afterwards by [linkRun]; the route does both (ADR 0025 section 3).
 */
class TaskRequestRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** A new request in PENDING, or the request the same (requester, key) already made. */
    fun submit(projectId: String, requester: String, idempotencyKey: String, title: String): TaskRequestSubmitResult {
        findByKey(requester, idempotencyKey)?.let { return replayOrConflict(it, projectId, title) }
        val requestId = Ulid.generate(clock)
        try {
            transaction(database) {
                val now = utcNow(clock)
                TaskRequestsTable.insert {
                    it[TaskRequestsTable.requestId] = requestId
                    it[TaskRequestsTable.projectId] = projectId
                    it[requestedBy] = requester
                    it[TaskRequestsTable.idempotencyKey] = idempotencyKey
                    it[TaskRequestsTable.title] = title
                    it[state] = TaskRequestState.PENDING.name
                    it[createdAt] = now
                }
                event(requestId, "REQUESTED", requester, null, now)
            }
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
            // Lost the race with the same key: the winner's row is committed, so a fresh transaction sees it.
            val existing = findByKey(requester, idempotencyKey) ?: throw e
            return replayOrConflict(existing, projectId, title)
        }
        return TaskRequestSubmitResult.Created(get(projectId, requestId)!!)
    }

    /** The same key with another title or in another project is a conflict: a key names one request. */
    private fun replayOrConflict(existing: TaskRequest, projectId: String, title: String): TaskRequestSubmitResult =
        if (existing.title == title && existing.projectId == projectId) TaskRequestSubmitResult.Replayed(existing) else TaskRequestSubmitResult.ConflictingTitle(existing)

    /** The request, only when it belongs to [projectId]: another project's request is absent. */
    fun get(projectId: String, requestId: String): TaskRequest? = transaction(database) { readIn(requestId)?.takeIf { it.projectId == projectId } }

    private fun findByKey(requester: String, idempotencyKey: String): TaskRequest? = transaction(database) {
        TaskRequestsTable.selectAll()
            .where { (TaskRequestsTable.requestedBy eq requester) and (TaskRequestsTable.idempotencyKey eq idempotencyKey) }
            .firstOrNull()?.toTaskRequest()
    }

    /**
     * The requests [principal] made, newest first, at most [limit]. With [decidable], also the PENDING requests of the other requesters:
     * the ones this caller may decide.
     */
    fun list(projectId: String, principal: String, decidable: Boolean, limit: Int): List<TaskRequest> = transaction(database) {
        val own = TaskRequestsTable.requestedBy eq principal
        val visible = if (decidable) {
            own or ((TaskRequestsTable.state eq TaskRequestState.PENDING.name) and (TaskRequestsTable.requestedBy neq principal))
        } else {
            own
        }
        val condition = (TaskRequestsTable.projectId eq projectId) and visible
        TaskRequestsTable.selectAll()
            .where { condition }
            .orderBy(TaskRequestsTable.createdAt to SortOrder.DESC, TaskRequestsTable.requestId to SortOrder.DESC)
            .limit(limit)
            .map { it.toTaskRequest() }
    }

    /**
     * PENDING to APPROVED or REJECTED by [decider]. The decider must not be the requester: the check is first here, and the database
     * CHECK enforces it too. [note] is the rejecter's optional reason; it is stored only in the row.
     */
    fun decide(projectId: String, requestId: String, decider: String, approve: Boolean, note: String?): TaskRequestTransition = transaction(database) {
        val current = readIn(requestId)?.takeIf { it.projectId == projectId } ?: return@transaction TaskRequestTransition.NotFound
        if (current.requestedBy == decider) return@transaction TaskRequestTransition.OwnRequest
        val target = if (approve) TaskRequestState.APPROVED else TaskRequestState.REJECTED
        val now = utcNow(clock)
        val updated = TaskRequestsTable.update({
            (TaskRequestsTable.requestId eq requestId) and
                (TaskRequestsTable.projectId eq projectId) and
                (TaskRequestsTable.state eq TaskRequestState.PENDING.name) and
                (TaskRequestsTable.requestedBy neq decider)
        }) {
            it[state] = target.name
            it[decidedBy] = decider
            it[decidedAt] = now
            it[decisionNote] = note
        }
        val row = readIn(requestId) ?: return@transaction TaskRequestTransition.NotFound
        if (updated == 0) return@transaction TaskRequestTransition.NotPending(row)
        event(requestId, if (approve) "APPROVED" else "REJECTED", decider, null, now)
        TaskRequestTransition.Moved(row)
    }

    /** PENDING to CANCELLED by the requester. A request another caller made is [TaskRequestTransition.NotFound]. */
    fun cancel(projectId: String, requestId: String, requester: String): TaskRequestTransition = transaction(database) {
        val current = readIn(requestId)?.takeIf { it.projectId == projectId && it.requestedBy == requester } ?: return@transaction TaskRequestTransition.NotFound
        val now = utcNow(clock)
        val updated = TaskRequestsTable.update({
            (TaskRequestsTable.requestId eq requestId) and
                (TaskRequestsTable.projectId eq projectId) and
                (TaskRequestsTable.state eq TaskRequestState.PENDING.name) and
                (TaskRequestsTable.requestedBy eq requester)
        }) {
            it[state] = TaskRequestState.CANCELLED.name
            it[decidedAt] = now
        }
        val row = readIn(requestId) ?: return@transaction TaskRequestTransition.NotFound
        if (updated == 0) return@transaction TaskRequestTransition.NotPending(row)
        event(requestId, "CANCELLED", current.requestedBy, null, now)
        TaskRequestTransition.Moved(row)
    }

    /**
     * Links the Run [run] to an APPROVED request that has none, and writes RUN_LINKED. Returns the request, or null when it is not
     * APPROVED or already has a run (the link is done once).
     */
    fun linkRun(requestId: String, run: String, actor: String): TaskRequest? = transaction(database) {
        val updated = TaskRequestsTable.update({
            (TaskRequestsTable.requestId eq requestId) and
                (TaskRequestsTable.state eq TaskRequestState.APPROVED.name) and
                TaskRequestsTable.runId.isNull()
        }) {
            it[runId] = run
        }
        if (updated == 0) return@transaction null
        event(requestId, "RUN_LINKED", actor, run, utcNow(clock))
        readIn(requestId)
    }

    private fun readIn(requestId: String): TaskRequest? =
        TaskRequestsTable.selectAll().where { TaskRequestsTable.requestId eq requestId }.firstOrNull()?.toTaskRequest()

    /** An append-only event, in the caller's transaction. The actor is never stored as the key: its [SelfDevAssigneeRepository.actorRef]. */
    private fun Transaction.event(requestId: String, kind: String, actor: String, run: String?, now: LocalDateTime) {
        TaskRequestEventsTable.insert {
            it[eventId] = Ulid.generate(clock)
            it[TaskRequestEventsTable.requestId] = requestId
            it[TaskRequestEventsTable.kind] = kind
            it[actorRef] = SelfDevAssigneeRepository.actorRef(actor)
            it[runId] = run
            it[occurredAt] = now
        }
    }

    private fun ResultRow.toTaskRequest() = TaskRequest(
        requestId = this[TaskRequestsTable.requestId],
        projectId = this[TaskRequestsTable.projectId],
        requestedBy = this[TaskRequestsTable.requestedBy],
        idempotencyKey = this[TaskRequestsTable.idempotencyKey],
        title = this[TaskRequestsTable.title],
        state = TaskRequestState.valueOf(this[TaskRequestsTable.state]),
        decidedBy = this[TaskRequestsTable.decidedBy],
        decidedAt = this[TaskRequestsTable.decidedAt],
        decisionNote = this[TaskRequestsTable.decisionNote],
        runId = this[TaskRequestsTable.runId],
        createdAt = this[TaskRequestsTable.createdAt],
    )
}
