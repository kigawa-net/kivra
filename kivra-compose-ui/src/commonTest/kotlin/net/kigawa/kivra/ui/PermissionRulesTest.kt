package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiMe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionRulesTest {

    // サーバーの ROLE_PERMISSIONS(auth/Permissions.kt)と同じ組み立て。
    private val readOnly = listOf("READ")
    private val approver = listOf("READ", "STOP_REQUEST", "STOP_DECIDE")
    private val ai = listOf("READ", "STOP_REQUEST", "SPEND_START")
    private val operator = listOf(
        "READ", "READ_TEXT", "GOAL_STOP", "ORDER_MANAGE", "STOP_REQUEST", "GOAL_WRITE", "SPEND_START", "BUDGET_OVERRIDE",
    )
    private val admin = operator + listOf("STOP_DECIDE", "ASSIGNEE_MANAGE")

    private fun me(mode: String, permissions: List<String>, role: String = "OPERATOR") = ApiMe(mode, role, permissions)

    private fun enforced(permissions: List<String>) = CallerPermissions.of(me("enforce", permissions))

    private val everyOperation = GatedOperation.entries

    @Test
    fun unknownShowsEverything() {
        // 取得前・失敗時は何も隠さない(旧 UI の loadMe と同じ)。担当だけは出さない。
        assertTrue(everyOperation.all { CallerPermissions.UNKNOWN.can(it) })
        assertTrue(CallerPermissions.UNKNOWN.navVisible(NavDestination.Memory))
        assertTrue(CallerPermissions.UNKNOWN.navVisible(NavDestination.Approvals))
        assertFalse(CallerPermissions.UNKNOWN.navVisible(NavDestination.Assignees))
    }

    @Test
    fun malformedAnswerIsUnknown() {
        // 形が合わない応答は UNKNOWN と同じ扱い(何も隠さない・担当は出さない)。
        val badMode = CallerPermissions.of(me("maybe", listOf("READ")))
        assertFalse(badMode.navVisible(NavDestination.Assignees))
        assertTrue(badMode.can(GatedOperation.RELEASE_RUN))
        val badRole = CallerPermissions.of(ApiMe("enforce", "viewer", readOnly))
        assertTrue(badRole.can(GatedOperation.RELEASE_RUN))
        val badPermission = CallerPermissions.of(me("enforce", listOf("read")))
        assertTrue(badPermission.can(GatedOperation.RELEASE_RUN))
    }

    @Test
    fun offAndShadowShowEverythingExceptAssigneesWithoutTheRight() {
        // off と shadow では役割の権限は強制されていない。操作は全部出す。
        for (mode in listOf("off", "shadow")) {
            val p = CallerPermissions.of(me(mode, readOnly))
            assertTrue(everyOperation.all { p.can(it) }, mode)
            assertFalse(p.assigneesVisible(), mode)
        }
        assertTrue(CallerPermissions.of(me("shadow", admin)).assigneesVisible())
        // off は担当の画面を出さない(旧 UI の assigneesVisible と同じ)。
        assertFalse(CallerPermissions.of(me("off", admin)).assigneesVisible())
    }

    @Test
    fun enforcedViewerSeesNoWriteButtons() {
        val viewer = enforced(readOnly)
        assertFalse(viewer.can(GatedOperation.DECIDE_STOP_REQUEST))
        assertFalse(viewer.can(GatedOperation.APPROVE_GOAL))
        assertFalse(viewer.can(GatedOperation.STOP_GOAL))
        assertFalse(viewer.can(GatedOperation.RELEASE_RUN))
        assertFalse(viewer.can(GatedOperation.ACKNOWLEDGE_BUDGET))
        assertFalse(viewer.can(GatedOperation.SUBMIT_TASK))
        assertFalse(viewer.can(GatedOperation.CREATE_PROJECT))
        assertFalse(viewer.navVisible(NavDestination.Memory))
        assertFalse(viewer.navVisible(NavDestination.Approvals))
        assertFalse(viewer.navVisible(NavDestination.Assignees))
        assertTrue(viewer.navVisible(NavDestination.Tasks))
        assertTrue(viewer.navVisible(NavDestination.Budget))
    }

    @Test
    fun approverOnlyDecidesStopRequests() {
        val p = enforced(approver)
        assertTrue(p.can(GatedOperation.DECIDE_STOP_REQUEST))
        assertFalse(p.can(GatedOperation.APPROVE_GOAL))
        assertFalse(p.can(GatedOperation.RELEASE_RUN))
        assertTrue(p.showsApprovals())
        assertFalse(p.navVisible(NavDestination.Memory))
    }

    @Test
    fun aiCanSubmitButNotStopOrRelease() {
        val p = enforced(ai)
        assertTrue(p.can(GatedOperation.SUBMIT_TASK))
        assertFalse(p.can(GatedOperation.STOP_GOAL))
        assertFalse(p.can(GatedOperation.RELEASE_RUN))
        assertFalse(p.can(GatedOperation.CREATE_PROJECT))
    }

    @Test
    fun operatorHasEveryOperationButApprovals() {
        val p = enforced(operator)
        assertTrue(p.can(GatedOperation.APPROVE_GOAL))
        assertTrue(p.can(GatedOperation.STOP_GOAL))
        assertTrue(p.can(GatedOperation.RELEASE_RUN))
        assertTrue(p.can(GatedOperation.ACKNOWLEDGE_BUDGET))
        assertTrue(p.can(GatedOperation.SUBMIT_TASK))
        assertTrue(p.can(GatedOperation.CREATE_PROJECT))
        assertTrue(p.can(GatedOperation.READ_MEMORY))
        // STOP_DECIDE は ADMIN と APPROVER だけ。OPERATOR は停止要求を承認できない。
        assertFalse(p.can(GatedOperation.DECIDE_STOP_REQUEST))
        assertFalse(p.showsApprovals())
        assertFalse(p.assigneesVisible())
    }

    @Test
    fun adminSeesEverything() {
        val p = enforced(admin)
        assertTrue(everyOperation.all { p.can(it) })
        assertTrue(p.navVisible(NavDestination.Assignees))
        assertTrue(p.navVisible(NavDestination.Approvals))
    }

    @Test
    fun unknownPermissionNamesAreIgnored() {
        // サーバーが後から増やした名前は読み飛ばす。知っている権限は効く。
        val p = enforced(listOf("READ", "FUTURE_PERMISSION", "SPEND_START"))
        assertTrue(p.can(GatedOperation.SUBMIT_TASK))
        assertFalse(p.can(GatedOperation.RELEASE_RUN))
    }

    @Test
    fun everyOperationNeedsTheNamedPermission() {
        // 対応表: 同じ権限を要する操作が、app.js と サーバーの規則どおりに並ぶ。
        assertEquals(SelfDevPermission.STOP_DECIDE, GatedOperation.DECIDE_STOP_REQUEST.permission)
        assertEquals(SelfDevPermission.GOAL_WRITE, GatedOperation.APPROVE_GOAL.permission)
        assertEquals(SelfDevPermission.GOAL_STOP, GatedOperation.STOP_GOAL.permission)
        assertEquals(SelfDevPermission.BUDGET_OVERRIDE, GatedOperation.RELEASE_RUN.permission)
        assertEquals(SelfDevPermission.BUDGET_OVERRIDE, GatedOperation.ACKNOWLEDGE_BUDGET.permission)
        assertEquals(SelfDevPermission.SPEND_START, GatedOperation.SUBMIT_TASK.permission)
        assertEquals(SelfDevPermission.ORDER_MANAGE, GatedOperation.CREATE_PROJECT.permission)
        assertEquals(SelfDevPermission.READ_TEXT, GatedOperation.READ_MEMORY.permission)
    }
}
