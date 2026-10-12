package net.kigawa.kivra.memory

import java.security.MessageDigest
import java.time.Instant

/**
 * Write provenance. A write without taskId / executionId / sources is not representable (issue #37: "provenance
 * なしでは受理されない"): [NewMemoryEntry] requires all of it at construction.
 */
data class WriteProvenance(val taskId: String, val executionId: String, val createdBy: String) {
    init {
        require(taskId.isNotBlank() && executionId.isNotBlank() && createdBy.isNotBlank()) { "provenance ids must not be blank" }
    }
}

/** An entry to be saved. Validation mirrors design #6 section 3.2 / 6.1 / 7.1. Secrets are checked by the write guard. */
data class NewMemoryEntry(
    val kind: MemoryKind,
    val scope: List<ScopeRef>,
    val title: String,
    val summary: String,
    val body: String? = null,
    val sources: List<SourceRef>,
    val provenance: MemoryProvenance,
    val confidence: Double,
    val importance: Double,
    val sensitivity: Sensitivity,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val retention: String,
    val supersedes: List<MemoryId> = emptyList(),
    val pinned: Boolean = false,
    val writtenBy: WriteProvenance,
) {
    init {
        require(scope.isNotEmpty() && scope.size <= 8) { "scope must have 1..8 entries" }
        require(title.isNotBlank() && title.length <= 200) { "title must be 1..200 chars" }
        require(summary.isNotBlank() && summary.length <= 2000) { "summary must be 1..2000 chars" }
        require(body == null || body.length <= MAX_BODY) { "body too large" }
        require(sources.isNotEmpty() && sources.size <= 32) { "at least one source (provenance) is required" }
        require(confidence in 0.0..1.0 && importance in 0.0..1.0) { "confidence / importance must be within 0..1" }
        if (provenance == MemoryProvenance.AGENT_INFERRED) require(confidence <= MAX_INFERRED_CONFIDENCE) { "agent_inferred confidence is capped at 0.6" }
        if (kind == MemoryKind.PREFERENCE) require(provenance == MemoryProvenance.USER_STATED) { "preference must be user_stated, never inferred" }
        if (sensitivity == Sensitivity.RESTRICTED) require(body == null) { "restricted entries must not store a body" }
        require(validFrom == null || validUntil == null || validFrom.isBefore(validUntil)) { "validFrom must precede validUntil" }
        require(retention.isNotBlank() && retention.length <= 64) { "retention policy id required" }
    }

    /** Never prints text: memory content may be sensitive. */
    override fun toString(): String =
        "NewMemoryEntry(kind=$kind, scope=$scope, sensitivity=$sensitivity, titleLen=${title.length}, summaryLen=${summary.length}, bodyLen=${body?.length ?: 0}, sources=${sources.size}, writtenBy=$writtenBy)"

    companion object {
        const val MAX_BODY = 64 * 1024
        const val MAX_INFERRED_CONFIDENCE = 0.6
    }
}

/** A stored entry as returned by a provider. */
data class MemoryEntry(
    val id: MemoryId,
    val version: Int,
    val status: MemoryStatus,
    val kind: MemoryKind,
    val scope: List<ScopeRef>,
    val title: String,
    val summary: String,
    val body: String?,
    val sources: List<SourceRef>,
    val provenance: MemoryProvenance,
    val confidence: Double,
    val importance: Double,
    val sensitivity: Sensitivity,
    val validFrom: Instant?,
    val validUntil: Instant?,
    val retention: String,
    val supersedes: List<MemoryId>,
    val pinned: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val writtenBy: WriteProvenance,
) {
    val citationId: CitationId get() = CitationId.of(id, version)

    override fun toString(): String =
        "MemoryEntry(id=${id.value}, version=$version, status=$status, kind=$kind, sensitivity=$sensitivity, titleLen=${title.length}, summaryLen=${summary.length}, bodyLen=${body?.length ?: 0})"
}

/** Partial update. `null` means "leave unchanged". */
data class MemoryPatch(
    val summary: String? = null,
    val body: String? = null,
    val importance: Double? = null,
    val pinned: Boolean? = null,
    val validUntil: Instant? = null,
) {
    init {
        require(summary != null || body != null || importance != null || pinned != null || validUntil != null) { "empty patch" }
        require(summary == null || (summary.isNotBlank() && summary.length <= 2000)) { "invalid summary" }
        require(body == null || body.length <= NewMemoryEntry.MAX_BODY) { "body too large" }
        require(importance == null || importance in 0.0..1.0) { "importance out of range" }
    }

    override fun toString(): String = "MemoryPatch(summary=${summary != null}, body=${body != null}, importance=$importance, pinned=$pinned, validUntil=$validUntil)"
}

/** Search query. The text is never printed (design #6 section 7.1: log hash + length only). */
data class MemoryQuery(
    val text: String,
    val scopes: List<ScopeRef>,
    val kinds: Set<MemoryKind> = emptySet(),
    val limit: Int = 10,
    val maxSensitivity: Sensitivity = Sensitivity.CONFIDENTIAL,
) {
    init {
        require(text.isNotBlank() && text.length <= 2000) { "query text must be 1..2000 chars" }
        require(scopes.isNotEmpty() && scopes.size <= 8) { "query scopes must have 1..8 entries" }
        require(limit in 1..50) { "limit must be 1..50" }
        require(maxSensitivity != Sensitivity.RESTRICTED) { "restricted memory is never returned" }
    }

    override fun toString(): String = "MemoryQuery(textLen=${text.length}, textHash=${sha256Hex(text).take(12)}, scopes=$scopes, kinds=$kinds, limit=$limit)"
}

data class MemoryHit(val entry: MemoryEntry, val score: Double)

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
