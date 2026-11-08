package net.kigawa.kivra.db

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A memory record takes its project from the goal's task (ADR 0022, PR-C). On H2 with the real repositories.
 */
class SelfDevMemoryProjectTest {
    @Test
    fun `a goal title recorded in memory takes the project of the goal's task`() {
        TestDb().use { db ->
            val project = ProjectRepository(db.database, Clock.systemUTC()).create("Memory", null).projectId
            val goal = assertIs<SelfDevSubmitResult.Created>(
                SelfDevOperatorRepository(db.database).submitTask("1:i:oper", "goal-memory", "Goal", null, "kigawa-net/kivra", project),
            ).task
            val memory = SelfDevMemoryRepository(db.database, enabled = true)
            val saved = memory.save(
                MemoryCandidate(
                    kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = "1:i:oper",
                    goalId = goal.taskId, body = "CI を速くする",
                ),
            )
            val recordId = assertIs<MemorySaveResult.Written>(saved).recordId
            val stored = transaction(db.database) {
                execQuery("SELECT project_id FROM selfdev_memory_records WHERE record_id = ?", recordId) { it.getString(1) }
            }
            assertEquals(listOf(project), stored)
        }
    }
}
