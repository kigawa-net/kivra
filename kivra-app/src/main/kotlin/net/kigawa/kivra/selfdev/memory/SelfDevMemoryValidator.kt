package net.kigawa.kivra.selfdev.memory

import java.security.MessageDigest
import java.text.Normalizer
import net.kigawa.kivra.memory.SecretFilter
import net.kigawa.kivra.memory.SecretKind

/*
 * The one validation function of the self-development memory (ADR 0016 section 3.2, rules V1 .. V14). It is pure: no I/O, no clock,
 * no logging. The repository gathers the database facts it needs (reference existence, the duplicate, the quotas) and passes them in.
 *
 * Validation is a REFUSAL, never a repair: a text that fails a rule is not stored in any changed form. A refusal carries a fixed code
 * and, for a secret, the names of the detected kinds. It never carries the text, a fragment of it or a hash of it (the reason is
 * ADR 0016 section 3.2: a short text's hash can be matched by guessing).
 */

/** The source of a record (ADR 0016 section 4.2 `source_kind`). */
enum class MemorySource { OPERATOR, PLANNER, DEVELOPER, SYSTEM }

/** The trust tier of a record. Only [TRUSTED_OPERATOR] text may ever be put in a prompt (and not before P4). */
enum class MemoryTier { TRUSTED_OPERATOR, UNTRUSTED }

/**
 * How a write arrived. The caller names the channel, never the tier: the channel and the kind decide the source and the tier (V2).
 * Only [HTTP_OPERATOR] (an authenticated operator request) may create a [MemoryTier.TRUSTED_OPERATOR] record.
 */
enum class MemoryChannel(val source: MemorySource) {
    HTTP_OPERATOR(MemorySource.OPERATOR),
    PLANNER_AUTO(MemorySource.PLANNER),
    DEVELOPER_AUTO(MemorySource.DEVELOPER),
    SYSTEM_AUTO(MemorySource.SYSTEM),
}

/**
 * The kinds (ADR 0016 section 4.1). [maxChars] is in code points after NFC; [multiline] says whether a newline is allowed.
 * A fact has no text: its fields are a fixed code and a count.
 */
enum class MemoryKind(
    val source: MemorySource,
    val tier: MemoryTier,
    val maxChars: Int,
    val multiline: Boolean,
    val isFact: Boolean,
) {
    OPERATOR_NOTE(MemorySource.OPERATOR, MemoryTier.TRUSTED_OPERATOR, 500, multiline = true, isFact = false),
    GOAL_TITLE(MemorySource.OPERATOR, MemoryTier.TRUSTED_OPERATOR, 200, multiline = false, isFact = false),
    REJECTION_REASON(MemorySource.OPERATOR, MemoryTier.TRUSTED_OPERATOR, 300, multiline = true, isFact = false),
    PLAN_RATIONALE(MemorySource.PLANNER, MemoryTier.UNTRUSTED, 300, multiline = false, isFact = false),
    FAILURE_EXPLANATION(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 300, multiline = false, isFact = false),
    CYCLE_REVIEW(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 300, multiline = false, isFact = false),
    PLAN_FACT(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 0, multiline = false, isFact = true),
    TASK_FACT(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 0, multiline = false, isFact = true),
    RUN_FACT(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 0, multiline = false, isFact = true),
    ATTEMPT_FACT(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 0, multiline = false, isFact = true),
    COST_FACT(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 0, multiline = false, isFact = true),
    GOAL_FACT(MemorySource.SYSTEM, MemoryTier.UNTRUSTED, 0, multiline = false, isFact = true),
}

/** The fixed reason codes of a refusal (ADR 0016 section 3.2). The name is the code written to the audit. */
enum class MemoryRefusal {
    KIND_NOT_ALLOWED, // V1
    TIER_MISMATCH, // V2
    ENCODING_INVALID, // V3
    EMPTY, // V4
    TOO_LONG, // V5
    CONTROL_CHAR, // V6
    INVISIBLE_OR_BIDI, // V7
    SECRET_DETECTED, // V8, V9
    REF_NOT_FOUND, // V10
    QUOTA_EXCEEDED, // V12
    FACT_SHAPE_INVALID, // V13
    NOT_OPERATOR, // V14
}

/**
 * A write as the caller offers it. Every field is raw: the validator decides. [kind] is a string so that an unknown kind is refused
 * (V1), not silently mapped. [declaredSource] and [declaredTier] are optional; when present, they must match the kind (V2).
 * [operatorId] is the authenticated operator's id: it is hashed here and never stored.
 */
data class MemoryCandidate(
    val kind: String,
    val channel: MemoryChannel,
    val declaredSource: String? = null,
    val declaredTier: String? = null,
    val operatorAuthenticated: Boolean = false,
    val operatorId: String? = null,
    val goalId: String? = null,
    val planId: String? = null,
    val runId: String? = null,
    val taskOrdinal: Int? = null,
    val body: String? = null,
    val code: String? = null,
    val countValue: Int? = null,
    val identitySha256: String? = null,
)

/** Database facts the repository gathers before it calls [SelfDevMemoryValidator.validate]. Nothing here is text. */
data class MemoryFacts(
    /** V10: whether each referenced row exists, keyed by "goal", "plan", "run", "task". Only the keys of the references given are read. */
    val referenceFound: Map<String, Boolean> = emptyMap(),
    /**
     * V11: the id of an ACTIVE record with the same kind, references and body hash, or null. Called once, with the body's SHA-256,
     * and only after every rule before V11 has passed: a write that would be refused never reaches the lookup.
     */
    val duplicateOf: (bodySha256: String) -> String? = { null },
    /** V12: ACTIVE text records of the goal. */
    val goalTextCount: Int = 0,
    /** V12: ACTIVE fact records of the goal. */
    val goalFactCount: Int = 0,
    /** V12: ACTIVE records written by the operator. */
    val operatorTextCount: Int = 0,
    /** V12: every ACTIVE record. */
    val totalActiveCount: Int = 0,
    /** V12: text records written by the automatic channels today (UTC). */
    val autoTextTodayCount: Int = 0,
)

/** A candidate that passed. [body] is the NFC text (null for a fact). The hashes and counts are the ones the row stores. */
data class NormalizedMemory(
    val kind: MemoryKind,
    val source: MemorySource,
    val tier: MemoryTier,
    val body: String?,
    val bodySha256: String?,
    val bodyChars: Int?,
    val code: String?,
    val countValue: Int?,
    val identitySha256: String?,
    val actorHash: String?,
)

sealed interface MemoryVerdict {
    data class Accept(val normalized: NormalizedMemory) : MemoryVerdict

    /** V11: the same record exists. Nothing is written; the caller returns the existing id. */
    data class Duplicate(val recordId: String) : MemoryVerdict

    /** A refusal. [chars] is the NFC length of a text (for the audit only). [detail] holds fixed names only (secret kinds). */
    data class Refuse(val code: MemoryRefusal, val chars: Int? = null, val detail: String? = null) : MemoryVerdict
}

object SelfDevMemoryValidator {
    private val codeShape = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val hexSha = Regex("[0-9a-f]{64}")
    private val urlCredentials = Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s/?#@]+:[^\\s/?#@]+@")

    /** The text as the validator sees it: NFC. Returns null when the text is not valid UTF-16 (V3). */
    fun normalizeText(raw: String): String? {
        if (!isValidUtf16(raw)) return null
        return Normalizer.normalize(raw, Normalizer.Form.NFC)
    }

    /** SHA-256 (hex) of the NFC text in UTF-8. Used for V11 and the stored `body_sha256`. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The first 12 hex characters of the SHA-256 of an operator id. The raw id is never stored. */
    fun actorHash(operatorId: String): String = sha256Hex(operatorId).take(12)

    /** V3 in code: no unpaired surrogate. A Java string may hold one; UTF-8 cannot represent it. */
    fun isValidUtf16(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                    continue
                }
                return false
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    fun validate(candidate: MemoryCandidate, facts: MemoryFacts): MemoryVerdict {
        // V1: the kind is one of the list.
        val kind = MemoryKind.entries.firstOrNull { it.name == candidate.kind }
            ?: return MemoryVerdict.Refuse(MemoryRefusal.KIND_NOT_ALLOWED)

        // V2: the source and the tier follow from the kind; the channel must be the kind's source; the caller may not pick a tier.
        if (candidate.channel.source != kind.source) return refuse(MemoryRefusal.TIER_MISMATCH)
        if (candidate.declaredSource != null && candidate.declaredSource != kind.source.name) return refuse(MemoryRefusal.TIER_MISMATCH)
        if (candidate.declaredTier != null && candidate.declaredTier != kind.tier.name) return refuse(MemoryRefusal.TIER_MISMATCH)
        if (kind.tier == MemoryTier.TRUSTED_OPERATOR && candidate.channel != MemoryChannel.HTTP_OPERATOR) {
            return refuse(MemoryRefusal.TIER_MISMATCH)
        }

        // V14: an operator write needs an authenticated operator.
        val actor: String?
        if (candidate.channel == MemoryChannel.HTTP_OPERATOR) {
            val id = candidate.operatorId
            if (!candidate.operatorAuthenticated || id.isNullOrBlank()) return refuse(MemoryRefusal.NOT_OPERATOR)
            actor = actorHash(id)
        } else {
            actor = null
        }

        if (kind.isFact) return validateFact(candidate, kind, facts, actor)

        // Text kinds carry no fact fields.
        if (candidate.code != null || candidate.countValue != null || candidate.identitySha256 != null) {
            return refuse(MemoryRefusal.FACT_SHAPE_INVALID)
        }

        // V10: references must exist (checked before the text, as the ADR order says).
        if (!referencesFound(candidate, facts)) return refuse(MemoryRefusal.REF_NOT_FOUND)

        val raw = candidate.body ?: return refuse(MemoryRefusal.EMPTY)
        // V3: valid UTF-16, then NFC for the rules below.
        val text = normalizeText(raw) ?: return refuse(MemoryRefusal.ENCODING_INVALID)
        val chars = text.codePointCount(0, text.length)
        // V4: empty or blank.
        if (text.isBlank()) return refuse(MemoryRefusal.EMPTY, chars)
        // V5: length in code points, never truncated.
        if (chars > kind.maxChars) return refuse(MemoryRefusal.TOO_LONG, chars)
        // V6: control characters. Tab is allowed; a newline only for a multiline kind.
        if (hasControlChar(text, kind.multiline)) return refuse(MemoryRefusal.CONTROL_CHAR, chars)
        // V7: invisible and bidi formatting characters.
        if (hasInvisibleOrBidi(text)) return refuse(MemoryRefusal.INVISIBLE_OR_BIDI, chars)
        // V8: secrets. The kinds are recorded; the matched text is not.
        val findings = SecretFilter.scan(text)
        val nonUrl = findings.filter { it.kind != SecretKind.URL_CREDENTIALS }
        if (nonUrl.isNotEmpty()) {
            return MemoryVerdict.Refuse(MemoryRefusal.SECRET_DETECTED, chars, detail = nonUrl.map { it.kind.name }.distinct().sorted().joinToString(","))
        }
        // V9: URL credentials (scheme://user:pass@), checked on their own.
        if (findings.any { it.kind == SecretKind.URL_CREDENTIALS } || urlCredentials.containsMatchIn(text)) {
            return MemoryVerdict.Refuse(MemoryRefusal.SECRET_DETECTED, chars, detail = SecretKind.URL_CREDENTIALS.name)
        }

        val sha = sha256Hex(text)
        // V11: the same kind, goal and body is already stored: return it, write nothing.
        facts.duplicateOf(sha)?.let { return MemoryVerdict.Duplicate(it) }
        // V12: quotas.
        if (quotaExceeded(kind, candidate, facts)) return refuse(MemoryRefusal.QUOTA_EXCEEDED, chars)

        return MemoryVerdict.Accept(
            NormalizedMemory(
                kind = kind, source = kind.source, tier = kind.tier, body = text, bodySha256 = sha, bodyChars = chars,
                code = null, countValue = null, identitySha256 = null, actorHash = actor,
            ),
        )
    }

    private fun validateFact(candidate: MemoryCandidate, kind: MemoryKind, facts: MemoryFacts, actor: String?): MemoryVerdict {
        // V13: a fact has no text, a fixed code (or a count, or both), and a count of 0 or more.
        if (candidate.body != null) return refuse(MemoryRefusal.FACT_SHAPE_INVALID)
        val code = candidate.code
        if (code == null && candidate.countValue == null) return refuse(MemoryRefusal.FACT_SHAPE_INVALID)
        if (code != null && !codeShape.matches(code)) return refuse(MemoryRefusal.FACT_SHAPE_INVALID)
        if (candidate.countValue != null && candidate.countValue < 0) return refuse(MemoryRefusal.FACT_SHAPE_INVALID)
        val identity = candidate.identitySha256
        if (identity != null && !hexSha.matches(identity)) return refuse(MemoryRefusal.FACT_SHAPE_INVALID)
        if (identity != null && kind != MemoryKind.TASK_FACT) return refuse(MemoryRefusal.FACT_SHAPE_INVALID)

        if (!referencesFound(candidate, facts)) return refuse(MemoryRefusal.REF_NOT_FOUND)
        if (quotaExceeded(kind, candidate, facts)) return refuse(MemoryRefusal.QUOTA_EXCEEDED)
        return MemoryVerdict.Accept(
            NormalizedMemory(
                kind = kind, source = kind.source, tier = kind.tier, body = null, bodySha256 = null, bodyChars = null,
                code = code, countValue = candidate.countValue, identitySha256 = identity, actorHash = actor,
            ),
        )
    }

    private fun referencesFound(candidate: MemoryCandidate, facts: MemoryFacts): Boolean {
        // A task is a plan task: it needs its plan.
        if (candidate.taskOrdinal != null && candidate.planId == null) return false
        val needed = mutableListOf<String>()
        if (candidate.goalId != null) needed += "goal"
        if (candidate.planId != null) needed += "plan"
        if (candidate.runId != null) needed += "run"
        if (candidate.taskOrdinal != null) needed += "task"
        return needed.all { facts.referenceFound[it] == true }
    }

    private fun quotaExceeded(kind: MemoryKind, candidate: MemoryCandidate, facts: MemoryFacts): Boolean {
        if (kind.isFact) {
            if (candidate.goalId != null && facts.goalFactCount >= MAX_FACTS_PER_GOAL) return true
        } else {
            if (candidate.goalId != null && facts.goalTextCount >= MAX_TEXTS_PER_GOAL) return true
            if (kind.source == MemorySource.OPERATOR && facts.operatorTextCount >= MAX_OPERATOR_TEXTS) return true
            if (candidate.channel != MemoryChannel.HTTP_OPERATOR && facts.autoTextTodayCount >= MAX_AUTO_TEXTS_PER_DAY) return true
        }
        return facts.totalActiveCount >= MAX_TOTAL_RECORDS
    }

    private fun hasControlChar(text: String, multiline: Boolean): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val bad = when {
                cp == 0x09 -> false
                cp == 0x0A -> !multiline
                cp < 0x20 || cp == 0x7F -> true
                cp in 0x80..0x9F -> true
                cp == 0x2028 || cp == 0x2029 -> true
                else -> false
            }
            if (bad) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun hasInvisibleOrBidi(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val bad = cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp in 0x2060..0x2064 ||
                cp == 0xFEFF || cp in 0xE0000..0xE007F
            if (bad) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun refuse(code: MemoryRefusal, chars: Int? = null) = MemoryVerdict.Refuse(code, chars)

    const val MAX_TEXTS_PER_GOAL = 10
    const val MAX_FACTS_PER_GOAL = 60
    const val MAX_OPERATOR_TEXTS = 500
    const val MAX_AUTO_TEXTS_PER_DAY = 50
    const val MAX_TOTAL_RECORDS = 50_000
}
