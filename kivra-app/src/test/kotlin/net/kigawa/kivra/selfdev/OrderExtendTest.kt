package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.AutoCycleResult
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.ExtendInvalid
import net.kigawa.kivra.db.ExtendResult
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

/** A clock the test moves by hand. (A private class of another test file of this package cannot be shared, so it is repeated here.) */
private class ExtendTestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

private val START: Instant = Instant.parse("2026-11-10T00:00:00Z")
private const val CAP = 3

/**
 * The operator's extension of the root order (ADR 0015 addendum 2026-11-10, section 2.6) on the real database (TestDb). The checks, the lift of a
 * LIMIT_REACHED or EXPIRED pause, the resume that follows, and the audit event are all tested here; the route's answers are in
 * SelfDevOrderRoutesTest.
 */
class OrderExtendTest {

    private inner class Harness(val db: TestDb, val clock: ExtendTestClock = ExtendTestClock(START)) {
        val orders = SelfDevOrderRepository(db.database, clock)
        val project: String = ProjectRepository(db.database, clock).create("Extend", null).projectId

        fun newOrder(maxCycles: Int = 1): SelfDevOrder =
            (orders.createRoot("operator", "Order title", "Improve the docs", "kigawa-net/kivra", maxCycles, project) as CreateRootResult.Created).order

        fun version(orderId: String): Long = assertNotNull(orders.get(orderId)).version

        fun extend(orderId: String, addCycles: Int? = null, extendDays: Int? = null, version: Long = version(orderId), cap: Int = CAP): ExtendResult =
            orders.extendByOperator(orderId, version, "operator", addCycles, extendDays, cap)

        fun startCycle(orderId: String): String = (orders.startCycle(orderId, version(orderId), "operator") as OrderResult.Moved).value.goalId

        /** The cycle's root task ends DONE, and the outcome is recorded: a cycle at the limit pauses the order with LIMIT_REACHED. */
        fun finishCycle(taskId: String) {
            transaction(db.database) { execUpdate("UPDATE selfdev_tasks SET state = 'DONE' WHERE task_id = ?", taskId) }
            orders.evaluateEndedCycles(10)
        }
    }

    private data class EventRow(val kind: String, val reason: String?, val actorKind: String, val payload: String?)

    private fun eventsOf(db: TestDb, orderId: String): List<EventRow> = transaction(db.database) {
        execQuery(
            "SELECT kind, reason_code, actor_kind, payload_json FROM selfdev_order_events WHERE order_id = ? ORDER BY occurred_at, event_id",
            orderId,
        ) { rs -> EventRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
    }

    private fun at(days: Long): LocalDateTime = LocalDateTime.ofInstant(START.plus(Duration.ofDays(days)), ZoneOffset.UTC)

    @Test
    fun `an active order takes more cycles and a later expiry, and its event keeps the values before and after`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 1)
        val moved = assertIs<ExtendResult.Moved>(h.extend(order.orderId, addCycles = 2, extendDays = 3))

        assertEquals(OrderState.ACTIVE, moved.order.state)
        assertEquals(3, moved.order.maxCycles)
        // The base is the later of now and the current expiry (day 14), so three days more is day 17.
        assertEquals(at(17), moved.order.expiresAt)
        assertEquals(order.version + 1, moved.order.version)
        val event = eventsOf(db, order.orderId).single { it.kind == "EXTENDED" }
        assertEquals("OPERATOR_EXTENDED", event.reason)
        assertEquals("HUMAN", event.actorKind)
        assertNotNull(event.payload).also {
            assertTrue(it.contains("\"maxCycles\":{\"from\":1,\"to\":3}"), it)
            assertTrue(it.contains("\"expiresAt\":{\"from\":\"${at(14)}\",\"to\":\"${at(17)}\"}"), it)
        }
    }

    @Test
    fun `a PAUSED order keeps its reason, and an operator's pause is not lifted by an extension`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 1)
        h.orders.pauseByOperator(order.orderId, order.version, "operator")
        val moved = assertIs<ExtendResult.Moved>(h.extend(order.orderId, addCycles = 1))

        assertEquals(OrderState.PAUSED, moved.order.state)
        assertEquals(OrderReason.OPERATOR_PAUSED, moved.order.stateReason)
        assertEquals(2, moved.order.maxCycles)
        assertIs<OrderResult.Moved<*>>(h.orders.resumeByOperator(order.orderId, moved.order.version, "operator"))
    }

    @Test
    fun `a LIMIT_REACHED pause is lifted by an extension, and only then can the operator resume it`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 1)
        val taskId = h.startCycle(order.orderId)
        h.finishCycle(taskId)
        val limited = assertNotNull(h.orders.get(order.orderId))
        assertEquals(OrderState.PAUSED, limited.state)
        assertEquals(OrderReason.LIMIT_REACHED, limited.stateReason)

        // Before any extension the resume is still refused.
        assertEquals(OrderResult.Refused(OrderConflict.RESUME_NOT_ALLOWED), h.orders.resumeByOperator(order.orderId, limited.version, "operator"))

        val extended = assertIs<ExtendResult.Moved>(h.extend(order.orderId, addCycles = 1))
        assertEquals(OrderState.PAUSED, extended.order.state)
        assertEquals(OrderReason.LIMIT_REACHED, extended.order.stateReason)
        assertEquals(2, extended.order.maxCycles)

        val resumed = assertIs<OrderResult.Moved<SelfDevOrder>>(h.orders.resumeByOperator(order.orderId, extended.order.version, "operator"))
        assertEquals(OrderState.ACTIVE, resumed.value.state)
        assertNull(resumed.value.stateReason)
        // The next cycle can start now.
        h.startCycle(order.orderId)
        assertEquals(2, assertNotNull(h.orders.get(order.orderId)).cyclesStarted)
    }

    @Test
    fun `an extension that does not lift the cycle limit is refused, and nothing is written`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 1)
        h.finishCycle(h.startCycle(order.orderId))
        val before = assertNotNull(h.orders.get(order.orderId))

        // Days alone do not lift a cycle limit.
        assertEquals(ExtendResult.Refused(OrderConflict.EXTENSION_DOES_NOT_LIFT), h.extend(order.orderId, extendDays = 2))
        assertEquals(before, h.orders.get(order.orderId))
        assertEquals(0, eventsOf(db, order.orderId).count { it.kind == "EXTENDED" })
    }

    @Test
    fun `an EXPIRED order is extended from now, can be resumed, and its expiry stays within the ceiling`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 1)
        h.clock.advance(Duration.ofDays(15))
        assertEquals(1, h.orders.expireDueOrders(10))
        val expired = assertNotNull(h.orders.get(order.orderId))
        assertEquals(OrderReason.EXPIRED, expired.stateReason)

        // From now (day 15), fourteen days more: day 29, inside the ceiling of day 30 (created on day 0).
        val extended = assertIs<ExtendResult.Moved>(h.extend(order.orderId, extendDays = 14))
        assertEquals(OrderState.PAUSED, extended.order.state)
        assertEquals(at(29), extended.order.expiresAt)

        assertIs<OrderResult.Moved<*>>(h.orders.resumeByOperator(order.orderId, extended.order.version, "operator"))
        assertEquals(OrderState.ACTIVE, assertNotNull(h.orders.get(order.orderId)).state)
    }

    @Test
    fun `a cap pause without an extension is still not resumed, and an extension then lets the operator resume it`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 3)
        h.finishCycle(h.startCycle(order.orderId))
        // The automatic start pauses at the cap (1), with LIMIT_REACHED, while max_cycles still has room.
        assertIs<AutoCycleResult.LimitPaused>(h.orders.startCycleAutomatically(order.orderId, cap = 1))
        val paused = assertNotNull(h.orders.get(order.orderId))
        assertEquals(OrderReason.LIMIT_REACHED, paused.stateReason)
        assertEquals(OrderResult.Refused(OrderConflict.RESUME_NOT_ALLOWED), h.orders.resumeByOperator(order.orderId, paused.version, "operator"))

        val extended = assertIs<ExtendResult.Moved>(h.extend(order.orderId, addCycles = 1, cap = 4))
        assertIs<OrderResult.Moved<*>>(h.orders.resumeByOperator(order.orderId, extended.order.version, "operator"))
    }

    @Test
    fun `the cap refuses a higher max_cycles, and nothing is written`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 2)
        assertEquals(ExtendResult.Invalid(ExtendInvalid.MAX_CYCLES_OVER_CAP), h.extend(order.orderId, addCycles = 2, cap = 3))
        assertEquals(order.version, h.version(order.orderId))
        assertEquals(2, assertNotNull(h.orders.get(order.orderId)).maxCycles)
    }

    @Test
    fun `an expiry more than 30 days after the creation is refused`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        // Day 14 + 14 = day 28 is inside the ceiling of day 30.
        val first = assertIs<ExtendResult.Moved>(h.extend(order.orderId, extendDays = 14))
        assertEquals(at(28), first.order.expiresAt)
        // The next fourteen days would reach day 42.
        assertEquals(ExtendResult.Invalid(ExtendInvalid.EXPIRY_OVER_CEILING), h.extend(order.orderId, extendDays = 14))
        assertEquals(first.order.version, h.version(order.orderId))
    }

    @Test
    fun `a child is refused, because only the root order is extended`() = TestDb().use { db ->
        val h = Harness(db)
        val root = h.newOrder()
        val child = assertIs<ChildCreateResult.Created>(
            h.orders.createChild(root.orderId, "Child", "Improve the docs", 1_000, "key-1", "operator", ChildLimits(2, 5, 10)),
        ).order
        assertEquals(ExtendResult.Refused(OrderConflict.NOT_ROOT), h.extend(child.orderId, addCycles = 1))
        assertEquals(child.version, h.version(child.orderId))
    }

    @Test
    fun `a pending stop request refuses the extension, and a stopped order is terminal`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val request = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            h.orders.requestStop(order.orderId, order.version, "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        ).value
        assertEquals(ExtendResult.Refused(OrderConflict.STOP_ALREADY_PENDING), h.extend(order.orderId, addCycles = 1))

        // The approval stops the order; a stopped order takes no extension.
        assertIs<OrderResult.Moved<*>>(h.orders.approveStop(request.requestId, "1:i:approver", request.digest))
        assertEquals(OrderState.STOPPED, assertNotNull(h.orders.get(order.orderId)).state)
        assertEquals(ExtendResult.Refused(OrderConflict.TERMINAL), h.extend(order.orderId, addCycles = 1))
    }

    @Test
    fun `a stale version is refused with nothing written, and an unknown order is not found`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        assertEquals(ExtendResult.StaleVersion, h.extend(order.orderId, addCycles = 1, version = order.version + 7))
        assertEquals(order.version, h.version(order.orderId))
        assertEquals(1, assertNotNull(h.orders.get(order.orderId)).maxCycles)
        assertEquals(ExtendResult.NotFound, h.extend("01AAAAAAAAAAAAAAAAAAAAAAAA", addCycles = 1, version = 0L))
        assertEquals(0, eventsOf(db, order.orderId).count { it.kind == "EXTENDED" })
    }

    @Test
    fun `an extension changes only the cycle limit and the expiry, not the budgets or the failure count`() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val moved = assertIs<ExtendResult.Moved>(h.extend(order.orderId, addCycles = 1, extendDays = 1))
        assertEquals(order.orderBudgetJpy, moved.order.orderBudgetJpy)
        assertEquals(order.cycleBudgetJpy, moved.order.cycleBudgetJpy)
        assertEquals(order.consecutiveFailedCycles, moved.order.consecutiveFailedCycles)
        assertEquals(order.spentJpy, moved.order.spentJpy)
        assertEquals(order.targetRepo, moved.order.targetRepo)
        assertEquals(order.title, moved.order.title)
        assertEquals(order.policy, moved.order.policy)
    }
}
