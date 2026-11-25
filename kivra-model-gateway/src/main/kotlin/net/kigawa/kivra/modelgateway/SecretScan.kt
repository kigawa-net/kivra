package net.kigawa.kivra.modelgateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.log2

/** Conservative defense in depth. Results contain fixed reasons only, never matched text or offsets. */
class SecretScan(private val credentials: () -> Collection<String> = { emptyList() }) {
    /**
     * True when [text] is refused on its own: the same rule that [detects] applies to each text part. The Planner's file list uses it so
     * that a path the gateway would refuse is never sent (a path is a text, not a request part).
     */
    fun detectsText(text: String): Boolean = unsafeText(text, knownCredentials())

    fun detects(req: ModelRequest): Boolean {
        val known = knownCredentials()
        fun unsafe(s: String): Boolean = unsafeText(s, known)
        fun jsonUnsafe(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.any { unsafe(it.key) || jsonUnsafe(it.value) }
            is JsonArray -> value.any { jsonUnsafe(it) }
            is JsonPrimitive -> unsafe(value.content)
        }
        return req.messages.any { m -> m.parts.any { p -> when (p) {
            is Part.Text -> unsafe(p.text)
            // Scan exact credentials / known formats, but do not treat image payload entropy as text entropy.
            is Part.Image -> known.any { p.base64Data.contains(it) } || patterns.any { it.containsMatchIn(p.base64Data) } || unsafe(p.mediaType)
            is Part.ToolCall -> unsafe(p.id) || unsafe(p.name) || jsonUnsafe(p.arguments)
            is Part.ToolResult -> unsafe(p.callId) || unsafe(p.content)
        } } } || req.tools.any { unsafe(it.name) || unsafe(it.description) || jsonUnsafe(it.inputSchema) } ||
            req.generation.stopSequences.any(::unsafe) || (req.responseFormat as? ResponseFormat.JsonSchema)?.let {
                unsafe(it.name) || jsonUnsafe(it.schema)
            } == true
    }

    private fun knownCredentials(): List<String> = credentials().filter { it.isNotEmpty() }

    private fun unsafeText(s: String, known: List<String>): Boolean =
        known.any { s.contains(it) } || patterns.any { it.containsMatchIn(s) } || candidates.findAll(s).any { match ->
            val value = match.value
            value.any { it.isLetter() } && value.any { it.isDigit() } &&
                value.groupingBy { it }.eachCount().values.sumOf { count ->
                    val p = count.toDouble() / value.length
                    -p * log2(p)
                } >= 4.0
        }

    companion object {
        private val candidates = Regex("[A-Za-z0-9_+/=-]{32,}")
        private val patterns = listOf(
            Regex("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
            Regex("(?:sk-|gh[pousr]_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]{16,}"),
            Regex("(?:AKIA|ASIA)[A-Z0-9]{16}"),
            Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
            Regex("(?i)\\b(?:api[_-]?key|secret|password|access[_-]?token)\\s*[=:]\\s*[\"']?[^\\s\"',;]{8,}"),
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{16,}=*"),
        )
    }
}
