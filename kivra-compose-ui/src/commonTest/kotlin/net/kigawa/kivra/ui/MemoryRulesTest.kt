package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryRulesTest {

    @Test
    fun filterKeepsOnlyKnownValuesAndSendsThemInTheOldOrder() {
        // 表にない値は送らない(旧 UI: NOT_A_KIND は kind= に入らない)。
        assertEquals(MemoryListFilter(), memoryFilterOf("NOT_A_KIND", ""))
        val f = memoryFilterOf("OPERATOR_NOTE", "TRUSTED_OPERATOR")
        assertEquals("limit=20&offset=40&kind=OPERATOR_NOTE&tier=TRUSTED_OPERATOR", memoryListQuery(f, 40, null))
        assertEquals("limit=20&offset=0&projectId=p1", memoryListQuery(MemoryListFilter(), 0, "p1"))
    }

    @Test
    fun kindsAndTiersAreTheServerNamesWithTheOldLabels() {
        // 旧 UI の MEM.KIND / MEM.TIER と同じ 12 種と 2 信頼(順も同じ)。
        assertEquals(12, MEMORY_KIND_LABELS.size)
        assertEquals("OPERATOR_NOTE", MEMORY_KIND_LABELS.keys.first())
        assertEquals("GOAL_FACT", MEMORY_KIND_LABELS.keys.last())
        assertEquals("運用者の覚え書き", memoryKindLabel("OPERATOR_NOTE"))
        assertEquals("不明", memoryKindLabel("NOPE"))
        assertEquals("運用者の文章（信頼）", memoryTierLabel("TRUSTED_OPERATOR"))
        assertEquals("未検証（モデルや系統が書いた）", memoryTierLabel("UNTRUSTED"))
        assertEquals(listOf("TRUSTED_OPERATOR", "UNTRUSTED"), MEMORY_TIER_LABELS.keys.toList())
    }

    @Test
    fun conditionNeedsAKindOrATier() {
        assertFalse(MemoryListFilter().hasCondition)
        assertTrue(MemoryListFilter(kind = "GOAL_FACT").hasCondition)
        assertTrue(MemoryListFilter(tier = "UNTRUSTED").hasCondition)
    }

    @Test
    fun pagingMovesByTwentyWithinTheTotal() {
        // 前は offset > 0 のとき。次は今のページの終わりが総数に届いていないとき(旧 UI と同じ)。
        assertFalse(memoryHasPrev(0))
        assertTrue(memoryHasPrev(20))
        assertTrue(memoryHasNext(0, 20, 21))
        assertFalse(memoryHasNext(20, 1, 21))
        assertEquals("21 から 21 件目を表示しています", memoryPageText(20, 1))
        assertEquals("", memoryPageText(0, 0))
    }

    @Test
    fun bodyIsShownOnlyWhenTheServerSentIt() {
        assertEquals("本文", memoryBodyText("本文", false))
        assertEquals(MEMORY_MSG_WITHHELD, memoryBodyText(null, true))
        assertNull(memoryBodyText(null, false))
    }

    @Test
    fun codeIsShownOnlyWhenItHasTheMachineShape() {
        assertEquals("CYCLE_LIMIT", memoryCodeText("CYCLE_LIMIT"))
        assertEquals("不明", memoryCodeText("lower-case"))
    }

    @Test
    fun deleteFailureTextsAreFixedAndNeverEchoTheServer() {
        assertEquals(MEMORY_MSG_NOT_FOUND, memoryFailureText(404))
        assertEquals(MEMORY_MSG_LIMIT, memoryFailureText(409))
        assertEquals(MEMORY_MSG_REASON_INVALID, memoryFailureText(400))
        assertEquals(MEMORY_MSG_SESSION, memoryFailureText(401))
        assertEquals(MEMORY_MSG_NETWORK, memoryFailureText(null))
        assertEquals(MEMORY_MSG_UNKNOWN, memoryFailureText(500))
        // 件数の確認は 409 も上限の案内。
        assertEquals(MEMORY_MSG_LIMIT, memoryDryRunFailureText(409))
    }

    @Test
    fun conditionDeleteNeedsADryRunOfTheSameFilterWithAtLeastOne() {
        val filter = MemoryListFilter(kind = "OPERATOR_NOTE")
        assertFalse(memoryConditionDeleteEnabled(null, filter))
        assertTrue(memoryConditionDeleteEnabled(MemoryDryRun(filter.key, 4), filter))
        assertFalse(memoryConditionDeleteEnabled(MemoryDryRun(filter.key, 0), filter), "no match: nothing to delete")
        // 件数を確かめた後で絞り込みを変えたら、削除は出さない。
        assertFalse(memoryConditionDeleteEnabled(MemoryDryRun(filter.key, 4), MemoryListFilter(tier = "UNTRUSTED")))
    }

    @Test
    fun dryRunAndExplanationTextsMatchTheOldUi() {
        assertEquals("該当: 4 件（1 回の上限は 500 件）", memoryDryRunText(4))
        assertEquals("表示中の条件に該当する 4 件を削除します。文章は消え、墓標と監査が残ります。取り消せません。", memoryExplainCondition(4))
        assertEquals("条件を 1 つ以上選んでから確認してください（種類か信頼）。", MEMORY_MSG_NEED_FILTER)
    }
}
