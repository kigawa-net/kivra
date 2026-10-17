package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Persistence of coding attempts (V14, design: Issue #187 design-coding-runtime.md section 2). Repository only: no port, no API,
 * no agent. Every state change is one conditional UPDATE whose affected-row count decides the outcome (CAS). A refused call
 * changes nothing and returns a result value; it does not throw.
 *
 * Lease and queue expiry are judged by the DB clock (NOW(6)), so expiry does not depend on the clocks of the nodes. Timestamps
 * written as created_at / updated_at come from the injected [Clock], like DevelopmentRunRepository.
 *
 * Values in this repository are fixed codes, identifiers or digests. Nothing here logs, and nothing stores a prompt, model
 * output, process output or a credential.
 */

/** Attempt states. QUEUED, CLAIMED and RUNNING are active; the others are terminal (see V14). */
enum class CodingAttemptState {
    QUEUED, CLAIMED, RUNNING, RESULT_RECEIVED, FAILED, CANCELLED, EXPIRED, ORPHANED;

    val active: Boolean get() = this == QUEUED || this == CLAIMED || this == RUNNING
}

/** One attempt. Holds no prompt, output or secret. */
data class CodingAttempt(
    val attemptId: String,
    val runId: String,
    val attemptNo: Int,
    val state: CodingAttemptState,
    val claimedBy: String?,
    val leaseGeneration: Long,
    val leaseExpiresAt: LocalDateTime?,
    val queuedExpiresAt: LocalDateTime,
    val baseSha: String,
    val resultDigest: String?,
    val exitStatus: Int?,
    val errorCode: String?,
    val sessionId: String?,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Why a call changed nothing. Fixed codes; no free text. */
enum class CodingRefusal {
    /** No attempt with this id. */
    NOT_FOUND,
    /** claim: the attempt is not QUEUED (already claimed, or terminal). */
    NOT_QUEUED,
    /** claim: queued_expires_at has passed. The attempt is still QUEUED until [CodingAttemptRepository.expire] runs. */
    QUEUE_EXPIRED,
    /** heartbeat / start / complete / fail: the generation does not match the current lease (a stale holder). */
    STALE_GENERATION,
    /** heartbeat / start / complete / fail: the lease has expired on the DB clock. */
    LEASE_EXPIRED,
    /** heartbeat / start / complete / fail: the attempt is not CLAIMED or RUNNING (QUEUED, or terminal). */
    NOT_ACTIVE,
}

sealed interface CodingAttemptResult {
    data class Ok(val attempt: CodingAttempt) : CodingAttemptResult
    data class Refused(val reason: CodingRefusal) : CodingAttemptResult
}

sealed interface EnqueueResult {
    /** A new QUEUED attempt was created. */
    data class Enqueued(val attempt: CodingAttempt) : EnqueueResult
    /** The Run already has an active attempt; it is returned and nothing is created. */
    data class Existing(val attempt: CodingAttempt) : EnqueueResult
    /** The Run has no active attempt but already has a terminal one, and the caller asked not to queue again. Nothing is created. */
    data class Settled(val latest: CodingAttempt) : EnqueueResult
    /** No development_runs row with this run id. */
    data object RunNotFound : EnqueueResult
}

/** Attempts that [CodingAttemptRepository.expire] moved in this call. */
data class ExpireResult(val expiredQueued: List<String>, val orphaned: List<String>)

/** Exposed mirror of V14 `coding_attempts` (the migration is the source of truth). active_flag is generated and not mapped. */
internal object CodingAttemptsTable : Table("coding_attempts") {
    val attemptId = char("attempt_id", 26)
    val runId = char("run_id", 26)
    val attemptNo = integer("attempt_no")
    val state = varchar("state", 16)
    val claimedBy = varchar("claimed_by", 64).nullable()
    val leaseGeneration = long("lease_generation").default(0)
    val leaseExpiresAt = datetime("lease_expires_at").nullable()
    val queuedExpiresAt = datetime("queued_expires_at")
    val baseSha = varchar("base_sha", 64)
    val resultDigest = char("result_digest", 64).nullable()
    val exitStatus = integer("exit_status").nullable()
    val errorCode = varchar("error_code", 64).nullable()
    val sessionId = varchar("session_id", 128).nullable()
    val version = long("version").default(0)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(attemptId)
}

/** Exposed mirror of V14 `coding_events`. Append-only. */
internal object CodingEventsTable : Table("coding_events") {
    val attemptId = char("attempt_id", 26)
    val seq = integer("seq")
    val type = varchar("type", 32)
    val payloadJson = varchar("payload_json", 512).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(attemptId, seq)
}

class CodingAttemptRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Queues a new attempt for [runId] with [baseSha] fixed now. Idempotent: when the Run already has an active attempt, that
     * attempt is returned. The Run row is locked (FOR UPDATE) for the duration, so two enqueues for one Run are serialized and
     * attempt_no is allocated without a gap race.
     */
    fun enqueue(runId: String, baseSha: String, queueTtl: Duration, allowAfterSettled: Boolean = true): EnqueueResult {
        require(BASE_SHA.matches(baseSha)) { "base_sha must be a lowercase hex commit id" }
        require(!queueTtl.isNegative && !queueTtl.isZero) { "queue ttl must be positive" }
        return transaction(database) {
            val locked = DevelopmentRunsTable.selectAll()
                .where { DevelopmentRunsTable.runId eq runId }
                .forUpdate()
                .firstOrNull()
            if (locked == null) return@transaction EnqueueResult.RunNotFound

            activeIn(this, runId)?.let { return@transaction EnqueueResult.Existing(it) }
            // With allowAfterSettled = false, a Run whose attempts all ended is not queued again (no retry loop). Checked under the
            // Run row lock, so it cannot race with another enqueue for the same Run.
            if (!allowAfterSettled) latestIn(this, runId)?.let { return@transaction EnqueueResult.Settled(it) }

            val nextNo = execQuery("SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM coding_attempts WHERE run_id = ?", runId) { it.getInt(1) }.single()
            val attemptId = Ulid.generate(clock)
            val now = utcNow(clock)
            execUpdate(
                """
                INSERT INTO coding_attempts
                    (attempt_id, run_id, attempt_no, state, lease_generation, queued_expires_at, base_sha, version, created_at, updated_at)
                VALUES (?, ?, ?, 'QUEUED', 0, ${DbTime.nowPlusSeconds()}, ?, 0, ?, ?)
                """.trimIndent(),
                attemptId, runId, nextNo, queueTtl.seconds, baseSha, now, now,
            )
            appendEvent(this, attemptId, "QUEUED", now)
            EnqueueResult.Enqueued(getIn(this, attemptId) ?: error("coding attempt vanished after insert"))
        }
    }

    /**
     * Claims a QUEUED attempt for [agent]. CAS: succeeds only for QUEUED with an unexpired queue. On success the lease generation
     * is bumped (the fencing token for this holder) and the lease runs for [leaseTtl] from the DB clock. Of several concurrent
     * claims, exactly one wins.
     */
    fun claim(attemptId: String, agent: String, leaseTtl: Duration): CodingAttemptResult {
        require(AGENT_ID.matches(agent)) { "agent identifier has an invalid form" }
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "lease ttl must be positive" }
        return transaction(database) {
            val now = utcNow(clock)
            val updated = execUpdate(
                """
                UPDATE coding_attempts
                   SET state = 'CLAIMED', claimed_by = ?, lease_generation = lease_generation + 1,
                       lease_expires_at = ${DbTime.nowPlusSeconds()}, version = version + 1, updated_at = ?
                 WHERE attempt_id = ? AND state = 'QUEUED' AND queued_expires_at > ${DbTime.NOW}
                """.trimIndent(),
                agent, leaseTtl.seconds, now, attemptId,
            )
            if (updated != 1) return@transaction CodingAttemptResult.Refused(classify(this, attemptId, claiming = true))
            appendEvent(this, attemptId, "CLAIMED", now)
            CodingAttemptResult.Ok(getIn(this, attemptId) ?: error("coding attempt vanished after claim"))
        }
    }

    /**
     * CLAIMED -> RUNNING, with the lease extended by [leaseTtl]. Only the current generation may do this. The transition is
     * recorded as a RUNNING event.
     */
    fun start(attemptId: String, generation: Long, leaseTtl: Duration): CodingAttemptResult {
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "lease ttl must be positive" }
        return transaction(database) {
            val now = utcNow(clock)
            val updated = execUpdate(
                """
                UPDATE coding_attempts
                   SET state = 'RUNNING', lease_expires_at = ${DbTime.nowPlusSeconds()}, version = version + 1, updated_at = ?
                 WHERE attempt_id = ? AND lease_generation = ? AND state = 'CLAIMED' AND lease_expires_at > ${DbTime.NOW}
                """.trimIndent(),
                leaseTtl.seconds, now, attemptId, generation,
            )
            if (updated != 1) return@transaction CodingAttemptResult.Refused(classify(this, attemptId, claiming = false, generation = generation))
            appendEvent(this, attemptId, "RUNNING", now)
            CodingAttemptResult.Ok(getIn(this, attemptId) ?: error("coding attempt vanished after start"))
        }
    }

    /**
     * Extends the lease of the current generation for [leaseTtl]. Accepted in CLAIMED or RUNNING only. [progressPayload] is an
     * optional small fixed-field JSON for a PROGRESS event. Heartbeats normally write no event; a progress event is written only
     * while the attempt has fewer than [MAX_PROGRESS_EVENTS] of them, so a chatty agent cannot grow the table without limit.
     */
    fun heartbeat(attemptId: String, generation: Long, leaseTtl: Duration, progressPayload: String? = null): CodingAttemptResult {
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "lease ttl must be positive" }
        return transaction(database) {
            val now = utcNow(clock)
            val updated = execUpdate(
                """
                UPDATE coding_attempts
                   SET lease_expires_at = ${DbTime.nowPlusSeconds()}, version = version + 1, updated_at = ?
                 WHERE attempt_id = ? AND lease_generation = ? AND state IN ('CLAIMED', 'RUNNING') AND lease_expires_at > ${DbTime.NOW}
                """.trimIndent(),
                leaseTtl.seconds, now, attemptId, generation,
            )
            if (updated != 1) return@transaction CodingAttemptResult.Refused(classify(this, attemptId, claiming = false, generation = generation))
            if (progressPayload != null) {
                val written = execQuery("SELECT COUNT(*) FROM coding_events WHERE attempt_id = ? AND type = 'PROGRESS'", attemptId) { it.getLong(1) }.single()
                if (written < MAX_PROGRESS_EVENTS) appendEvent(this, attemptId, "PROGRESS", now, progressPayload)
            }
            CodingAttemptResult.Ok(getIn(this, attemptId) ?: error("coding attempt vanished after heartbeat"))
        }
    }

    /**
     * QUEUED -> CANCELLED: the attempt was never claimed, so nothing runs. A CLAIMED or RUNNING attempt is not touched here (its holder
     * learns of the cancel another way). Refused with NOT_ACTIVE for any other state.
     */
    fun cancelQueued(attemptId: String): CodingAttemptResult = transaction(database) {
        val now = utcNow(clock)
        val updated = execUpdate(
            "UPDATE coding_attempts SET state = 'CANCELLED', error_code = 'CANCELLED', version = version + 1, updated_at = ? " +
                "WHERE attempt_id = ? AND state = 'QUEUED'",
            now, attemptId,
        )
        if (updated != 1) return@transaction CodingAttemptResult.Refused(classify(this, attemptId, claiming = false))
        appendEvent(this, attemptId, "CANCELLED", now)
        CodingAttemptResult.Ok(getIn(this, attemptId) ?: error("coding attempt vanished after cancel"))
    }

    /** Ids of QUEUED attempts whose queue has not expired, oldest first. A hint only: [claim] is the CAS that decides. */
    fun listClaimable(limit: Int): List<String> {
        require(limit > 0) { "limit must be positive" }
        return transaction(database) {
            execQuery(
                "SELECT attempt_id FROM coding_attempts WHERE state = 'QUEUED' AND queued_expires_at > ${DbTime.NOW} " +
                    "ORDER BY created_at, attempt_id LIMIT ?",
                limit,
            ) { it.getString(1) }
        }
    }

    /** The attempt with the highest attempt_no of [runId] (active or terminal), or null. */
    fun latestAttempt(runId: String): CodingAttempt? = transaction(database) { latestIn(this, runId) }

    /**
     * ORPHANED or EXPIRED attempts whose Run is still DEVELOPING: the ones reconcile has to settle. Oldest first, at most [limit]. The
     * join keeps attempts of Runs that were already settled from using up the limit.
     */
    fun unsettledOfDevelopingRuns(limit: Int): List<CodingAttempt> {
        require(limit > 0) { "limit must be positive" }
        return transaction(database) {
            val ids = execQuery(
                "SELECT a.attempt_id FROM coding_attempts a JOIN development_runs r ON r.run_id = a.run_id " +
                    "WHERE a.state IN ('ORPHANED', 'EXPIRED') AND r.state = 'DEVELOPING' " +
                    "ORDER BY a.updated_at, a.attempt_id LIMIT ?",
                limit,
            ) { it.getString(1) }
            ids.mapNotNull { getIn(this, it) }
        }
    }

    /**
     * Terminal success: CLAIMED or RUNNING -> RESULT_RECEIVED, with the result digest, the exit status and an optional Claude CLI
     * session identifier. Only the current generation may do this. The row and its event are written in one transaction.
     */
    fun complete(attemptId: String, generation: Long, resultDigest: String, exitStatus: Int, sessionId: String?): CodingAttemptResult {
        require(SHA256_HEX.matches(resultDigest)) { "result digest must be a SHA-256 hex digest" }
        require(sessionId == null || SESSION_ID.matches(sessionId)) { "session identifier has an invalid form" }
        return finishUpdate(attemptId, generation) { now ->
            execUpdate(
                """
                UPDATE coding_attempts
                   SET state = 'RESULT_RECEIVED', result_digest = ?, exit_status = ?, session_id = ?,
                       version = version + 1, updated_at = ?
                 WHERE attempt_id = ? AND lease_generation = ? AND state IN ('CLAIMED', 'RUNNING') AND lease_expires_at > ${DbTime.NOW}
                """.trimIndent(),
                resultDigest, exitStatus, sessionId, now, attemptId, generation,
            )
        }
    }

    /**
     * Terminal failure: CLAIMED or RUNNING -> FAILED (or CANCELLED when the holder acknowledges a cancel request). [errorCode] is a
     * fixed code such as TIMEOUT or CLI_NONZERO. Only the current generation may do this.
     */
    fun fail(attemptId: String, generation: Long, errorCode: String, state: CodingAttemptState = CodingAttemptState.FAILED): CodingAttemptResult {
        require(state == CodingAttemptState.FAILED || state == CodingAttemptState.CANCELLED) { "fail takes FAILED or CANCELLED" }
        require(ERROR_CODE.matches(errorCode)) { "error code must be a fixed upper-case code" }
        return finishUpdate(attemptId, generation) { now ->
            execUpdate(
                """
                UPDATE coding_attempts
                   SET state = ?, error_code = ?, version = version + 1, updated_at = ?
                 WHERE attempt_id = ? AND lease_generation = ? AND state IN ('CLAIMED', 'RUNNING') AND lease_expires_at > ${DbTime.NOW}
                """.trimIndent(),
                state.name, errorCode, now, attemptId, generation,
            )
        }
    }

    /**
     * Applies the timeouts on the DB clock, at most [limit] rows per call: QUEUED with an expired queue -> EXPIRED; CLAIMED or
     * RUNNING with an expired lease -> ORPHANED. Each row is one CAS, so a concurrent heartbeat or claim is never overwritten.
     */
    fun expire(limit: Int = DEFAULT_EXPIRE_LIMIT): ExpireResult {
        require(limit > 0) { "limit must be positive" }
        return transaction(database) {
            val now = utcNow(clock)
            val queued = execQuery(
                "SELECT attempt_id FROM coding_attempts WHERE state = 'QUEUED' AND queued_expires_at <= ${DbTime.NOW} LIMIT ?",
                limit,
            ) { it.getString(1) }
            val expired = queued.filter { id ->
                val n = execUpdate(
                    "UPDATE coding_attempts SET state = 'EXPIRED', version = version + 1, updated_at = ? " +
                        "WHERE attempt_id = ? AND state = 'QUEUED' AND queued_expires_at <= ${DbTime.NOW}",
                    now, id,
                )
                if (n == 1) appendEvent(this, id, "EXPIRED", now)
                n == 1
            }

            val leased = execQuery(
                "SELECT attempt_id FROM coding_attempts WHERE state IN ('CLAIMED', 'RUNNING') AND lease_expires_at <= ${DbTime.NOW} LIMIT ?",
                limit,
            ) { it.getString(1) }
            val orphaned = leased.filter { id ->
                val n = execUpdate(
                    "UPDATE coding_attempts SET state = 'ORPHANED', version = version + 1, updated_at = ? " +
                        "WHERE attempt_id = ? AND state IN ('CLAIMED', 'RUNNING') AND lease_expires_at <= ${DbTime.NOW}",
                    now, id,
                )
                if (n == 1) appendEvent(this, id, "ORPHANED", now)
                n == 1
            }
            ExpireResult(expiredQueued = expired, orphaned = orphaned)
        }
    }

    /** The active attempt of [runId] (QUEUED, CLAIMED or RUNNING), or null. At most one exists (V14). */
    fun findActive(runId: String): CodingAttempt? = transaction(database) { activeIn(this, runId) }

    fun get(attemptId: String): CodingAttempt? = transaction(database) { getIn(this, attemptId) }

    /** Event sequence of one attempt, oldest first. */
    fun events(attemptId: String): List<CodingEvent> = transaction(database) {
        CodingEventsTable.selectAll()
            .where { CodingEventsTable.attemptId eq attemptId }
            .orderBy(CodingEventsTable.seq to SortOrder.ASC)
            .map { CodingEvent(it[CodingEventsTable.seq], it[CodingEventsTable.type], it[CodingEventsTable.payloadJson]) }
    }

    private fun finishUpdate(attemptId: String, generation: Long, cas: Transaction.(LocalDateTime) -> Int): CodingAttemptResult =
        transaction(database) {
            val now = utcNow(clock)
            val updated = cas(now)
            if (updated != 1) return@transaction CodingAttemptResult.Refused(classify(this, attemptId, claiming = false, generation = generation))
            val row = getIn(this, attemptId) ?: error("coding attempt vanished after finish")
            appendEvent(this, attemptId, row.state.name, now)
            CodingAttemptResult.Ok(row)
        }

    /**
     * Why a conditional UPDATE matched nothing. Read after the failed CAS, so the reason is advisory: a concurrent change can
     * make it differ from the state the CAS saw, but the refused call still changed nothing.
     */
    private fun classify(tx: Transaction, attemptId: String, claiming: Boolean, generation: Long? = null): CodingRefusal {
        val row = tx.execQuery(
            "SELECT state, lease_generation, lease_expires_at > ${DbTime.NOW}, queued_expires_at > ${DbTime.NOW} " +
                "FROM coding_attempts WHERE attempt_id = ?",
            attemptId,
        ) { rs ->
            Liveness(rs.getString(1), rs.getLong(2), rs.getBoolean(3), rs.getBoolean(4))
        }.singleOrNull() ?: return CodingRefusal.NOT_FOUND
        if (claiming) {
            return when {
                row.state != "QUEUED" -> CodingRefusal.NOT_QUEUED
                !row.queueLive -> CodingRefusal.QUEUE_EXPIRED
                else -> CodingRefusal.NOT_QUEUED
            }
        }
        return when {
            row.state !in setOf("CLAIMED", "RUNNING") -> CodingRefusal.NOT_ACTIVE
            generation != null && row.generation != generation -> CodingRefusal.STALE_GENERATION
            !row.leaseLive -> CodingRefusal.LEASE_EXPIRED
            else -> CodingRefusal.NOT_ACTIVE
        }
    }

    private data class Liveness(val state: String, val generation: Long, val leaseLive: Boolean, val queueLive: Boolean)

    private fun activeIn(tx: Transaction, runId: String): CodingAttempt? = with(tx) {
        CodingAttemptsTable.selectAll()
            .where { (CodingAttemptsTable.runId eq runId) and (CodingAttemptsTable.state inList ACTIVE_STATES) }
            .firstOrNull()
            ?.toAttempt()
    }

    private fun latestIn(tx: Transaction, runId: String): CodingAttempt? = with(tx) {
        CodingAttemptsTable.selectAll()
            .where { CodingAttemptsTable.runId eq runId }
            .orderBy(CodingAttemptsTable.attemptNo to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toAttempt()
    }

    private fun getIn(tx: Transaction, attemptId: String): CodingAttempt? = with(tx) {
        CodingAttemptsTable.selectAll().where { CodingAttemptsTable.attemptId eq attemptId }.firstOrNull()?.toAttempt()
    }

    private fun appendEvent(tx: Transaction, attemptId: String, type: String, now: LocalDateTime, payloadJson: String? = null) {
        require(payloadJson == null || payloadJson.length <= MAX_PAYLOAD_CHARS) { "event payload is too large" }
        val seq = tx.execQuery("SELECT COALESCE(MAX(seq), 0) + 1 FROM coding_events WHERE attempt_id = ?", attemptId) { it.getInt(1) }.single()
        tx.execUpdate(
            "INSERT INTO coding_events (attempt_id, seq, type, payload_json, created_at) VALUES (?, ?, ?, ?, ?)",
            attemptId, seq, type, payloadJson, now,
        )
    }

    private fun ResultRow.toAttempt() = CodingAttempt(
        attemptId = this[CodingAttemptsTable.attemptId],
        runId = this[CodingAttemptsTable.runId],
        attemptNo = this[CodingAttemptsTable.attemptNo],
        state = CodingAttemptState.valueOf(this[CodingAttemptsTable.state]),
        claimedBy = this[CodingAttemptsTable.claimedBy],
        leaseGeneration = this[CodingAttemptsTable.leaseGeneration],
        leaseExpiresAt = this[CodingAttemptsTable.leaseExpiresAt],
        queuedExpiresAt = this[CodingAttemptsTable.queuedExpiresAt],
        baseSha = this[CodingAttemptsTable.baseSha],
        resultDigest = this[CodingAttemptsTable.resultDigest],
        exitStatus = this[CodingAttemptsTable.exitStatus],
        errorCode = this[CodingAttemptsTable.errorCode],
        sessionId = this[CodingAttemptsTable.sessionId],
        version = this[CodingAttemptsTable.version],
        createdAt = this[CodingAttemptsTable.createdAt],
        updatedAt = this[CodingAttemptsTable.updatedAt],
    )

    companion object {
        const val DEFAULT_EXPIRE_LIMIT = 100
        const val MAX_PAYLOAD_CHARS = 512
        const val MAX_PROGRESS_EVENTS = 100L
        private val ACTIVE_STATES = listOf("QUEUED", "CLAIMED", "RUNNING")
        private val BASE_SHA = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val AGENT_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val ERROR_CODE = Regex("^[A-Z][A-Z_]{0,63}$")
    }
}

/** One progress event. [payloadJson] is null or small fixed-field JSON. */
data class CodingEvent(val seq: Int, val type: String, val payloadJson: String?)
