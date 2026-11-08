package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadRulesTest {

    @Test
    fun failureTextNamesTheCauseAndNeverCarriesServerText() {
        assertEquals(SESSION_EXPIRED_TEXT, loadFailureText(401, "unauthorized"))
        assertEquals("この画面を見る権限がありません(403)。", loadFailureText(403, "HIDDEN-BODY"))
        assertEquals("見つかりません(404)。id が違うか、存在しません。", loadFailureText(404, "x"))
        assertEquals("読み込みに失敗しました(500)。", loadFailureText(500, "LEAK-500"))
        assertFalse(loadFailureText(500, "LEAK-500").contains("LEAK"))
    }

    @Test
    fun unavailableShowsOnlyAMachineCodeShapedDetail() {
        assertTrue(loadFailureText(503, "AUTOPILOT_DISABLED").contains("503: AUTOPILOT_DISABLED"))
        val text = loadFailureText(503, "<b>not a code</b>")
        assertFalse(text.contains("<b>"))
        assertTrue(text.startsWith("この機能は今使えません(503)"))
    }

    @Test
    fun hiddenStatusesAreExactlyTheSilentOnes() {
        assertTrue(isHiddenForCaller(401))
        assertTrue(isHiddenForCaller(403))
        assertTrue(isHiddenForCaller(404))
        assertFalse(isHiddenForCaller(500))
        assertFalse(isHiddenForCaller(null))
    }
}

class RefreshRulesTest {

    @Test
    fun autoRefreshWaitsForTheInterval() {
        assertFalse(shouldAutoRefresh(now = 29_999, lastAttemptAt = 0, hidden = false, holds = 0, sessionExpired = false))
        assertTrue(shouldAutoRefresh(now = 30_000, lastAttemptAt = 0, hidden = false, holds = 0, sessionExpired = false))
    }

    @Test
    fun autoRefreshStopsWhileHiddenHeldOrExpired() {
        assertFalse(shouldAutoRefresh(60_000, 0, hidden = true, holds = 0, sessionExpired = false), "hidden page")
        assertFalse(shouldAutoRefresh(60_000, 0, hidden = false, holds = 1, sessionExpired = false), "dialog or write open")
        assertFalse(shouldAutoRefresh(60_000, 0, hidden = false, holds = 0, sessionExpired = true), "session expired")
    }

    @Test
    fun healthTracksTheLastSuccess() {
        val first = HealthState().after(ok = true, now = 100)
        assertEquals(HealthState(ok = true, checkedAt = 100, okAt = 100), first)
        val failed = first.after(ok = false, now = 200)
        assertEquals(HealthState(ok = false, checkedAt = 200, okAt = 100), failed)
        assertEquals(0L, HealthState().after(ok = false, now = 5).okAt)
    }

    @Test
    fun connectionLabelFollowsTheOldUi() {
        val clock: (Long) -> String = { "T$it" }
        assertEquals("接続: 未確認", connectionLabel(HealthState(), clock))
        assertEquals("接続: OK（確認 T100）", connectionLabel(HealthState(true, 100, 100), clock))
        assertEquals(
            "接続: 失敗（確認 T200、最後に成功 T100）",
            connectionLabel(HealthState(false, 200, 100), clock),
        )
        assertEquals(
            "接続: 失敗（確認 T200、成功したことはありません）",
            connectionLabel(HealthState(false, 200, 0), clock),
        )
    }
}

class SelfDevRulesTest {

    @Test
    fun availabilityIsTheThreeStatesFromTheStatusRead() {
        assertEquals(SelfDevAvailability.ENABLED, selfDevAvailabilityOf(true))
        assertEquals(SelfDevAvailability.DISABLED, selfDevAvailabilityOf(false))
        assertEquals(SelfDevAvailability.UNKNOWN, selfDevAvailabilityOf(null))
        assertEquals("自己開発: 取得失敗", SelfDevAvailability.UNKNOWN.label)
    }
}

class PullRequestRulesTest {

    @Test
    fun aGitHubPullRequestUrlIsLinked() {
        val url = "https://github.com/kigawa-net/kivra/pull/241"
        assertEquals(url, safePullRequestUrl(url))
    }

    @Test
    fun anythingElseIsNotLinked() {
        assertNull(safePullRequestUrl(null))
        assertNull(safePullRequestUrl(""))
        assertNull(safePullRequestUrl("http://github.com/a/b/pull/1"), "not https")
        assertNull(safePullRequestUrl("https://example.com/a/b/pull/1"), "other host")
        assertNull(safePullRequestUrl("https://github.com/a/b/issues/1"), "an issue")
        assertNull(safePullRequestUrl("https://github.com/a/b/pull/1/files"), "a sub page")
        assertNull(safePullRequestUrl("https://github.com/a/b/pull/1\n"), "a trailing line break")
        assertNull(safePullRequestUrl("javascript:alert(1)"))
        assertNull(safePullRequestUrl("https://github.com/a/b/pull/x"), "not a number")
    }
}
