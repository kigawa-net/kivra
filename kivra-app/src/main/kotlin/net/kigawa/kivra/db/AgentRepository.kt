package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.core.AgentState
import net.kigawa.kivra.core.ExecutionSessionState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import net.kigawa.kivra.core.leaveUnknownOn
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class AgentRecord(
    val agentId: String,
    val runId: RunId,
    val role: String,
    val state: AgentState,
    val version: Long,
    val createdAt: LocalDateTime,
)

/** Kivra-side binding to exkes. The exkes_* ids are references only; exkes owns their state (design #1 section 5.3). */
data class SessionRecord(
    val sessionId: String,
    val runId: RunId,
    val agentId: String?,
    val state: ExecutionSessionState,
    val workspaceId: String?,
    val runtimeId: String?,
    val executionId: String?,
    val observedStatus: String?,
    val version: Long,
)

/**
 * Agent / ExecutionSession persistence (M2). Same fencing rules as the Run and the Operation Journal:
 * every write starts with [requireLease] (lease valid on the DB clock, no Hold, no pending Cancel unless
 * [allowCancelRequested] for the wind-down) and every status change is a CAS on `version` that must follow the
 * M0 state machines. Reads need no lease.
 */
class AgentRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {

    fun createAgent(lease: Lease, role: String): AgentRecord = transaction(database) {
        requireLease(lease)
        val id = Ulid.generate(clock)
        val now = utcNow(clock)
        AgentsTable.insert {
            it[agentId] = id
            it[runId] = lease.runId.value
            it[AgentsTable.role] = role
            it[state] = AgentState.CREATED.name
            it[createdAt] = now
            it[updatedAt] = now
        }
        AgentsTable.selectAll().where { AgentsTable.agentId eq id }.single().toAgent()
    }

    fun transitionAgent(lease: Lease, agentId: String, expectedVersion: Long, to: AgentState, allowCancelRequested: Boolean = false): AgentRecord =
        transaction(database) {
            requireLease(lease, allowCancelRequested)
            val row = AgentsTable.selectAll().where { (AgentsTable.agentId eq agentId) and (AgentsTable.runId eq lease.runId.value) }
                .firstOrNull() ?: throw NoSuchElementException("agent $agentId not found in run ${lease.runId}")
            val from = AgentState.valueOf(row[AgentsTable.state])
            if (row[AgentsTable.version] != expectedVersion) {
                throw VersionConflictException("agent $agentId is at version ${row[AgentsTable.version]}, expected $expectedVersion")
            }
            if (!from.canTransitionTo(to)) throw InvalidTransitionException("agent $agentId: $from -> $to is not allowed")
            val n = AgentsTable.update({ (AgentsTable.agentId eq agentId) and (AgentsTable.version eq expectedVersion) }) {
                it[state] = to.name
                it[version] = expectedVersion + 1
                it[updatedAt] = utcNow(clock)
            }
            if (n != 1) throw VersionConflictException("agent $agentId changed concurrently")
            AgentsTable.selectAll().where { AgentsTable.agentId eq agentId }.single().toAgent()
        }

    fun agentsOf(runId: RunId): List<AgentRecord> = transaction(database) {
        AgentsTable.selectAll().where { AgentsTable.runId eq runId.value }.orderBy(AgentsTable.agentId).map { it.toAgent() }
    }

    fun createSession(lease: Lease, agentId: String): SessionRecord = transaction(database) {
        requireLease(lease)
        val id = Ulid.generate(clock)
        val now = utcNow(clock)
        ExecutionSessionsTable.insert {
            it[sessionId] = id
            it[runId] = lease.runId.value
            it[ExecutionSessionsTable.agentId] = agentId
            it[state] = ExecutionSessionState.REQUESTED.name
            it[createdAt] = now
            it[updatedAt] = now
        }
        ExecutionSessionsTable.selectAll().where { ExecutionSessionsTable.sessionId eq id }.single().toSession()
    }

    fun sessionOf(agentId: String): SessionRecord? = transaction(database) {
        ExecutionSessionsTable.selectAll().where { ExecutionSessionsTable.agentId eq agentId }.firstOrNull()?.toSession()
    }

    /** Moves the session to [to] (when not null) and records newly learned exkes references (existing ones are kept). */
    fun updateSession(
        lease: Lease,
        sessionId: String,
        expectedVersion: Long,
        to: ExecutionSessionState? = null,
        workspaceId: String? = null,
        runtimeId: String? = null,
        executionId: String? = null,
        observedStatus: String? = null,
        allowCancelRequested: Boolean = false,
    ): SessionRecord = transaction(database) {
        requireLease(lease, allowCancelRequested)
        val row = ExecutionSessionsTable.selectAll().where {
            (ExecutionSessionsTable.sessionId eq sessionId) and (ExecutionSessionsTable.runId eq lease.runId.value)
        }.firstOrNull() ?: throw NoSuchElementException("session $sessionId not found in run ${lease.runId}")
        val from = ExecutionSessionState.valueOf(row[ExecutionSessionsTable.state])
        if (row[ExecutionSessionsTable.version] != expectedVersion) {
            throw VersionConflictException("session $sessionId is at version ${row[ExecutionSessionsTable.version]}, expected $expectedVersion")
        }
        // UNKNOWN is left only towards the exkes state that was actually observed (ExecutionSessionState.leaveUnknownOn).
        if (to != null && from == ExecutionSessionState.UNKNOWN && from.leaveUnknownOn(to) == null) {
            throw InvalidTransitionException("session $sessionId: UNKNOWN -> $to is not an observed state")
        }
        if (to != null && from != ExecutionSessionState.UNKNOWN && !from.canTransitionTo(to)) {
            throw InvalidTransitionException("session $sessionId: $from -> $to is not allowed")
        }
        val n = ExecutionSessionsTable.update({ (ExecutionSessionsTable.sessionId eq sessionId) and (ExecutionSessionsTable.version eq expectedVersion) }) {
            if (to != null) it[state] = to.name
            if (workspaceId != null) it[ExecutionSessionsTable.workspaceId] = workspaceId
            if (runtimeId != null) it[ExecutionSessionsTable.runtimeId] = runtimeId
            if (executionId != null) it[ExecutionSessionsTable.executionId] = executionId
            if (observedStatus != null) it[ExecutionSessionsTable.observedStatus] = observedStatus
            it[version] = expectedVersion + 1
            it[updatedAt] = utcNow(clock)
        }
        if (n != 1) throw VersionConflictException("session $sessionId changed concurrently")
        ExecutionSessionsTable.selectAll().where { ExecutionSessionsTable.sessionId eq sessionId }.single().toSession()
    }

    /**
     * Startup reconciliation, minimal version: non-terminal runs of the Agent Manager's stages (PREPARING / DEVELOPING /
     * RETRY_WAITING) that no live worker owns (no lease, or expired by more than [graceSeconds] on the DB clock).
     * Resuming one is just [net.kigawa.kivra.agent.AgentManager.run]: every step is idempotent through the Operation Journal.
     */
    fun resumableRuns(graceSeconds: Long = 0): List<RunId> = transaction(database) {
        execQuery(
            """
            SELECT run_id FROM development_runs
             WHERE state IN ('PREPARING', 'DEVELOPING', 'RETRY_WAITING')
               AND hold_reason IS NULL
               AND (lease_owner IS NULL OR lease_expires_at < ${DbTime.nowMinusSeconds()})
             ORDER BY run_id
            """.trimIndent(),
            graceSeconds,
        ) { RunId(it.getString(1)) }
    }

    private fun ResultRow.toAgent() = AgentRecord(
        agentId = this[AgentsTable.agentId],
        runId = RunId(this[AgentsTable.runId]),
        role = this[AgentsTable.role],
        state = AgentState.valueOf(this[AgentsTable.state]),
        version = this[AgentsTable.version],
        createdAt = this[AgentsTable.createdAt],
    )

    private fun ResultRow.toSession() = SessionRecord(
        sessionId = this[ExecutionSessionsTable.sessionId],
        runId = RunId(this[ExecutionSessionsTable.runId]),
        agentId = this[ExecutionSessionsTable.agentId],
        state = ExecutionSessionState.valueOf(this[ExecutionSessionsTable.state]),
        workspaceId = this[ExecutionSessionsTable.workspaceId],
        runtimeId = this[ExecutionSessionsTable.runtimeId],
        executionId = this[ExecutionSessionsTable.executionId],
        observedStatus = this[ExecutionSessionsTable.observedStatus],
        version = this[ExecutionSessionsTable.version],
    )
}
