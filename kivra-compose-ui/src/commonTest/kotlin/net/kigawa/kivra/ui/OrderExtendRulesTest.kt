package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderExtendRulesTest {

    @Test
    fun extendIsOnlyForARootThatIsActiveOrPaused() {
        assertTrue(canExtendOrder("ACTIVE", parentOrderId = null))
        assertTrue(canExtendOrder("PAUSED", parentOrderId = null))
        // 子の order は NOT_ROOT(409)になるので出さない。
        assertFalse(canExtendOrder("ACTIVE", parentOrderId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertFalse(canExtendOrder("PAUSED", parentOrderId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        // 停止の要求中と停止済みは 409(STOP_ALREADY_PENDING / TERMINAL)になるので出さない。
        assertFalse(canExtendOrder("STOP_REQUESTED", parentOrderId = null))
        assertFalse(canExtendOrder("STOPPED", parentOrderId = null))
    }

    @Test
    fun cyclesAndDaysAreWholeNumbersInTheirRanges() {
        assertTrue(isValidAddCycles(""))
        assertTrue(isValidAddCycles("1"))
        assertTrue(isValidAddCycles(" 12 "))
        assertFalse(isValidAddCycles("0"))
        assertFalse(isValidAddCycles("-1"))
        assertFalse(isValidAddCycles("1.5"))
        assertFalse(isValidAddCycles("abc"))
        assertFalse(isValidAddCycles("1234567890"))

        assertTrue(isValidExtendDays(""))
        assertTrue(isValidExtendDays("1"))
        assertTrue(isValidExtendDays("14"))
        assertTrue(isValidExtendDays("15"))
        assertTrue(isValidExtendDays("3650"))
        assertFalse(isValidExtendDays("0"))
        assertFalse(isValidExtendDays("3651"))
        assertFalse(isValidExtendDays("3.0"))
    }

    @Test
    fun bothEmptyIsNotAFormAndOneOfThemIsEnough() {
        assertFalse(isExtendFormOk("", ""))
        assertFalse(isExtendFormOk("  ", " "))
        assertTrue(isExtendFormOk("2", ""))
        assertTrue(isExtendFormOk("", "14"))
        assertTrue(isExtendFormOk("1", "14"))
        // 入れた方が形式に合わなければ送れない。
        assertFalse(isExtendFormOk("0", ""))
        assertFalse(isExtendFormOk("1", "3651"))
    }

    @Test
    fun emptyInputsAreNotSentAndValidOnesAreTrimmed() {
        assertNull(addCyclesOrNull(""))
        assertNull(extendDaysOrNull("  "))
        assertEquals(3, addCyclesOrNull(" 3 "))
        assertEquals(7, extendDaysOrNull("7"))
        assertNull(addCyclesOrNull("x"))
    }

    @Test
    fun maxCyclesCodeHasItsOwnText() {
        assertTrue(extendFailureText(WriteFailure.BAD_REQUEST, "MAX_CYCLES_INVALID").contains("周の上限"))
        // The expiry has no ceiling any more (ADR 0015 addendum 2026-11-11): its old code is not a text of its own.
        assertFalse(extendFailureText(WriteFailure.BAD_REQUEST, "EXPIRES_AT_INVALID").contains("30 日"))
        // 400 で code が無い(入力の形の誤り)は入力の確認。
        assertTrue(extendFailureText(WriteFailure.BAD_REQUEST, null).contains("入力を確認"))
    }

    @Test
    fun conflictCodesHaveTheirOwnTextsAndAMissingCodeIsAStaleVersion() {
        assertTrue(extendFailureText(WriteFailure.STALE, "NOT_ROOT").contains("子の order"))
        assertTrue(extendFailureText(WriteFailure.STALE, "STOP_ALREADY_PENDING").contains("停止が要求"))
        assertTrue(extendFailureText(WriteFailure.STALE, "TERMINAL").contains("停止済み"))
        assertTrue(extendFailureText(WriteFailure.STALE, "EXTENSION_DOES_NOT_LIFT").contains("両方が解消されません"))
        // code の無い 409 は version の競合(STALE)の共通文言。
        assertEquals(writeFailureText(WriteFailure.STALE), extendFailureText(WriteFailure.STALE, null))
        assertEquals(writeFailureText(WriteFailure.STALE), extendFailureText(WriteFailure.STALE, "SOMETHING_NEW"))
    }

    @Test
    fun otherFailuresUseTheCommonTexts() {
        assertEquals(writeFailureText(WriteFailure.FORBIDDEN), extendFailureText(WriteFailure.FORBIDDEN, null))
        assertEquals(writeFailureText(WriteFailure.NOT_FOUND), extendFailureText(WriteFailure.NOT_FOUND, null))
        assertEquals(writeFailureText(WriteFailure.DISABLED), extendFailureText(WriteFailure.DISABLED, null))
        assertEquals(writeFailureText(WriteFailure.NETWORK), extendFailureText(WriteFailure.NETWORK, null))
    }

    @Test
    fun theOperatorActionRoutesExtendThroughTheSameTexts() {
        // 詳細画面のゲートは operatorWriteFailureText を通すので、延長の文言も同じ入口から出る。
        assertEquals(
            extendFailureText(WriteFailure.BAD_REQUEST, "MAX_CYCLES_INVALID"),
            operatorWriteFailureText(OperatorAction.EXTEND_ORDER, WriteFailure.BAD_REQUEST, "MAX_CYCLES_INVALID"),
        )
        assertEquals(
            extendFailureText(WriteFailure.STALE, "NOT_ROOT"),
            operatorWriteFailureText(OperatorAction.EXTEND_ORDER, WriteFailure.STALE, "NOT_ROOT"),
        )
    }
}
