package net.kigawa.kivra.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidatorsTest {
    /** The bidi controls that must be refused: U+202A..202E, U+2066..2069, U+200E, U+200F, U+061C. */
    private val bidi = listOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
        '\u200E', '\u200F', '\u061C',
    )

    @Test
    fun `each bidi control is refused by validatedText and validatedBody, wherever it is`() {
        for (c in bidi) {
            for (text in listOf("$c" + "abc", "ab${c}cd", "abc$c", "  $c  ")) {
                assertNull(validatedText(text, 200), "validatedText ${text.codePoints().toArray().toList()}")
                assertNull(validatedBody(text), "validatedBody ${text.codePoints().toArray().toList()}")
            }
        }
    }

    @Test
    fun `a bidi control in a multi-line body is refused too`() {
        assertNull(validatedBody("line1\nline2\u202Eevil"))
        assertNull(validatedBody("\u2067line1\n"))
    }

    @Test
    fun `ZWJ emoji, ZWNJ, variation selectors, Japanese, plain emoji and line breaks still pass`() {
        assertEquals("👨\u200D👩\u200D👧", validatedText("👨\u200D👩\u200D👧", 200))
        assertEquals("a\u200Cb", validatedText("a\u200Cb", 200))
        assertEquals("☺\uFE0F", validatedText("☺\uFE0F", 200))
        assertEquals("日本語のタイトル 🚀", validatedText("日本語のタイトル 🚀", 200))
        assertEquals("👨\u200D👩", validatedBody("👨\u200D👩"))
        assertEquals("a\u200Cb\uFE0F 日本語", validatedBody("a\u200Cb\uFE0F 日本語"))
    }

    @Test
    fun `line feed and tab keep their existing handling - the body keeps them, the text refuses them`() {
        assertEquals("line1\nline2\tx", validatedBody("line1\nline2\tx"))
        assertNull(validatedText("line1\nline2", 200))
        assertNull(validatedText("a\tb", 200))
        assertNull(validatedBody("a\u0007b"))
    }
}
