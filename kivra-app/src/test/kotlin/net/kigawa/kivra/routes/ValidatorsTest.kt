package net.kigawa.kivra.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidatorsTest {
    /** The bidi controls that must be refused: U+202A..202E, U+2066..2069, U+200E, U+200F, U+061C. */
    private val bidi = listOf(
        '‪', '‫', '‬', '‭', '‮',
        '⁦', '⁧', '⁨', '⁩',
        '‎', '‏', '؜',
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
        assertNull(validatedBody("line1\nline2‮evil"))
        assertNull(validatedBody("⁧line1\n"))
    }

    @Test
    fun `ZWJ emoji, ZWNJ, variation selectors, Japanese, plain emoji and line breaks still pass`() {
        assertEquals("👨‍👩‍👧", validatedText("👨‍👩‍👧", 200))
        assertEquals("a‌b", validatedText("a‌b", 200))
        assertEquals("☺️", validatedText("☺️", 200))
        assertEquals("日本語のタイトル 🚀", validatedText("日本語のタイトル 🚀", 200))
        assertEquals("👨‍👩", validatedBody("👨‍👩"))
        assertEquals("a‌b️ 日本語", validatedBody("a‌b️ 日本語"))
    }

    @Test
    fun `line feed and tab keep their existing handling - the body keeps them, the text refuses them`() {
        assertEquals("line1\nline2\tx", validatedBody("line1\nline2\tx"))
        assertNull(validatedText("line1\nline2", 200))
        assertNull(validatedText("a\tb", 200))
        assertNull(validatedBody("a\u0007b"))
    }
}
