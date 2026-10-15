package net.kigawa.kivra.core.patch

import java.nio.charset.StandardCharsets

/*
 * Strict parser for the PatchSet JSON an LLM returns (Issue #56). It is deliberately small and dependency-free:
 * kivra-core has no JSON library, and the grammar here is far narrower than JSON.
 *
 * Expected shape:
 *   {"files":[{"op":"write","path":"...","content":"..."},{"op":"delete","path":"..."}],
 *    "summary":"...","rationale":"..." (optional, may be null)}
 *
 * Unknown fields, wrong types, duplicate keys, excess nesting and oversize input are rejected. Semantic checks
 * (paths, sizes, secrets) belong to [PatchPolicy], not here. The parser only guarantees the structure.
 * Failure reasons never include the input text.
 */

sealed interface ParseResult {
    data class Parsed(val patch: PatchSet) : ParseResult

    data class Invalid(val reason: ParseReason) : ParseResult
}

enum class ParseReason {
    INPUT_TOO_LARGE,
    MALFORMED_JSON,
    TOO_DEEP,
    DUPLICATE_KEY,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    INVALID_OP,
    INVALID_TEXT,
}

object PatchSetParser {
    /** Input limit in UTF-8 bytes. */
    const val MAX_INPUT_BYTES: Int = 2 * 1024 * 1024

    /** Maximum nesting of objects and arrays. The PatchSet shape needs 3. */
    const val MAX_DEPTH: Int = 16

    fun parse(json: String): ParseResult {
        // UTF-8 never takes fewer bytes than UTF-16 units, so this check is safe before encoding.
        if (json.length > MAX_INPUT_BYTES) return ParseResult.Invalid(ParseReason.INPUT_TOO_LARGE)
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_INPUT_BYTES) {
            return ParseResult.Invalid(ParseReason.INPUT_TOO_LARGE)
        }
        return try {
            ParseResult.Parsed(toPatchSet(JsonReader(json).readDocument()))
        } catch (e: ParseFailure) {
            ParseResult.Invalid(e.reason)
        }
    }
}

private class ParseFailure(val reason: ParseReason) : RuntimeException(null, null, false, false)

private fun fail(reason: ParseReason): Nothing = throw ParseFailure(reason)

private sealed interface JValue

private class JObj(val members: Map<String, JValue>) : JValue

private class JArr(val items: List<JValue>) : JValue

private class JStr(val value: String) : JValue

private class JBool(val value: Boolean) : JValue

private object JNum : JValue

private object JNull : JValue

/** Recursive-descent reader for a single JSON document. Depth is bounded, so recursion is bounded. */
private class JsonReader(private val s: String) {
    private var pos = 0

    fun readDocument(): JValue {
        skipWs()
        val value = readValue(0)
        skipWs()
        if (pos != s.length) fail(ParseReason.MALFORMED_JSON)
        return value
    }

    private fun readValue(depth: Int): JValue {
        return when (current()) {
            '{' -> readObject(depth + 1)
            '[' -> readArray(depth + 1)
            '"' -> JStr(readString())
            't' -> literal("true", JBool(true))
            'f' -> literal("false", JBool(false))
            'n' -> literal("null", JNull)
            '-', in '0'..'9' -> readNumber()
            else -> fail(ParseReason.MALFORMED_JSON)
        }
    }

    private fun readObject(depth: Int): JValue {
        if (depth > PatchSetParser.MAX_DEPTH) fail(ParseReason.TOO_DEEP)
        pos++
        skipWs()
        val members = LinkedHashMap<String, JValue>()
        if (current() == '}') {
            pos++
            return JObj(members)
        }
        while (true) {
            skipWs()
            if (current() != '"') fail(ParseReason.MALFORMED_JSON)
            val key = readString()
            if (members.containsKey(key)) fail(ParseReason.DUPLICATE_KEY)
            skipWs()
            expect(':')
            skipWs()
            members[key] = readValue(depth)
            skipWs()
            when (current()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JObj(members)
                }

                else -> fail(ParseReason.MALFORMED_JSON)
            }
        }
    }

    private fun readArray(depth: Int): JValue {
        if (depth > PatchSetParser.MAX_DEPTH) fail(ParseReason.TOO_DEEP)
        pos++
        skipWs()
        val items = ArrayList<JValue>()
        if (current() == ']') {
            pos++
            return JArr(items)
        }
        while (true) {
            skipWs()
            items.add(readValue(depth))
            skipWs()
            when (current()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JArr(items)
                }

                else -> fail(ParseReason.MALFORMED_JSON)
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = current()
            pos++
            when {
                c == '"' -> break
                c == '\\' -> sb.append(readEscape())
                c < ' ' -> fail(ParseReason.MALFORMED_JSON)
                else -> sb.append(c)
            }
        }
        val text = sb.toString()
        if (!isWellFormedUtf16(text)) fail(ParseReason.INVALID_TEXT)
        return text
    }

    private fun readEscape(): Char {
        return when (current()) {
            '"' -> '"'.also { pos++ }
            '\\' -> '\\'.also { pos++ }
            '/' -> '/'.also { pos++ }
            'b' -> '\b'.also { pos++ }
            'f' -> '\u000C'.also { pos++ }
            'n' -> '\n'.also { pos++ }
            'r' -> '\r'.also { pos++ }
            't' -> '\t'.also { pos++ }
            'u' -> {
                pos++
                readHex4()
            }

            else -> fail(ParseReason.MALFORMED_JSON)
        }
    }

    private fun readHex4(): Char {
        if (pos + 4 > s.length) fail(ParseReason.MALFORMED_JSON)
        var code = 0
        repeat(4) {
            val digit = Character.digit(s[pos], 16)
            if (digit < 0) fail(ParseReason.MALFORMED_JSON)
            code = code * 16 + digit
            pos++
        }
        return code.toChar()
    }

    private fun readNumber(): JValue {
        if (current() == '-') pos++
        if (current() == '0') {
            pos++
        } else {
            requireDigit()
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        if (pos < s.length && s[pos] == '.') {
            pos++
            requireDigit()
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            requireDigit()
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        return JNum
    }

    private fun requireDigit() {
        if (current() !in '0'..'9') fail(ParseReason.MALFORMED_JSON)
        pos++
    }

    private fun literal(word: String, value: JValue): JValue {
        if (!s.startsWith(word, pos)) fail(ParseReason.MALFORMED_JSON)
        pos += word.length
        return value
    }

    private fun expect(c: Char) {
        if (current() != c) fail(ParseReason.MALFORMED_JSON)
        pos++
    }

    /** The character at the cursor. End of input is malformed. */
    private fun current(): Char {
        if (pos >= s.length) fail(ParseReason.MALFORMED_JSON)
        return s[pos]
    }

    private fun skipWs() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
    }
}

private fun toPatchSet(root: JValue): PatchSet {
    val obj = root as? JObj ?: fail(ParseReason.WRONG_TYPE)
    checkKeys(obj, required = setOf("files", "summary"), optional = setOf("rationale"))
    val files = (obj.members.getValue("files") as? JArr ?: fail(ParseReason.WRONG_TYPE))
        .items
        .map { toFileChange(it) }
    val summary = stringField(obj, "summary")
    val rationale = when (val r = obj.members["rationale"]) {
        null, JNull -> null
        is JStr -> r.value
        else -> fail(ParseReason.WRONG_TYPE)
    }
    return PatchSet(files = files, summary = summary, rationale = rationale)
}

private fun toFileChange(value: JValue): FileChange {
    val obj = value as? JObj ?: fail(ParseReason.WRONG_TYPE)
    return when (stringField(obj, "op")) {
        "write" -> {
            checkKeys(obj, required = setOf("op", "path", "content"), optional = emptySet())
            Write(path = stringField(obj, "path"), content = stringField(obj, "content"))
        }

        "delete" -> {
            checkKeys(obj, required = setOf("op", "path"), optional = emptySet())
            Delete(path = stringField(obj, "path"))
        }

        else -> fail(ParseReason.INVALID_OP)
    }
}

private fun checkKeys(obj: JObj, required: Set<String>, optional: Set<String>) {
    if (obj.members.keys.any { it !in required && it !in optional }) fail(ParseReason.UNKNOWN_FIELD)
    if (required.any { it !in obj.members }) fail(ParseReason.MISSING_FIELD)
}

private fun stringField(obj: JObj, key: String): String {
    val value = obj.members[key] ?: fail(ParseReason.MISSING_FIELD)
    return (value as? JStr ?: fail(ParseReason.WRONG_TYPE)).value
}
