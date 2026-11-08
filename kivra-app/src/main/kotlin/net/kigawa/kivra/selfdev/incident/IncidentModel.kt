package net.kigawa.kivra.selfdev.incident

import java.security.MessageDigest
import net.kigawa.kivra.github.CreateIssue
import net.kigawa.kivra.github.RepoRef

/*
 * The incident report of the self-development failure detector (issue #225). Everything that reaches GitHub is built here from fixed
 * codes and counts. No run id, no log, no task text, no model output and no provider text is ever part of an issue.
 */

/** The fixed incident codes. A new code is a design change: it needs its own rule in [IncidentIssueWatch]. */
enum class IncidentCode {
    /** Self-development Runs ended FAILED at least the configured minimum number of times in the window, with the same reason code. */
    RUN_FAILED,
    /** A self-development Run ended UNKNOWN (an external result is unknown and held for a human). One is enough. */
    RUN_UNKNOWN,
    /** The autopilot's circuit breaker is open. */
    BREAKER_OPEN,
}

/** A reason code is an upper-case identifier. Anything else (free text, a value that looks like a secret) becomes [REASON_OTHER]. */
private val REASON_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")

const val REASON_NONE = "NONE"
const val REASON_OTHER = "OTHER"

/** The reason code of a Run as it may appear in an issue: the stored code when it has the fixed shape, [REASON_NONE] for none, else [REASON_OTHER]. */
fun fixedReasonCode(raw: String?): String = when {
    raw == null -> REASON_NONE
    REASON_CODE.matches(raw) -> raw
    else -> REASON_OTHER
}

/** One detected incident: its code, its reason code and the number of Runs behind it in the window (1 for the breaker). */
data class Incident(val code: IncidentCode, val reason: String, val count: Long) {
    fun fingerprint(repo: RepoRef): String = incidentFingerprint(repo, code, reason)
}

/** The fingerprint of a cause: SHA-256 hex of the fixed codes and the repository. A cause keeps its fingerprint across Pods and restarts. */
fun incidentFingerprint(repo: RepoRef, code: IncidentCode, reason: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("${code.name}|$reason|$repo".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** The issue of one incident: fixed text, the code, the reason code, the count, the window and a short fingerprint. Nothing else. */
fun renderIncidentIssue(incident: Incident, repo: RepoRef, windowHours: Int, fingerprint: String): CreateIssue {
    val title = "[kivra-incident] ${incident.code.name} ${incident.reason}"
    val body = buildString {
        appendLine("Kivra の自己開発の失敗検知により自動起票されました(#225)。")
        appendLine()
        appendLine("- repository: $repo")
        appendLine("- code: ${incident.code.name}")
        appendLine("- reason: ${incident.reason}")
        appendLine("- count: ${incident.count} (window: ${windowHours}h)")
        appendLine("- fingerprint: ${fingerprint.take(FINGERPRINT_SHOWN)}")
        appendLine()
        appendLine("この Issue には実行 ID、ログ、タスク本文、モデルの出力を含めません。対象の Run は Kivra の管理画面または監査で確認してください。")
        appendLine("同じ原因は ${windowHours} 時間の間は再度起票されません。")
    }
    return CreateIssue(title = title.take(CreateIssue.MAX_TITLE), body = body)
}

private const val FINGERPRINT_SHOWN = 12
