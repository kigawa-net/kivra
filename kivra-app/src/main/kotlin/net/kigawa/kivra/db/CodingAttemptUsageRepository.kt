package net.kigawa.kivra.db

import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * V16 `coding_attempt_usage`: the usage of one coding attempt, per lease generation (ADR 0010 addendum 4). Only the usage record lives
 * here. The money is booked by [DbBudgetStore.recordSpend] under the key `coding:<attemptId>:<generation>`, and that key is what stops
 * a second report from being counted twice. This table's primary key is the same guard, one row per (attempt, generation).
 *
 * Holds numbers and fixed names only: never a prompt, output, path, summary or secret. Nothing here logs.
 */

/** Exposed mirror of V16 `coding_attempt_usage` (the migration is the source of truth). */
internal object CodingAttemptUsageTable : Table("coding_attempt_usage") {
    val attemptId = char("attempt_id", 26)
    val leaseGeneration = long("lease_generation")
    val costUsd = decimal("cost_usd", 12, 6)
    val model = varchar("model", 64).nullable()
    val numTurns = integer("num_turns").nullable()
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val cacheCreationTokens = long("cache_creation_tokens").nullable()
    val cacheReadTokens = long("cache_read_tokens").nullable()
    val usageSource = varchar("source", 20)
    val reportedAt = datetime("reported_at")
    override val primaryKey = PrimaryKey(attemptId, leaseGeneration)
}

/** One usage row. [source] is `REPORTED` or `ESTIMATED_RESERVE`; the token and model fields are null for an estimate. */
data class CodingUsageRow(
    val attemptId: String,
    val leaseGeneration: Long,
    val costUsd: BigDecimal,
    val source: String,
    val model: String? = null,
    val numTurns: Int? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
    val cacheReadTokens: Long? = null,
)

class CodingAttemptUsageRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Writes [row]. Returns false when a row for (attempt, generation) already exists; nothing changes then. Throws on other failures. */
    fun insert(row: CodingUsageRow): Boolean = try {
        transaction(database) {
            CodingAttemptUsageTable.insert {
                it[attemptId] = row.attemptId
                it[leaseGeneration] = row.leaseGeneration
                it[costUsd] = row.costUsd
                it[model] = row.model
                it[numTurns] = row.numTurns
                it[inputTokens] = row.inputTokens
                it[outputTokens] = row.outputTokens
                it[cacheCreationTokens] = row.cacheCreationTokens
                it[cacheReadTokens] = row.cacheReadTokens
                it[usageSource] = row.source
                it[reportedAt] = nowUtc()
            }
        }
        true
    } catch (e: Exception) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is SQLException && (it.sqlState == "23505" || it.errorCode == 1062) }) {
            false
        } else {
            throw e
        }
    }

    /** The usage row of (attempt, generation), or null. */
    fun get(attemptId: String, leaseGeneration: Long): CodingUsageRow? = transaction(database) {
        CodingAttemptUsageTable.selectAll()
            .where { (CodingAttemptUsageTable.attemptId eq attemptId) and (CodingAttemptUsageTable.leaseGeneration eq leaseGeneration) }
            .singleOrNull()
            ?.let {
                CodingUsageRow(
                    attemptId = it[CodingAttemptUsageTable.attemptId],
                    leaseGeneration = it[CodingAttemptUsageTable.leaseGeneration],
                    costUsd = it[CodingAttemptUsageTable.costUsd],
                    source = it[CodingAttemptUsageTable.usageSource],
                    model = it[CodingAttemptUsageTable.model],
                    numTurns = it[CodingAttemptUsageTable.numTurns],
                    inputTokens = it[CodingAttemptUsageTable.inputTokens],
                    outputTokens = it[CodingAttemptUsageTable.outputTokens],
                    cacheCreationTokens = it[CodingAttemptUsageTable.cacheCreationTokens],
                    cacheReadTokens = it[CodingAttemptUsageTable.cacheReadTokens],
                )
            }
    }

    private fun nowUtc(): LocalDateTime = utcNow(clock)
}
