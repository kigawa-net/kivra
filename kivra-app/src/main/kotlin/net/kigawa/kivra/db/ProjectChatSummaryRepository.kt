package net.kigawa.kivra.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal data class ProjectChatSummaryRow(
    val coversUpToMessageId: Long,
    val body: String,
    val generation: Int,
)

class ProjectChatSummaryRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val summaries = ProjectChatSummariesTable

    internal fun find(sessionId: String): ProjectChatSummaryRow? = transaction(database) {
        summaries.selectAll().where { summaries.sessionId eq sessionId }.singleOrNull()?.let {
            ProjectChatSummaryRow(it[summaries.coversUpToMessageId], it[summaries.body], it[summaries.generation])
        }
    }

    /** Saves a redacted summary when coverage advances. Returns the committed generation, or null if it did not advance. */
    internal fun upsert(sessionId: String, coversUpToMessageId: Long, redactedBody: String): Int? = transaction(database) {
        // Lock the session row to serialize first inserts across instances. No lock is held during generation.
        ProjectChatSessionsTable.selectAll().where { ProjectChatSessionsTable.projectId eq sessionId }
            .forUpdate().firstOrNull() ?: return@transaction null
        val now = Instant.now(clock).atOffset(ZoneOffset.UTC).toLocalDateTime()
        val updated = summaries.update({
            (summaries.sessionId eq sessionId) and (summaries.coversUpToMessageId less coversUpToMessageId)
        }) {
            it[summaries.coversUpToMessageId] = coversUpToMessageId
            it[summaries.body] = redactedBody
            it[summaries.generation] = summaries.generation + 1
            it[summaries.createdAt] = now
        }
        val current = summaries.select(summaries.generation).where { summaries.sessionId eq sessionId }.singleOrNull()
        if (current != null) return@transaction if (updated == 1) current[summaries.generation] else null
        summaries.insert {
            it[summaries.sessionId] = sessionId
            it[summaries.coversUpToMessageId] = coversUpToMessageId
            it[summaries.body] = redactedBody
            it[summaries.generation] = 1
            it[summaries.createdAt] = now
        }
        1
    }
}
