package net.kigawa.kivra.fusha.poller

import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Raised when a task received from the provider cannot be trusted. The whole poll is rejected (fail closed). */
class TaskValidationException(message: String) : RuntimeException(message)

/**
 * Checks every snapshot before it can reach change detection or TaskBinding (issue #28 acceptance: TaskId identity,
 * duplicate and invalid ids, Environment boundary, updatedAt normalization).
 *
 * A single invalid entry rejects the whole listing: a partial listing cannot be told apart from a removal.
 */
internal class TaskSnapshotGuard(private val environment: String) {
    private val prefix = "fusha:$environment:"

    /** Returns the numeric part of `fusha:{environment}:{id}`. Only canonical positive decimal ids are accepted. */
    fun parseId(id: TaskId): Long {
        val raw = id.value
        if (!raw.startsWith(prefix)) throw TaskValidationException("task id does not belong to environment '$environment'")
        val digits = raw.removePrefix(prefix)
        if (!CANONICAL_ID.matches(digits)) throw TaskValidationException("task id has a non-canonical numeric part")
        return digits.toLong()
    }

    /** Validates ids and normalizes `updatedAt` to millisecond precision (fusha-task exposes milliseconds). */
    fun normalize(snapshot: TaskSnapshot): TaskSnapshot {
        parseId(snapshot.id)
        snapshot.nextTaskIds.forEach { parseId(it) }
        snapshot.relatedTaskIds.forEach { parseId(it) }
        return snapshot.copy(updatedAt = snapshot.updatedAt.truncatedTo(ChronoUnit.MILLIS))
    }

    /** Listing: every entry must be valid and ids must be unique. Result is keyed by id in listing order. */
    fun validateList(listing: List<TaskSnapshot>): Map<TaskId, TaskSnapshot> {
        val out = LinkedHashMap<TaskId, TaskSnapshot>(listing.size * 2)
        for (raw in listing) {
            val snapshot = normalize(raw)
            if (out.put(snapshot.id, snapshot) != null) throw TaskValidationException("listing contains a duplicate task id")
        }
        return out
    }

    /** GET result must be the requested resource, not a different one returned by a misconfigured proxy or a bug. */
    fun validateGet(requested: TaskId, returned: TaskSnapshot): TaskSnapshot {
        val snapshot = normalize(returned)
        if (snapshot.id != requested) throw TaskValidationException("GET returned a different task id than requested")
        return snapshot
    }

    private companion object {
        val CANONICAL_ID = Regex("[1-9][0-9]{0,18}")
    }
}

/**
 * Content hash used for change detection. Covers everything except [TaskSnapshot.fetchedAt], which changes on every read.
 * Strings are NFC-normalized and length-prefixed so that different inputs cannot produce the same byte stream.
 * Fields are sorted by key. `updatedAt` is taken at millisecond precision.
 */
internal object TaskContentHash {
    fun of(snapshot: TaskSnapshot): String {
        val sb = StringBuilder()
        fun put(value: String?) {
            if (value == null) {
                sb.append("N;")
            } else {
                val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                sb.append('S').append(normalized.length).append(':').append(normalized).append(';')
            }
        }
        put(snapshot.id.value)
        put(snapshot.title)
        put(snapshot.description)
        put(snapshot.assigneeId)
        put(snapshot.dueDate)
        sb.append('F').append(snapshot.fields.size).append(';')
        snapshot.fields.toSortedMap().forEach { (k, v) -> put(k); put(v) }
        sb.append('N').append(snapshot.nextTaskIds.size).append(';')
        snapshot.nextTaskIds.forEach { put(it.value) }
        sb.append('R').append(snapshot.relatedTaskIds.size).append(';')
        snapshot.relatedTaskIds.forEach { put(it.value) }
        put(snapshot.updatedAt.truncatedTo(ChronoUnit.MILLIS).toString())
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun Instant.toRevisionText(): String = truncatedTo(ChronoUnit.MILLIS).toString()
