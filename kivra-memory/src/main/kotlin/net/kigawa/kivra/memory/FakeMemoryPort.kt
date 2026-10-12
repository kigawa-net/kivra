package net.kigawa.kivra.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kigawa.kivra.core.IdempotencyKey
import java.time.Clock

/**
 * In-memory [MemoryProvider] for tests and for running Kivra without recl. Implements the full contract
 * (scope isolation, optimistic locking, idempotent save, soft delete / redact) and supports failure injection.
 */
class FakeMemoryPort(private val clock: Clock = Clock.systemUTC()) : MemoryProvider {
    override val capabilities: Set<MemoryCapability> = setOf(MemoryCapability.IDEMPOTENT_SAVE, MemoryCapability.REDACT)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<MemoryId, MemoryEntry>()
    private val saved = HashMap<String, Pair<String, MemoryId>>()
    private var seq = 0

    /** When set, every call except [health] throws it (simulates recl being down). */
    @Volatile
    var failure: MemoryException? = null

    @Volatile
    var healthStatus: ProviderHealth = ProviderHealth.UP

    /** Number of calls per operation name, including failed ones. */
    val calls: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    private fun enter(op: String) {
        calls.merge(op, 1, Int::plus)
        failure?.let { throw it }
    }

    override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> = mutex.withLock {
        enter("search")
        requireScopes(q.scopes, ctx)
        val terms = tokens(q.text)
        entries.values.asSequence()
            .filter { it.status == MemoryStatus.ACTIVE && visible(it, ctx) }
            .filter { e -> e.scope.any { s -> q.scopes.any { s.isWithin(it) } } }
            .filter { q.kinds.isEmpty() || it.kind in q.kinds }
            .filter { it.sensitivity <= q.maxSensitivity }
            .map { e ->
                val hay = tokens(e.title + " " + e.summary + " " + (e.body ?: ""))
                MemoryHit(e.withoutBody(), terms.count { it in hay }.toDouble() / terms.size.coerceAtLeast(1))
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score * (0.5 + it.entry.importance) }
            .take(q.limit).toList()
    }

    override suspend fun get(id: MemoryId, ctx: AccessContext): MemoryEntry? = mutex.withLock {
        enter("get")
        entries[id]?.takeIf { it.status != MemoryStatus.DELETED && visible(it, ctx) }
    }

    override suspend fun pinned(scopes: List<ScopeRef>, ctx: AccessContext): List<MemoryEntry> = mutex.withLock {
        enter("pinned")
        requireScopes(scopes, ctx)
        entries.values.filter { it.status == MemoryStatus.ACTIVE && it.pinned && visible(it, ctx) && it.scope.any { s -> scopes.any { s.isWithin(it) } } }
    }

    override suspend fun save(e: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId = mutex.withLock {
        enter("save")
        saveLocked(e, key, ctx)
    }

    private fun saveLocked(e: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext, supersedes: List<MemoryId> = e.supersedes): MemoryId {
        requireScopes(e.scope, ctx)
        val fingerprint = sha256Hex(e.toString() + e.title + e.summary + e.body + e.sources)
        saved[key.value]?.let { (fp, id) ->
            if (fp != fingerprint) throw MemoryException(MemoryErrorKind.REJECTED, "idempotency key reused with different content")
            return id
        }
        val id = MemoryId("mem-${++seq}")
        val now = clock.instant()
        entries[id] = MemoryEntry(
            id, 1, MemoryStatus.ACTIVE, e.kind, e.scope, e.title, e.summary, e.body, e.sources, e.provenance, e.confidence, e.importance,
            e.sensitivity, e.validFrom, e.validUntil, e.retention, supersedes, e.pinned, now, now, e.writtenBy,
        )
        saved[key.value] = fingerprint to id
        return id
    }

    override suspend fun update(id: MemoryId, expectedVersion: Int, patch: MemoryPatch, ctx: AccessContext): MemoryEntry = mutex.withLock {
        enter("update")
        val cur = entries[id]?.takeIf { it.status == MemoryStatus.ACTIVE && visible(it, ctx) } ?: throw MemoryException(MemoryErrorKind.NOT_FOUND, "memory not found")
        if (cur.version != expectedVersion) throw MemoryException(MemoryErrorKind.VERSION_CONFLICT, "version conflict")
        if (cur.sensitivity == Sensitivity.RESTRICTED && patch.body != null) throw MemoryException(MemoryErrorKind.REJECTED, "restricted entries must not store a body")
        val next = cur.copy(
            version = cur.version + 1,
            summary = patch.summary ?: cur.summary,
            body = patch.body ?: cur.body,
            importance = patch.importance ?: cur.importance,
            pinned = patch.pinned ?: cur.pinned,
            validUntil = patch.validUntil ?: cur.validUntil,
            updatedAt = clock.instant(),
        )
        entries[id] = next
        next
    }

    override suspend fun supersede(oldId: MemoryId, expectedVersion: Int, new: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId = mutex.withLock {
        enter("supersede")
        saved[key.value]?.let { return@withLock saveLocked(new, key, ctx, listOf(oldId)) }
        val cur = entries[oldId]?.takeIf { it.status == MemoryStatus.ACTIVE && visible(it, ctx) } ?: throw MemoryException(MemoryErrorKind.NOT_FOUND, "memory not found")
        if (cur.version != expectedVersion) throw MemoryException(MemoryErrorKind.VERSION_CONFLICT, "version conflict")
        val newId = saveLocked(new, key, ctx, listOf(oldId))
        entries[oldId] = cur.copy(status = MemoryStatus.SUPERSEDED, version = cur.version + 1, updatedAt = clock.instant())
        newId
    }

    override suspend fun delete(id: MemoryId, mode: DeleteMode, ctx: AccessContext) = mutex.withLock {
        enter("delete")
        val cur = entries[id]?.takeIf { it.status != MemoryStatus.DELETED && visible(it, ctx) } ?: throw MemoryException(MemoryErrorKind.NOT_FOUND, "memory not found")
        entries[id] = when (mode) {
            DeleteMode.SOFT -> cur.copy(status = MemoryStatus.DELETED, version = cur.version + 1, updatedAt = clock.instant())
            DeleteMode.REDACT -> cur.copy(
                status = MemoryStatus.DELETED, version = cur.version + 1, updatedAt = clock.instant(),
                title = "[redacted]", summary = "[redacted]", body = null, sources = emptyList(),
            )
        }
    }

    override suspend fun health(): ProviderHealth = healthStatus

    /** Test helper: raw stored entry including deleted ones (to assert redaction). */
    suspend fun rawEntry(id: MemoryId): MemoryEntry? = mutex.withLock { entries[id] }

    suspend fun size(): Int = mutex.withLock { entries.size }

    private fun visible(e: MemoryEntry, ctx: AccessContext) = e.scope.all { ctx.permits(it) }

    private fun requireScopes(scopes: List<ScopeRef>, ctx: AccessContext) {
        if (scopes.any { !ctx.permits(it) }) throw MemoryException(MemoryErrorKind.FORBIDDEN, "scope not permitted")
    }

    private fun MemoryEntry.withoutBody() = copy(body = null)

    private fun tokens(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()
}
