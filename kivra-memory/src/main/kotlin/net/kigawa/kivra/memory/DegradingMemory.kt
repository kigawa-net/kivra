package net.kigawa.kivra.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.journal.DispatchDecision
import net.kigawa.kivra.memory.journal.MemoryMutationJournalPort
import net.kigawa.kivra.memory.journal.MutationCapability
import net.kigawa.kivra.memory.journal.MutationDigest
import net.kigawa.kivra.memory.journal.MutationIds
import net.kigawa.kivra.memory.journal.MutationIntent
import net.kigawa.kivra.memory.journal.MutationJournalRejectedException
import net.kigawa.kivra.memory.journal.MutationKind
import net.kigawa.kivra.memory.journal.MutationRecord
import net.kigawa.kivra.memory.journal.MutationStatus
import net.kigawa.kivra.memory.journal.MutationWriter
import net.kigawa.kivra.memory.journal.NotSentDispatchException
import net.kigawa.kivra.memory.journal.PrepareResult
import net.kigawa.kivra.memory.journal.validReasonCode
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class MemoryAuditType {
    READ_DEGRADED, WRITE_QUEUED, WRITE_FLUSHED, WRITE_DROPPED_OVERFLOW, WRITE_DROPPED_EXPIRED, WRITE_DROPPED_PERMANENT,
    /** An outcome-unknown write was held for reconciliation (never re-sent). Detail carries the count only. */
    WRITE_HELD_RECONCILE, WRITE_DROPPED_ATTEMPTS, SECRET_DETECTED,
    /** Journal path (issue #77): operation id + machine code only. */
    MUTATION_ESCALATED, MUTATION_SETTLED, MUTATION_CONFLICT,
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
    /**
     * The provider reported OUTCOME_UNKNOWN: the save may or may not have been applied. It is NOT queued and NOT
     * re-sent (even when the provider declares IDEMPOTENT_SAVE) until reconciliation is possible through the
     * persistent Operation Journal (M0 `external_operations`). It is kept for reconciliation, not discarded.
     */
    data object HeldForReconciliation : RememberOutcome

    /**
     * Journal path only: the same operation is already recorded (CONFIRMED, or IN_FLIGHT under another dispatcher).
     * No new external call was made by this call.
     */
    data object AlreadyRecorded : RememberOutcome
}

/** [held] = writes whose outcome is unknown and that were kept for reconciliation during this flush (never re-sent). */
data class FlushReport(val flushed: Int, val remaining: Int, val dropped: Int, val held: Int = 0)

/**
 * Degradation layer between the Core and a [MemoryProvider] (design #6 section 4.4, issue #37 "縮退"):
 * recl unreachable / unimplemented never stops the Core.
 *  - reads: any failure, timeout or open circuit returns an EMPTY result flagged `degraded` (long-term memory is
 *    treated as empty, `MEMORY_UNAVAILABLE` is surfaced to the Agent);
 *  - writes: delivery failures that are definitely NOT applied (`UNAVAILABLE`) are parked in the [WriteQueue] and
 *    re-sent with the SAME idempotency key; permanent refusals are reported, not retried;
 *  - an ambiguous outcome (`OUTCOME_UNKNOWN`: the request may have been applied, including unexpected exceptions) is
 *    NEVER re-sent. It is held for reconciliation and only counted in the audit log, regardless of whether the
 *    provider declares `IDEMPOTENT_SAVE`. Automatic re-sending of such writes is disabled until the persistent
 *    Operation Journal (M0 `external_operations`) is connected: a durable reconciliation (not a blind re-POST) must
 *    decide whether the write was applied.
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
    /**
     * Durable Operation Journal (issue #77). When set, [remember] / [flush] use it: the intent is recorded BEFORE any
     * external call, the in-memory queue and held list are not used, and the outcome is settled in the journal.
     */
    private val mutationJournal: MemoryMutationJournalPort? = null,
    private val writer: MutationWriter? = null,
    /** Stays DISABLED until recl `recl.memory.v1` is accepted (issue #77 (5)). A DISABLED journal path writes nothing. */
    private val mutationCapability: MutationCapability = MutationCapability.DISABLED,
) {
    init {
        require((mutationJournal == null) == (writer == null)) { "mutation journal and writer identity must be given together" }
    }

    private val flushMutex = Mutex()
    private val stateMutex = Mutex()
    private val heldMutex = Mutex()
    private val held = LinkedHashMap<String, QueuedWrite>()
    private var consecutiveFailures = 0
    private var openUntil: Instant = Instant.MIN

    /** Journal path: payloads of PREPARED writes that this process may still dispatch. In-memory only, never persisted. */
    private val pendingMutex = Mutex()
    private val pending = LinkedHashMap<String, PendingWrite>()
    private val sweepMutex = Mutex()
    private var swept = false

    private class PendingWrite(
        val entry: NewMemoryEntry,
        val key: IdempotencyKey,
        val ctx: AccessContext,
        val enqueuedAt: Instant,
        var version: Long,
        var attempts: Int = 0,
    )

    /**
     * Writes whose outcome is unknown, held for reconciliation. They are never returned to the [WriteQueue] and never
     * re-sent by this class. Read-only snapshot for the future reconciliation step (persistent Operation Journal).
     */
    suspend fun heldForReconciliation(): List<QueuedWrite> = heldMutex.withLock { held.values.toList() }

    /** Journal path: UNKNOWN_HELD rows of this writer's tenant + principal (ids and codes only). */
    suspend fun unknownHeldOperations(): List<MutationRecord> {
        val j = mutationJournal ?: return emptyList()
        return j.unknownHeld(writer!!.identity)
    }

    private suspend fun holdForReconciliation(w: QueuedWrite) {
        val count = heldMutex.withLock {
            held[w.key.value] = w
            held.size
        }
        // Audit carries the count only: no keys, no content.
        audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_HELD_RECONCILE, w.ctx.executionId, w.ctx.traceId, mapOf("count" to count.toString())))
    }

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
        if (mutationJournal != null) return journaledRemember(entry, ctx, key)
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
                        holdForReconciliation(QueuedWrite(key, entry, ctx, clock.instant()))
                        return RememberOutcome.HeldForReconciliation
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

    /**
     * Re-sends queued writes (call periodically). Stops at the first `UNAVAILABLE` (definitely not applied). A queued
     * write whose re-send returns OUTCOME_UNKNOWN is held for reconciliation, never re-sent. Never throws on provider errors.
     */
    suspend fun flush(): FlushReport = flushMutex.withLock {
        if (mutationJournal != null) return@withLock journaledFlush()
        var flushed = 0
        var dropped = 0
        var heldNow = 0
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
                if (e.kind == MemoryErrorKind.UNAVAILABLE) {
                    onFailure()
                    break
                }
                queue.remove(w.key)
                if (e.kind == MemoryErrorKind.OUTCOME_UNKNOWN) {
                    // The re-send may have been applied: hold for reconciliation, do not retry (regardless of IDEMPOTENT_SAVE).
                    onFailure()
                    holdForReconciliation(w)
                    heldNow++
                    continue
                }
                dropped++
                audit.record(MemoryAuditEvent(MemoryAuditType.WRITE_DROPPED_PERMANENT, w.ctx.executionId, w.ctx.traceId, mapOf("key" to w.key.value, "kind" to e.kind.name)))
            }
        }
        FlushReport(flushed, queue.size(), dropped, heldNow)
    }

    // ---- Durable Operation Journal path (issue #77) -------------------------------------------------------------
    // Rules: intent is recorded before any external call; only the CAS winner calls the provider; anything that is not a
    // proven NotSent or an explicit permanent refusal settles as UNKNOWN_HELD and is never re-sent automatically.
    // Audit and journal carry ids and machine codes only, never content.

    private suspend fun journaledRemember(entry: NewMemoryEntry, ctx: AccessContext, key: IdempotencyKey): RememberOutcome {
        val journal = mutationJournal!!
        val w = writer!!
        if (mutationCapability == MutationCapability.DISABLED) return RememberOutcome.Rejected(MemoryErrorKind.UNSUPPORTED)
        // Fail-closed content policy, before any row or external call. RESTRICTED is never stored or sent.
        if (entry.sensitivity == Sensitivity.RESTRICTED) {
            journalAudit(MemoryAuditType.WRITE_DROPPED_PERMANENT, ctx, mapOf("kind" to "RESTRICTED"))
            return RememberOutcome.Rejected(MemoryErrorKind.REJECTED)
        }
        val texts = listOfNotNull(entry.title, entry.summary, entry.body) + entry.sources.mapNotNull { it.locator }
        val kinds = texts.flatMap { SecretFilter.scan(it) }.map { it.kind }.toSet()
        if (kinds.isNotEmpty()) {
            journalAudit(MemoryAuditType.SECRET_DETECTED, ctx, mapOf("kinds" to kinds.joinToString(",") { it.name }))
            return RememberOutcome.Rejected(MemoryErrorKind.REJECTED)
        }
        val intent = MutationIntent(
            operationId = MutationIds.operationId(w.identity, key),
            identity = w.identity,
            kind = MutationKind.SAVE,
            requestId = MutationIds.requestId(w.identity, key),
            payloadDigest = MutationDigest.of(MutationKind.SAVE, entry),
            expectedRevision = 0,
            writerGeneration = w.writerGeneration,
            executionId = ctx.executionId,
        )
        val prepared = try {
            ensureSwept()
            journal.prepare(intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Journal unavailable before any external call: nothing was sent and nothing is queued (fail-closed).
            journalAudit(MemoryAuditType.MUTATION_CONFLICT, ctx, mapOf("code" to "JOURNAL_UNAVAILABLE"))
            return RememberOutcome.Rejected(MemoryErrorKind.UNAVAILABLE)
        }
        val rec = when (prepared) {
            is PrepareResult.Conflict -> {
                journalAudit(MemoryAuditType.MUTATION_CONFLICT, ctx, mapOf("operationId" to intent.operationId, "code" to prepared.code.name))
                return RememberOutcome.Rejected(MemoryErrorKind.REJECTED)
            }
            is PrepareResult.Prepared -> prepared.record
            is PrepareResult.Existing -> prepared.record
        }
        return when (rec.status) {
            MutationStatus.PREPARED -> dispatch(rec.operationId, PendingWrite(entry, key, ctx, clock.instant(), rec.version))
            MutationStatus.IN_FLIGHT, MutationStatus.CONFIRMED -> RememberOutcome.AlreadyRecorded
            MutationStatus.UNKNOWN_HELD -> RememberOutcome.HeldForReconciliation
            MutationStatus.FAILED_PERMANENT -> RememberOutcome.Rejected(MemoryErrorKind.REJECTED)
        }
    }

    private suspend fun dispatch(opId: String, pw: PendingWrite): RememberOutcome {
        val journal = mutationJournal!!
        val gen = writer!!.writerGeneration
        if (isOpen()) {
            putPending(opId, pw)
            journalAudit(MemoryAuditType.WRITE_QUEUED, pw.ctx, mapOf("operationId" to opId))
            return RememberOutcome.Queued
        }
        val won = when (val d = journal.beginDispatch(opId, gen, pw.version)) {
            is DispatchDecision.Won -> d.record
            is DispatchDecision.Refused -> return onRefused(opId, pw, d)
        }
        val id = try {
            provider.save(pw.entry, pw.key, pw.ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (ex: Exception) {
            return settleFailure(opId, won.version, pw, ex)
        }
        onSuccess()
        removePending(opId)
        try {
            journal.markConfirmed(opId, gen, won.version)
            journalAudit(MemoryAuditType.MUTATION_SETTLED, pw.ctx, mapOf("operationId" to opId, "code" to "CONFIRMED"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The external write succeeded; a late settle from a superseded generation is discarded, not re-sent.
            journalAudit(MemoryAuditType.MUTATION_CONFLICT, pw.ctx, mapOf("operationId" to opId, "code" to ((e as? MutationJournalRejectedException)?.code?.name ?: "SETTLE_FAILED")))
        }
        return RememberOutcome.Saved(id)
    }

    private suspend fun onRefused(opId: String, pw: PendingWrite, d: DispatchDecision.Refused): RememberOutcome {
        val record = d.record
        return when (record?.status) {
            MutationStatus.PREPARED -> {
                // Not dispatchable by this writer yet (for example a PREPARED row of another generation). Keep it pending.
                pw.version = record.version
                putPending(opId, pw)
                RememberOutcome.Queued
            }
            MutationStatus.UNKNOWN_HELD -> { removePending(opId); RememberOutcome.HeldForReconciliation }
            MutationStatus.IN_FLIGHT, MutationStatus.CONFIRMED -> { removePending(opId); RememberOutcome.AlreadyRecorded }
            MutationStatus.FAILED_PERMANENT, null -> { removePending(opId); RememberOutcome.Rejected(MemoryErrorKind.REJECTED) }
        }
    }

    private suspend fun settleFailure(opId: String, version: Long, pw: PendingWrite, ex: Exception): RememberOutcome {
        val journal = mutationJournal!!
        val gen = writer!!.writerGeneration
        if (ex is NotSentDispatchException && ex.evidence.operationId == opId) {
            return try {
                journal.markNotSent(opId, gen, version, ex.evidence)
                pw.version = version + 1
                putPending(opId, pw)
                journalAudit(MemoryAuditType.WRITE_QUEUED, pw.ctx, mapOf("operationId" to opId, "code" to "NOT_SENT_PROVEN"))
                RememberOutcome.Queued
            } catch (e: MutationJournalRejectedException) {
                markHeld(opId, version, pw, "NOT_SENT_PROOF_REJECTED")
            }
        }
        val e = asMemoryException(ex)
        if (e.kind in TRANSPORT_FAILURES) onFailure()
        if (e.kind == MemoryErrorKind.REJECTED) {
            removePending(opId)
            try {
                journal.markFailedPermanent(opId, gen, version, "PROVIDER_REJECTED")
            } catch (je: MutationJournalRejectedException) {
                journalAudit(MemoryAuditType.MUTATION_CONFLICT, pw.ctx, mapOf("operationId" to opId, "code" to je.code.name))
            }
            return RememberOutcome.Rejected(MemoryErrorKind.REJECTED)
        }
        // 429 / 5xx / timeout / ResponseLost / 404 / 409 / anything unexpected: sent-or-unknown, never a proof of no effect.
        return markHeld(opId, version, pw, "PROVIDER_${e.kind.name}")
    }

    private suspend fun markHeld(opId: String, version: Long, pw: PendingWrite, code: String): RememberOutcome {
        removePending(opId)
        try {
            mutationJournal!!.markUnknownHeld(opId, writer!!.writerGeneration, version, validReasonCode(code))
        } catch (je: MutationJournalRejectedException) {
            journalAudit(MemoryAuditType.MUTATION_CONFLICT, pw.ctx, mapOf("operationId" to opId, "code" to je.code.name))
        }
        journalAudit(MemoryAuditType.WRITE_HELD_RECONCILE, pw.ctx, mapOf("operationId" to opId, "code" to code))
        return RememberOutcome.HeldForReconciliation
    }

    private suspend fun journaledFlush(): FlushReport {
        val journal = mutationJournal!!
        try {
            ensureSwept()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FlushReport(0, pendingCount(), 0, 0)
        }
        var flushed = 0
        var dropped = 0
        var held = 0
        val now = clock.instant()
        for (opId in pendingIds()) {
            val pw = pendingGet(opId) ?: continue
            if (pw.enqueuedAt.plus(queueTtl).isBefore(now) || pw.attempts >= maxAttempts) {
                removePending(opId)
                dropped++
                journalAudit(MemoryAuditType.WRITE_DROPPED_ATTEMPTS, pw.ctx, mapOf("operationId" to opId))
                continue
            }
            if (isOpen()) break
            val rec = journal.get(opId)
            if (rec == null || rec.status != MutationStatus.PREPARED) {
                // Settled or owned elsewhere since the last look: this process must not dispatch it.
                removePending(opId)
                continue
            }
            pw.attempts++
            pw.version = rec.version
            when (dispatch(opId, pw)) {
                is RememberOutcome.Saved -> flushed++
                is RememberOutcome.HeldForReconciliation -> held++
                is RememberOutcome.Rejected -> dropped++
                else -> Unit
            }
        }
        return FlushReport(flushed, pendingCount(), dropped, held)
    }

    private suspend fun ensureSwept() = sweepMutex.withLock {
        if (!swept) {
            // A new writer generation converts older generations' IN_FLIGHT rows to UNKNOWN_HELD (never re-sent).
            mutationJournal!!.sweepStaleInFlight(writer!!.writerGeneration)
            swept = true
        }
    }

    private suspend fun putPending(opId: String, pw: PendingWrite) = pendingMutex.withLock { pending[opId] = pw }
    private suspend fun removePending(opId: String) = pendingMutex.withLock { pending.remove(opId) }
    private suspend fun pendingGet(opId: String): PendingWrite? = pendingMutex.withLock { pending[opId] }
    private suspend fun pendingIds(): List<String> = pendingMutex.withLock { pending.keys.toList() }
    private suspend fun pendingCount(): Int = pendingMutex.withLock { pending.size }

    private fun journalAudit(type: MemoryAuditType, ctx: AccessContext, detail: Map<String, String>) =
        audit.record(MemoryAuditEvent(type, ctx.executionId, ctx.traceId, detail))

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
