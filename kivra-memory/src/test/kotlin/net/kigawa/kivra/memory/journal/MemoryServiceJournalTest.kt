package net.kigawa.kivra.memory.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.FlushReport
import net.kigawa.kivra.memory.FakeMemoryPort
import net.kigawa.kivra.memory.IdempotencyKeys
import net.kigawa.kivra.memory.MemoryAuditEvent
import net.kigawa.kivra.memory.MemoryErrorKind
import net.kigawa.kivra.memory.MemoryException
import net.kigawa.kivra.memory.MemoryId
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryService
import net.kigawa.kivra.memory.NewMemoryEntry
import net.kigawa.kivra.memory.RememberOutcome
import net.kigawa.kivra.memory.Sensitivity
import net.kigawa.kivra.memory.T
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Save-side behaviour of [MemoryService] with the durable journal path (issue #77). "Restart" = a new MemoryService
 * (new writer generation) over the same journal instance.
 */
class MemoryServiceJournalTest {
    private val identity = MutationIdentity("t1", "agent-dev", "recl", "recl.memory.v1")
    private val ctx: AccessContext = T.ctx(T.project)
    private val body = "BODYMARK-7731 detailed note"
    private val entry = T.entry(body = body)
    private val key: IdempotencyKey = IdempotencyKeys.forSave(entry)
    private val opId = MutationIds.operationId(identity, key)
    private val digest = MutationDigest.of(MutationKind.SAVE, entry)
    private val events = mutableListOf<MemoryAuditEvent>()
    private val trusted = setOf(MutationJournalContract.TRUSTED_ISSUER)

    /**
     * Test provider: counts every save attempt (a POST), optionally applies the write before failing (response lost),
     * fails before applying (429 / 409 / 404), or proves NotSent once, or blocks until released.
     */
    private class ScriptedPort(val inner: FakeMemoryPort) : MemoryProvider by inner {
        val posts = AtomicInteger(0)
        var failBeforeApply: MemoryException? = null
        var applyThenFail: MemoryException? = null
        var notSentOnce: ((Long) -> Exception)? = null
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun save(e: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId {
            val attempt = posts.incrementAndGet()
            if (!entered.isCompleted) entered.complete(Unit)
            gate?.await()
            notSentOnce?.let { make -> notSentOnce = null; throw make(attempt.toLong()) }
            failBeforeApply?.let { throw it }
            val id = inner.save(e, key, ctx)
            applyThenFail?.let { throw it }
            return id
        }
    }

    private fun journal(trust: Set<String> = trusted) = InMemoryMemoryMutationJournal(trust, environment = "test")

    private fun service(
        port: MemoryProvider,
        j: MemoryMutationJournalPort,
        gen: Long,
        capability: MutationCapability = MutationCapability.ENABLED,
    ) = MemoryService(
        port, audit = { events += it }, mutationJournal = j, writer = MutationWriter(identity, gen), mutationCapability = capability,
    )

    private fun newPort() = ScriptedPort(FakeMemoryPort())

    private fun lostResponse() = MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "recl server error (502)")

    @Test
    fun `response lost after the POST is held, and three restarts never POST again`() = runBlocking {
        val port = newPort().apply { applyThenFail = lostResponse() }
        val j = journal()
        assertIs<RememberOutcome.HeldForReconciliation>(service(port, j, 1).remember(entry, ctx))
        assertEquals(1, port.posts.get())
        repeat(3) { i ->
            val restarted = service(port, j, 2L + i)
            assertIs<RememberOutcome.HeldForReconciliation>(restarted.remember(entry, ctx))
            assertEquals(FlushReportEmpty, restarted.flush())
            assertEquals(1, restarted.unknownHeldOperations().size)
        }
        assertEquals(1, port.posts.get(), "no additional POST after restarts")
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get(opId)!!.status)
        assertEquals(opId, j.unknownHeld(identity).single().operationId)
    }

    @Test
    fun `429 before apply is held the same way and never re-sent after restarts`() = runBlocking {
        val port = newPort().apply {
            failBeforeApply = MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "recl: rate limited; mutation outcome unverified")
        }
        val j = journal()
        assertIs<RememberOutcome.HeldForReconciliation>(service(port, j, 1).remember(entry, ctx))
        repeat(3) { i ->
            assertEquals(FlushReportEmpty, service(port, j, 2L + i).flush())
            assertIs<RememberOutcome.HeldForReconciliation>(service(port, j, 2L + i).remember(entry, ctx))
        }
        assertEquals(1, port.posts.get())
        assertEquals(0, port.inner.size())
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get(opId)!!.status)
    }

    @Test
    fun `409, 404, 403 and transport failures never confirm or re-send`() = runBlocking {
        val kinds = listOf(MemoryErrorKind.VERSION_CONFLICT, MemoryErrorKind.NOT_FOUND, MemoryErrorKind.FORBIDDEN, MemoryErrorKind.UNAVAILABLE)
        for (kind in kinds) {
            val port = newPort().apply { failBeforeApply = MemoryException(kind, "provider said $kind") }
            val j = journal()
            val s = service(port, j, 1)
            assertIs<RememberOutcome.HeldForReconciliation>(s.remember(entry, ctx), kind.name)
            assertEquals(FlushReportEmpty, s.flush())
            assertEquals(1, port.posts.get(), kind.name)
            assertEquals(MutationStatus.UNKNOWN_HELD, j.get(opId)!!.status, kind.name)
        }
    }

    @Test
    fun `a successful write is confirmed once and a repeat adopts the confirmed operation`() = runBlocking {
        val port = newPort()
        val j = journal()
        assertIs<RememberOutcome.Saved>(service(port, j, 1).remember(entry, ctx))
        assertEquals(MutationStatus.CONFIRMED, j.get(opId)!!.status)
        assertIs<RememberOutcome.AlreadyRecorded>(service(port, j, 2).remember(entry, ctx))
        assertEquals(1, port.posts.get())
        assertEquals(1, port.inner.size())
    }

    @Test
    fun `a proven not-sent is re-dispatched once by the same process with the same key`() = runBlocking {
        val port = newPort().apply {
            notSentOnce = { _ ->
                NotSentDispatchException(MutationEvidence(identity, opId, digest, MutationJournalContract.TRUSTED_ISSUER, "precheck-1", Instant.now()))
            }
        }
        val j = journal()
        val s = service(port, j, 1)
        assertIs<RememberOutcome.Queued>(s.remember(entry, ctx))
        assertEquals(MutationStatus.PREPARED, j.get(opId)!!.status)
        assertEquals(FlushReport(flushed = 1, remaining = 0, dropped = 0, held = 0), s.flush())
        assertEquals(2, port.posts.get())
        assertEquals(1, port.inner.size())
        assertEquals(MutationStatus.CONFIRMED, j.get(opId)!!.status)
    }

    @Test
    fun `a not-sent proof from an untrusted issuer is refused and the write is held`() = runBlocking {
        val port = newPort().apply {
            notSentOnce = { _ ->
                NotSentDispatchException(MutationEvidence(identity, opId, digest, "rogue-issuer", "precheck-1", Instant.now()))
            }
        }
        val j = journal()
        val s = service(port, j, 1)
        assertIs<RememberOutcome.HeldForReconciliation>(s.remember(entry, ctx))
        assertEquals(FlushReportEmpty, s.flush())
        assertEquals(1, port.posts.get())
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get(opId)!!.status)
    }

    @Test
    fun `two replicas racing on the same OperationId produce one POST`() = runBlocking {
        val port = newPort().apply { gate = CompletableDeferred() }
        val j = journal()
        val first = service(port, j, 1)
        val second = service(port, j, 2)
        val inFirst = async(Dispatchers.Default) { first.remember(entry, ctx) }
        port.entered.await()
        val racing = second.remember(entry, ctx)
        assertTrue(racing is RememberOutcome.AlreadyRecorded || racing is RememberOutcome.HeldForReconciliation, "got $racing")
        port.gate!!.complete(Unit)
        inFirst.await()
        assertEquals(1, port.posts.get(), "single POST across replicas")
    }

    @Test
    fun `a writer that dies while in flight is swept to unknown held by the next generation and never re-sent`() = runBlocking {
        val port = newPort().apply { gate = CompletableDeferred() }
        val j = journal()
        val dying = launch(start = CoroutineStart.DEFAULT) { service(port, j, 1).remember(entry, ctx) }
        port.entered.await()
        dying.cancelAndJoin()
        port.gate = null
        val next = service(port, j, 2)
        assertIs<RememberOutcome.HeldForReconciliation>(next.remember(entry, ctx))
        assertEquals(1, port.posts.get())
        val row = j.get(opId)!!
        assertEquals(MutationStatus.UNKNOWN_HELD, row.status)
        assertEquals("SWEPT_WORKER_LOST", row.reasonCode)
    }

    @Test
    fun `mutation capability disabled writes nothing`() = runBlocking {
        val port = newPort()
        val j = journal()
        val out = service(port, j, 1, MutationCapability.DISABLED).remember(entry, ctx)
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.UNSUPPORTED), out)
        assertEquals(0, port.posts.get())
        assertEquals(null, j.get(opId))
    }

    @Test
    fun `restricted content is refused before any row or POST`() = runBlocking {
        val port = newPort()
        val j = journal()
        val restricted = T.entry(body = null, sensitivity = Sensitivity.RESTRICTED)
        val restrictedOp = MutationIds.operationId(identity, IdempotencyKeys.forSave(restricted))
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.REJECTED), service(port, j, 1).remember(restricted, ctx))
        assertEquals(0, port.posts.get())
        assertEquals(null, j.get(restrictedOp))
    }

    @Test
    fun `secrets are refused before any row or POST and only the kind is audited`() = runBlocking {
        val port = newPort()
        val j = journal()
        val secret = T.entry(title = "AKIAIOSFODNN7EXAMPLE leaked", body = null)
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.REJECTED), service(port, j, 1).remember(secret, ctx))
        assertEquals(0, port.posts.get())
        val audit = events.single { it.detail.containsKey("kinds") }
        assertTrue(audit.detail.values.none { it.contains("AKIA") })
    }

    @Test
    fun `journal unavailable before dispatch fails closed without a POST`() = runBlocking {
        val port = newPort()
        val broken = object : MemoryMutationJournalPort by journal() {
            override suspend fun prepare(intent: MutationIntent): PrepareResult = error("db down")
        }
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.UNAVAILABLE), service(port, broken, 1).remember(entry, ctx))
        assertEquals(0, port.posts.get())
    }

    @Test
    fun `bodies never appear in audit events or journal records`() = runBlocking {
        val port = newPort().apply { applyThenFail = lostResponse() }
        val j = journal()
        service(port, j, 1).remember(entry, ctx)
        assertTrue(events.isNotEmpty())
        assertTrue(events.none { e -> e.detail.values.any { it.contains("BODYMARK") } || e.detail.keys.any { it.contains("BODYMARK") } })
        val record = j.get(opId)!!
        assertTrue(!record.toString().contains("BODYMARK") && !record.intent.toString().contains("BODYMARK"))
    }

    private companion object {
        val FlushReportEmpty = FlushReport(flushed = 0, remaining = 0, dropped = 0, held = 0)
    }
}
