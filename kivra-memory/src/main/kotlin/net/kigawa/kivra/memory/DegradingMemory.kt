package net.kigawa.kivra.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.kigawa.kivra.core.IdempotencyKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class MemoryAuditType {
    READ_DEGRADED, WRITE_QUEUED, WRITE_FLUSHED, WRITE_DROPPED_OVERFLOW, WRITE_DROPPED_EXPIRED, WRITE_DROPPED_PERMANENT,
    WRITE_DROPPED_AMBIGUOUS, WRITE_DROPPED_ATTEMPTS, SECRET_DETECTED,
}

/** Audit record. [detail] carries only ids, counts and kinds, never memory content or secret values. */
data class MemoryAuditEvent(val type: MemoryAuditType, val executionId: String?, val traceId: String?, val detail: Map<String, String> = emptyMap())

fun interface MemoryAudit {
    fun record(event: MemoryAuditEvent)

    companion object {
        val NONE = MemoryAudit { }
    }
}

class QueuedWrite(
    val key: IdempotencyKey,
    val entry: NewMemoryEntry,
    val ctx: AccessContext,
    val enqueuedAt: Instant,
    var attempts: Int = 0,
) {
    override fun toString(): String = "QueuedWrite(key=${key.value}, attempts=$attempts)"
}

/**
 * Bounded outbox for writes that could not reach recl (design #6 section 4.4). In-memory only: entries are lost on
 * restart. A durable outbox (Kivra DB) is a later step and must keep the same semantics (same key on resend,
 * at-least-once).
 */
interface WriteQueue {
    /** Returns false when the queue is full (the entry is NOT stored) or the key is already queued (no-op). */
    suspend fun offer(w: QueuedWrite): OfferResult
    suspend fun snapshot(): List<QueuedWrite>
    suspend fun remove(key: IdempotencyKey)
    suspend fun size(): Int
}

enum class OfferResult { QUEUED, DUPLICATE, FULL }

class InMemoryWriteQueue(private val maxSize: Int = 1000) : WriteQueue {
    private val mutex = Mutex()
    private val items = LinkedHashMap<String, QueuedWrite>()

    override suspend fun offer(w: QueuedWrite): OfferResult = mutex.withLock {
        when {
            items.containsKey(w.key.value) -> OfferResult.DUPLICATE
            items.size >= maxSize -> OfferResult.FULL
            else -> { items[w.key.value] = w; OfferResult.QUEUED }
        }
    }

    override suspend fun snapshot(): List<QueuedWrite> = mutex.withLock { items.values.toList() }
    override suspend fun remove(key: IdempotencyKey) { mutex.withLock { items.remove(key.value) } }
    override suspend fun size(): Int = mutex.withLock { items.size }
}

data class RecallResult(val entries: List<MemoryEntry>, val degraded: Boolean) {
    /** Warnings for the ContextBundle: a degraded recall is explicit, never silent. */
    val warnings: Set<ContextWarning> get() = if (degraded) setOf(ContextWarning.MEMORY_UNAVAILABLE) else emptySet()

    companion object {
        fun degraded() = RecallResult(emptyList(), true)
    }
}

sealed interface RememberOutcome {
    data class Saved(val id: MemoryId) : RememberOutcome
    /** Not delivered yet; kept in the write queue under the same idempotency key. */
    data object Queued : RememberOutcome
    /** Permanently refused (secret, validation, scope). Not queued; the Core keeps running. */
    data class Rejected(val kind: MemoryErrorKind) : RememberOutcome
    /** Queue full: dropped and audited. */
    data object Dropped : RememberOutcome
}

data class FlushReport(val flushed: Int, val remaining: Int, val dropped: Int)

/**
 * Degradation layer between the Core and a [MemoryProvider] (design #6 section 4.4, issue #37 "縮退"):
 * recl unreachable / unimplemented never stops the Core.
 *  - reads: any failure, timeout or open circuit returns an EMPTY result flagged `degraded` (long-term memory is
 *    treated as empty, `MEMORY_UNAVAILABLE` is surfaced to the Agent);
 *  - writes: delivery failures are parked in the [WriteQueue] and re-sent with the SAME idempotency key;
 *    permanent refusals are reported, not retried;
 *  - an ambiguous outcome (request may have been applied) is re-sent only if the provider declares
 *    `IDEMPOTENT_SAVE`; otherwise it is dropped + audited, because a blind re-POST could duplicate the entry.
 * Memory is advisory: callers must not make authorization decisions from it.
 */
class MemoryService(
    private val provider: MemoryProvider,
    private val queue: WriteQueue = InMemoryWriteQueue(),
    private val audit: MemoryAudit = MemoryAudit.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val readTimeout: Duration = Duration.ofSeconds(2),
    private val queueTtl: Duration = Duration.ofHours(24),
    private val maxAttempts: Int = 20,
    private val breakerThreshold: Int = 3,
    private val breakerCooldown: Duration = Duration.ofSeconds(30),
) {
    private val flushMutex = Mutex()
    private val stateMutex = Mutex()
    private var consecutiveFailures = 0
    private var openUntil: Instant = Instant.MIN

    suspend fun recall(q: MemoryQuery, ctx: AccessContext): RecallResult =
        guardedRead(ctx, "search") { provider.search(q, ctx).map { it.entry } }

    suspend fun pinned(scopes: List<ScopeRef>, ctx: AccessContext): RecallResult =
        guardedRead(ctx, "pinned") { provider.pinned(scopes, ctx) }

    private suspend fun guardedRead(ctx: AccessContext, op: String, block: suspend () -> List<MemoryEntry>): RecallResult {
        if (isOpen()) {
            audit.record(MemoryAuditEvent(MemoryAuditType.READ_DEGRADED, ctx.executionId, ctx.traceId, mapOf("op" to op, "reason" to "circuit_open")))
            return RecallResult.degraded()
        }
        return try {
            val r = withTimeoutOrNull(readTimeout.toMillis()) { block() }
            if (r == null) {
                onFailure()
                audit.record(MemoryAuditEvent(MemoryAuditType.READ_DEGRADED, ctx.executionId, ctx.traceId, mapOf("op" to op, "reason" to "timeout")))
                RecallResult.degraded()
            } else {
                onSuccess()
                RecallResult(r, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Memory is advisory: any failure (including bugs in an adapter) degrades instead of stopping the Core.
            val reason = (e as? MemoryException)?.kind?.name ?: e.javaClass.simpleName
            if (e !is MemoryException || e.kind in TRANSPORT_FAILURES) onFailure()
            audit.record(MemoryAuditEvent(MemoryAuditType.READ_DEGRADED, ctx.executionId, ctx.traceId, mapOf("op" to op, "reason" to reason)))
            RecallResult.degraded()
        }
    }

    suspend fun remember(entry: NewMemoryEntry, ctx: AccessContext, key: IdempotencyKey = IdempotencyKeys.forSave(entry)): RememberOutcome {
        if (!isOpen()) {
            try {
                return RememberOutcome.Saved(provider.save(entry, key, ctx))
            } catch (e: CancellationException) {
                throw e
            } catch (ex: Exception) {
                val e = asMemoryException(ex)
                when (e.kind) {
                    MemoryErrorKind.UNAVAILABLE -> onFailure()
                    MemoryErrorKind.OUTCOME_UNKNOWN -> {
                        onFailure()
                        if (MemoryCapability.IDEMPOTENT_SAVE !in provider.capabilities) {
                            audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_DROPPED_AMBIGUOUS, ctx.executionId, ctx.traceId, mapOf("key" to key.value)))
                            return RememberOutcome.Rejected(e.kind)
                        }
                    }
                    else -> return RememberOutcome.Rejected(e.kind).also {
                        audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_DROPPED_PERMANENT, ctx.executionId, ctx.traceId, mapOf("key" to key.value, "kind" to e.kind.name)))
                    }
                }
            }
        }
        return when (queue.offer(QueuedWrite(key, entry, ctx, clock.instant()))) {
            OfferResult.QUEUED, OfferResult.DUPLICATE -> {
                audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_QUEUED, ctx.executionId, ctx.traceId, mapOf("key" to key.value)))
                RememberOutcome.Queued
            }
            OfferResult.FULL -> {
                audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_DROPPED_OVERFLOW, ctx.executionId, ctx.traceId, mapOf("key" to key.value)))
                RememberOutcome.Dropped
            }
        }
    }

    /** Re-sends queued writes (call periodically). Stops at the first transport failure. Never throws on provider errors. */
    suspend fun flush(): FlushReport = flushMutex.withLock {
        var flushed = 0
        var dropped = 0
        val now = clock.instant()
        for (w in queue.snapshot()) {
            if (w.enqueuedAt.plus(queueTtl).isBefore(now) || w.attempts >= maxAttempts) {
                queue.remove(w.key)
                dropped++
                audit.record(MemoryAuditEvent(if (w.attempts >= maxAttempts) MemoryAuditType.WRITE_DROPPED_ATTEMPTS else MemoryAuditType.WRITE_DROPPED_EXPIRED, w.ctx.executionId, w.ctx.traceId, mapOf("key" to w.key.value)))
                continue
            }
            if (isOpen()) break
            w.attempts++
            try {
                provider.save(w.entry, w.key, w.ctx)
                queue.remove(w.key)
                onSuccess()
                flushed++
                audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_FLUSHED, w.ctx.executionId, w.ctx.traceId, mapOf("key" to w.key.value)))
            } catch (e: CancellationException) {
                throw e
            } catch (ex: Exception) {
                val e = asMemoryException(ex)
                if (e.kind == MemoryErrorKind.UNAVAILABLE ||
                    (e.kind == MemoryErrorKind.OUTCOME_UNKNOWN && MemoryCapability.IDEMPOTENT_SAVE in provider.capabilities)
                ) {
                    onFailure()
                    break
                }
                queue.remove(w.key)
                dropped++
                val type = if (e.kind == MemoryErrorKind.OUTCOME_UNKNOWN) MemoryAuditType.WRITE_DROPPED_AMBIGUOUS else MemoryAuditType.WRITE_DROPPED_PERMANENT
                audit.record(MemoryAuditEvent(type, w.ctx.executionId, w.ctx.traceId, mapOf("key" to w.key.value, "kind" to e.kind.name)))
            }
        }
        FlushReport(flushed, queue.size(), dropped)
    }

    private suspend fun isOpen(): Boolean = stateMutex.withLock { clock.instant().isBefore(openUntil) }
    private suspend fun onSuccess() = stateMutex.withLock { consecutiveFailures = 0; openUntil = Instant.MIN }
    private suspend fun onFailure() = stateMutex.withLock {
        if (++consecutiveFailures >= breakerThreshold) openUntil = clock.instant().plus(breakerCooldown)
    }

    private companion object {
        val TRANSPORT_FAILURES = setOf(MemoryErrorKind.UNAVAILABLE, MemoryErrorKind.OUTCOME_UNKNOWN, MemoryErrorKind.PROTOCOL)

        /**
         * A write that failed with an unexpected exception may or may not have been applied, so it is classified as
         * an ambiguous outcome (never blindly re-sent unless the provider is idempotent). The message carries only the
         * exception class name, never content.
         */
        fun asMemoryException(e: Exception): MemoryException =
            e as? MemoryException ?: MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "unexpected provider failure (${e.javaClass.simpleName})", e)
    }
}

/**
 * Write guard (issue #37 "Secret を保存させないフィルタ"): checks every free-text field of a write before it
 * reaches the delegate. [Mode.REJECT] (default, fail-closed) refuses the write; [Mode.MASK] saves a redacted copy.
 * Detection is best-effort: never rely on it as the only protection. Also reports kinds (never values) to [audit].
 */
class FilteringMemoryProvider(
    private val delegate: MemoryProvider,
    private val mode: Mode = Mode.REJECT,
    private val audit: MemoryAudit = MemoryAudit.NONE,
) : MemoryProvider by delegate {
    enum class Mode { REJECT, MASK }

    override suspend fun save(e: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId =
        delegate.save(guard(e, ctx), key, ctx)

    override suspend fun supersede(oldId: MemoryId, expectedVersion: Int, new: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId =
        delegate.supersede(oldId, expectedVersion, guard(new, ctx), key, ctx)

    override suspend fun update(id: MemoryId, expectedVersion: Int, patch: MemoryPatch, ctx: AccessContext): MemoryEntry {
        val kinds = listOfNotNull(patch.summary, patch.body).flatMap { SecretFilter.scan(it) }.map { it.kind }.toSet()
        if (kinds.isEmpty()) return delegate.update(id, expectedVersion, patch, ctx)
        report(kinds, ctx)
        if (mode == Mode.REJECT) throw MemoryException(MemoryErrorKind.REJECTED, "secret detected in update (kinds=$kinds)")
        return delegate.update(id, expectedVersion, patch.copy(summary = patch.summary?.let(SecretFilter::redact), body = patch.body?.let(SecretFilter::redact)), ctx)
    }

    private fun guard(e: NewMemoryEntry, ctx: AccessContext): NewMemoryEntry {
        val texts = listOfNotNull(e.title, e.summary, e.body) + e.sources.mapNotNull { it.locator }
        val kinds = texts.flatMap { SecretFilter.scan(it) }.map { it.kind }.toSet()
        if (kinds.isEmpty()) return e
        report(kinds, ctx)
        if (mode == Mode.REJECT) throw MemoryException(MemoryErrorKind.REJECTED, "secret detected in memory write (kinds=$kinds)")
        return e.copy(
            title = SecretFilter.redact(e.title), summary = SecretFilter.redact(e.summary), body = e.body?.let(SecretFilter::redact),
            sources = e.sources.map { s -> s.copy(locator = s.locator?.let(SecretFilter::redact)) },
        )
    }

    private fun report(kinds: Set<SecretKind>, ctx: AccessContext) {
        audit.record(MemoryAuditEvent(MemoryAuditType.SECRET_DETECTED, ctx.executionId, ctx.traceId, mapOf("kinds" to kinds.joinToString(",") { it.name })))
    }
}
