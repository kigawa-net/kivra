package net.kigawa.kivra.coding

/**
 * The task body the coding agent receives (Issue #229, addendum). Sizes are counted in code points, as the title is.
 *
 * A body is the manual body (what the operator typed) and, when the title is a reference, the resolved text after [REFERENCE_SEPARATOR]. The
 * whole is at most [MAX_CHARS]. When kivra cuts text, it ends the cut text with [TRUNCATION_MARK]; the claim reports `bodyTruncated` from that
 * mark, so the stored body needs no separate column. The text is UNTRUSTED. It is never logged (only its length and the mark's presence).
 */
object TaskBodyText {
    const val MAX_CHARS = 8_000

    /** Ends a cut text. Counted in [MAX_CHARS]. */
    const val TRUNCATION_MARK = "\n\n[truncated by kivra: the task body is longer than 8000 characters]"

    /** Between the manual body and the resolved reference. */
    const val REFERENCE_SEPARATOR = "\n\n--- 参照 ---\n\n"

    /** When the manual body leaves less room than this for the reference, the run fails instead of dropping the reference silently. */
    const val MIN_REFERENCE_CHARS = 200

    private val LINE_CONTROL = Regex("[\\u0000-\\u001F\\u007F-\\u009F]")
    private val TEXT_CONTROL = Regex("[\\u0000-\\u0008\\u000B-\\u000D\\u000E-\\u001F\\u007F-\\u009F]")

    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    fun isTruncated(text: String): Boolean = text.endsWith(TRUNCATION_MARK)

    /** [text] itself when it fits in [max] code points; otherwise its start followed by [TRUNCATION_MARK], in [max] code points in all. */
    fun clip(text: String, max: Int): String {
        if (codePoints(text) <= max) return text
        val base = text.removeSuffix(TRUNCATION_MARK)
        val keep = max - codePoints(TRUNCATION_MARK)
        return base.substring(0, base.offsetByCodePoints(0, keep)) + TRUNCATION_MARK
    }

    /**
     * The body for a run: the manual body alone when there is no reference, otherwise the manual body, the separator and the reference
     * (clipped to the room left). Null when the manual body leaves less than [MIN_REFERENCE_CHARS] for the reference.
     */
    fun compose(manual: String?, resolved: String): String? {
        if (manual.isNullOrEmpty()) return resolved
        val room = MAX_CHARS - codePoints(manual) - codePoints(REFERENCE_SEPARATOR)
        if (room < MIN_REFERENCE_CHARS) return null
        return manual + REFERENCE_SEPARATOR + clip(resolved, room)
    }

    /** A single line of text from outside (an issue title): control characters become spaces. */
    fun plainLine(text: String): String = text.replace(LINE_CONTROL, " ").trim()

    /** Multi-line text from outside (an issue body): line feed and tab are kept, other control characters are removed. */
    fun plainText(text: String): String = text.replace(TEXT_CONTROL, "")
}
