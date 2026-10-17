package net.kigawa.kivra.coding

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.EnumSet
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.FileChange
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.PatchVerdict
import net.kigawa.kivra.core.patch.RejectReason
import net.kigawa.kivra.core.patch.Write

/**
 * Checks a [CodingResult] before it can become a stored change set. Fail-closed: any problem rejects the whole result.
 *
 * Order: (1) the number of changes, before any content is read; (2) the shape of each change (a symlink, submodule, executable-bit
 * change or binary file is refused, and a WRITE must carry content, a DELETE must not); (3) the result fields (exit status, session id);
 * (4) the existing [PatchPolicy], without a repository snapshot. The snapshot checks (missing delete targets, shrinking writes) run later
 * in the self-development runner, against the base commit kivra reads itself.
 *
 * The digest is the SHA-256 of a length-prefixed encoding of the result as received. It is stored in the attempt row, so a result can
 * be matched to the row it was accepted with.
 */
internal class CodingResultValidator(private val policy: PatchPolicy, private val limits: CodingLimits) {

    sealed interface Verdict {
        data class Accepted(val patch: PatchSet, val digest: String) : Verdict
        data class Rejected(val reasons: List<CodingRejectReason>, val policyReasons: List<RejectReason>) : Verdict
    }

    fun check(result: CodingResult): Verdict {
        if (result.changes.size > limits.maxChangedFiles) return reject(CodingRejectReason.TOO_MANY_CHANGES)

        val shape = EnumSet.noneOf(CodingRejectReason::class.java)
        for (change in result.changes) {
            when (change.fileKind) {
                FileKind.REGULAR -> Unit
                FileKind.SYMLINK -> shape.add(CodingRejectReason.SYMLINK)
                FileKind.SUBMODULE -> shape.add(CodingRejectReason.SUBMODULE)
                FileKind.EXECUTABLE -> shape.add(CodingRejectReason.EXECUTABLE_BIT)
                FileKind.BINARY -> shape.add(CodingRejectReason.BINARY_FILE)
            }
            val hasContent = change.content != null
            val malformed = when (change.kind) {
                ChangeKind.WRITE -> !hasContent
                ChangeKind.DELETE -> hasContent
            }
            if (malformed) shape.add(CodingRejectReason.MALFORMED_CHANGE)
        }
        if (!isValidExitStatus(result.exitStatus)) shape.add(CodingRejectReason.MALFORMED_RESULT)
        if (result.sessionId != null && !SESSION_ID.matches(result.sessionId)) shape.add(CodingRejectReason.MALFORMED_RESULT)
        if (shape.isNotEmpty()) return Verdict.Rejected(shape.toList(), emptyList())

        val patch = PatchSet(
            files = result.changes.map { change -> toFileChange(change) },
            summary = result.summary,
        )
        return when (val verdict = policy.validate(patch)) {
            is PatchVerdict.Accepted -> Verdict.Accepted(patch, digestOf(result))
            is PatchVerdict.Rejected -> Verdict.Rejected(listOf(CodingRejectReason.POLICY_REJECTED), verdict.reasons)
        }
    }

    private fun reject(reason: CodingRejectReason) = Verdict.Rejected(listOf(reason), emptyList())

    private fun toFileChange(change: CodingChange): FileChange = when (change.kind) {
        ChangeKind.WRITE -> Write(change.path, change.content.orEmpty())
        ChangeKind.DELETE -> Delete(change.path)
    }

    private fun isValidExitStatus(status: Int): Boolean = status in 0..255

    companion object {
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** SHA-256 (lowercase hex) of the length-prefixed fields of [result], in order. Deterministic for the same input. */
        fun digestOf(result: CodingResult): String {
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            fun put(text: String) {
                val raw = text.toByteArray(Charsets.UTF_8)
                out.writeInt(raw.size)
                out.write(raw)
            }
            put("coding-result-v1")
            put(result.summary)
            out.writeInt(result.changes.size)
            for (change in result.changes) {
                put(change.kind.name)
                put(change.fileKind.name)
                put(change.path)
                out.writeBoolean(change.content != null)
                put(change.content.orEmpty())
            }
            put(result.exitStatus.toString())
            put(result.sessionId.orEmpty())
            out.flush()
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
