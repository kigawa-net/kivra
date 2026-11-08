package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiMe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmitRulesTest {

    // ---- 入力の検査(app.js の MAX_TITLE / MAX_BODY / CONTROL_CHARS / BODY_CONTROL_CHARS) ----

    @Test
    fun countsCodePointsNotUtf16Units() {
        assertEquals(3, codePointCount("a😀b"))
        assertEquals(2, codePointCount("日本"))
        assertEquals(0, codePointCount(""))
    }

    @Test
    fun titleIsTrimmedAndLimitedToTwoHundredCodePoints() {
        assertEquals("CI を速くする", validatedTaskTitle("  CI を速くする  "))
        assertNull(validatedTaskTitle("   "))
        assertEquals("a".repeat(200), validatedTaskTitle("a".repeat(200)))
        assertNull(validatedTaskTitle("a".repeat(201)))
        // 😀 は 1 コードポイント。200 個は通り、201 個は通らない。
        assertEquals("😀".repeat(200), validatedTaskTitle("😀".repeat(200)))
        assertNull(validatedTaskTitle("😀".repeat(201)))
    }

    @Test
    fun titleRefusesControlAndBidiCharacters() {
        assertNull(validatedTaskTitle("a\u0007b"))
        assertNull(validatedTaskTitle("a\nb"))
        assertNull(validatedTaskTitle("a‮b"))
        assertNull(validatedTaskTitle("a\u0085b"))
    }

    @Test
    fun bodyIsOptionalLimitedToEightThousandAndAllowsLineBreaksAndTabs() {
        assertEquals("", validatedBody("   "))
        assertEquals("a\n\tb", validatedBody("a\n\tb"))
        assertEquals("x".repeat(8000), validatedBody("x".repeat(8000)))
        assertNull(validatedBody("x".repeat(8001)))
        assertNull(validatedBody("a\rb"), "a carriage return is a control character in the body")
        assertNull(validatedBody("a\u0007b"))
        assertNull(validatedBody("a⁦b"))
    }

    // ---- 投入キー(app.js の keyForSubmission / newSubmitKey) ----

    private fun counter(): () -> String {
        var n = 0
        return { "key-${n++}" }
    }

    @Test
    fun aRetryWithTheSameContentKeepsTheKey() {
        val keys = IdempotencyKeys(counter())
        val sig = TaskSignature(SubmitKind.SIMULATED, "t", "", null)
        val first = keys.keyFor(sig)
        assertEquals(first, keys.keyFor(sig), "a retry after a failure is the same submission")
    }

    @Test
    fun aChangedTitleOrKindGetsANewKey() {
        val keys = IdempotencyKeys(counter())
        val first = keys.keyFor(TaskSignature(SubmitKind.SIMULATED, "first", "", null))
        val second = keys.keyFor(TaskSignature(SubmitKind.SIMULATED, "second", "", null))
        assertTrue(first != second)
        val third = keys.keyFor(TaskSignature(SubmitKind.SELF_DEV, "second", "", "P"))
        assertTrue(second != third, "a kind change gives a new key")
    }

    @Test
    fun aChangedProjectOrBodyGetsANewKey() {
        val keys = IdempotencyKeys(counter())
        val first = keys.keyFor(TaskSignature(SubmitKind.SELF_DEV, "t", "", "P1"))
        val other = keys.keyFor(TaskSignature(SubmitKind.SELF_DEV, "t", "", "P2"))
        assertTrue(first != other)
        val withBody = keys.keyFor(TaskSignature(SubmitKind.SELF_DEV, "t", "b", "P2"))
        assertTrue(other != withBody)
    }

    @Test
    fun theFirstKeyIsNotRegenerated() {
        var generated = 0
        val keys = IdempotencyKeys { "k${++generated}" }
        assertEquals("k1", keys.keyFor(TaskSignature(SubmitKind.SIMULATED, "t", "", null)))
        assertEquals(1, generated)
    }

    @Test
    fun rotateGivesANewKeyForTheNextSubmission() {
        val keys = IdempotencyKeys(counter())
        val sig = TaskSignature(SubmitKind.SIMULATED, "t", "", null)
        val before = keys.keyFor(sig)
        keys.rotate()
        val after = keys.keyFor(sig)
        assertTrue(before != after, "after a success (or a 409) the next send uses a new key")
    }

    @Test
    fun newKeysHaveTheExpectedShape() {
        val key = newIdempotencyKey()
        assertTrue(key.startsWith("compose-"))
        assertTrue(key.length in 1..128)
        assertTrue(newIdempotencyKey() != newIdempotencyKey())
    }

    // ---- 応答 -> 結果と文言(app.js の onSubmit / onGoalSubmit) ----

    @Test
    fun statusMapsToTheFailureKind() {
        assertEquals(SubmitFailure.NETWORK, submitFailureOf(null))
        assertEquals(SubmitFailure.BAD_REQUEST, submitFailureOf(400))
        assertEquals(SubmitFailure.SESSION, submitFailureOf(401))
        assertEquals(SubmitFailure.FORBIDDEN, submitFailureOf(403))
        assertEquals(SubmitFailure.NOT_FOUND, submitFailureOf(404))
        assertEquals(SubmitFailure.CONFLICT, submitFailureOf(409))
        assertEquals(SubmitFailure.UNSUPPORTED, submitFailureOf(415))
        assertEquals(SubmitFailure.TOO_MANY, submitFailureOf(429))
        assertEquals(SubmitFailure.DISABLED, submitFailureOf(503))
        assertEquals(SubmitFailure.SERVER, submitFailureOf(500))
    }

    @Test
    fun status202IsCreatedAndStatus200IsReplayed() {
        assertEquals(SubmitResult.Created("R1", "CREATED"), submitResultOfSuccess(202, "R1", "CREATED"))
        assertEquals(SubmitResult.Replayed("R1", "CREATED"), submitResultOfSuccess(200, "R1", "CREATED"))
    }

    @Test
    fun aSuccessWithoutAnIdOrWithAnOtherStatusIsInvalid() {
        assertEquals(SubmitResult.Failed(SubmitFailure.INVALID), submitResultOfSuccess(202, null, "CREATED"))
        assertEquals(SubmitResult.Failed(SubmitFailure.INVALID), submitResultOfSuccess(202, "", "CREATED"))
        assertEquals(SubmitResult.Failed(SubmitFailure.INVALID), submitResultOfSuccess(204, "R1", null))
    }

    @Test
    fun taskSuccessTextSaysTheRunIdAndTheKindLikeTheOldUi() {
        val created = taskSuccessText(SubmitResult.Created("RUN1", "CREATED"), SubmitKind.SIMULATED)
        assertEquals("受け付けました。Run ID: RUN1 / 状態: CREATED（シミュレーション）", created)
        val replayed = taskSuccessText(SubmitResult.Replayed("RUN1", "CREATED"), SubmitKind.SELF_DEV)
        assertTrue(replayed.startsWith("同じ投入として既に受け付け済みです（既存の Run）。"))
        assertTrue(replayed.endsWith("Run ID: RUN1 / 状態: CREATED（自己開発）"))
    }

    @Test
    fun aConflictSaysTheKeyWasMadeNewAndNeverShowsAServerText() {
        val text = taskFailureText(SubmitFailure.CONFLICT, SubmitKind.SIMULATED)
        assertTrue(text.contains("別の内容（別のタイトル、または別の種別）"))
        assertTrue(text.contains("投入キーを作り直しました"))
    }

    @Test
    fun theFailureTextsAreFixedAndDistinctForTheCodesThatHaveOne() {
        val texts = listOf(
            SubmitFailure.BAD_REQUEST,
            SubmitFailure.SESSION,
            SubmitFailure.FORBIDDEN,
            SubmitFailure.CONFLICT,
            SubmitFailure.TOO_MANY,
            SubmitFailure.UNSUPPORTED,
            SubmitFailure.SERVER,
            SubmitFailure.NETWORK,
            SubmitFailure.INVALID,
        ).map { taskFailureText(it, SubmitKind.SELF_DEV) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(taskFailureText(SubmitFailure.TOO_MANY, SubmitKind.SELF_DEV).contains("自己開発の Run が上限に達しています"))
        assertTrue(taskFailureText(SubmitFailure.UNSUPPORTED, SubmitKind.SIMULATED).contains("送信の形式がサーバーに受け付けられませんでした"))
        assertTrue(taskFailureText(SubmitFailure.BAD_REQUEST, SubmitKind.SIMULATED).contains("入力内容を確認してください"))
    }

    @Test
    fun aNetworkFailureOnTaskSubmitSaysTheRunMayHaveBeenMade() {
        val text = taskFailureText(SubmitFailure.NETWORK, SubmitKind.SIMULATED)
        assertTrue(text.contains("Run が作成されたかどうかは確認できていません"))
        assertTrue(text.contains("二重には作られません"))
    }

    @Test
    fun goalTextsFollowTheOldGoalMessages() {
        assertEquals(GOAL_SAVED_MESSAGE, goalSuccessText(SubmitResult.Created("T1", "TASK_SUBMITTED")))
        assertEquals(GOAL_REPLAYED_MESSAGE, goalSuccessText(SubmitResult.Replayed("T1", "TASK_SUBMITTED")))
        assertEquals(GOAL_KEY_CONFLICT_MESSAGE, goalFailureText(SubmitFailure.CONFLICT))
        assertEquals(GOAL_BAD_MESSAGE, goalFailureText(SubmitFailure.BAD_REQUEST))
        assertEquals("通信に失敗しました。もう一度お試しください。", goalFailureText(SubmitFailure.NETWORK))
        assertTrue(goalFailureText(SubmitFailure.DISABLED).contains("自律運転が無効"))
    }

    // ---- 種別の出し分け(app.js の updateKindControls / renderKindNote / applySelfDevAvailability) ----

    @Test
    fun selfDevIsSelectableOnlyWhenTheStatusSaysEnabled() {
        assertTrue(selfDevSelectable(SelfDevAvailability.ENABLED))
        assertFalse(selfDevSelectable(SelfDevAvailability.DISABLED))
        assertFalse(selfDevSelectable(SelfDevAvailability.UNKNOWN))
        assertFalse(selfDevSelectable(SelfDevAvailability.LOADING))
    }

    @Test
    fun theSelfDevNoteHasTheThreeStates() {
        assertTrue(selfDevNoteText(SelfDevAvailability.ENABLED).contains("PR を作成"))
        assertTrue(selfDevNoteText(SelfDevAvailability.DISABLED).contains("無効のため、シミュレーションだけ"))
        assertTrue(selfDevNoteText(SelfDevAvailability.UNKNOWN).contains("選べません"))
        assertEquals(selfDevNoteText(SelfDevAvailability.UNKNOWN), selfDevNoteText(SelfDevAvailability.LOADING))
    }

    @Test
    fun aSelfDevSelectionFallsBackToSimulationWhenItIsNoLongerAvailable() {
        assertEquals(SubmitKind.SELF_DEV, effectiveKind(SubmitKind.SELF_DEV, SelfDevAvailability.ENABLED))
        assertEquals(SubmitKind.SIMULATED, effectiveKind(SubmitKind.SELF_DEV, SelfDevAvailability.DISABLED))
        assertEquals(SubmitKind.SIMULATED, effectiveKind(SubmitKind.SELF_DEV, SelfDevAvailability.UNKNOWN))
        assertEquals(SubmitKind.SIMULATED, effectiveKind(SubmitKind.SIMULATED, SelfDevAvailability.ENABLED))
    }

    @Test
    fun theBodyFieldIsForSelfDevOnly() {
        assertTrue(showsBodyField(SubmitKind.SELF_DEV))
        assertFalse(showsBodyField(SubmitKind.SIMULATED))
    }

    @Test
    fun submissionIsBlockedWhileSendingOrWhenTheSessionEnded() {
        assertFalse(submitBlocked(submitting = false, sessionExpired = false))
        assertTrue(submitBlocked(submitting = true, sessionExpired = false))
        assertTrue(submitBlocked(submitting = false, sessionExpired = true))
    }

    @Test
    fun projectIdIsAUlidShape() {
        assertTrue(isProjectId("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertFalse(isProjectId("01arz3ndektsv4rrffq69g5fav"))
        assertFalse(isProjectId("unassigned"))
        assertFalse(isProjectId(""))
    }

    // ---- Goal フォームの出し分け ----

    private fun perms(vararg granted: String) = CallerPermissions.of(ApiMe("enforce", "OPERATOR", granted.toList()))

    @Test
    fun theGoalFormNeedsTheAutopilotAndGoalWrite() {
        assertTrue(showsGoalForm(AutopilotAvailability.ENABLED, perms("GOAL_WRITE")))
        assertFalse(showsGoalForm(AutopilotAvailability.ENABLED, perms("READ")), "no GOAL_WRITE: no form")
        assertFalse(showsGoalForm(AutopilotAvailability.HIDDEN, perms("GOAL_WRITE")), "autopilot off: no form")
        assertFalse(showsGoalForm(AutopilotAvailability.LOADING, perms("GOAL_WRITE")), "not known yet: no form")
    }

    @Test
    fun withoutRolesEnforcedTheGoalFormIsShownWhenTheAutopilotIsOn() {
        // app.js の can(): 未取得・mode が enforce でないときは隠さない(サーバーが判定する)。
        assertTrue(showsGoalForm(AutopilotAvailability.ENABLED, CallerPermissions.UNKNOWN))
    }
}
