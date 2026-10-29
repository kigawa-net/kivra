package net.kigawa.kivra.fusha.outbox

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskPatch
import net.kigawa.kivra.core.TaskWriteResult
import net.kigawa.kivra.fusha.FushaTaskAdapter
import net.kigawa.kivra.db.OutboxEvent
import net.kigawa.kivra.db.OutboxRepository
import org.slf4j.LoggerFactory

/**
 * Outbox publisher that delivers fusha-task write events (design #3 section 7.2, A6).
 */
class FushaTaskOutboxPublisher(
    private val outbox: OutboxRepository,
    private val adapter: FushaTaskAdapter,
    private val publisherId: String,
    private val config: FushaTaskOutboxConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(FushaTaskOutboxPublisher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun publishDue(limit: Int = config.batchSize): Int {
        val events = outbox.claim(publisherId, limit, config.claimTtl)
        if (events.isEmpty()) return 0
        var published = 0
        for (event in events) {
            val token = event.claimToken ?: continue
            val delivered = try { deliver(event) } catch (e: Exception) {
                logger.error("outbox fusha-task: delivery failed for event {}: {}", event.eventId, e.message)
                false
            }
            if (delivered) {
                if (outbox.markPublished(event.eventId, publisherId, token)) {
                    published++
                }
            } else {
                val backoff = config.backoffBase.times(Math.pow(2.0, event.publishAttempts.toDouble())).toLong()
                val backoffDuration = Duration.ofMillis(backoff.coerceAtMost(config.maxBackoff.toMillis()))
                outbox.markFailed(event.eventId, publisherId, token, backoffDuration, config.maxAttempts)
            }
        }
        return published
    }

    private fun deliver(event: OutboxEvent): Boolean = when (event.eventType) {
        "TaskUpdate" -> deliverUpdate(event)
        "TaskComment" -> deliverComment(event)
        else -> false
    }

    private fun deliverUpdate(event: OutboxEvent): Boolean {
        val payload = json.parseToJsonElement(event.payload)
        val taskId = payload.jsonObject["taskId"]?.jsonPrimitive?.content ?: return false
        val fields = payload.jsonObject["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        if (fields.isEmpty()) return true
        val id = TaskId(taskId)
        val patch = TaskPatch(fields)
        val expectedUpdatedAt = try { runBlocking { adapter.get(id)?.updatedAt } } catch (e: Exception) { null }
        val result = runBlocking { adapter.update(id, patch, expectedUpdatedAt) }
        return result is TaskWriteResult.Ok
    }

    private fun deliverComment(event: OutboxEvent): Boolean {
        val payload = json.parseToJsonElement(event.payload)
        val taskId = payload.jsonObject["taskId"]?.jsonPrimitive?.content ?: return false
        val body = payload.jsonObject["body"]?.jsonPrimitive?.content ?: return false
        val id = TaskId(taskId)
        val key = IdempotencyKey(event.eventId)
        val result = runBlocking { adapter.comment(id, body, key) }
        return result is TaskWriteResult.Ok
    }
}

data class FushaTaskOutboxConfig(
    val batchSize: Int = 10,
    val claimTtl: Duration = Duration.ofMinutes(5),
    val backoffBase: Long = 1000,
    val maxBackoff: Duration = Duration.ofMinutes(5),
    val maxAttempts: Int = 5,
)
