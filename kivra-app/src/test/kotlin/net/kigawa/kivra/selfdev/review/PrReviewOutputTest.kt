package net.kigawa.kivra.selfdev.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrReviewOutputTest {

    private fun finding(sev: String = "HIGH", title: String = "t", detail: String = "d") =
        """{"severity":"$sev","title":"$title","detail":"$detail"}"""

    @Test
    fun `a concerns answer with findings is parsed`() {
        val r = PrReviewOutputParser.parse("""{"verdict":"CONCERNS","findings":[${finding()}]}""")
        assertEquals(PrReviewVerdict.CONCERNS, r?.verdict)
        assertEquals(PrReviewSeverity.HIGH, r?.findings?.single()?.severity)
    }

    @Test
    fun `a pass answer with no finding is parsed`() {
        val r = PrReviewOutputParser.parse("""{"verdict":"PASS","findings":[]}""")
        assertEquals(PrReviewVerdict.PASS, r?.verdict)
        assertTrue(r!!.findings.isEmpty())
    }

    @Test
    fun `the outermost object is read when the answer has text or a code fence around it`() {
        val r = PrReviewOutputParser.parse("Here:\n```json\n{\"verdict\":\"PASS\",\"findings\":[]}\n```")
        assertEquals(PrReviewVerdict.PASS, r?.verdict)
    }

    @Test
    fun `a pass with findings, a concerns without findings, and an unknown value are refused`() {
        assertNull(PrReviewOutputParser.parse("""{"verdict":"PASS","findings":[${finding()}]}"""))
        assertNull(PrReviewOutputParser.parse("""{"verdict":"CONCERNS","findings":[]}"""))
        assertNull(PrReviewOutputParser.parse("""{"verdict":"APPROVE","findings":[]}"""))
        assertNull(PrReviewOutputParser.parse("""{"verdict":"PASS","findings":[${finding(sev = "CRITICAL")}]}"""))
    }

    @Test
    fun `a missing field, a non-object finding and text that is not JSON are refused`() {
        assertNull(PrReviewOutputParser.parse("""{"verdict":"PASS"}"""))
        assertNull(PrReviewOutputParser.parse("""{"verdict":"CONCERNS","findings":["x"]}"""))
        assertNull(PrReviewOutputParser.parse("""{"verdict":"CONCERNS","findings":[{"severity":"HIGH","title":"t"}]}"""))
        assertNull(PrReviewOutputParser.parse("not json at all"))
        assertNull(PrReviewOutputParser.parse("{broken"))
    }

    @Test
    fun `findings beyond the limit are cut to the limit`() {
        val many = (1..15).joinToString(",") { finding(title = "t$it") }
        val r = PrReviewOutputParser.parse("""{"verdict":"CONCERNS","findings":[$many]}""")
        assertEquals(PrReviewOutputParser.MAX_FINDINGS, r?.findings?.size)
    }

    @Test
    fun `plain text strips control and format characters and escapes markup and mentions`() {
        val s = PrReviewText.clean("a\u0007b‮c<d>&@e[f](g)`h`|i\nj", 500)
        assertEquals("ab" + "c&lt;d&gt;&amp;@​e\\[f\\](g)'h'\\|ij", s)
    }

    @Test
    fun `plain text is cut to the length limit with an ellipsis`() {
        val s = PrReviewText.clean("x".repeat(1000), 20)
        assertEquals(20, s.length)
        assertTrue(s.endsWith("..."))
    }

    @Test
    fun `the renderer names the head and writes one marker at the end`() {
        val head = "a".repeat(40)
        val body = PrReviewRenderer.comment(PrReviewResult(PrReviewVerdict.PASS, emptyList()), head)
        assertTrue(body.contains("指摘はありません。"))
        assertTrue(body.endsWith("<!-- kivra-pr-review head=$head -->"))
        assertFalse(body.contains("承認しま"))
    }

    @Test
    fun `the renderer refuses a head that is not a sha`() {
        assertFailsWith<IllegalArgumentException> { PrReviewRenderer.marker("not-a-sha") }
    }

    private fun env(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun `the review is off and reads nothing else unless the switch is exactly true`() {
        val off = PrReviewSettings.fromEnv(env(PrReviewSettings.ENV_ENABLED to "false", PrReviewSettings.ENV_MAX_PER_HOUR to "abc"))
        assertEquals(PrReviewSettings.DISABLED, off)
        assertFailsWith<IllegalStateException> { PrReviewSettings.fromEnv(env(PrReviewSettings.ENV_ENABLED to "yes")) }
        assertFalse(PrReviewSettings.fromEnv(emptyMap()).enabled)
    }

    @Test
    fun `an enabled review needs a model name of the allowed shape and bounded numbers`() {
        assertFailsWith<IllegalStateException> { PrReviewSettings.fromEnv(env(PrReviewSettings.ENV_ENABLED to "true")) }
        assertFailsWith<IllegalStateException> {
            PrReviewSettings.fromEnv(env(PrReviewSettings.ENV_ENABLED to "true", PrReviewSettings.ENV_MODEL to "bad model!"))
        }
        assertFailsWith<IllegalStateException> {
            PrReviewSettings.fromEnv(
                env(PrReviewSettings.ENV_ENABLED to "true", PrReviewSettings.ENV_MODEL to "m", PrReviewSettings.ENV_MAX_PER_HOUR to "101"),
            )
        }
        val s = PrReviewSettings.fromEnv(env(PrReviewSettings.ENV_ENABLED to "true", PrReviewSettings.ENV_MODEL to "claude-haiku-5-5"))
        assertTrue(s.enabled)
        assertEquals("claude-haiku-5-5", s.model)
        assertEquals(PrReviewSettings.DEFAULT_MAX_PER_HOUR, s.maxPerHour)
        assertEquals(PrReviewSettings.DEFAULT_MAX_PER_PR, s.maxPerPr)
        assertEquals(PrReviewSettings.DEFAULT_MAX_DIFF_BYTES, s.maxDiffBytes)
    }
}
