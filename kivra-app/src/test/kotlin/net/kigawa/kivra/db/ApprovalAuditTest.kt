package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.policy.*
import org.jetbrains.exposed.sql.transactions.transaction

private const val AGENT = "01AGENT0000000000000000000"
private fun human(subject: String = "kc-user-1", expiresAt: Instant = Instant.now().plusSeconds(600)) =
    VerifiedHuman.issue(subject, "https://idp.test/realms/kivra", expiresAt)

private class Fixture(val db: TestDb) {
    val audits = db.audit
    val outbox = db.outbox
    private val database = db.database
    val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
    val runId = run.runId.value
    val principal = Principal(runId, AGENT, Role.DEVELOPER, setOf("k8s.*", "fusha.*", "approval.*", "audit.*", "policy.*"))
    val approvals = ApprovalRepository(db.database, db.audit)
    val failingAudit = object : AuditRepository() {
        override fun append(tx: org.jetbrains.exposed.sql.Transaction, runId: RunId, entry: AuditEntry): String = throw IllegalStateException("audit store down")
    }
    val sink = DbAuditSink(db.database, db.audit)
    val executed = AtomicInteger()
    val policy = PolicyEngine.parse(
        """{"version":1,"rules":[
          {"action":"k8s.apply","resource":"ns:prod","roles":["DEVELOPER"],"risk":"HIGH"},
          {"action":"fusha.comment","resource":"*","roles":["DEVELOPER"],"risk":"LOW"}]}""",
    )
    fun binding(a: String) = ToolBinding(
        StrictParamsDecoder(a, setOf("namespace")) { "ns:" + it.getValue("namespace") },
        ToolHandler { _, _ -> executed.incrementAndGet(); "ok" },
    )
    fun gateway(audit: AuditSink = sink, extra: Map<String, ToolBinding> = emptyMap()) = ToolGateway(
        verifier = { if (it == "tok") principal else null },
        status = { _, _ -> true },
        engine = policy,
        approvals = approvals,
        audit = audit,
        bindings = mapOf("k8s.apply" to binding("k8s.apply"), "fusha.comment" to binding("fusha.comment")) + extra,
    )
    fun apply(approval: String? = null, ns: String = "prod") =
        ToolRequest("tok", "k8s.apply", "ns:$ns", mapOf("namespace" to ns), approval)
    fun digest(ns: String = "prod") =
        OperationDigest.of(principal, CanonicalCommand.of("k8s.apply", "ns:$ns", mapOf("namespace" to ns)), policy.policyHash)
    fun requestApproval(ns: String = "prod", ttl: Long = 600) =
        approvals.request(principal, digest(ns), "k8s.apply", "ns:$ns", ttl)
    fun results(result: String) = db.count("audit_events", "result = '$result'")
    fun reaper(offset: Duration, phase: ExecutionPhaseAuthority = ExecutionPhaseAuthority { ExecutionPhase.NOT_STARTED }) =
        OrphanIntentReaper(database, audits, phase, Clock.offset(Clock.systemUTC(), offset))
    fun chainOk() = transaction(database) { audits.verifyChain(this, run.runId) }
    fun expire(id: String) = transaction(db.database) {
        execUpdate("UPDATE approvals SET expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE approval_id = ?", id)
    }
}

class ApprovalAuditTest {
    private fun fixture(body: Fixture.() -> Unit) = TestDb().use { Fixture(it).body() }

    @Test
    fun `V3 creates approvals and leaves V1 V2 tables intact`() = fixture {
        assertEquals(0, db.count("approvals"))
        val versions = transaction(db.database) { execQuery("SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank") { it.getString(1) } }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18"), versions)
    }

    @Test
    fun `approval is bound to the digest, single use, and consumed only after human approval`() = fixture {
        val a = requestApproval()
        assertEquals(ApprovalStatus.PENDING, a.status)
        assertFalse(approvals.consume(a.approvalId, digest(), "d-test"), "pending approval must not be consumable")
        assertTrue(approvals.approve(a.approvalId, human()))
        assertFalse(approvals.consume(a.approvalId, digest("dev"), "d-test"), "digest mismatch")
        assertEquals(ApprovalStatus.APPROVED, approvals.get(a.approvalId)!!.status)
        assertTrue(approvals.consume(a.approvalId, digest(), "d-test"))
        assertFalse(approvals.consume(a.approvalId, digest(), "d-test"), "reuse")
        assertEquals(ApprovalStatus.CONSUMED, approvals.get(a.approvalId)!!.status)
        assertFalse(approvals.consume("nope", digest(), "d-test"))
        assertTrue(chainOk())
    }

    @Test
    fun `expired approvals cannot be approved or consumed and are swept to EXPIRED`() = fixture {
        val a = requestApproval()
        val b = requestApproval(ns = "other")
        assertTrue(approvals.approve(b.approvalId, human()))
        expire(a.approvalId); expire(b.approvalId)
        assertFalse(approvals.approve(a.approvalId, human()))
        assertFalse(approvals.consume(b.approvalId, digest("other"), "d-test"))
        assertEquals(2, approvals.expireDue())
        assertEquals(ApprovalStatus.EXPIRED, approvals.get(a.approvalId)!!.status)
        assertEquals(ApprovalStatus.EXPIRED, approvals.get(b.approvalId)!!.status)
        assertEquals(2, db.count("audit_events", "action = 'approval.expire' AND result = 'EXPIRED'"), "each expiry is audited")
        assertTrue(chainOk())
    }

    @Test
    fun `rejected or revoked approvals can never be consumed`() = fixture {
        val a = requestApproval()
        assertTrue(approvals.reject(a.approvalId, human(), "no"))
        assertFalse(approvals.approve(a.approvalId, human()), "rejected stays rejected")
        assertFalse(approvals.consume(a.approvalId, digest(), "d-test"))
        val b = requestApproval(ns = "x")
        assertTrue(approvals.approve(b.approvalId, human()))
        assertTrue(approvals.reject(b.approvalId, human(), "revoke"))
        assertFalse(approvals.consume(b.approvalId, digest("x"), "d-test"))
        assertEquals("no", approvals.get(a.approvalId)!!.decisionReason)
    }

    @Test
    fun `four-eyes the requester cannot approve its own request and agents have no approver identity`() = fixture {
        val a = requestApproval()
        assertFalse(approvals.approve(a.approvalId, human(AGENT)), "approver == requester")
        assertFalse(approvals.reject(a.approvalId, human(AGENT)))
        for (bad in listOf(" ", "", "bad\nsubject")) {
            assertFailsWith<IllegalArgumentException>(bad) { VerifiedHuman.issue(bad, "https://idp.test", Instant.now().plusSeconds(60)) }
        }
        assertEquals(ApprovalStatus.PENDING, approvals.get(a.approvalId)!!.status)
        // the schema itself refuses decided_by == requester
        assertFailsWith<Exception> {
            transaction(db.database) { execUpdate("UPDATE approvals SET decided_by = requester_agent_id WHERE approval_id = ?", a.approvalId) }
        }
    }

    @Test
    fun `concurrent consumption succeeds exactly once`() = fixture {
        val a = requestApproval()
        assertTrue(approvals.approve(a.approvalId, human()))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        val futures = (1..8).map { pool.submit { start.await(); if (approvals.consume(a.approvalId, digest(), "d-test")) wins.incrementAndGet() } }
        start.countDown(); futures.forEach { it.get() }; pool.shutdown()
        assertEquals(1, wins.get())
    }

    @Test
    fun `request is idempotent per run and digest`() = fixture {
        assertEquals(requestApproval().approvalId, requestApproval().approvalId)
        assertEquals(1, db.count("approvals"))
    }

    @Test
    fun `approval events are written to the hash chain`() = fixture {
        val a = requestApproval()
        approvals.approve(a.approvalId, human())
        assertEquals(1, db.count("audit_events", "action = 'approval.request' AND approval_id = '${a.approvalId}'"))
        assertEquals(1, db.count("audit_events", "action = 'approval.decide' AND result = 'APPROVED' AND actor = 'human:kc-user-1'"))
        assertTrue(chainOk())
    }

    @Test
    fun `gateway end to end High operation needs approval and cannot reuse it`() = fixture {
        val g = gateway()
        val first = g.call(apply())
        assertIs<ToolResult.ApprovalRequired>(first)
        assertEquals(0, executed.get())
        val a = approvals.request(principal, first.digest, "k8s.apply", "ns:prod", 600)
        assertIs<ToolResult.Denied>(g.call(apply(a.approvalId)), "not approved yet")
        approvals.approve(a.approvalId, human())
        assertIs<ToolResult.Denied>(g.call(apply(a.approvalId, ns = "dev")), "other operation")
        assertIs<ToolResult.Success>(g.call(apply(a.approvalId)))
        assertEquals(1, executed.get())
        assertIs<ToolResult.Denied>(g.call(apply(a.approvalId)), "reuse")
        assertEquals(1, executed.get())
        assertEquals(1, results("EXECUTED"))
        assertTrue(chainOk())
    }

    @Test
    fun `INTENT_ALLOWED that cannot be stored denies and nothing executes`() = fixture {
        // sink for a run that does not exist: the insert is rejected -> false -> fail-closed
        val ghost = Principal(Ulid.generate(), AGENT, Role.DEVELOPER, principal.scopes)
        val g = ToolGateway({ ghost }, { _, _ -> true }, policy, approvals, sink, mapOf("fusha.comment" to binding("fusha.comment")))
        val r = g.call(ToolRequest("t", "fusha.comment", "ns:x", mapOf("namespace" to "x")))
        assertIs<ToolResult.Denied>(r)
        assertEquals(0, executed.get())
        // and a broken DB behind the sink
        val down = AuditSink { throw IllegalStateException("db down") }
        assertIs<ToolResult.Denied>(gateway(down).call(ToolRequest("tok", "fusha.comment", "ns:x", mapOf("namespace" to "x"))))
        assertEquals(0, executed.get())
    }

    @Test
    fun `sink writes chained events and refuses records it cannot attach to a run`() = fixture {
        fun rec(o: AuditOutcome, run: String? = runId) = AuditRecord("d1", run, AGENT, "k8s.apply", "ns:prod", o, "r", null, "dg")
        assertTrue(sink.record(rec(AuditOutcome.INTENT_ALLOWED)))
        assertTrue(sink.record(rec(AuditOutcome.EXECUTED)))
        assertFalse(sink.record(rec(AuditOutcome.DENIED, run = null)))
        assertFalse(sink.record(rec(AuditOutcome.DENIED, run = Ulid.generate())))
        assertTrue(chainOk())
        // tampering is detected
        transaction(db.database) { execUpdate("UPDATE audit_events SET result = 'FAILED' WHERE result = 'EXECUTED'") }
        assertFalse(chainOk())
    }

    @Test
    fun `an Agent cannot reach approval or audit through the gateway`() = fixture {
        val evil = listOf("approval.approve", "approval.grant", "approval.issue", "audit.delete", "policy.update")
            .associateWith { binding(it) }
        val g = gateway(extra = evil)
        val a = requestApproval()
        for (act in evil.keys) {
            val r = g.call(ToolRequest("tok", act, "ns:prod", mapOf("namespace" to "prod")))
            assertIs<ToolResult.Denied>(r, act)
        }
        assertEquals(0, executed.get())
        assertEquals(ApprovalStatus.PENDING, approvals.get(a.approvalId)!!.status)
    }

    @Test
    fun `orphan intent without approval is closed as UNKNOWN for reconciliation`() = fixture {
        assertTrue(sink.record(AuditRecord("d-orphan", runId, AGENT, "fusha.comment", "ns:x", AuditOutcome.INTENT_ALLOWED, "r", null)))
        assertTrue(sink.record(AuditRecord("d-done", runId, AGENT, "fusha.comment", "ns:x", AuditOutcome.INTENT_ALLOWED, "r", null)))
        assertTrue(sink.record(AuditRecord("d-done", runId, AGENT, "fusha.comment", "ns:x", AuditOutcome.EXECUTED, "r", null)))
        assertTrue(reaper(Duration.ZERO).findOrphans(300).isEmpty(), "within grace")
        val late = reaper(Duration.ofHours(1))
        assertEquals(listOf("d-orphan"), late.findOrphans(300).map { it.decisionId })
        assertEquals(listOf("UNKNOWN"), late.reap(300).map { it.second })
        assertTrue(late.reap(300).isEmpty(), "idempotent")
        assertEquals(1, db.count("audit_events", "decision_id = 'd-orphan' AND result = 'UNKNOWN' AND actor = 'system:orphan-reaper'"))
        assertTrue(chainOk())
    }

    @Test
    fun `orphan intent with consumed approval is UNKNOWN, with unconsumed approval is ABANDONED`() = fixture {
        val unused = requestApproval(ns = "a"); approvals.approve(unused.approvalId, human())
        val used = requestApproval(ns = "b"); approvals.approve(used.approvalId, human())
        assertTrue(approvals.consume(used.approvalId, digest("b"), "d-test"))
        sink.record(AuditRecord("d-unused", runId, AGENT, "k8s.apply", "ns:a", AuditOutcome.INTENT_ALLOWED, "r", unused.approvalId))
        sink.record(AuditRecord("d-used", runId, AGENT, "k8s.apply", "ns:b", AuditOutcome.INTENT_ALLOWED, "r", used.approvalId))
        val res = reaper(Duration.ofHours(1)).reap(300).associate { it.first.decisionId to it.second }
        assertEquals(mapOf("d-unused" to "ABANDONED", "d-used" to "UNKNOWN"), res)
        assertEquals(ApprovalStatus.APPROVED, approvals.get(unused.approvalId)!!.status, "approval stays usable")
        assertTrue(chainOk())
    }

    @Test
    fun `consume and expiry are audited in the same transaction and roll back when the audit cannot be written`() = fixture {
        val a = requestApproval()
        assertTrue(approvals.approve(a.approvalId, human()))
        val broken = ApprovalRepository(db.database, failingAudit)
        assertFalse(broken.consume(a.approvalId, digest(), "d-x"), "no audit, no consumption")
        assertEquals(ApprovalStatus.APPROVED, approvals.get(a.approvalId)!!.status)
        assertEquals(0, db.count("audit_events", "action = 'approval.consume'"))
        // a successful consume writes its audit row in the same transaction
        assertTrue(approvals.consume(a.approvalId, digest(), "d-x"))
        assertEquals(1, db.count("audit_events", "action = 'approval.consume' AND result = 'CONSUMED' AND decision_id = 'd-x'"))
        assertTrue(chainOk())

        val b = requestApproval(ns = "exp")
        expire(b.approvalId)
        assertFailsWith<IllegalStateException> { ApprovalRepository(db.database, failingAudit).expireDue() }
        assertEquals(ApprovalStatus.PENDING, approvals.get(b.approvalId)!!.status, "expiry rolled back with its audit")
        assertEquals(1, approvals.expireDue())
        assertEquals(ApprovalStatus.EXPIRED, approvals.get(b.approvalId)!!.status)
    }

    @Test
    fun `a stale human assertion cannot approve and only issued assertions are accepted`() = fixture {
        val a = requestApproval()
        assertFalse(approvals.approve(a.approvalId, human(expiresAt = Instant.now().minusSeconds(1))), "expired assertion")
        assertEquals(ApprovalStatus.PENDING, approvals.get(a.approvalId)!!.status)
        // a verifier port that rejects an invalid credential yields no approver at all
        val verifier = HumanAssertionVerifier { c -> if (c == "good") human() else null }
        assertEquals(null, verifier.verify("forged-sub"))
        assertTrue(approvals.approve(a.approvalId, verifier.verify("good")!!))
    }

    @Test
    fun `unconsumed approval is ABANDONED only when the execution side confirms NOT_STARTED`() = fixture {
        val cases = listOf(
            Triple("STARTED", ExecutionPhaseAuthority { ExecutionPhase.STARTED }, "UNKNOWN"),
            Triple("UNCONFIRMED", ExecutionPhaseAuthority { null }, "UNKNOWN"),
            Triple("QUERY_FAILED", ExecutionPhaseAuthority { throw IllegalStateException("store down") }, "UNKNOWN"),
            Triple("NOT_STARTED", ExecutionPhaseAuthority { ExecutionPhase.NOT_STARTED }, "ABANDONED"),
        )
        for ((i, case) in cases.withIndex()) {
            val (name, phase, expected) = case
            val a = requestApproval(ns = "p$i")
            approvals.approve(a.approvalId, human())
            sink.record(AuditRecord("d-p$i", runId, AGENT, "k8s.apply", "ns:p$i", AuditOutcome.INTENT_ALLOWED, "r", a.approvalId))
            val res = reaper(Duration.ofHours(1), phase).reap(300).single()
            assertEquals(expected, res.second, name)
            assertEquals(ApprovalStatus.APPROVED, approvals.get(a.approvalId)!!.status, "$name: approval not spent by the reaper")
        }
        assertTrue(chainOk())
    }

    @Test
    fun `an ABANDONED decision can no longer consume its approval while a new decision can`() = fixture {
        val a = requestApproval(ns = "f")
        approvals.approve(a.approvalId, human())
        sink.record(AuditRecord("d-f1", runId, AGENT, "k8s.apply", "ns:f", AuditOutcome.INTENT_ALLOWED, "r", a.approvalId))
        assertEquals(listOf("ABANDONED"), reaper(Duration.ofHours(1)).reap(300).map { it.second })
        assertFalse(approvals.consume(a.approvalId, digest("f"), "d-f1"), "fenced: the reaper closed this decision")
        assertEquals(ApprovalStatus.APPROVED, approvals.get(a.approvalId)!!.status)
        assertTrue(approvals.consume(a.approvalId, digest("f"), "d-f2"), "a retry under a new decision is allowed")
        assertTrue(chainOk())
    }

    private class FakeAnchor(var fail: Boolean = false) : AuditHeadAnchor {
        val calls = mutableListOf<String>()
        val stored = linkedMapOf<String, AuditHead>()
        override fun publish(head: AuditHead, idempotencyKey: String) {
            calls += idempotencyKey
            if (fail) throw IllegalStateException("anchor down")
            stored.putIfAbsent(idempotencyKey, head)
        }
    }

    private fun Fixture.recordTwo() {
        fun rec(o: AuditOutcome) = AuditRecord("d-anc", runId, AGENT, "k8s.apply", "ns:prod", o, "r", null, "dg")
        sink.record(rec(AuditOutcome.INTENT_ALLOWED))
        sink.record(rec(AuditOutcome.EXECUTED))
    }

    private fun Fixture.enqueue() = transaction(db.database) { enqueueAuditHeadAnchor(this, audits, outbox, RunId(runId)) }

    @Test
    fun `a failing anchor publish does not roll back the transaction that enqueued the head`() = fixture {
        recordTwo()
        val enqueued = enqueue()
        assertEquals(3L, enqueued!!.seq, "run creation, INTENT_ALLOWED and EXECUTED")
        val anchor = FakeAnchor(fail = true)
        val publisher = AuditAnchorPublisher(outbox, anchor, "pub-a", backoff = Duration.ZERO)
        assertEquals(0, publisher.publishDue())
        assertEquals(1, anchor.calls.size)
        assertEquals(1, db.count("outbox_events", "aggregate_type = 'AUDIT_ANCHOR' AND status = 'PENDING' AND publish_attempts = 1"))
        assertEquals(3, db.count("audit_events"), "the audit chain is untouched by the failed publish")
        assertTrue(chainOk())
    }

    @Test
    fun `an anchor enqueued in a transaction that rolls back leaves no outbox row`() = fixture {
        recordTwo()
        assertFailsWith<IllegalStateException> {
            transaction(db.database) {
                enqueueAuditHeadAnchor(this, audits, outbox, RunId(runId))
                error("boom")
            }
        }
        assertEquals(0, db.count("outbox_events", "aggregate_type = 'AUDIT_ANCHOR'"))
    }

    @Test
    fun `redelivery after a lost mark reuses the idempotency key and does not duplicate the anchor`() = fixture {
        recordTwo()
        enqueue()
        val anchor = FakeAnchor()
        val publisher = AuditAnchorPublisher(outbox, anchor, "pub-a")
        assertEquals(1, publisher.publishDue())
        // markPublished was lost: the row goes back to PENDING and is delivered again
        transaction(db.database) { execUpdate("UPDATE outbox_events SET status = 'PENDING', published_at = NULL WHERE aggregate_type = 'AUDIT_ANCHOR'") }
        assertEquals(1, publisher.publishDue())
        assertEquals(2, anchor.calls.size)
        assertEquals(anchor.calls[0], anchor.calls[1], "the same idempotency key is presented on redelivery")
        assertEquals(1, anchor.stored.size, "the external store holds one copy")
        assertEquals(0, publisher.publishDue(), "nothing is due after PUBLISHED")
    }

    @Test
    fun `re-enqueueing the same head is idempotent and a rewritten head at the same seq is refused`() = fixture {
        recordTwo()
        enqueue()
        enqueue()
        assertEquals(1, db.count("outbox_events", "aggregate_type = 'AUDIT_ANCHOR'"))
        transaction(db.database) { execUpdate("UPDATE audit_events SET hash = ? WHERE result = 'EXECUTED'", "f".repeat(64)) }
        assertFailsWith<IllegalStateException> { enqueue() }
    }

    @Test
    fun `external anchor reveals tail truncation that the chain alone cannot detect`() = fixture {
        recordTwo()
        enqueue()
        val anchor = FakeAnchor()
        val publisher = AuditAnchorPublisher(outbox, anchor, "pub-a")
        assertEquals(1, publisher.publishDue())
        // a DB administrator removes the newest event
        transaction(db.database) { execUpdate("DELETE FROM audit_events WHERE result = 'EXECUTED'") }
        assertTrue(chainOk(), "documented limit: deleting the newest event leaves a valid chain")
        enqueue()
        assertEquals(1, publisher.publishDue())
        assertEquals(3L, anchor.stored.values.maxOf { it.seq }, "the external copy still records seq 3")
        assertEquals(2L, transaction(db.database) { audits.head(this, RunId(runId)) }!!.seq, "the database head is now seq 2")
    }
}
