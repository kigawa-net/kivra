package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiClaudeAccountOption
import net.kigawa.kivra.ui.api.ApiProjectClaudeAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectClaudeAccountRulesTest {
    private val primary = ApiClaudeAccountOption("primary", "開発用")
    private val secondary = ApiClaudeAccountOption("secondary", "運用用")
    private val settings = ApiProjectClaudeAccount(primary.id, listOf(primary, secondary))

    @Test
    fun resolvesOptionsById() {
        assertEquals(primary.label, settings.currentClaudeAccountLabel())
        assertEquals(ClaudeAccountChange.Set(secondary), settings.claudeAccountChange(secondary.id))
        assertNull(settings.claudeAccountChange(primary.id))
        assertNull(settings.claudeAccountChange(secondary.label))
        assertEquals("removed（選択肢にありません）", settings.copy(accountId = "removed").currentClaudeAccountLabel())
    }

    @Test
    fun nullAccountIsUnsetAndCanBeConfigured() {
        val unset = settings.copy(accountId = null)
        assertEquals("未設定", unset.currentClaudeAccountLabel())
        assertEquals(ClaudeAccountChange.Set(primary), unset.claudeAccountChange(primary.id))
        assertNull(unset.claudeAccountChange(null))
    }

    @Test
    fun clearingIsAvailableOnlyWhileAnAccountIsSet() {
        assertEquals(ClaudeAccountChange.Clear, settings.claudeAccountChange(null))
        assertNull(settings.copy(accountId = null).claudeAccountChange(null))
    }

    @Test
    fun noOptionsShowsProxyHelpAndStillAllowsClearing() {
        val empty = settings.copy(available = emptyList())
        assertNull(settings.claudeAccountEmptyMessage())
        assertNotNull(empty.claudeAccountEmptyMessage())
        assertNull(empty.claudeAccountChange(secondary.id))
        assertEquals(ClaudeAccountChange.Clear, empty.claudeAccountChange(null))
    }
}
