package net.kigawa.kivra.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsTest {

    private val ai = "1:i:ai"
    private val approver = "1:i:root"
    private val human = "1:i:human"

    private class Fake(
        val map: Map<String, AssigneeAccess> = emptyMap(),
        val fail: Boolean = false,
    ) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? {
            if (fail) throw IllegalStateException("db down")
            return map[ownerKey]
        }
    }

    private fun svc(
        mode: PermissionMode,
        lookup: AssigneeLookup? = Fake(),
        default: RolesDefault = RolesDefault.OPERATOR,
    ) = PermissionService(mode, default, ai, approver, lookup)

    private fun allowed(s: PermissionService, key: String, p: Permission, admin: Boolean = true, scopeOk: Boolean = true) =
        s.check(s.access(key, admin), p, scopeOk) == Decision.Allow

    // The plan's table, one row per permission, columns ADMIN OPERATOR APPROVER AI VIEWER.
    private val table: Map<Permission, String> = mapOf(
        Permission.READ to "YYYYY",
        Permission.ORDER_MANAGE to "YYNNN",
        Permission.STOP_REQUEST to "YYYYN",
        Permission.STOP_DECIDE to "NNYNN",
        Permission.GOAL_WRITE to "YYNNN",
        Permission.SPEND_START to "YYNYN",
        Permission.BUDGET_OVERRIDE to "YYNNN",
        Permission.ASSIGNEE_MANAGE to "YNNNN",
    )

    @Test
    fun `the table matches the plan for every role and permission`() {
        val roles = listOf(Role.ADMIN, Role.OPERATOR, Role.APPROVER, Role.AI, Role.VIEWER)
        assertEquals(Permission.entries.toSet(), table.keys)
        for ((p, row) in table) for ((i, r) in roles.withIndex()) {
            assertEquals(row[i] == 'Y', p in ROLE_PERMISSIONS.getValue(r), "$r $p")
        }
        assertEquals(roles.toSet(), ROLE_PERMISSIONS.keys)
    }

    @Test
    fun `admin cannot decide a stop and approver is a separate role`() {
        assertFalse(Permission.STOP_DECIDE in ROLE_PERMISSIONS.getValue(Role.ADMIN))
        assertEquals(
            setOf(Permission.READ, Permission.STOP_REQUEST, Permission.STOP_DECIDE),
            ROLE_PERMISSIONS.getValue(Role.APPROVER),
        )
    }

    // ---- OFF parity: today's decisions ----

    @Test
    fun `off reproduces today's rules for the AI principal`() {
        val s = svc(PermissionMode.OFF)
        for (p in listOf(Permission.ORDER_MANAGE, Permission.STOP_DECIDE, Permission.GOAL_WRITE, Permission.ASSIGNEE_MANAGE)) {
            assertFalse(allowed(s, ai, p), "$p")
        }
        for (p in listOf(Permission.READ, Permission.STOP_REQUEST)) assertTrue(allowed(s, ai, p), "$p")
        // The known gap: the AI may start a run, use the Task API and release a budget today, so OFF keeps that.
        assertTrue(allowed(s, ai, Permission.SPEND_START))
        assertTrue(allowed(s, ai, Permission.BUDGET_OVERRIDE))
        assertEquals(Role.AI, s.access(ai, true).role)
    }

    @Test
    fun `off reproduces today's rules for the root approver and other operators`() {
        val s = svc(PermissionMode.OFF)
        val approverAllowed = Permission.entries - Permission.ASSIGNEE_MANAGE
        for (p in Permission.entries) {
            assertEquals(p in approverAllowed, allowed(s, approver, p), "approver $p")
            val human = p != Permission.STOP_DECIDE && p != Permission.ASSIGNEE_MANAGE
            assertEquals(human, allowed(s, this.human, p), "operator $p")
        }
    }

    @Test
    fun `off ignores the assignee store and denies a caller who is not an operator`() {
        val s = svc(PermissionMode.OFF, Fake(fail = true))
        assertTrue(allowed(s, human, Permission.GOAL_WRITE))
        for (p in Permission.entries) {
            assertFalse(allowed(s, human, p, admin = false), "$p")
            assertFalse(allowed(s, ai, p, admin = false), "$p")
        }
    }

    @Test
    fun `off applies the scope intersection`() {
        val s = svc(PermissionMode.OFF)
        assertEquals(Decision.Deny(DENY_SCOPE), s.check(s.access(human, true), Permission.READ, scopeOk = false))
    }

    // ---- SHADOW ----

    @Test
    fun `shadow computes like enforce and marks the denial as would-deny`() {
        val map = mapOf(human to AssigneeAccess(Role.VIEWER, true))
        val shadow = svc(PermissionMode.SHADOW, Fake(map))
        val enforce = svc(PermissionMode.ENFORCE, Fake(map))
        for (p in Permission.entries) {
            val a = shadow.check(shadow.access(human, true), p)
            val b = enforce.check(enforce.access(human, true), p)
            when (b) {
                Decision.Allow -> assertEquals(Decision.Allow, a)
                is Decision.Deny -> {
                    assertEquals(Decision.Deny(b.code, wouldDeny = true), a)
                    assertFalse(b.wouldDeny)
                }
            }
        }
        // legacy remains available to the caller that acts on today's rules while shadowing.
        assertEquals(Role.OPERATOR, shadow.legacyAccess(human, true).role)
    }

    // ---- ENFORCE ----

    @Test
    fun `enforce applies the table to each assigned role`() {
        for (role in Role.entries) {
            val s = svc(PermissionMode.ENFORCE, Fake(mapOf(human to AssigneeAccess(role, true))))
            val access = s.access(human, true)
            assertEquals(AccessSource.ASSIGNEE, access.source)
            for (p in Permission.entries) assertEquals(p in ROLE_PERMISSIONS.getValue(role), allowed(s, human, p), "$role $p")
        }
    }

    @Test
    fun `enforce with no assignee uses the default`() {
        val op = svc(PermissionMode.ENFORCE, default = RolesDefault.OPERATOR)
        assertEquals(Role.OPERATOR, op.access(human, true).role)
        assertEquals(AccessSource.LEGACY_DEFAULT, op.access(human, true).source)
        // a caller without the admin role (member only) gets nothing, even with the operator default
        assertEquals(AccessSource.NONE, op.access(human, false).source)
        assertFalse(allowed(op, human, Permission.READ, admin = false))
        val none = svc(PermissionMode.ENFORCE, default = RolesDefault.NONE)
        assertEquals(null, none.access(human, true).role)
        assertEquals(Decision.Deny(DENY_NO_ROLE), none.check(none.access(human, true), Permission.READ))
    }

    @Test
    fun `enforce denies a disabled assignee`() {
        val s = svc(PermissionMode.ENFORCE, Fake(mapOf(human to AssigneeAccess(Role.ADMIN, false))))
        assertEquals(AccessSource.ASSIGNEE_DISABLED, s.access(human, true).source)
        for (p in Permission.entries) assertFalse(allowed(s, human, p))
    }

    @Test
    fun `the env AI principal is an AI whatever the store says`() {
        val s = svc(PermissionMode.ENFORCE, Fake(mapOf(ai to AssigneeAccess(Role.ADMIN, true))))
        val a = s.access(ai, true)
        assertEquals(Role.AI, a.role)
        assertEquals(AccessSource.ENV_AI_CONFLICT, a.source)
        assertFalse(allowed(s, ai, Permission.ORDER_MANAGE))
        assertFalse(allowed(s, ai, Permission.BUDGET_OVERRIDE))
        assertTrue(allowed(s, ai, Permission.SPEND_START))
        // matching role: no conflict; disabled or unbound: still AI
        assertEquals(
            AccessSource.ENV_AI,
            svc(PermissionMode.ENFORCE, Fake(mapOf(ai to AssigneeAccess(Role.AI, true)))).access(ai, true).source,
        )
        assertEquals(Role.AI, svc(PermissionMode.ENFORCE, Fake(mapOf(ai to AssigneeAccess(Role.AI, false)))).access(ai, true).role)
        assertEquals(Role.AI, svc(PermissionMode.ENFORCE).access(ai, true).role)
    }

    @Test
    fun `a failing or missing store denies and does not fall back to the default`() {
        for (lookup in listOf(Fake(fail = true), null)) {
            val s = svc(PermissionMode.ENFORCE, lookup)
            val a = s.access(human, true)
            assertEquals(AccessSource.LOOKUP_FAILED, a.source)
            for (p in Permission.entries) assertFalse(allowed(s, human, p))
        }
        assertEquals(Role.AI, svc(PermissionMode.ENFORCE, Fake(fail = true)).access(ai, true).role)
    }

    @Test
    fun `the root approver has no special power in enforce`() {
        val s = svc(PermissionMode.ENFORCE, Fake(mapOf(approver to AssigneeAccess(Role.ADMIN, true))))
        assertFalse(allowed(s, approver, Permission.STOP_DECIDE))
    }

    @Test
    fun `scope is intersected with the role`() {
        val s = svc(PermissionMode.ENFORCE, Fake(mapOf(human to AssigneeAccess(Role.ADMIN, true))))
        assertEquals(Decision.Deny(DENY_SCOPE), s.check(s.access(human, true), Permission.READ, scopeOk = false))
        assertEquals(Decision.Allow, s.check(s.access(human, true), Permission.READ, scopeOk = true))
        val viewer = svc(PermissionMode.ENFORCE, Fake(mapOf(human to AssigneeAccess(Role.VIEWER, true))))
        assertEquals(Decision.Deny(DENY_PERMISSION), viewer.check(viewer.access(human, true), Permission.GOAL_WRITE, scopeOk = true))
    }

    @Test
    fun `the AI principal must differ from the root approver`() {
        assertFailsWith<IllegalArgumentException> { PermissionService(PermissionMode.OFF, RolesDefault.NONE, ai, ai, null) }
    }

    @Test
    fun `nothing identifying is printed`() {
        val a = svc(PermissionMode.ENFORCE).access(human, true)
        assertFalse(a.toString().contains("human"))
    }
}
