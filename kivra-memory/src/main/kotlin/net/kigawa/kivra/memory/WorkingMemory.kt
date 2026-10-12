package net.kigawa.kivra.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Handoff between agents (design #6 section 5.4): REFERENCES only. Artifacts are [SourceRef]s, memories are
 * citation ids that the receiver re-fetches with its own access context. Must not contain secrets.
 */
data class HandoffRecord(
    val handoffId: String,
    val fromExecutionId: String,
    /** The receiver this handoff is bound to. Only this execution may read the handoff (fail-closed). */
    val toExecutionId: String,
    val taskId: String,
    val createdAt: Instant,
    val ttl: Duration,
    val goal: String,
    val doneSummary: String,
    val openQuestions: List<String> = emptyList(),
    val artifactRefs: List<SourceRef> = emptyList(),
    val memoryRefs: List<CitationId> = emptyList(),
    val constraints: List<String> = emptyList(),
    val checkpointRef: String? = null,
) {
    init {
        require(handoffId.isNotBlank() && fromExecutionId.isNotBlank() && toExecutionId.isNotBlank() && taskId.isNotBlank()) { "ids must not be blank" }
        require(!ttl.isNegative && !ttl.isZero && ttl <= MAX_TTL) { "ttl must be within (0, 7d]" }
        require(goal.isNotBlank()) { "goal required" }
        require(memoryRefs.size <= 100 && artifactRefs.size <= 100 && openQuestions.size <= 100 && constraints.size <= 100) { "too many entries" }
    }

    internal fun textFields(): List<String> =
        listOf(goal, doneSummary) + openQuestions + constraints + listOfNotNull(checkpointRef, toExecutionId)

    val expiresAt: Instant get() = createdAt.plus(ttl)

    override fun toString(): String = "HandoffRecord(handoffId=$handoffId, from=$fromExecutionId, to=$toExecutionId, taskId=$taskId, memoryRefs=${memoryRefs.size}, artifactRefs=${artifactRefs.size})"

    companion object {
        val MAX_TTL: Duration = Duration.ofDays(7)
    }
}

/**
 * Working Memory (design #6 section 5.5): per-execution scratch space on the Kivra side. It is NOT a source of
 * truth and never touches recl, so it is unaffected by recl outages. Secrets are rejected on write (checkpoints
 * must not contain secrets either).
 */
interface WorkingMemoryStore {
    suspend fun put(executionId: String, key: String, value: String)
    suspend fun get(executionId: String, key: String): String?
    suspend fun putHandoff(record: HandoffRecord)

    /** Returns the handoff only when [AccessContext.executionId] is its bound receiver; otherwise `null`. */
    suspend fun getHandoff(handoffId: String, ctx: AccessContext): HandoffRecord?

    /** Starts the retention window: 24h after success, 7d after failure (kept for investigation). */
    suspend fun finish(executionId: String, failed: Boolean)

    /** Removes expired data and returns the number of removed items. */
    suspend fun purgeExpired(): Int
}

class InMemoryWorkingMemory(
    private val clock: Clock = Clock.systemUTC(),
    private val finishedTtl: Duration = Duration.ofHours(24),
    private val failedTtl: Duration = Duration.ofDays(7),
    /** Safety net for executions that never call [finish]. */
    private val activeTtl: Duration = Duration.ofDays(7),
    private val maxValueBytes: Int = 64 * 1024,
    private val maxKeysPerExecution: Int = 256,
    private val maxBytesPerExecution: Int = 1024 * 1024,
) : WorkingMemoryStore {
    private class Slot(var expiresAt: Instant) {
        val values = LinkedHashMap<String, String>()
        var bytes = 0
    }

    private val mutex = Mutex()
    private val slots = HashMap<String, Slot>()
    private val handoffs = HashMap<String, HandoffRecord>()

    override suspend fun put(executionId: String, key: String, value: String) {
        require(executionId.isNotBlank() && key.isNotBlank() && key.length <= 200) { "invalid execution id or key" }
        rejectSecrets(listOf(value))
        val size = value.toByteArray(Charsets.UTF_8).size
        if (size > maxValueBytes) throw MemoryException(MemoryErrorKind.REJECTED, "working memory value too large")
        mutex.withLock {
            val now = clock.instant()
            val slot = slots[executionId]?.takeIf { it.expiresAt.isAfter(now) } ?: Slot(now.plus(activeTtl)).also { slots[executionId] = it }
            val old = slot.values[key]
            val oldSize = old?.toByteArray(Charsets.UTF_8)?.size ?: 0
            if (old == null && slot.values.size >= maxKeysPerExecution) throw MemoryException(MemoryErrorKind.REJECTED, "too many working memory keys")
            if (slot.bytes - oldSize + size > maxBytesPerExecution) throw MemoryException(MemoryErrorKind.REJECTED, "working memory quota exceeded")
            slot.values[key] = value
            slot.bytes = slot.bytes - oldSize + size
        }
    }

    override suspend fun get(executionId: String, key: String): String? = mutex.withLock {
        val slot = slots[executionId] ?: return null
        if (!slot.expiresAt.isAfter(clock.instant())) return null
        slot.values[key]
    }

    override suspend fun putHandoff(record: HandoffRecord) {
        rejectSecrets(record.textFields())
        mutex.withLock { handoffs[record.handoffId] = record }
    }

    override suspend fun getHandoff(handoffId: String, ctx: AccessContext): HandoffRecord? = mutex.withLock {
        handoffs[handoffId]?.takeIf { it.toExecutionId == ctx.executionId && it.expiresAt.isAfter(clock.instant()) }
    }

    override suspend fun finish(executionId: String, failed: Boolean) {
        mutex.withLock {
            val slot = slots[executionId] ?: return
            slot.expiresAt = clock.instant().plus(if (failed) failedTtl else finishedTtl)
        }
    }

    override suspend fun purgeExpired(): Int = mutex.withLock {
        val now = clock.instant()
        val s = slots.entries.filter { !it.value.expiresAt.isAfter(now) }.map { it.key }
        s.forEach(slots::remove)
        val h = handoffs.values.filter { !it.expiresAt.isAfter(now) }.map { it.handoffId }
        h.forEach(handoffs::remove)
        s.size + h.size
    }

    private fun rejectSecrets(texts: List<String>) {
        val kinds = texts.flatMap { SecretFilter.scan(it) }.map { it.kind }.toSet()
        if (kinds.isNotEmpty()) throw MemoryException(MemoryErrorKind.REJECTED, "working memory must not contain secrets (detected: $kinds)")
    }
}
