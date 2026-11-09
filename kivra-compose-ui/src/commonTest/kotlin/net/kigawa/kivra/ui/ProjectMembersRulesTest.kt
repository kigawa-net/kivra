package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiAssignee
import net.kigawa.kivra.ui.api.ApiMemberImportReport
import net.kigawa.kivra.ui.api.ApiProjectMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectMembersRulesTest {

    private fun assignee(id: String, name: String, state: String = "ACTIVE") =
        ApiAssignee(
            assigneeId = id,
            displayName = name,
            role = "VIEWER",
            state = state,
            version = 1L,
            memberCount = 0,
            activeTokenCount = 0,
        )

    private fun member(id: String, name: String, role: String = "VIEWER", state: String = "ACTIVE") =
        ApiProjectMember(assigneeId = id, displayName = name, role = role, state = state)

    private fun report(
        dryRun: Boolean = true,
        role: String = "VIEWER",
        candidates: Long = 0L,
        added: Long = 0L,
        alreadyMember: Long = 0L,
        disabled: Long = 0L,
    ) = ApiMemberImportReport(dryRun, role, candidates, added, alreadyMember, disabled)

    @Test
    fun roleChoicesAreTheServerSetInTheServerOrder() {
        assertEquals(listOf("ADMIN", "OPERATOR", "APPROVER", "AI", "VIEWER"), MEMBER_ROLES)
        assertEquals("VIEWER", MEMBER_DEFAULT_ROLE)
    }

    @Test
    fun candidatesAreTheActiveAssigneesThatAreNotMembersInTheGivenOrder() {
        val assignees = listOf(
            assignee("AS-1", "Alice"),
            assignee("AS-2", "Bob"),
            assignee("AS-3", "Carol"),
            assignee("AS-4", "Dan", state = "DISABLED"),
        )
        val members = listOf(member("AS-1", "Alice", role = "OPERATOR"))
        assertEquals(listOf("AS-2", "AS-3"), memberCandidates(assignees, members).map { it.assigneeId })
    }

    @Test
    fun noCandidateWhenEveryActiveAssigneeIsAlreadyAMember() {
        val assignees = listOf(assignee("AS-1", "Alice"))
        assertTrue(memberCandidates(assignees, listOf(member("AS-1", "Alice"))).isEmpty())
        assertTrue(memberCandidates(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun aDisabledMemberIsLabelledAsDisabled() {
        assertEquals("Alice", memberLabel(member("AS-1", "Alice")))
        assertEquals("Dan（無効）", memberLabel(member("AS-4", "Dan", state = "DISABLED")))
    }

    @Test
    fun memberFailureTextIsFixedByStatusAndNeverShowsTheServerBody() {
        assertEquals(SESSION_EXPIRED_MESSAGE, memberFailureText(401))
        assertTrue(memberFailureText(409).startsWith("このプロジェクトの最後の管理者（ADMIN）"))
        assertTrue(memberFailureText(404).startsWith("担当またはプロジェクトが見つかりません"))
        assertTrue(memberFailureText(400).startsWith("役割（role）の値が正しくありません"))
        assertEquals("この操作は運用者だけができます。", memberFailureText(403))
        assertTrue(memberFailureText(503).contains("503"))
        assertEquals("操作に失敗しました。時間をおいてもう一度お試しください。", memberFailureText(500))
        assertEquals("操作に失敗しました。時間をおいてもう一度お試しください。", memberFailureText(null))
        assertFalse(memberFailureText(409).contains("LEAK"))
    }

    @Test
    fun importSummaryShowsTheFourCounts() {
        val text = memberImportSummary(report(candidates = 2, added = 0, alreadyMember = 1, disabled = 1))
        assertEquals(
            "対象（有効で未登録）2 件、追加 0 件、すでにメンバー 1 件（role は変えません）、無効（対象外）1 件",
            text,
        )
    }

    @Test
    fun aRealImportNeedsADryRunThatFoundSomething() {
        assertFalse(canRunMemberImport(null, busy = false), "no real run before a dry run")
        assertFalse(canRunMemberImport(report(candidates = 0), busy = false), "a dry run that finds nothing disables the real run")
        assertFalse(canRunMemberImport(report(candidates = 2), busy = true), "a run in progress disables it")
        assertTrue(canRunMemberImport(report(candidates = 2), busy = false))
    }

    @Test
    fun importConfirmationNamesTheRoleOfTheDryRun() {
        val text = memberImportExplain(report(role = "APPROVER", candidates = 3))
        assertTrue(text.contains("3 人の有効な担当を role「APPROVER」"))
        assertTrue(text.contains("すでにメンバーの人の role は変えません"))
        assertTrue(text.contains("一括で外す機能はありません"))
    }

    @Test
    fun removalConfirmationNamesTheAssigneeAndSaysItIsNotDeleted() {
        val text = memberRemoveExplain("Alice")
        assertTrue(text.startsWith("この担当（Alice）を、このプロジェクトのメンバーから外します。"))
        assertTrue(text.contains("担当そのものは消えません"))
        assertTrue(text.contains("最後の管理者（ADMIN）は外せません"))
    }
}
