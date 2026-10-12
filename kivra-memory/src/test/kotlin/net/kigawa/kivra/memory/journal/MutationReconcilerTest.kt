package net.kigawa.kivra.memory.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationReconcilerTest {
    private val alice = MutationIdentity("t1", "alice", "recl", "recl.memory.v1")
    private val bob = MutationIdentity("t1", "bob", "recl", "recl.memory.v1")
    private val digest = "sha256:" + "c".repeat(64)
    private val trusted = MutationJournalContract.TRUSTED_ISSUER

    private fun intent(opId: String, identity: MutationIdentity) =
        MutationIntent(opId, identity, MutationKind.SAVE, "req-$opId", digest, 0, 1, "exec-1")

    private fun evidence(opId: String, identity: MutationIdentity = alice) =
        MutationEvidence(identity, opId, digest, trusted, "lookup-$opId", Instant.now())

    /** Creates one UNKNOWN_HELD row (sent-or-unknown) for [identity]. */
    private suspend fun unknownHeld(j: MemoryMutationJournalPort, opId: String, identity: MutationIdentity = alice) {
        val rec = (j.prepare(intent(opId, identity)) as PrepareResult.Prepared).record
        val won = j.beginDispatch(opId, 1, rec.version) as DispatchDecision.Won
        j.markUnknownHeld(opId, 1, won.record.version, "PROVIDER_OUTCOME_UNKNOWN")
    }

    private class Recorder : OperatorEscalation {
        val calls = mutableListOf<Pair<String, String>>()
        override fun escalate(operationId: String, reasonCode: String) { synchronized(calls) { calls += operationId to reasonCode } }
    }

    private fun writer(identity: MutationIdentity = alice) = MutationWriter(identity, 9)

    @Test
    fun `bound applied lookup settles the row to CONFIRMED`() = runBlocking {
        val j = InMemoryMemoryMutationJournal(setOf(trusted), environment = "test")
        unknownHeld(j, "op-1")
        val rec = Recorder()
        val report = MemoryMutationReconciler(j, { id, _, _ -> LookupResult.Applied(evidence("op-1", id)) }, rec)
            .reconcile(writer())
        assertEquals(ReconcileReport(examined = 1, confirmed = 1, escalated = 0), report)
        assertEquals(MutationStatus.CONFIRMED, j.get("op-1")!!.status)
        assertTrue(rec.calls.isEmpty())
    }

    @Test
    fun `a not-found lookup is not absence - the row stays held, is escalated and never re-sent`() = runBlocking {
        val j = InMemoryMemoryMutationJournal(setOf(trusted), environment = "test")
        unknownHeld(j, "op-1")
        val rec = Recorder()
        val report = MemoryMutationReconciler(j, { _, _, _ -> LookupResult.NotFound }, rec).reconcile(writer())
        assertEquals(ReconcileReport(1, 0, 1), report)
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get("op-1")!!.status)
        assertEquals(listOf("op-1" to "LOOKUP_NOT_FOUND_NOT_AUTHORITATIVE"), rec.calls)
    }

    @Test
    fun `unsupported, forbidden or failing lookups are escalated and keep the row held`() = runBlocking {
        val j = InMemoryMemoryMutationJournal(setOf(trusted), environment = "test")
        unknownHeld(j, "op-1")
        val rec = Recorder()
        MemoryMutationReconciler(j, { _, _, _ -> LookupResult.Unavailable("UNSUPPORTED") }, rec).reconcile(writer())
        MemoryMutationReconciler(j, { _, _, _ -> error("lookup crashed") }, rec).reconcile(writer())
        assertEquals(
            listOf("op-1" to "LOOKUP_UNSUPPORTED", "op-1" to "LOOKUP_ERROR"),
            rec.calls,
        )
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get("op-1")!!.status)
    }

    @Test
    fun `evidence bound to another principal is rejected and the row stays held`() = runBlocking {
        val j = InMemoryMemoryMutationJournal(setOf(trusted), environment = "test")
        unknownHeld(j, "op-1", alice)
        val rec = Recorder()
        val report = MemoryMutationReconciler(j, { _, _, _ -> LookupResult.Applied(evidence("op-1", bob)) }, rec).reconcile(writer())
        assertEquals(ReconcileReport(1, 0, 1), report)
        assertEquals(listOf("op-1" to "LOOKUP_EVIDENCE_REJECTED"), rec.calls)
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get("op-1")!!.status)
    }

    @Test
    fun `reconciles only the writer's own tenant and principal`() = runBlocking {
        val j = InMemoryMemoryMutationJournal(setOf(trusted), environment = "test")
        unknownHeld(j, "op-alice", alice)
        unknownHeld(j, "op-bob", bob)
        val seen = mutableListOf<MutationIdentity>()
        MemoryMutationReconciler(j, { id, _, _ -> seen += id; LookupResult.NotFound }, Recorder()).reconcile(writer(alice))
        assertEquals(listOf(alice), seen)
    }

    @Test
    fun `ten concurrent reconcilers settle a row exactly once`() = runBlocking {
        val j = InMemoryMemoryMutationJournal(setOf(trusted), environment = "test")
        unknownHeld(j, "op-1")
        val lookup = MutationResultLookup { id, _, _ -> LookupResult.Applied(evidence("op-1", id)) }
        val reports = (1..10).map {
            async(Dispatchers.Default) { MemoryMutationReconciler(j, lookup, Recorder()).reconcile(writer()) }
        }.awaitAll()
        assertEquals(1, reports.sumOf { it.confirmed })
        assertEquals(MutationStatus.CONFIRMED, j.get("op-1")!!.status)
    }
}
