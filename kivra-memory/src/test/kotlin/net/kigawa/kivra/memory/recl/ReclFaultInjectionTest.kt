package net.kigawa.kivra.memory.recl

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.FakeMemoryPort
import net.kigawa.kivra.memory.IdempotencyKeys
import net.kigawa.kivra.memory.MemoryAuditEvent
import net.kigawa.kivra.memory.MemoryId
import net.kigawa.kivra.memory.MemoryService
import net.kigawa.kivra.memory.NewMemoryEntry
import net.kigawa.kivra.memory.RememberOutcome
import net.kigawa.kivra.memory.T
import net.kigawa.kivra.memory.journal.InMemoryMemoryMutationJournal
import net.kigawa.kivra.memory.journal.LookupResult
import net.kigawa.kivra.memory.journal.MemoryMutationJournalPort
import net.kigawa.kivra.memory.journal.MemoryMutationReconciler
import net.kigawa.kivra.memory.journal.MutationCapability
import net.kigawa.kivra.memory.journal.MutationIdentity
import net.kigawa.kivra.memory.journal.MutationJournalContract
import net.kigawa.kivra.memory.journal.MutationIds
import net.kigawa.kivra.memory.journal.MutationResultLookup
import net.kigawa.kivra.memory.journal.MutationStatus
import net.kigawa.kivra.memory.journal.MutationWriter
import net.kigawa.kivra.memory.journal.OperatorEscalation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fault injection against [ReclAdapter] over a fake recl server ([MockRecl]). Response-lost = the server APPLIES the
 * POST and then the connection drops. "Restart" = a new [ReclAdapter] and a new [MemoryService] (new writer generation)
 * over the same durable journal instance. No real recl is contacted.
 */
class ReclFaultInjectionTest {
    private val identity = MutationIdentity("t1", "agent-dev", "recl", "recl.memory.v1")
    private val ctx: AccessContext = T.ctx(T.project)
    private val secretToken = "tok-SUPER-SECRET-FAULT-998877"
    private val body = "BODYMARK-FAULT-4242 detailed note"
    private val entry: NewMemoryEntry = T.entry(body = body)
    private val key: IdempotencyKey = IdempotencyKeys.forSave(entry)
    private val opId = MutationIds.operationId(identity, key)
    private val events = mutableListOf<MemoryAuditEvent>()
    private val cfg = ReclConfig("https://recl.example.test", mutationsEnabled = true, idempotentSaveConfirmed = true)

    private class Server {
        val fake = FakeMemoryPort()
        val recl = MockRecl(fake, T.ctx(T.project))
        val engine: MockEngine get() = recl.engine
        val posts: Int get() = recl.requests.count { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/contexts") }
    }

    private fun adapter(server: Server, sleeps: MutableList<Long> = mutableListOf()) =
        ReclAdapter(cfg, server.engine, { secretToken }, sleep = { sleeps.add(it) }, jitter = { 0.0 })

    private fun service(a: ReclAdapter, journal: MemoryMutationJournalPort, gen: Long) = MemoryService(
        a, audit = { events += it }, mutationJournal = journal, writer = MutationWriter(identity, gen),
        mutationCapability = MutationCapability.ENABLED,
    )

    @Test
    fun `response lost after apply is held, restarts and reconciliation never re-POST`() = runTest {
        val server = Server().apply { recl.responsesLostAfterApply = 1 }
        val journal = InMemoryMemoryMutationJournal(setOf(MutationJournalContract.TRUSTED_ISSUER), environment = "test")

        assertIs<RememberOutcome.HeldForReconciliation>(service(adapter(server), journal, 1).remember(entry, ctx, key))
        assertEquals(1, server.posts)
        assertEquals(1, server.fake.size(), "the server applied the write exactly once")

        repeat(3) { i ->
            val restarted = service(adapter(server), journal, 2L + i)
            assertIs<RememberOutcome.HeldForReconciliation>(restarted.remember(entry, ctx, key))
            assertEquals(0, restarted.flush().flushed)
            assertEquals(1, restarted.unknownHeldOperations().size)
        }
        assertEquals(1, server.posts, "no POST after restarts or flushes")
        assertEquals(1, server.fake.size(), "no duplicate apply")
        assertEquals(MutationStatus.UNKNOWN_HELD, journal.get(opId)!!.status)

        // Reconciliation is read-only and cannot prove absence: the row stays held, and nothing is re-sent.
        val lookup = MutationResultLookup { _, _, _ -> LookupResult.NotFound }
        val report = MemoryMutationReconciler(journal, lookup, OperatorEscalation { _, _ -> }).reconcile(MutationWriter(identity, 9))
        assertEquals(1, report.examined)
        assertEquals(0, report.confirmed)
        assertEquals(MutationStatus.UNKNOWN_HELD, journal.get(opId)!!.status)
        assertEquals(1, server.posts, "reconciliation must not POST")
    }

    @Test
    fun `applied then 429 with Retry-After is held, and a restarted process never re-sends it`() = runTest {
        val server = Server().apply { recl.rateLimitedAfterApply = 1 }
        val journal = InMemoryMemoryMutationJournal(setOf(MutationJournalContract.TRUSTED_ISSUER), environment = "test")
        assertIs<RememberOutcome.HeldForReconciliation>(service(adapter(server), journal, 1).remember(entry, ctx, key))
        assertEquals(1, server.posts)

        val sleeps = mutableListOf<Long>()
        assertIs<RememberOutcome.HeldForReconciliation>(service(adapter(server, sleeps), journal, 2).remember(entry, ctx, key))
        assertEquals(0, service(adapter(server, sleeps), journal, 3).flush().flushed)
        assertEquals(1, server.posts, "Retry-After must not authorize a re-POST")
        assertEquals(1, server.fake.size())
        assertTrue(sleeps.isEmpty())
        assertEquals(MutationStatus.UNKNOWN_HELD, journal.get(opId)!!.status)
    }

    @Test
    fun `audit events and journal records never carry the body or the token`() = runTest {
        val server = Server().apply { recl.responsesLostAfterApply = 1 }
        val journal = InMemoryMemoryMutationJournal(setOf(MutationJournalContract.TRUSTED_ISSUER), environment = "test")
        service(adapter(server), journal, 1).remember(entry, ctx, key)
        service(adapter(server), journal, 2).flush()
        val dump = events.joinToString("\n") { it.toString() } + "\n" + journal.get(opId).toString()
        assertFalse(dump.contains(body.substringBefore(" ")), "body leaked into audit or journal")
        assertFalse(dump.contains(secretToken))
    }
}
