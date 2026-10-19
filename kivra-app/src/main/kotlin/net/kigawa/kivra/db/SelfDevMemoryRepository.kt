package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryFacts
import net.kigawa.kivra.selfdev.memory.MemoryKind
import net.kigawa.kivra.selfdev.memory.MemoryRefusal
import net.kigawa.kivra.selfdev.memory.MemorySource
import net.kigawa.kivra.selfdev.memory.MemoryTier
import net.kigawa.kivra.selfdev.memory.MemoryVerdict
import net.kigawa.kivra.selfdev.memory.NormalizedMemory
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryValidator
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * The self-development memory (ADR 0016, V21 `selfdev_memory_records` and `selfdev_memory_events`).
 *
 * Write rules, enforced here and pinned by SelfDevMemoryArchitectureTest:
 * - The only INSERT into the record table is [insertRecord], and it is called only from [save] after [SelfDevMemoryValidator] has
 *   accepted the candidate. The only INSERT into the event table is [insertEvent]. Nothing else in the code base names these tables.
 * - Nothing here logs. An error never echoes a text: a refusal is a fixed code.
 * - Deletion is a tombstone for a note (text removed, row kept as DELETED) and a physical delete for a fact.
 *
 * P0 has no caller outside the tests: the repository is not constructed by the application.
 */

/** A record as read back. Only ACTIVE rows are returned. [body] is null for a fact. */
data class MemoryRecordView(
    val recordId: String,
    val kind: String,
    val source: String,
    val tier: String,
    val goalId: String?,
    val body: String?,
    val code: String?,
    val countValue: Int?,
    val createdAt: LocalDateTime,
)

/** An event as read back. Holds no text. */
data class MemoryEventView(val eventId: String, val recordId: String?, val kind: String, val code: String?, val scope: String?, val detail: String?, val countValue: Int?, val at: LocalDateTime)

/** The conditions for a search or a conditional deletion. A null field is not a condition. */
data class MemoryFilter(
    val goalId: String? = null,
    val kind: MemoryKind? = null,
    val source: MemorySource? = null,
    val tier: MemoryTier? = null,
    val createdBefore: LocalDateTime? = null,
) {
    fun isEmpty(): Boolean = goalId == null && kind == null && source == null && tier == null && createdBefore == null
}

sealed interface MemorySaveResult {
    data class Written(val recordId: String) : MemorySaveResult
    data class Duplicate(val recordId: String) : MemorySaveResult
    data class Refused(val code: MemoryRefusal) : MemorySaveResult

    /** The feature is off: nothing was read or written. */
    data object Disabled : MemorySaveResult
}

sealed interface MemoryDeleteResult {
    /** A single record was deleted (a note became a tombstone, a fact was removed). */
    data class Deleted(val recordId: String) : MemoryDeleteResult

    /** No ACTIVE record has that id. Nothing changed. */
    data object NotFound : MemoryDeleteResult
}

sealed interface MemoryConditionDeleteResult {
    /** [matched] rows match; with [dryRun] nothing changed; otherwise [deleted] rows were removed. */
    data class Done(val matched: Int, val deleted: Int, val dryRun: Boolean) : MemoryConditionDeleteResult

    /** Refused without any change: an empty filter, or more than [SelfDevMemoryRepository.MAX_CONDITION_DELETE] rows match. */
    data class Refused(val code: String, val matched: Int) : MemoryConditionDeleteResult
}

data class MemoryRetentionResult(val tombstoned: Int, val tombstonesPurged: Int, val factsPurged: Int, val eventsPurged: Int)

class SelfDevMemoryRepository(
    private val database: Database,
    /** From `KIVRA_SELFDEV_MEMORY_ENABLED`. False: [save] and the reads do nothing. Deletion stays available (the operator's safety valve). */
    private val enabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(MAX_CONDITION_DELETE > 0) { "MAX_CONDITION_DELETE must be positive" }
    }

    /** Validates [candidate] and, when it passes, writes one record. Every write in this class goes through here. */
    fun save(candidate: MemoryCandidate): MemorySaveResult {
        if (!enabled) return MemorySaveResult.Disabled
        return transaction(database) {
            val now = utcNow(clock)
            val facts = gatherFacts(candidate, now)
            when (val verdict = SelfDevMemoryValidator.validate(candidate, facts)) {
                is MemoryVerdict.Refuse -> {
                    // The audit keeps the code, the length and the secret kind names. Not the text, not its hash.
                    insertEvent(EVENT_REJECTED, null, verdict.code.name, null, verdict.detail, verdict.chars, actorOf(candidate), now)
                    MemorySaveResult.Refused(verdict.code)
                }
                is MemoryVerdict.Duplicate -> {
                    insertEvent(EVENT_DUPLICATE, verdict.recordId, null, null, null, null, actorOf(candidate), now)
                    MemorySaveResult.Duplicate(verdict.recordId)
                }
                is MemoryVerdict.Accept -> {
                    val id = Ulid.generate(clock)
                    insertRecord(id, candidate, verdict.normalized, now)
                    insertEvent(EVENT_WRITTEN, id, null, null, null, verdict.normalized.bodyChars, verdict.normalized.actorHash, now)
                    MemorySaveResult.Written(id)
                }
            }
        }
    }

    /** ACTIVE records that match [filter], newest first, at most [limit]. A tombstone and a deleted fact never appear. */
    fun search(filter: MemoryFilter, limit: Int): List<MemoryRecordView> {
        require(limit in 1..MAX_SEARCH_LIMIT) { "limit must be from 1 to $MAX_SEARCH_LIMIT" }
        if (!enabled) return emptyList()
        return transaction(database) {
            val where = filterSql(filter)
            execQuery(
                "SELECT record_id, kind, source_kind, trust_tier, goal_id, body, code, count_value, created_at " +
                    "FROM selfdev_memory_records WHERE status = 'ACTIVE'${where.sql} ORDER BY created_at DESC, record_id DESC LIMIT ?",
                *(where.params + listOf<Any?>(limit)).toTypedArray(),
            ) { rs ->
                MemoryRecordView(
                    recordId = rs.getString(1), kind = rs.getString(2), source = rs.getString(3), tier = rs.getString(4),
                    goalId = rs.getString(5), body = rs.getString(6), code = rs.getString(7),
                    countValue = rs.getObject(8)?.let { (it as Number).toInt() }, createdAt = rs.getTimestamp(9).toLocalDateTime(),
                )
            }
        }
    }

    /** Stored events, newest first. They hold no text. Read-only. */
    fun events(limit: Int): List<MemoryEventView> {
        require(limit in 1..MAX_SEARCH_LIMIT) { "limit must be from 1 to $MAX_SEARCH_LIMIT" }
        if (!enabled) return emptyList()
        return transaction(database) {
            execQuery(
                "SELECT event_id, record_id, kind, code, scope, detail, count_value, at FROM selfdev_memory_events ORDER BY at DESC, event_id DESC LIMIT ?",
                limit,
            ) { rs ->
                MemoryEventView(
                    eventId = rs.getString(1), recordId = rs.getString(2), kind = rs.getString(3), code = rs.getString(4),
                    scope = rs.getString(5), detail = rs.getString(6), countValue = rs.getObject(7)?.let { (it as Number).toInt() },
                    at = rs.getTimestamp(8).toLocalDateTime(),
                )
            }
        }
    }

    /** Deletes one ACTIVE record by id (ADR 0016 section 8.1 (a)). A note keeps a tombstone; a fact is removed. */
    fun deleteOne(recordId: String, operatorId: String): MemoryDeleteResult = transaction(database) {
        val now = utcNow(clock)
        val kind = execQuery(
            "SELECT kind FROM selfdev_memory_records WHERE record_id = ? AND status = 'ACTIVE'", recordId,
        ) { it.getString(1) }.singleOrNull() ?: return@transaction MemoryDeleteResult.NotFound
        removeRecords(listOf(recordId to kind), REASON_OPERATOR_DELETE, now)
        insertEvent(EVENT_DELETED, null, REASON_OPERATOR_DELETE, SCOPE_ID, null, 1, SelfDevMemoryValidator.actorHash(operatorId), now)
        MemoryDeleteResult.Deleted(recordId)
    }

    /**
     * Deletes the ACTIVE records that match [filter] (ADR 0016 section 8.1 (b)). An empty filter is refused. More than
     * [MAX_CONDITION_DELETE] matches are refused, with nothing changed: narrow the filter and repeat. [dryRun] only counts.
     */
    fun deleteByCondition(filter: MemoryFilter, operatorId: String, dryRun: Boolean): MemoryConditionDeleteResult {
        if (filter.isEmpty()) return MemoryConditionDeleteResult.Refused(CODE_EMPTY_FILTER, 0)
        return transaction(database) {
            val now = utcNow(clock)
            val where = filterSql(filter)
            val matched = execQuery(
                "SELECT COUNT(*) FROM selfdev_memory_records WHERE status = 'ACTIVE'${where.sql}", *where.params.toTypedArray(),
            ) { it.getInt(1) }.single()
            if (matched > MAX_CONDITION_DELETE) return@transaction MemoryConditionDeleteResult.Refused(CODE_LIMIT_EXCEEDED, matched)
            if (dryRun || matched == 0) return@transaction MemoryConditionDeleteResult.Done(matched, 0, dryRun)
            val rows = execQuery(
                "SELECT record_id, kind FROM selfdev_memory_records WHERE status = 'ACTIVE'${where.sql} ORDER BY created_at, record_id LIMIT ?",
                *(where.params + listOf<Any?>(MAX_CONDITION_DELETE)).toTypedArray(),
            ) { it.getString(1) to it.getString(2) }
            removeRecords(rows, REASON_CONDITION_DELETE, now)
            insertEvent(EVENT_DELETED, null, REASON_CONDITION_DELETE, scopeOf(filter), null, rows.size, SelfDevMemoryValidator.actorHash(operatorId), now)
            MemoryConditionDeleteResult.Done(matched, rows.size, dryRun = false)
        }
    }

    /**
     * The retention job's step (ADR 0016 section 9), at most [batchSize] rows of each class per call. Notes older than 180 days
     * (by created_at) become tombstones; tombstones deleted more than 30 days ago are removed; facts older than 365 days are removed;
     * events older than 365 days are removed. Run it again while the result is not all zero.
     */
    fun purgeRetention(batchSize: Int = MAX_CONDITION_DELETE): MemoryRetentionResult {
        require(batchSize in 1..MAX_CONDITION_DELETE) { "batchSize must be from 1 to $MAX_CONDITION_DELETE" }
        return transaction(database) {
            val now = utcNow(clock)
            val textKinds = MemoryKind.entries.filter { !it.isFact }
            val factKinds = MemoryKind.entries.filter { it.isFact }
            val textCut = now.minusDays(TEXT_RETENTION_DAYS)
            val tombstoneCut = now.minusDays(TOMBSTONE_RETENTION_DAYS)
            val factCut = now.minusDays(FACT_RETENTION_DAYS)

            val expiredTexts = execQuery(
                "SELECT record_id, kind FROM selfdev_memory_records WHERE status = 'ACTIVE' AND created_at < ? AND kind IN (${marks(textKinds)}) " +
                    "ORDER BY created_at LIMIT ?",
                *(listOf<Any?>(textCut) + textKinds.map { it.name } + listOf<Any?>(batchSize)).toTypedArray(),
            ) { it.getString(1) to it.getString(2) }
            if (expiredTexts.isNotEmpty()) {
                removeRecords(expiredTexts, REASON_RETENTION, now)
                insertEvent(EVENT_DELETED, null, REASON_RETENTION, "$SCOPE_RETENTION:TEXT", null, expiredTexts.size, null, now)
            }

            val purgedTombstones = execUpdate(
                "DELETE FROM selfdev_memory_records WHERE record_id IN (" +
                    "SELECT record_id FROM (SELECT record_id FROM selfdev_memory_records WHERE status = 'DELETED' AND deleted_at < ? " +
                    "ORDER BY deleted_at LIMIT ?) AS purge_batch)",
                tombstoneCut, batchSize,
            )

            val expiredFacts = execQuery(
                "SELECT record_id, kind FROM selfdev_memory_records WHERE status = 'ACTIVE' AND created_at < ? AND kind IN (${marks(factKinds)}) " +
                    "ORDER BY created_at LIMIT ?",
                *(listOf<Any?>(factCut) + factKinds.map { it.name } + listOf<Any?>(batchSize)).toTypedArray(),
            ) { it.getString(1) to it.getString(2) }
            if (expiredFacts.isNotEmpty()) {
                removeRecords(expiredFacts, REASON_RETENTION, now)
                insertEvent(EVENT_PURGED, null, REASON_RETENTION, "$SCOPE_RETENTION:FACT", null, expiredFacts.size, null, now)
            }

            val purgedEvents = execUpdate(
                "DELETE FROM selfdev_memory_events WHERE event_id IN (" +
                    "SELECT event_id FROM (SELECT event_id FROM selfdev_memory_events WHERE at < ? ORDER BY at LIMIT ?) AS purge_batch)",
                factCut, batchSize,
            )
            MemoryRetentionResult(
                tombstoned = expiredTexts.size,
                tombstonesPurged = purgedTombstones,
                factsPurged = expiredFacts.size,
                eventsPurged = purgedEvents,
            )
        }
    }

    /**
     * Gathers what the validator needs: the references that exist, the duplicate, and the counts for the quotas. Read-only, in the
     * same transaction as the write, and only for what the candidate uses.
     */
    private fun org.jetbrains.exposed.sql.Transaction.gatherFacts(candidate: MemoryCandidate, now: LocalDateTime): MemoryFacts {
        val refs = linkedMapOf<String, Boolean>()
        candidate.goalId?.let { refs["goal"] = countOf("SELECT COUNT(*) FROM selfdev_goals WHERE goal_id = ?", it) > 0 }
        candidate.planId?.let { refs["plan"] = countOf("SELECT COUNT(*) FROM selfdev_plans WHERE plan_id = ?", it) > 0 }
        candidate.runId?.let { refs["run"] = countOf("SELECT COUNT(*) FROM development_runs WHERE run_id = ?", it) > 0 }
        if (candidate.taskOrdinal != null && candidate.planId != null) {
            refs["task"] = countOf(
                "SELECT COUNT(*) FROM selfdev_plan_tasks WHERE plan_id = ? AND ordinal = ?", candidate.planId, candidate.taskOrdinal,
            ) > 0
        }

        val textKinds = MemoryKind.entries.filter { !it.isFact }
        val factKinds = MemoryKind.entries.filter { it.isFact }
        val goal = candidate.goalId
        return MemoryFacts(
            referenceFound = refs,
            duplicateOf = { sha -> findDuplicate(candidate, sha) },
            goalTextCount = if (goal == null) 0 else countActive(textKinds, "goal_id = ?", goal),
            goalFactCount = if (goal == null) 0 else countActive(factKinds, "goal_id = ?", goal),
            operatorTextCount = countActive(textKinds, "source_kind = ?", MemorySource.OPERATOR.name),
            totalActiveCount = countActive(MemoryKind.entries, null),
            autoTextTodayCount = countActive(textKinds, "source_kind <> ? AND created_at >= ?", MemorySource.OPERATOR.name, startOfUtcDay(now)),
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.findDuplicate(candidate: MemoryCandidate, sha: String): String? {
        val params = mutableListOf<Any?>(candidate.kind, sha)
        val refClause = StringBuilder()
        fun ref(column: String, value: Any?) {
            if (value == null) {
                refClause.append(" AND $column IS NULL")
            } else {
                refClause.append(" AND $column = ?")
                params += value
            }
        }
        ref("goal_id", candidate.goalId)
        ref("plan_id", candidate.planId)
        ref("run_id", candidate.runId)
        ref("task_ordinal", candidate.taskOrdinal)
        return execQuery(
            "SELECT record_id FROM selfdev_memory_records WHERE status = 'ACTIVE' AND kind = ? AND body_sha256 = ?$refClause ORDER BY created_at LIMIT 1",
            *params.toTypedArray(),
        ) { it.getString(1) }.firstOrNull()
    }

    private fun org.jetbrains.exposed.sql.Transaction.countActive(kinds: List<MemoryKind>, clause: String?, vararg params: Any?): Int {
        val sb = StringBuilder("SELECT COUNT(*) FROM selfdev_memory_records WHERE status = 'ACTIVE'")
        val all = mutableListOf<Any?>()
        if (kinds.size < MemoryKind.entries.size) {
            sb.append(" AND kind IN (${marks(kinds)})")
            all.addAll(kinds.map { it.name })
        }
        if (clause != null) {
            sb.append(" AND ").append(clause)
            all.addAll(params.toList())
        }
        return execQuery(sb.toString(), *all.toTypedArray()) { it.getInt(1) }.single()
    }

    private fun org.jetbrains.exposed.sql.Transaction.countOf(sql: String, vararg params: Any?): Int =
        execQuery(sql, *params) { it.getInt(1) }.single()

    /**
     * Tombstones the notes and deletes the facts in [rows] (record id, kind), with their plan references. The only UPDATE and DELETE
     * of the record table. Rows that are no longer ACTIVE are left alone.
     */
    private fun org.jetbrains.exposed.sql.Transaction.removeRecords(rows: List<Pair<String, String>>, reason: String, now: LocalDateTime) {
        if (rows.isEmpty()) return
        val factKinds = MemoryKind.entries.filter { it.isFact }.map { it.name }.toSet()
        val facts = rows.filter { it.second in factKinds }.map { it.first }
        val notes = rows.filter { it.second !in factKinds }.map { it.first }
        val all = rows.map { it.first }
        execUpdate("DELETE FROM selfdev_plan_memory_refs WHERE record_id IN (${marks(all)})", *all.toTypedArray())
        if (notes.isNotEmpty()) {
            execUpdate(
                "UPDATE selfdev_memory_records SET body = NULL, body_sha256 = NULL, body_chars = NULL, code = NULL, count_value = NULL, " +
                    "identity_sha256 = NULL, goal_id = NULL, plan_id = NULL, run_id = NULL, task_ordinal = NULL, status = 'DELETED', " +
                    "deleted_at = ?, delete_reason_code = ? WHERE status = 'ACTIVE' AND record_id IN (${marks(notes)})",
                *(listOf<Any?>(now, reason) + notes).toTypedArray(),
            )
        }
        if (facts.isNotEmpty()) {
            execUpdate(
                "DELETE FROM selfdev_memory_records WHERE status = 'ACTIVE' AND record_id IN (${marks(facts)})",
                *facts.toTypedArray(),
            )
        }
    }

    /** The only INSERT into `selfdev_memory_records`. Called only by [save], after the validator accepted the candidate. */
    private fun org.jetbrains.exposed.sql.Transaction.insertRecord(id: String, c: MemoryCandidate, n: NormalizedMemory, now: LocalDateTime) {
        execUpdate(
            "INSERT INTO selfdev_memory_records (record_id, kind, source_kind, trust_tier, goal_id, plan_id, run_id, task_ordinal, body, " +
                "body_sha256, body_chars, code, count_value, identity_sha256, status, created_by_hash, created_at, deleted_at, delete_reason_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL, NULL)",
            id, n.kind.name, n.source.name, n.tier.name, c.goalId, c.planId, c.runId, c.taskOrdinal, n.body, n.bodySha256, n.bodyChars,
            n.code, n.countValue, n.identitySha256, n.actorHash, now,
        )
    }

    /** The only INSERT into `selfdev_memory_events`. It takes fixed codes, counts and hashes only: never text. */
    private fun org.jetbrains.exposed.sql.Transaction.insertEvent(
        kind: String,
        recordId: String?,
        code: String?,
        scope: String?,
        detail: String?,
        count: Int?,
        actorHash: String?,
        now: LocalDateTime,
    ) {
        execUpdate(
            "INSERT INTO selfdev_memory_events (event_id, record_id, kind, code, scope, detail, count_value, actor_hash, at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Ulid.generate(clock), recordId, kind, code, scope, detail, count, actorHash, now,
        )
    }

    private fun actorOf(c: MemoryCandidate): String? = if (c.channel == net.kigawa.kivra.selfdev.memory.MemoryChannel.HTTP_OPERATOR) {
        c.operatorId?.let { SelfDevMemoryValidator.actorHash(it) }
    } else {
        null
    }

    private class Where(val sql: String, val params: List<Any?>)

    private fun filterSql(f: MemoryFilter): Where {
        val sb = StringBuilder()
        val params = mutableListOf<Any?>()
        f.goalId?.let { sb.append(" AND goal_id = ?"); params += it }
        f.kind?.let { sb.append(" AND kind = ?"); params += it.name }
        f.source?.let { sb.append(" AND source_kind = ?"); params += it.name }
        f.tier?.let { sb.append(" AND trust_tier = ?"); params += it.name }
        f.createdBefore?.let { sb.append(" AND created_at < ?"); params += it }
        return Where(sb.toString(), params)
    }

    private fun scopeOf(f: MemoryFilter): String {
        val fields = buildList {
            if (f.goalId != null) add("goal_id")
            if (f.kind != null) add("kind")
            if (f.source != null) add("source")
            if (f.tier != null) add("tier")
            if (f.createdBefore != null) add("created_before")
        }
        return "$SCOPE_CONDITION:${fields.joinToString("+")}"
    }

    private fun marks(items: List<Any?>): String = items.joinToString(",") { "?" }

    private fun startOfUtcDay(now: LocalDateTime): LocalDateTime = now.toLocalDate().atStartOfDay()

    companion object {
        /** The most rows one conditional deletion or one retention batch may touch (ADR 0016 section 8.1 (b)). */
        const val MAX_CONDITION_DELETE = 500
        const val MAX_SEARCH_LIMIT = 200

        const val TEXT_RETENTION_DAYS = 180L
        const val TOMBSTONE_RETENTION_DAYS = 30L
        const val FACT_RETENTION_DAYS = 365L

        const val CODE_EMPTY_FILTER = "EMPTY_FILTER"
        const val CODE_LIMIT_EXCEEDED = "LIMIT_EXCEEDED"

        const val REASON_OPERATOR_DELETE = "OPERATOR_DELETE"
        const val REASON_CONDITION_DELETE = "CONDITION_DELETE"
        const val REASON_RETENTION = "RETENTION"

        private const val SCOPE_ID = "ID"
        private const val SCOPE_CONDITION = "CONDITION"
        private const val SCOPE_RETENTION = "RETENTION"

        private const val EVENT_WRITTEN = "WRITTEN"
        private const val EVENT_REJECTED = "REJECTED"
        private const val EVENT_DUPLICATE = "DUPLICATE"
        private const val EVENT_DELETED = "DELETED"
        private const val EVENT_PURGED = "PURGED"
    }
}
