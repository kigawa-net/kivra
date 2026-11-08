package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalRulesTest {

    @Test
    fun reasonIsTrimmedAndKept() {
        assertEquals(ReasonCheck.Valid("理由"), checkReason("  理由  \n"))
    }

    @Test
    fun blankReasonIsEmpty() {
        assertEquals(ReasonCheck.Empty, checkReason(""))
        assertEquals(ReasonCheck.Empty, checkReason(" \t\n "))
    }

    @Test
    fun reasonAtTheLimitIsValidAndOneOverIsTooLong() {
        val atLimit = "a".repeat(REASON_MAX_CODE_POINTS)
        assertEquals(ReasonCheck.Valid(atLimit), checkReason(atLimit))
        assertEquals(ReasonCheck.TooLong, checkReason("a".repeat(REASON_MAX_CODE_POINTS + 1)))
    }

    @Test
    fun limitCountsCodePointsNotUtf16Units() {
        // An emoji is one code point but two UTF-16 units: 500 of them is still within the limit.
        val emoji = "😀"
        assertEquals(ReasonCheck.Valid(emoji.repeat(REASON_MAX_CODE_POINTS)), checkReason(emoji.repeat(REASON_MAX_CODE_POINTS)))
        assertEquals(ReasonCheck.TooLong, checkReason(emoji.repeat(REASON_MAX_CODE_POINTS + 1)))
        assertEquals(REASON_MAX_CODE_POINTS, reasonLength(emoji.repeat(REASON_MAX_CODE_POINTS)))
    }

    @Test
    fun controlCharactersAreRefused() {
        assertIs<ReasonCheck.ControlChars>(checkReason("a\u0000b"))
        assertIs<ReasonCheck.ControlChars>(checkReason("a\tb"))
        assertIs<ReasonCheck.ControlChars>(checkReason("a\nb"))
        assertIs<ReasonCheck.ControlChars>(checkReason("a\u007fb"))
        assertIs<ReasonCheck.ControlChars>(checkReason("a\u009fb"))
    }

    @Test
    fun customLimitIsUsed() {
        assertEquals(ReasonCheck.TooLong, checkReason("abc", maxCodePoints = 2))
        assertEquals(ReasonCheck.Valid("ab"), checkReason("ab", maxCodePoints = 2))
        assertEquals("理由は 2 文字以内で入力してください。", reasonErrorText(ReasonCheck.TooLong, maxCodePoints = 2))
    }

    @Test
    fun errorTextOnlyForInvalidReasons() {
        assertNull(reasonErrorText(ReasonCheck.Valid("x")))
        assertNull(reasonErrorText(ReasonCheck.Empty))
        assertTrue(reasonErrorText(ReasonCheck.ControlChars) != null)
    }

    @Test
    fun digestMustBe64LowerHex() {
        assertTrue(isValidStopDigest("0123456789abcdef".repeat(4)))
        assertFalse(isValidStopDigest(null))
        assertFalse(isValidStopDigest(""))
        assertFalse(isValidStopDigest("0123456789ABCDEF".repeat(4)))
        assertFalse(isValidStopDigest("0123456789abcdef".repeat(4) + "0"))
        assertFalse(isValidStopDigest("g".repeat(64)))
    }

    @Test
    fun statusMapsToFailureKind() {
        assertEquals(WriteFailure.NETWORK, writeFailureOf(null))
        assertEquals(WriteFailure.SESSION, writeFailureOf(401))
        assertEquals(WriteFailure.FORBIDDEN, writeFailureOf(403))
        assertEquals(WriteFailure.NOT_FOUND, writeFailureOf(404))
        assertEquals(WriteFailure.BAD_REQUEST, writeFailureOf(400))
        assertEquals(WriteFailure.STALE, writeFailureOf(409))
        assertEquals(WriteFailure.DISABLED, writeFailureOf(503))
        assertEquals(WriteFailure.UNKNOWN, writeFailureOf(500))
    }

    @Test
    fun everyFailureHasAJapaneseText() {
        WriteFailure.entries.forEach { failure ->
            assertTrue(writeFailureText(failure).isNotEmpty(), failure.name)
        }
        assertEquals(
            "状態が変わっていました。一覧と詳細を読み直してから、もう一度操作してください。",
            writeFailureText(writeFailureOf(409)),
        )
    }
}
