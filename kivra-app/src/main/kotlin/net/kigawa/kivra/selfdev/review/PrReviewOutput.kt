package net.kigawa.kivra.selfdev.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The model's answer is untrusted. It is parsed strictly here, and only its fixed codes and length-bounded plain text reach a comment.
 */

enum class PrReviewVerdict { PASS, CONCERNS }

enum class PrReviewSeverity { HIGH, MEDIUM, LOW }

data class PrReviewFinding(val severity: PrReviewSeverity, val title: String, val detail: String)

data class PrReviewResult(val verdict: PrReviewVerdict, val findings: List<PrReviewFinding>)

object PrReviewOutputParser {
    const val MAX_FINDINGS = 10

    /**
     * The answer as a [PrReviewResult], or null when it is not the expected shape. Only the outermost JSON object is read. PASS carries no
     * finding and CONCERNS carries at least one. More than [MAX_FINDINGS] findings are cut to that many.
     */
    fun parse(text: String): PrReviewResult? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val verdict = (obj["verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { name -> PrReviewVerdict.entries.firstOrNull { it.name == name } } ?: return null
        val items = obj["findings"] as? JsonArray ?: return null
        val findings = items.take(MAX_FINDINGS).map { el ->
            val f = el as? JsonObject ?: return null
            val severity = (f["severity"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { name -> PrReviewSeverity.entries.firstOrNull { it.name == name } } ?: return null
            val title = (f["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val detail = (f["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            PrReviewFinding(severity, title, detail)
        }
        if (verdict == PrReviewVerdict.PASS && findings.isNotEmpty()) return null
        if (verdict == PrReviewVerdict.CONCERNS && findings.isEmpty()) return null
        return PrReviewResult(verdict, findings)
    }
}

/** Plain text for a comment. Nothing of the model's text can become markup, a mention, a link, a code block or a second line. */
object PrReviewText {
    const val MAX_TITLE = 120
    const val MAX_DETAIL = 400
    private const val ELLIPSIS = "..."

    fun clean(raw: String, maxChars: Int): String {
        val printable = raw.filter { ch ->
            ch != '\n' && ch != '\r' && ch != '\t' && !ch.isISOControl() && Character.getType(ch) != Character.FORMAT.toInt()
        }
        val escaped = printable
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("`", "'")
            .replace("@", "@​")
            .replace("|", "\\|")
            .trim()
        return if (escaped.length > maxChars) escaped.take(maxChars - ELLIPSIS.length) + ELLIPSIS else escaped
    }
}

/** The comment the watch posts: fixed text, the sanitized findings, and a marker that names the reviewed head. */
object PrReviewRenderer {
    const val MAX_COMMENT_CHARS = 6_000
    private val SHA = Regex("[0-9a-f]{40}")

    fun marker(headSha: String): String {
        require(SHA.matches(headSha)) { "headSha must be 40 lowercase hex chars" }
        return "<!-- kivra-pr-review head=$headSha -->"
    }

    fun comment(result: PrReviewResult, headSha: String): String {
        val lines = buildList {
            add("## AI によるレビュー（参考情報）")
            add("")
            add("- 対象のコミット: `$headSha`")
            add("- 判定: ${result.verdict.name}")
            add("- この結果は参考情報です。承認やマージの判断には使われず、人のレビューの代わりにもなりません。")
            if (result.findings.isEmpty()) {
                add("")
                add("指摘はありません。")
            } else {
                add("")
                add("### 指摘")
                result.findings.forEachIndexed { i, f ->
                    val title = PrReviewText.clean(f.title, PrReviewText.MAX_TITLE)
                    val detail = PrReviewText.clean(f.detail, PrReviewText.MAX_DETAIL)
                    add("${i + 1}. [${f.severity.name}] $title: $detail")
                }
            }
        }
        val marker = marker(headSha)
        val body = lines.joinToString("\n").take(MAX_COMMENT_CHARS - marker.length - 2)
        return "$body\n\n$marker"
    }
}
