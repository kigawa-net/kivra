package net.kigawa.kivra.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A project's chat session (V55). One per project, so the session id is the [projectId]. */
data class ProjectChatSessionRow(
    val projectId: String,
    val title: String,
    val updatedAt: Instant,
)

/** One message of a chat session. [role] is "user" or "assistant". */
data class ProjectChatMessageRow(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Instant,
)

/** Persistence of the project chat (V55): the session of a project and its messages. */
class ProjectChatRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private fun Instant.toLocal() = atZone(ZoneOffset.UTC).toLocalDateTime()

    /** Creates the session of [projectId]. The project must exist and have no session yet (primary key / foreign key). */
    fun createSession(projectId: String, title: String): ProjectChatSessionRow {
        val now = Instant.now(clock)
        transaction(database) {
            ProjectChatSessionsTable.insert {
                it[ProjectChatSessionsTable.projectId] = projectId
                it[ProjectChatSessionsTable.title] = title
                it[updatedAt] = now.toLocal()
            }
        }
        return ProjectChatSessionRow(projectId, title, now)
    }

    /** The session of [projectId], or null when it has none. */
    fun getSession(projectId: String): ProjectChatSessionRow? = transaction(database) {
        ProjectChatSessionsTable.selectAll()
            .where { ProjectChatSessionsTable.projectId eq projectId }
            .firstOrNull()
            ?.let { row: ResultRow ->
                ProjectChatSessionRow(
                    projectId = row[ProjectChatSessionsTable.projectId],
                    title = row[ProjectChatSessionsTable.title],
                    updatedAt = row[ProjectChatSessionsTable.updatedAt].atZone(ZoneOffset.UTC).toInstant(),
                )
            }
    }

    /** The messages of [sessionId] in chronological order, [limit] of them from [offset]. */
    fun getMessages(sessionId: String, limit: Int, offset: Long = 0): List<ProjectChatMessageRow> = transaction(database) {
        ProjectChatMessagesTable.selectAll()
            .where { ProjectChatMessagesTable.sessionId eq sessionId }
            .orderBy(ProjectChatMessagesTable.id to SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { it.toMessageRow() }
    }

    /** The last [limit] messages of [sessionId], oldest first. */
    fun recentMessages(sessionId: String, limit: Int): List<ProjectChatMessageRow> = transaction(database) {
        ProjectChatMessagesTable.selectAll()
            .where { ProjectChatMessagesTable.sessionId eq sessionId }
            .orderBy(ProjectChatMessagesTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toMessageRow() }
            .reversed()
    }

    /** Appends a message to [sessionId] and touches the session's updated_at. */
    fun appendMessage(sessionId: String, role: String, content: String): ProjectChatMessageRow {
        val now = Instant.now(clock)
        val id = transaction(database) {
            val newId = ProjectChatMessagesTable.insert {
                it[ProjectChatMessagesTable.sessionId] = sessionId
                it[ProjectChatMessagesTable.role] = role
                it[ProjectChatMessagesTable.content] = content
                it[createdAt] = now.toLocal()
            }[ProjectChatMessagesTable.id]
            ProjectChatSessionsTable.update({ ProjectChatSessionsTable.projectId eq sessionId }) {
                it[updatedAt] = now.toLocal()
            }
            newId
        }
        return ProjectChatMessageRow(id, sessionId, role, content, now)
    }

    /** The number of messages of [sessionId]. */
    fun countMessages(sessionId: String): Long = transaction(database) {
        ProjectChatMessagesTable.selectAll()
            .where { ProjectChatMessagesTable.sessionId eq sessionId }
            .count()
    }

    private fun ResultRow.toMessageRow() = ProjectChatMessageRow(
        id = this[ProjectChatMessagesTable.id],
        sessionId = this[ProjectChatMessagesTable.sessionId],
        role = this[ProjectChatMessagesTable.role],
        content = this[ProjectChatMessagesTable.content],
        createdAt = this[ProjectChatMessagesTable.createdAt].atZone(ZoneOffset.UTC).toInstant(),
    )
}
