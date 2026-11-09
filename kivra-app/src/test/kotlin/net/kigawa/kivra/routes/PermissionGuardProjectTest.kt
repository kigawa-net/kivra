package net.kigawa.kivra.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.DENY_NOT_MEMBER
import net.kigawa.kivra.auth.DENY_PROJECT_LOOKUP
import net.kigawa.kivra.auth.DENY_PROJECT_PERMISSION
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.ProjectMemberLookup
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault

/**
 * The project check at the guard (ADR 0022 PR-D, `KIVRA_SELFDEV_PROJECT_MODE`). Shadow changes no answer; enforce refuses a non-member; the
 * global ADMIN passes; a row with no project is judged by the plain check alone; the log names no person and no project.
 */
class PermissionGuardProjectTest {
    private val ai = "1:i:ai"
    private val approver = "1:i:root"
    private val human = "1:i:SECRET-OWNER-KEY"
    private val admin = "1:i:SECRET-ADMIN-KEY"
    private val project = "01PROJ" + "A".repeat(20)
    private val humanId = "assignee-human"
    private val adminId = "assignee-admin"
    private val aiId = "assignee-ai"

    private class Log : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    private class Assignees(private val map: Map<String, AssigneeAccess>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = map[ownerKey]
    }

    private class Members(private val map: Map<Pair<String, String>, Role>, var fail: Boolean = false) : ProjectMemberLookup {
        var calls = 0
        override fun roleIn(projectId: String, assigneeId: String): Role? {
            calls++
            if (fail) throw IllegalStateException("store down")
            return map[projectId to assigneeId]
        }

        override fun rolesOf(assigneeId: String): Map<String, Role> {
            calls++
            if (fail) throw IllegalStateException("store down")
            return map.filterKeys { it.second == assigneeId }.map { (k, role) -> k.first to role }.toMap()
        }
    }

    private val assignees = mapOf(
        human to AssigneeAccess(Role.OPERATOR, true, humanId),
        admin to AssigneeAccess(Role.ADMIN, true, adminId),
        ai to AssigneeAccess(Role.AI, true, aiId),
    )

    private fun guard(
        roles: PermissionMode,
        projects: PermissionMode,
        members: Members,
        log: Log = Log(),
    ) = PermissionGuard(
        PermissionService(roles, RolesDefault.NONE, ai, approver, Assignees(assignees), projects, members),
        log,
    )

    private fun PermissionGuard.access(key: String) = resolve(key, legacyAdmin = true)

    @Test
    fun `project off - the plain check, and the membership is never read`() {
        val members = Members(emptyMap())
        val g = guard(PermissionMode.SHADOW, PermissionMode.OFF, members)
        for (p in Permission.entries) {
            for (key in listOf(human, admin, ai)) {
                assertEquals(g.check(g.access(key), p, "r"), g.checkInProject(g.access(key), project, p, "r"), "$key $p")
            }
        }
        assertEquals(0, members.calls)
    }

    @Test
    fun `a row with no project is judged by the plain check alone, and nothing is read`() {
        val members = Members(emptyMap())
        val g = guard(PermissionMode.ENFORCE, PermissionMode.ENFORCE, members)
        // the plain check allows the operator's order management; with no project there is no membership to look at
        assertEquals(GuardResult.Allow, g.checkInProject(g.access(human), null, Permission.ORDER_MANAGE, "r"))
        assertEquals(0, members.calls)
    }

    @Test
    fun `shadow - the answer is the plain check's, and a project refusal the plain check does not make is logged with fixed words only`() {
        val members = Members(emptyMap())
        val log = Log()
        val g = guard(PermissionMode.SHADOW, PermissionMode.SHADOW, members, log)
        // the operator is not a member of the project: the project would refuse, the plain check allows
        assertEquals(GuardResult.Allow, g.checkInProject(g.access(human), project, Permission.ORDER_MANAGE, "order.create"))
        val line = log.lines.single { it.startsWith("project shadow:") }
        assertTrue(line.contains("code=$DENY_NOT_MEMBER"), line)
        assertTrue(line.contains("permission=ORDER_MANAGE"), line)
        assertTrue(line.contains("route=order.create"), line)
        assertFalse(line.contains(human) || line.contains("SECRET-OWNER"), "the owner key is never logged: $line")
        assertFalse(line.contains(project), "the project id is never logged: $line")
    }

    @Test
    fun `shadow - a plain refusal stays the plain refusal, and no project line is written for it`() {
        val log = Log()
        val g = guard(PermissionMode.SHADOW, PermissionMode.SHADOW, Members(emptyMap()), log)
        // the AI may not manage orders by the plain check
        assertIs<GuardResult.Deny>(g.checkInProject(g.access(ai), project, Permission.ORDER_MANAGE, "order.create"))
        assertFalse(log.lines.any { it.startsWith("project shadow:") })
    }

    @Test
    fun `shadow never changes an answer, for every permission, role and membership`() {
        val memberships = listOf(
            emptyMap(),
            mapOf((project to humanId) to Role.VIEWER, (project to adminId) to Role.ADMIN, (project to aiId) to Role.AI),
            mapOf((project to humanId) to Role.OPERATOR, (project to aiId) to Role.OPERATOR),
        )
        for (map in memberships) {
            val g = guard(PermissionMode.SHADOW, PermissionMode.SHADOW, Members(map))
            for (p in Permission.entries) {
                for (key in listOf(human, admin, ai, "1:i:stranger")) {
                    for (humanOnly in listOf(false, true)) {
                        assertEquals(
                            g.check(g.access(key), p, "r", humanOnly = humanOnly),
                            g.checkInProject(g.access(key), project, p, "r", humanOnly = humanOnly),
                            "$key $p humanOnly=$humanOnly members=$map",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `enforce - a non-member is refused, and the global ADMIN passes without a membership`() {
        val g = guard(PermissionMode.ENFORCE, PermissionMode.ENFORCE, Members(emptyMap()))
        assertEquals(GuardResult.Deny(DENY_NOT_MEMBER), g.checkInProject(g.access(human), project, Permission.ORDER_MANAGE, "r"))
        assertEquals(GuardResult.Allow, g.checkInProject(g.access(admin), project, Permission.ORDER_MANAGE, "r"))
    }

    @Test
    fun `enforce - a member passes when its project role holds the permission, and is refused with the permission code when it does not`() {
        val g = guard(
            PermissionMode.ENFORCE,
            PermissionMode.ENFORCE,
            Members(mapOf((project to humanId) to Role.OPERATOR, (project to aiId) to Role.VIEWER)),
        )
        assertEquals(GuardResult.Allow, g.checkInProject(g.access(human), project, Permission.ORDER_MANAGE, "r"))
        // the AI is refused by its role in the project: it is a viewer here, so it may not start a Task
        assertEquals(
            GuardResult.Deny(DENY_PROJECT_PERMISSION),
            g.checkInProject(g.access(ai), project, Permission.SPEND_START, "r"),
        )
    }

    @Test
    fun `enforce - the plain refusal comes first, and a failed store is a refusal`() {
        val members = Members(mapOf((project to humanId) to Role.OPERATOR), fail = true)
        val g = guard(PermissionMode.ENFORCE, PermissionMode.ENFORCE, members)
        assertEquals(GuardResult.Deny(DENY_PROJECT_LOOKUP), g.checkInProject(g.access(human), project, Permission.READ, "r"))
        // the plain refusal (the AI holds no ORDER_MANAGE) is the answer, whatever the store says
        assertEquals(GuardResult.Deny("PERMISSION_DENIED"), g.checkInProject(g.access(ai), project, Permission.ORDER_MANAGE, "r"))
    }

    @Test
    fun `the project scope is on only with the roles and the project mode both on`() {
        assertFalse(guard(PermissionMode.OFF, PermissionMode.ENFORCE, Members(emptyMap())).projectScopeOn)
        assertFalse(guard(PermissionMode.ENFORCE, PermissionMode.OFF, Members(emptyMap())).projectScopeOn)
        assertTrue(guard(PermissionMode.SHADOW, PermissionMode.SHADOW, Members(emptyMap())).projectScopeOn)
    }
}
