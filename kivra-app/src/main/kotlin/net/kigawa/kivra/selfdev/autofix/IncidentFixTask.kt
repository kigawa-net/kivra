package net.kigawa.kivra.selfdev.autofix

import net.kigawa.kivra.selfdev.incident.IncidentCode
import net.kigawa.kivra.selfdev.incident.fixedReasonCode

/** The title and the body of the task a fix submits. Built from fixed codes, the reason code and the issue number only. */
internal data class IncidentFixTaskText(val title: String, val body: String)

/**
 * The task text of an incident fix (issue #226). The issue body is never read: the input is the incident code (an enum name), the reason
 * code (fixed shape, else OTHER), the issue number and the first twelve characters of the fingerprint. No free text, no model output, no
 * log, no secret. The fixed instructions tell the Planner the order of the work and its limits.
 */
internal fun renderIncidentFixTask(code: String, reason: String, issueNumber: Long, fingerprint: String): IncidentFixTaskText {
    val incident = IncidentCode.valueOf(code).name
    val fixedReason = fixedReasonCode(reason)
    val title = "[kivra-incident] $incident $fixedReason"
    val body = buildString {
        appendLine("Kivra の自己修復(Issue #226)による修正の依頼です。")
        appendLine()
        appendLine("- incident: $incident")
        appendLine("- reason: $fixedReason")
        appendLine("- incident issue: #$issueNumber")
        appendLine("- fingerprint: ${fingerprint.take(FINGERPRINT_SHOWN)}")
        appendLine()
        appendLine(
            "進め方: (1) 上の理由に対応する失敗を再現するテストを先に追加し、失敗することを確認する。 " +
                "(2) 最小の修正を加え、そのテストと既存のテストを通す。 " +
                "(3) 監視、テスト、安全規則、権限を無効化・緩和しない。再現できない場合は変更しない。",
        )
    }
    return IncidentFixTaskText(title.take(TITLE_MAX), body.take(BODY_MAX))
}

private const val FINGERPRINT_SHOWN = 12
private const val TITLE_MAX = 200
private const val BODY_MAX = 8_000
