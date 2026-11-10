package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.ui.api.ApiDrainReport
import net.kigawa.kivra.ui.api.ApiProject

/** The rules of "空にして削除" (ADR 0022 PR-H): the target choices, the counts, when a drain or a delete may run, and the texts. */
class ProjectDrainRulesTest {
    private val source = "01AAAAAAAAAAAAAAAAAAAAAAAA"
    private val target = "01BBBBBBBBBBBBBBBBBBBBBBBB"
    private val other = "01CCCCCCCCCCCCCCCCCCCCCCCC"

    private fun report(vararg pairs: Pair<String, Long>, dryRun: Boolean = true, to: String = target) =
        ApiDrainReport(dryRun = dryRun, targetProjectId = to, counts = mapOf(*pairs))

    @Test
    fun `the target choices leave the source out and keep the order`() {
        val list = listOf(ApiProject(source, "移し元", null), ApiProject(target, "移し先", null), ApiProject(other, "別", null))
        assertEquals(listOf(target, other), drainTargetChoices(list, source).map { it.projectId })
    }

    @Test
    fun `the total is the sum of the counts`() {
        assertEquals(3L, drainTotal(mapOf("tasks" to 2L, "repos" to 1L)))
        assertEquals(0L, drainTotal(emptyMap()))
    }

    @Test
    fun `the count lines leave out the zero kinds, and name each kind`() {
        val lines = drainCountLines(linkedMapOf("repos" to 1L, "members" to 0L, "tasks" to 2L, "weird" to 3L))
        assertEquals(listOf("repo の登録：1 件", "Task：2 件", "その他：3 件"), lines)
    }

    @Test
    fun `a drain needs a dry run for the same target, with something to move, and nothing in flight`() {
        val dry = report("tasks" to 2L)
        assertTrue(canRunDrain(dry, target, busy = false))
        assertFalse(canRunDrain(dry, target, busy = true))
        assertFalse(canRunDrain(null, target, busy = false), "no dry run yet")
        assertFalse(canRunDrain(dry, other, busy = false), "the target changed after the dry run")
        assertFalse(canRunDrain(dry, null, busy = false), "no target chosen")
        assertFalse(canRunDrain(report("tasks" to 0L), target, busy = false), "nothing to move")
    }

    @Test
    fun `a delete needs an emptied project and nothing in flight`() {
        assertTrue(canDeleteEmptied(emptied = true, busy = false))
        assertFalse(canDeleteEmptied(emptied = false, busy = false))
        assertFalse(canDeleteEmptied(emptied = true, busy = true))
    }

    @Test
    fun `the drain explain names the target and what leaves, and says that nothing is deleted`() {
        val text = drainExplain(report("tasks" to 2L, "members" to 1L), "移し先A")
        assertTrue(text.contains("「移し先A」"), text)
        assertTrue(text.contains("Task：2 件"), text)
        assertTrue(text.contains("メンバー（外す）：1 件"), text)
        assertTrue(text.contains("行は消しません"), text)
        assertTrue(text.contains("最後の管理者を含みます"), text)
    }

    @Test
    fun `the failure texts follow the status, and 409 names the busy state`() {
        assertTrue(drainFailureText(409).contains("Run"))
        assertEquals(SESSION_EXPIRED_MESSAGE, drainFailureText(401))
        assertTrue(drainFailureText(null).isNotBlank())
        assertTrue(deleteProjectFailureText(409).contains("行が残って"))
    }

    @Test
    fun `the delete explain names the project`() {
        assertTrue(deleteExplain("検証").contains("「検証」"))
    }
}
