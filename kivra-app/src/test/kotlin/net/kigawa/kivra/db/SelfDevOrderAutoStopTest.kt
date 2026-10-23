package net.kigawa.kivra.db

import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "op-kigawa"
private const val AI = "ai-principal"
private const val TARGET = "kigawa-net/kivra"
private const val TITLE_MARKER = "AUTO-TITLE-MARKER-XYZ"
private const val POLICY_MARKER = "AUTO-POLICY-MARKER-XYZ"
private val START: Instant = Instant.parse("2026-10-19T10:00:00Z")
private val LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)

/** A clock the test moves. */
private class AutoClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The automatic child stop (ADR 0015 section 4.4) on H2 (MariaDB with `KIVRA_TEST_DB=mariadb`). Each test has its own database, which is
 * closed when the test ends. The tick and the switch are tested in OrderWatchTest and SelfDevOrderSettingsTest.
 */
class SelfDevOrderAutoStopTest {

    private class Fixture(val db: TestDb, val clock: AutoClock) {
        val repo = SelfDevOrderRepository(db.database, clock)
        val goals = SelfDevGoalRepository(db.database, clock)
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db, AutoClock(START))) }

    private fun Fixture.root(): SelfDevOrder =
        (repo.createRoot(OPERATOR, TITLE_MARKER, POLICY_MARKER, TARGET) as CreateRootResult.Created).order

    private fun Fixture.child(parentId: String, key: String, budget: Long = 1_000): SelfDevOrder =
        (repo.createChild(parentId, TITLE_MARKER, POLICY_MARKER, budget, key, OPERATOR, LIMITS) as ChildCreateResult.Created).order

    private fun Fixture.exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

    private fun Fixture.count(sql: String): Int = transaction(db.database) { execQuery(sql) { it.getInt(1) }.single() }

    private fun Fixture.now(orderId: String): SelfDevOrder = assertNotNull(repo.get(orderId))

    private fun Fixture.failures(orderId: String, n: Int) =
        exec("UPDATE selfdev_orders SET consecutive_failed_cycles = ? WHERE order_id = ?", n, orderId)

    private fun Fixture.setState(orderId: String, state: OrderState) = exec("UPDATE selfdev_orders SET state = ? WHERE order_id = ?", state.name, orderId)

    private fun Fixture.stopped(orderId: String): SelfDevOrder = assertIs<AutoStopResult.Stopped>(repo.autoStopChild(orderId)).order

    @Test
    fun `two failed cycles of a child stop it and its grandchild, and one failure decides nothing`() = withFixture { f ->
        val root = f.root()
        val child = f.child(root.orderId, "a")
        val grand = f.child(child.orderId, "a-grand", budget = 500)
        f.failures(child.orderId, 1)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.CYCLE_FAILURES_BELOW_LIMIT), f.repo.autoStopChild(child.orderId))
        assertEquals(OrderState.ACTIVE, f.now(child.orderId).state)
        assertEquals(OrderState.ACTIVE, f.now(grand.orderId).state)

        f.failures(child.orderId, 2)
        val stopped = f.stopped(child.orderId)
        assertEquals(OrderState.STOPPED, stopped.state)
        assertEquals(OrderReason.PARENT_STOPPED, stopped.stateReason)
        assertEquals(OrderState.STOPPED, f.now(grand.orderId).state)
        assertEquals(OrderReason.ANCESTOR_STOPPED, f.now(grand.orderId).stateReason)
        assertEquals(OrderState.ACTIVE, f.now(root.orderId).state)
    }

    @Test
    fun `the root is never decided, and the schema refuses an automatic decision on the root`() = withFixture { f ->
        val root = f.root()
        val child = f.child(root.orderId, "root-check")
        f.failures(root.orderId, 5)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.IS_ROOT), f.repo.autoStopChild(root.orderId))
        assertEquals(OrderState.ACTIVE, f.now(root.orderId).state)
        // The root is not even a candidate of the tick.
        assertEquals(emptyList(), f.repo.autoStopCandidates(10))

        // A row that says the ORDER decided the stop of the root, with the requester and the decider as two principals, is refused by the
        // schema only because of the root rule (the other CHECKs hold: the parent is named, and the requester differs).
        fun insertDecision(orderId: String, targetIsRoot: Int, deciderKind: String, deciderRef: String?) = f.exec(
            "INSERT INTO selfdev_order_stop_requests (request_id, order_id, requested_by, requester_kind, reason_code, order_version, digest, " +
                "state, active_key, decided_by, decider_kind, decider_order_id, decided_at, expires_at, created_at, target_is_root) " +
                "VALUES (?, ?, ?, 'AI', 'CHILD_CYCLE_FAILURES', 1, ?, 'CONSUMED', NULL, ?, ?, ?, NOW(6), NOW(6), NOW(6), ?)",
            Ulid.generate(), orderId, AI, "0".repeat(64), AUTO_STOP_PRINCIPAL, deciderKind, deciderRef, targetIsRoot,
        )
        assertFailsWith<SQLException> { insertDecision(root.orderId, 1, "ORDER", root.orderId) }
        // A decision by a parent for a child is allowed by the schema; the same row without the parent named is refused.
        insertDecision(child.orderId, 0, "ORDER", root.orderId)
        assertFailsWith<SQLException> { insertDecision(child.orderId, 0, "ORDER", null) }
        assertEquals(1, f.count("SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE decider_kind = 'ORDER' AND target_is_root = 0"))
    }

    @Test
    fun `a parent that is not effectively ACTIVE decides nothing, and a stopped or paused ancestor holds the decision too`() = withFixture { f ->
        val root = f.root()
        val child = f.child(root.orderId, "b")
        val grand = f.child(child.orderId, "b-grand", budget = 500)
        f.failures(grand.orderId, 2)
        f.setState(child.orderId, OrderState.PAUSED)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.PARENT_NOT_ACTIVE), f.repo.autoStopChild(grand.orderId))

        f.setState(child.orderId, OrderState.ACTIVE)
        f.setState(root.orderId, OrderState.PAUSED)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.PARENT_NOT_ACTIVE), f.repo.autoStopChild(grand.orderId))
        assertEquals(OrderState.ACTIVE, f.now(grand.orderId).state)

        // A child that is itself STOPPED is not decided again (its parent is ACTIVE here).
        f.setState(root.orderId, OrderState.ACTIVE)
        f.setState(child.orderId, OrderState.ACTIVE)
        f.setState(grand.orderId, OrderState.STOPPED)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.CHILD_NOT_STOPPABLE), f.repo.autoStopChild(grand.orderId))
    }

    @Test
    fun `a child's own grandchild is decided by its direct parent, which names itself, and the ancestors stay ACTIVE`() = withFixture { f ->
        val root = f.root()
        val child = f.child(root.orderId, "g")
        val grand = f.child(child.orderId, "g-grand", budget = 500)
        f.failures(grand.orderId, 2)
        f.stopped(grand.orderId)
        assertEquals(OrderState.ACTIVE, f.now(child.orderId).state)
        assertEquals(OrderState.ACTIVE, f.now(root.orderId).state)
        assertEquals(
            1,
            f.count(
                "SELECT COUNT(*) FROM selfdev_order_events WHERE kind = 'STOPPED' AND order_id = '${grand.orderId}' " +
                    "AND actor_kind = 'SYSTEM' AND parent_order_id = '${child.orderId}' AND depth = 2",
            ),
        )
    }

    @Test
    fun `a parent decides one child a day - the second is held back and told once, and the next UTC day resets it`() = withFixture { f ->
        val root = f.root()
        val a = f.child(root.orderId, "c-a")
        val b = f.child(root.orderId, "c-b")
        f.failures(a.orderId, 2)
        f.failures(b.orderId, 2)
        f.stopped(a.orderId)
        assertEquals(AutoStopResult.RateLimited(root.orderId, recorded = true), f.repo.autoStopChild(b.orderId))
        assertEquals(AutoStopResult.RateLimited(root.orderId, recorded = false), f.repo.autoStopChild(b.orderId))
        assertEquals(OrderState.ACTIVE, f.now(b.orderId).state)
        assertEquals(1, f.count("SELECT COUNT(*) FROM selfdev_order_events WHERE order_id = '${b.orderId}' AND kind = 'AUTO_STOP_RATE_LIMITED'"))

        f.clock.now = START.plus(Duration.ofDays(1))
        f.stopped(b.orderId)
        assertEquals(OrderState.STOPPED, f.now(b.orderId).state)
    }

    @Test
    fun `a tree decides two children a day, a third is held back, and a stopped descendant is not stopped again`() = withFixture { f ->
        val root = f.root()
        val a = f.child(root.orderId, "t-a")
        val b = f.child(root.orderId, "t-b")
        val g = f.child(a.orderId, "t-g", budget = 500)
        val h = f.child(b.orderId, "t-h", budget = 500)
        listOf(a, b, g, h).forEach { f.failures(it.orderId, 2) }
        // One decision by B on H, and one by A on G: two in the tree, each by a different parent.
        f.stopped(h.orderId)
        f.stopped(g.orderId)
        // The root's decision on A is the third in the tree, so it is held back.
        assertEquals(AutoStopResult.RateLimited(root.orderId, recorded = true), f.repo.autoStopChild(a.orderId))
        assertEquals(OrderState.ACTIVE, f.now(a.orderId).state)

        f.clock.now = START.plus(Duration.ofDays(1))
        // The next day A is decided; its descendant G was stopped already, so only A changes.
        val stopped = f.stopped(a.orderId)
        assertEquals(OrderState.STOPPED, stopped.state)
        assertEquals(OrderState.STOPPED, f.now(g.orderId).state)
        // G was stopped on the first day as the target of A's decision (its own parent), so its reason is the parent's.
        assertEquals(OrderReason.PARENT_STOPPED, f.now(g.orderId).stateReason)
    }

    @Test
    fun `a pending request in the tree holds the decision, and the person's request is not taken over`() = withFixture { f ->
        val root = f.root()
        val a = f.child(root.orderId, "p-a")
        val b = f.child(root.orderId, "p-b")
        f.failures(a.orderId, 2)
        val request = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            f.repo.requestStop(b.orderId, f.now(b.orderId).version, OPERATOR, OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        ).value
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.PENDING_REQUEST), f.repo.autoStopChild(a.orderId))
        assertEquals(OrderState.ACTIVE, f.now(a.orderId).state)
        assertEquals(StopRequestState.PENDING, f.repo.stopRequest(request.requestId)!!.state)
        assertEquals(OrderState.STOP_REQUESTED, f.now(b.orderId).state)
    }

    @Test
    fun `the automatic decision is audited as a SYSTEM decision by the parent, with fixed codes and no title or policy`() = withFixture { f ->
        val root = f.root()
        val a = f.child(root.orderId, "e-a")
        val g = f.child(a.orderId, "e-g", budget = 500)
        f.failures(a.orderId, 2)
        f.stopped(a.orderId)
        // The stop of A: STOP_APPROVED and STOPPED name the root as the decider and the depth 1; no actor reference, no free text.
        for (kind in listOf("STOP_APPROVED", "STOPPED")) {
            assertEquals(
                1,
                f.count(
                    "SELECT COUNT(*) FROM selfdev_order_events WHERE kind = '$kind' AND order_id = '${a.orderId}' " +
                        "AND reason_code = 'CHILD_CYCLE_FAILURES' AND actor_kind = 'SYSTEM' AND actor_ref IS NULL " +
                        "AND parent_order_id = '${root.orderId}' AND depth = 1 AND request_id IS NOT NULL",
                ),
            )
        }
        assertEquals(
            1,
            f.count(
                "SELECT COUNT(*) FROM selfdev_order_events WHERE kind = 'STOPPED' AND order_id = '${g.orderId}' " +
                    "AND reason_code = 'ANCESTOR_STOPPED' AND actor_kind = 'SYSTEM' AND parent_order_id = '${a.orderId}' AND depth = 2",
            ),
        )
        // The request row is the parent's CONSUMED decision by the fixed principal, and not a root request.
        assertEquals(
            1,
            f.count(
                "SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE state = 'CONSUMED' AND requester_kind = 'SYSTEM' " +
                    "AND requested_by = '$AUTO_STOP_PRINCIPAL' AND decided_by = '$AUTO_STOP_PRINCIPAL' AND decider_kind = 'ORDER' " +
                    "AND decider_order_id = '${root.orderId}' AND target_is_root = 0 AND reason_code = 'CHILD_CYCLE_FAILURES'",
            ),
        )
        // No title or policy in any event or request row.
        val text = transaction(f.db.database) {
            listOf("selfdev_order_events", "selfdev_order_stop_requests").flatMap { table ->
                execQuery("SELECT * FROM $table") { rs -> (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() } }
            }
        }.joinToString("\n")
        assertTrue(!text.contains(TITLE_MARKER) && !text.contains(POLICY_MARKER))
    }

    @Test
    fun `a request can never carry the automatic reason, and the automatic one is not requestable`() = withFixture { f ->
        val root = f.root()
        val a = f.child(root.orderId, "r-a")
        assertFailsWith<IllegalArgumentException> {
            f.repo.requestStop(a.orderId, f.now(a.orderId).version, OPERATOR, OrderActor.HUMAN, StopRequestReason.CHILD_CYCLE_FAILURES)
        }
        assertEquals(
            listOf(StopRequestReason.OPERATOR_REQUEST, StopRequestReason.RUNAWAY_SUSPECTED, StopRequestReason.BUDGET_CONCERN, StopRequestReason.SCOPE_CONCERN),
            StopRequestReason.REQUESTABLE,
        )
        assertEquals(OrderState.ACTIVE, f.now(a.orderId).state)
    }

    @Test
    fun `children of a PAUSED parent do not fill the batch ahead of an eligible child, so the eligible one is a candidate`() = withFixture { f ->
        val root = f.root()
        val paused = f.child(root.orderId, "cand-paused")
        val wide = ChildLimits(maxDepth = 2, maxChildren = 100, maxTree = 500)
        val crowd = (1..55).map { i ->
            (f.repo.createChild(paused.orderId, TITLE_MARKER, POLICY_MARKER, 1, "cand-crowd-$i", OPERATOR, wide) as ChildCreateResult.Created)
                .order.orderId
        }
        f.clock.now = START.plusSeconds(1)
        val eligible = (f.repo.createChild(root.orderId, TITLE_MARKER, POLICY_MARKER, 1, "cand-eligible", OPERATOR, wide) as ChildCreateResult.Created)
            .order
        f.setState(paused.orderId, OrderState.PAUSED)
        crowd.forEach { f.failures(it, CYCLE_FAILURE_LIMIT) }
        f.failures(eligible.orderId, CYCLE_FAILURE_LIMIT)

        // The crowd is older (updated_at) and sits under a parent that is not ACTIVE: autoStopChild would answer PARENT_NOT_ACTIVE for each.
        assertEquals(listOf(eligible.orderId), f.repo.autoStopCandidates(50))
        assertIs<AutoStopResult.NotDecided>(f.repo.autoStopChild(crowd.first()))
        assertEquals(OrderState.ACTIVE, f.now(crowd.first()).state)
        assertEquals(OrderState.STOPPED, f.stopped(eligible.orderId).state)
    }
}
