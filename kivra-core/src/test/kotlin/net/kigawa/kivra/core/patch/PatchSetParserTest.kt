package net.kigawa.kivra.core.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PatchSetParserTest {
    private fun invalid(json: String): ParseReason {
        val r = PatchSetParser.parse(json)
        assertIs<ParseResult.Invalid>(r)
        return r.reason
    }

    @Test
    fun parsesValidPatchSet() {
        val json = """
            {"files":[
              {"op":"write","path":"docs/a.md","content":"hi\n"},
              {"op":"delete","path":"docs/b.md"}
            ],"summary":"s","rationale":"r"}
        """.trimIndent()
        val r = PatchSetParser.parse(json)
        assertIs<ParseResult.Parsed>(r)
        assertEquals(
            PatchSet(
                files = listOf(Write("docs/a.md", "hi\n"), Delete("docs/b.md")),
                summary = "s",
                rationale = "r",
            ),
            r.patch,
        )
    }

    @Test
    fun rationaleIsOptionalAndMayBeNull() {
        val absent = PatchSetParser.parse("""{"files":[{"op":"delete","path":"docs/a.md"}],"summary":"s"}""")
        val nulled = PatchSetParser.parse("""{"files":[{"op":"delete","path":"docs/a.md"}],"summary":"s","rationale":null}""")
        assertIs<ParseResult.Parsed>(absent)
        assertIs<ParseResult.Parsed>(nulled)
        assertEquals(null, absent.patch.rationale)
        assertEquals(null, nulled.patch.rationale)
    }

    @Test
    fun unicodeEscapesAndSurrogatePairsDecode() {
        val json = """{"files":[],"summary":"A😀\n\t\"\\\/"}"""
        val r = PatchSetParser.parse(json)
        assertIs<ParseResult.Parsed>(r)
        assertEquals("A😀\n\t\"\\/", r.patch.summary)
    }

    @Test
    fun unknownTopLevelFieldIsRejected() {
        assertEquals(
            ParseReason.UNKNOWN_FIELD,
            invalid("""{"files":[],"summary":"s","extra":1}"""),
        )
    }

    @Test
    fun unknownFieldInsideFileIsRejected() {
        assertEquals(
            ParseReason.UNKNOWN_FIELD,
            invalid("""{"files":[{"op":"delete","path":"docs/a.md","force":true}],"summary":"s"}"""),
        )
    }

    @Test
    fun contentOnDeleteIsRejectedAsUnknownField() {
        assertEquals(
            ParseReason.UNKNOWN_FIELD,
            invalid("""{"files":[{"op":"delete","path":"docs/a.md","content":"x"}],"summary":"s"}"""),
        )
    }

    @Test
    fun wrongTypesAreRejected() {
        assertEquals(ParseReason.WRONG_TYPE, invalid("""{"files":"x","summary":"s"}"""))
        assertEquals(ParseReason.WRONG_TYPE, invalid("""{"files":[],"summary":1}"""))
        assertEquals(ParseReason.WRONG_TYPE, invalid("""{"files":[],"summary":"s","rationale":2}"""))
        assertEquals(ParseReason.WRONG_TYPE, invalid("""{"files":[{"op":"write","path":"a","content":1}],"summary":"s"}"""))
        assertEquals(ParseReason.WRONG_TYPE, invalid("""{"files":["x"],"summary":"s"}"""))
        assertEquals(ParseReason.WRONG_TYPE, invalid("""[]"""))
        assertEquals(ParseReason.WRONG_TYPE, invalid("""{"files":[],"summary":1.5e3}"""))
    }

    @Test
    fun missingFieldsAreRejected() {
        assertEquals(ParseReason.MISSING_FIELD, invalid("""{"files":[]}"""))
        assertEquals(ParseReason.MISSING_FIELD, invalid("""{"summary":"s"}"""))
        assertEquals(ParseReason.MISSING_FIELD, invalid("""{"files":[{"op":"write","path":"a"}],"summary":"s"}"""))
        assertEquals(ParseReason.MISSING_FIELD, invalid("""{"files":[{"path":"a"}],"summary":"s"}"""))
    }

    @Test
    fun unknownOpIsRejected() {
        assertEquals(ParseReason.INVALID_OP, invalid("""{"files":[{"op":"rename","path":"a"}],"summary":"s"}"""))
        assertEquals(ParseReason.INVALID_OP, invalid("""{"files":[{"op":"WRITE","path":"a","content":""}],"summary":"s"}"""))
    }

    @Test
    fun duplicateKeysAreRejected() {
        assertEquals(ParseReason.DUPLICATE_KEY, invalid("""{"files":[],"files":[],"summary":"s"}"""))
        assertEquals(
            ParseReason.DUPLICATE_KEY,
            invalid("""{"files":[{"op":"delete","path":"a","path":"b"}],"summary":"s"}"""),
        )
    }

    @Test
    fun malformedJsonIsRejected() {
        val cases = listOf(
            "",
            "{",
            "[1,]",
            "{} x",
            """{"files":[],"summary":"s",}""",
            """{"files":[],"summary":"unterminated}""",
            """{"files":[],"summary":"raw """ + "\u0001" + """ control"}""",
            """{"files":[],"summary":"bad \q escape"}""",
            """{"files":[],"summary":"\u12G4"}""",
            """{"files":[],"summary":01}""",
            """{"files":[],"summary":tru}""",
        )
        for (json in cases) {
            assertEquals(ParseReason.MALFORMED_JSON, invalid(json), json.take(10))
        }
    }

    @Test
    fun excessiveNestingIsRejected() {
        assertEquals(ParseReason.TOO_DEEP, invalid("[".repeat(40) + "]".repeat(40)))
        // at the limit, the structure still parses (and then fails on shape, not depth)
        val atLimit = "[".repeat(PatchSetParser.MAX_DEPTH) + "]".repeat(PatchSetParser.MAX_DEPTH)
        assertEquals(ParseReason.WRONG_TYPE, invalid(atLimit))
    }

    @Test
    fun oversizeInputIsRejectedBeforeParsing() {
        val big = " ".repeat(PatchSetParser.MAX_INPUT_BYTES + 1)
        assertEquals(ParseReason.INPUT_TOO_LARGE, invalid(big))
    }

    @Test
    fun inputAtTheByteLimitIsNotTooLarge() {
        // Whitespace only: not a document, but the size check must pass.
        val atLimit = " ".repeat(PatchSetParser.MAX_INPUT_BYTES)
        assertEquals(ParseReason.MALFORMED_JSON, invalid(atLimit))
    }

    @Test
    fun multibyteInputIsCountedInBytes() {
        // 3 bytes per character: fewer characters than the limit, but over it in UTF-8.
        val chars = PatchSetParser.MAX_INPUT_BYTES / 3 + 1
        assertEquals(ParseReason.INPUT_TOO_LARGE, invalid("あ".repeat(chars)))
    }

    @Test
    fun loneSurrogateEscapeIsRejected() {
        val json = """{"files":[],"summary":"\uD800"}"""
        assertEquals(ParseReason.INVALID_TEXT, invalid(json))
        assertEquals(ParseReason.INVALID_TEXT, invalid("""{"files":[],"summary":"\uDC00"}"""))
    }

    @Test
    fun failureReasonNeverContainsInputText() {
        val secretish = "SECRET_VALUE_XYZ_123"
        val inputs = listOf(
            """{"files":[],"summary":"$secretish",}""",
            """{"files":[{"op":"$secretish","path":"a"}],"summary":"s"}""",
            """{"files":[],"summary":"s","$secretish":1}""",
        )
        for (json in inputs) {
            val r = PatchSetParser.parse(json)
            assertIs<ParseResult.Invalid>(r)
            assertFalse(r.toString().contains(secretish), r.toString())
            assertFalse(r.reason.name.contains(secretish))
        }
    }

    @Test
    fun parsedPatchIsStillCheckedByPolicy() {
        val r = PatchSetParser.parse("""{"files":[{"op":"write","path":"../x","content":"a"}],"summary":"s"}""")
        assertIs<ParseResult.Parsed>(r)
        val v = PatchPolicy().validate(r.patch)
        assertTrue(v is PatchVerdict.Rejected)
    }

    private val validJson = """{"files":[{"op":"delete","path":"docs/a.md"}],"summary":"s"}"""

    @Test
    fun answerWrappedInOneCodeFenceIsAccepted() {
        for (wrapped in listOf("```json\n$validJson\n```", "```\n$validJson\n```", "  ```json\r\n$validJson\n```\n")) {
            val r = PatchSetParser.parse(wrapped)
            assertIs<ParseResult.Parsed>(r, wrapped.take(12))
            assertEquals(listOf(Delete("docs/a.md")), r.patch.files)
        }
    }

    @Test
    fun textOutsideTheFenceStillRejected() {
        assertEquals(ParseReason.MALFORMED_JSON, invalid("Here it is:\n```json\n$validJson\n```"))
        assertEquals(ParseReason.MALFORMED_JSON, invalid("```json\n$validJson\n```\nDone."))
        // An unclosed fence is not stripped.
        assertEquals(ParseReason.MALFORMED_JSON, invalid("```json\n$validJson"))
    }

    @Test
    fun rawLineBreakAndTabInsideStringDecodeAsTheirEscapes() {
        val raw = "{\"files\":[{\"op\":\"write\",\"path\":\"a.kt\",\"content\":\"fun a() {\n\tTODO()\r\n}\"}],\"summary\":\"s\"}"
        val escaped = raw.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
        val r = PatchSetParser.parse(raw)
        assertIs<ParseResult.Parsed>(r)
        assertEquals(PatchSetParser.parse(escaped), r)
        assertEquals("fun a() {\n\tTODO()\r\n}", (r.patch.files.single() as Write).content)
    }

    @Test
    fun otherRawControlCharactersInsideStringStillRejected() {
        assertEquals(ParseReason.MALFORMED_JSON, invalid("{\"files\":[],\"summary\":\"a\u0008b\"}"))
    }

    @Test
    fun invalidResultReportsShapeWithoutContent() {
        val truncated = "{\"files\":[],\"summary\":\"SECRET-QQQ"
        val r = PatchSetParser.parse(truncated)
        assertIs<ParseResult.Invalid>(r)
        assertEquals(OutputShape(charCount = truncated.length, startsWithObject = true, endsWithObject = false, fenced = false), r.shape)
        assertFalse(r.toString().contains("SECRET"))

        val fenced = PatchSetParser.parse("```json\n[1]\n```")
        assertIs<ParseResult.Invalid>(fenced)
        assertEquals(ParseReason.WRONG_TYPE, fenced.reason)
        assertTrue(fenced.shape.fenced)
        assertFalse(fenced.shape.startsWithObject)

        val empty = PatchSetParser.parse("")
        assertIs<ParseResult.Invalid>(empty)
        assertEquals(OutputShape(0, startsWithObject = false, endsWithObject = false, fenced = false), empty.shape)
    }
}
