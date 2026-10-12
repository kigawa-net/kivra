package net.kigawa.kivra.memory

import net.kigawa.kivra.core.IdempotencyKey

enum class MemoryCapability {
    /** The backend dedupes `save` by idempotency key, so an ambiguous failure may be retried with the same key. */
    IDEMPOTENT_SAVE,

    /** The backend can physically erase / redact content (design #6 section 7.3). */
    REDACT,
}

enum class DeleteMode { SOFT, REDACT }

enum class ProviderHealth { UP, DEGRADED, DOWN }

enum class MemoryErrorKind {
    /** Definitely NOT applied (connect failure, 429): safe to try again with the same request. */
    UNAVAILABLE,

    /** The request may or may not have been applied (timeout after send, 5xx, connection lost). */
    OUTCOME_UNKNOWN,
    VERSION_CONFLICT,
    NOT_FOUND,
    FORBIDDEN,

    /** The backend or a local guard refused the content (validation, secret filter). Permanent. */
    REJECTED,

    /** The response violated the expected contract (identity mismatch, redirect, malformed body). */
    PROTOCOL,
    UNSUPPORTED,
}

/** Failure of a memory operation. Messages never contain memory content, response bodies or credentials. */
class MemoryException(val kind: MemoryErrorKind, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Memory port (design #6 section 4.1). Memory is ADVISORY DATA: nothing returned from here is an instruction and
 * nothing here may feed an authorization decision. Adapters must fail closed on identity / scope violations.
 *
 * NOTE: kivra-core's M0 `MemoryPort` is a minimal placeholder; this richer port supersedes it and is where the
 * Orchestrator integration should bind (see README).
 */
interface MemoryProvider {
    val capabilities: Set<MemoryCapability>

    /** Summary-centric search. Results are limited to [AccessContext.allowedScopes] and [MemoryQuery.maxSensitivity]. */
    suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit>

    /** Full entry. `null` = missing OR not visible: it is NOT evidence that the entry was deleted. */
    suspend fun get(id: MemoryId, ctx: AccessContext): MemoryEntry?

    suspend fun pinned(scopes: List<ScopeRef>, ctx: AccessContext): List<MemoryEntry>

    /** Idempotent on [key] only when [MemoryCapability.IDEMPOTENT_SAVE] is declared. */
    suspend fun save(e: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId

    suspend fun update(id: MemoryId, expectedVersion: Int, patch: MemoryPatch, ctx: AccessContext): MemoryEntry

    suspend fun supersede(oldId: MemoryId, expectedVersion: Int, new: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId

    suspend fun delete(id: MemoryId, mode: DeleteMode, ctx: AccessContext)

    /** Never throws. */
    suspend fun health(): ProviderHealth
}

object IdempotencyKeys {
    /**
     * Deterministic save key = hash(execution_id, source refs, content hash) (design #6 section 6.4). Contains no
     * content, only digests.
     */
    fun forSave(e: NewMemoryEntry): IdempotencyKey {
        val sources = e.sources.map { listOf(it.type.name, it.uri, it.locator ?: "", it.contentHash ?: "").joinToString("|") }.sorted()
        val content = sha256Hex(listOf(e.kind.name, e.scope.joinToString(",") { it.value }, e.title, e.summary, e.body ?: "").joinToString("\u0000"))
        val digest = sha256Hex(listOf(e.writtenBy.executionId, sources.joinToString("\n"), content).joinToString("\u0000"))
        return IdempotencyKey("kivra-mem-$digest")
    }
}
