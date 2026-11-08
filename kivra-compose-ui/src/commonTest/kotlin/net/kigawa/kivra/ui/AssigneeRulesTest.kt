package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssigneeRulesTest {

    @Test
    fun displayNameIsOneToSixtyFourCharactersWithoutControlChars() {
        // 旧 UI の nameBad: 前後を除いて 1〜64 文字、制御文字なし。
        assertNull(validAssigneeDisplayName("   "))
        assertNull(validAssigneeDisplayName("a\u0007b"))
        assertNull(validAssigneeDisplayName("x".repeat(65)))
        assertEquals("bot", validAssigneeDisplayName("  bot  "))
        assertEquals("x".repeat(64), validAssigneeDisplayName("x".repeat(64)))
        // サロゲートペアは 1 文字として数える(旧 UI の Array.from と同じ)。
        assertEquals("😀".repeat(64), validAssigneeDisplayName("😀".repeat(64)))
        assertNull(validAssigneeDisplayName("😀".repeat(65)))
    }

    @Test
    fun ttlDaysIsAWholeNumberFromOneToThreeHundredSixtyFive() {
        // 旧 UI の ttlBad: 1〜365 の整数。小数・空・英字・負は不可。
        for (bad in listOf("0", "366", "", "abc", "1.5", "-1", "1000")) {
            assertNull(validAssigneeTtlDays(bad), bad)
        }
        assertEquals(1, validAssigneeTtlDays("1"))
        assertEquals(365, validAssigneeTtlDays(" 365 "))
        assertEquals(90, ASSIGNEE_TTL_DEFAULT_DAYS)
    }

    @Test
    fun subIsAsciiTokenOfOneToTwoHundredFiftyFiveCharacters() {
        assertEquals("user.name:1-x_y", validAssigneeSub(" user.name:1-x_y "))
        assertNull(validAssigneeSub("bad sub with spaces"))
        assertNull(validAssigneeSub(""))
        assertNull(validAssigneeSub("a".repeat(256)))
        assertNull(validAssigneeSub("日本語"))
    }

    @Test
    fun tokenIsIssuedOnlyForAnActiveAiOrViewer() {
        // 旧 UI と同じ: AI と VIEWER で有効な担当だけ。
        assertTrue(canIssueAssigneeToken("AI", "ACTIVE"))
        assertTrue(canIssueAssigneeToken("VIEWER", "ACTIVE"))
        assertFalse(canIssueAssigneeToken("ADMIN", "ACTIVE"))
        assertFalse(canIssueAssigneeToken("OPERATOR", "ACTIVE"))
        assertFalse(canIssueAssigneeToken("APPROVER", "ACTIVE"))
        assertFalse(canIssueAssigneeToken("AI_APPROVER", "ACTIVE"))
        assertFalse(canIssueAssigneeToken("AI", "DISABLED"))
    }

    @Test
    fun aiApproverCannotBindAHuman() {
        assertFalse(canBindAssigneeMember("AI_APPROVER"))
        assertTrue(canBindAssigneeMember("OPERATOR"))
    }

    @Test
    fun referencesShowOnlyTheFirstEightCharacters() {
        assertTrue(isValidAssigneeRef("0123456789abcdef"))
        assertFalse(isValidAssigneeRef("0123456789ABCDEF"))
        assertFalse(isValidAssigneeRef("short"))
        assertEquals("01234567", assigneeRefShort("0123456789abcdef"))
    }

    @Test
    fun failureTextUsesTheCodeFirstThenTheStatusWithoutServerText() {
        // 固定の code は、その文言(旧 UI の ASG_CODES)。
        assertEquals("最後の有効な管理者（ADMIN）は、無効化・ロール変更・メンバーの解除ができません。先に別の管理者を用意してください。", assigneeFailureText(409, "LAST_ADMIN"))
        assertTrue(assigneeFailureText(409, "TOO_MANY_ACTIVE").contains("上限（2 つ）"))
        // 知らない code は status で決める。409 は再読み込みの案内。
        assertEquals(ASSIGNEE_MSG_STALE, assigneeFailureText(409, "NEW_CODE"))
        assertEquals(ASSIGNEE_MSG_STALE, assigneeFailureText(409, null))
        assertEquals("操作が多すぎます。1 分ほど待ってから、もう一度お試しください。", assigneeFailureText(429, null))
        assertEquals("担当の管理は、今は無効です。", assigneeFailureText(503, null))
        assertEquals("入力を確認できませんでした。再読み込みしてください。", assigneeFailureText(400, null))
        assertEquals(ASSIGNEE_MSG_UNKNOWN, assigneeFailureText(500, null))
        assertEquals(ASSIGNEE_MSG_NETWORK, assigneeFailureText(null, null))
        assertEquals(ASSIGNEE_MSG_SESSION, assigneeFailureText(401, "FORBIDDEN"))
        // 通信とセッション以外で、サーバーの本文(ここでは扱わない)は文言に出ない。
        assertFalse(assigneeFailureText(409, "LEAK").contains("LEAK"))
    }

    @Test
    fun labelsFollowTheOldUi() {
        assertEquals(listOf("ADMIN", "OPERATOR", "APPROVER", "AI", "VIEWER", "AI_APPROVER"), ASSIGNEE_ROLES)
        assertEquals("有効", assigneeStateLabel("ACTIVE"))
        assertEquals("無効", assigneeStateLabel("DISABLED"))
        assertEquals("失効済み", assigneeTokenStateLabel("REVOKED"))
        assertEquals("期限切れ", assigneeTokenStateLabel("EXPIRED"))
        assertEquals("不明", assigneeStateLabel("SOMETHING"))
        assertEquals("不明", assigneeCountText(-1))
        assertEquals("3", assigneeCountText(3))
    }

    @Test
    fun gatedOperationsForTheAssigneePageAndTheMemoryDelete() {
        assertEquals(SelfDevPermission.ASSIGNEE_MANAGE, GatedOperation.MANAGE_ASSIGNEE.permission)
        assertEquals(SelfDevPermission.GOAL_WRITE, GatedOperation.DELETE_MEMORY.permission)
    }
}
