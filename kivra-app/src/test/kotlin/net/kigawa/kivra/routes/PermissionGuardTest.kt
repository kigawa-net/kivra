package net.kigawa.kivra.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.AccessSource
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault

class PermissionGuardTest {
    private val ai = "1:i:ai"
    private val approver = "1:i:root"
    private val human = "1:i:human"

    private class Log : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += "I $message" }
        override fun warn(message: String) { lines += "W $message" }
    }

    private class Lookup(val map: Map<String, AssigneeAccess> = emptyMap(), var fail: Boolean = false, var calls: Int = 0) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? {
            calls++
            if (fail) throw IllegalStateException("down $ownerKey")
            return map[ownerKey]
        }
    }

    private class Time(var now: Long = 0) : () -> Long {
        override fun invoke() = now
    }

    private fun guard(mode: PermissionMode, lookup: Lookup?, log: Log = Log(), time: Time = Time(), default: RolesDefault = RolesDefault.OPERATOR) =
        PermissionGuard(PermissionService(mode, default, ai, approver, lookup), log, time)

    private fun PermissionGuard.allows(key: String, p: Permission, admin: Boolean = true, humanOnly: Boolean = false) =
        check(resolve(key, admin), p, "r", humanOnly = humanOnly) == GuardResult.Allow

    @Test
    fun `off reproduces the old rules for every permission and never reads the store`() {
        val store = Lookup()
        val g = guard(PermissionMode.OFF, store)
        for (p in Permission.entries) {
            // the AI: what it could do before (reads, a stop request, the ungated start and budget release)
            assertEquals(p in setOf(Permission.READ, Permission.STOP_REQUEST, Permission.SPEND_START, Permission.BUDGET_OVERRIDE), g.allows(ai, p), "ai $p")
            // the root approver: an operator plus the decision, but no assignee management
            assertEquals(p !in setOf(Permission.ASSIGNEE_MANAGE, Permission.STOP_DECIDE_AUTO), g.allows(approver, p), "root $p")
            // another operator
            assertEquals(p !in setOf(Permission.STOP_DECIDE, Permission.ASSIGNEE_MANAGE, Permission.STOP_DECIDE_AUTO), g.allows(human, p), "op $p")
            // not an admin by today's rule
            assertFalse(g.allows(human, p, admin = false), "non-admin $p")
        }
        assertEquals(0, store.calls)
    }

    @Test
    fun `off - the AI keeps no read-text and no goal-stop, which is what it is refused today`() {
        val g = guard(PermissionMode.OFF, null)
        assertFalse(g.allows(ai, Permission.READ_TEXT))
        assertFalse(g.allows(ai, Permission.GOAL_STOP))
        assertTrue(g.allows(human, Permission.READ_TEXT))
        assertTrue(g.allows(human, Permission.GOAL_STOP))
    }

    @Test
    fun `humanOnly refuses the AI in every mode even though it holds the permission`() {
        val store = Lookup(mapOf("1:i:dbai" to AssigneeAccess(Role.AI, true)))
        assertTrue(guard(PermissionMode.OFF, null).allows(ai, Permission.STOP_REQUEST))
        assertFalse(guard(PermissionMode.OFF, null).allows(ai, Permission.STOP_REQUEST, humanOnly = true))
        assertFalse(guard(PermissionMode.ENFORCE, store).allows(ai, Permission.STOP_REQUEST, humanOnly = true))
        assertFalse(guard(PermissionMode.ENFORCE, store).allows("1:i:dbai", Permission.STOP_REQUEST, humanOnly = true))
        assertTrue(guard(PermissionMode.ENFORCE, store).allows("1:i:dbai", Permission.STOP_REQUEST))
    }

    @Test
    fun `enforce refuses with the fixed code, and a failing store is a refusal`() {
        val store = Lookup(mapOf(human to AssigneeAccess(Role.VIEWER, true)))
        val g = guard(PermissionMode.ENFORCE, store)
        assertEquals(GuardResult.Deny("PERMISSION_DENIED"), g.check(g.resolve(human, true), Permission.ORDER_MANAGE, "r"))
        assertEquals(GuardResult.Allow, g.check(g.resolve(human, true), Permission.READ, "r"))
        store.fail = true
        assertEquals(GuardResult.Deny("NO_ROLE"), g.check(g.resolve(human, true), Permission.READ, "r"))
        assertEquals(GuardResult.Deny("NO_ROLE"), g.check(g.resolve("1:i:other", true), Permission.READ, "r"))
        assertEquals(GuardResult.Deny("SCOPE_DENIED"), guard(PermissionMode.ENFORCE, Lookup(mapOf(human to AssigneeAccess(Role.ADMIN, true)))).let { it.check(it.resolve(human, true), Permission.READ, "r", scopeOk = false) })
    }

    @Test
    fun `enforce - no assignee gets the default role, and none gets nothing`() {
        assertTrue(guard(PermissionMode.ENFORCE, Lookup()).allows(human, Permission.ORDER_MANAGE))
        assertFalse(guard(PermissionMode.ENFORCE, Lookup()).allows(human, Permission.STOP_DECIDE))
        assertFalse(guard(PermissionMode.ENFORCE, Lookup(), default = RolesDefault.NONE).allows(human, Permission.READ))
        assertFalse(guard(PermissionMode.ENFORCE, Lookup(mapOf(human to AssigneeAccess(Role.ADMIN, false)))).allows(human, Permission.READ))
    }

    @Test
    fun `the one-call form looks up and checks`() {
        val g = guard(PermissionMode.ENFORCE, Lookup(mapOf(human to AssigneeAccess(Role.APPROVER, true))))
        assertEquals(GuardResult.Allow, g.check(human, true, Permission.STOP_DECIDE, route = "r"))
        assertIs<GuardResult.Deny>(g.check(human, true, Permission.ORDER_MANAGE, route = "r"))
    }

    @Test
    fun `shadow acts on the old rules and logs only fixed fields`() {
        val log = Log()
        val g = guard(PermissionMode.SHADOW, Lookup(mapOf(human to AssigneeAccess(Role.VIEWER, true))), log)
        val access = g.resolve(human, true)
        assertEquals(GuardResult.Allow, g.check(access, Permission.ORDER_MANAGE, "order.create"))
        assertEquals(
            listOf("I permission shadow: wouldDeny permission=ORDER_MANAGE code=PERMISSION_DENIED source=ASSIGNEE role=VIEWER route=order.create via=rest suppressed=0"),
            log.lines,
        )
        // a legacy refusal is a refusal, and is not a would-deny record
        assertEquals(GuardResult.Deny("PERMISSION_DENIED"), g.check(g.resolve(ai, true), Permission.ORDER_MANAGE, "order.create"))
        assertEquals(1, log.lines.size)
        // what enforce allows is not logged
        assertEquals(GuardResult.Allow, g.check(access, Permission.READ, "order.list"))
        assertEquals(1, log.lines.size)
    }

    @Test
    fun `shadow throttles by permission, code, source and route, and reports the count`() {
        val log = Log()
        val time = Time()
        val g = guard(PermissionMode.SHADOW, Lookup(mapOf(human to AssigneeAccess(Role.VIEWER, true))), log, time)
        repeat(5) { g.check(g.resolve(human, true), Permission.ORDER_MANAGE, "order.create") }
        g.check(g.resolve(human, true), Permission.ORDER_MANAGE, "order.pause") // another route: its own window
        assertEquals(2, log.lines.size)
        time.now = 59_999
        g.check(g.resolve(human, true), Permission.ORDER_MANAGE, "order.create")
        assertEquals(2, log.lines.size)
        time.now = 60_000
        g.check(g.resolve(human, true), Permission.ORDER_MANAGE, "order.create")
        assertEquals(3, log.lines.size)
        assertTrue(log.lines.last().endsWith("suppressed=5"), log.lines.last())
    }

    @Test
    fun `an env AI conflict and a failing store are warned about, throttled, without the key`() {
        val log = Log()
        val time = Time()
        val conflict = guard(PermissionMode.SHADOW, Lookup(mapOf(ai to AssigneeAccess(Role.ADMIN, true))), log, time)
        repeat(3) { conflict.resolve(ai, true) }
        assertEquals(listOf("W permission anomaly: source=ENV_AI_CONFLICT mode=SHADOW via=rest suppressed=0"), log.lines)
        val log2 = Log()
        val failing = guard(PermissionMode.ENFORCE, Lookup(fail = true), log2, time)
        repeat(3) { failing.resolve(human, true) }
        assertEquals(listOf("W permission anomaly: source=LOOKUP_FAILED mode=ENFORCE via=rest suppressed=0"), log2.lines)
        assertFalse((log.lines + log2.lines).any { it.contains(human) || it.contains(ai) })
    }

    @Test
    fun `can reports the permission under the mode and records nothing`() {
        val log = Log()
        val g = guard(PermissionMode.SHADOW, Lookup(mapOf(human to AssigneeAccess(Role.VIEWER, true))), log)
        assertTrue(g.can(g.resolve(human, true), Permission.ORDER_MANAGE)) // the old rules decide in shadow
        assertFalse(g.can(g.resolve(human, true), Permission.STOP_DECIDE))
        assertTrue(g.can(g.resolve(approver, true), Permission.STOP_DECIDE))
        val e = guard(PermissionMode.ENFORCE, Lookup(mapOf(human to AssigneeAccess(Role.VIEWER, true))))
        assertFalse(e.can(e.resolve(human, true), Permission.ORDER_MANAGE))
        assertEquals(emptyList(), log.lines)
    }

    @Test
    fun `GuardAccess does not print an identity`() {
        val g = guard(PermissionMode.OFF, null)
        assertEquals("GuardAccess(isAi=false)", g.resolve(human, true).toString())
        assertEquals(AccessSource.LEGACY_DEFAULT, g.resolve(human, true).let { PermissionGuardProbe.source(it) })
    }
}

/** Reads the effective source of a [GuardAccess] (internal to the main module, visible to its tests). */
private object PermissionGuardProbe {
    fun source(access: GuardAccess): AccessSource = access.effective.source
}
