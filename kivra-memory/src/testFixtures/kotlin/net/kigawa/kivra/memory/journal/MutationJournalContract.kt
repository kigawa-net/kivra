package net.kigawa.kivra.memory.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.ExternalObservation

/**
 * Contract for every [MemoryMutationJournalPort] implementation: the in-memory dev/test journal (kivra-memory) and the M0
 * `external_operations` adapter (kivra-app, Issue #91). Subclasses provide a fresh journal that trusts [TRUSTED_ISSUER].
 *
 * Two seams keep the contract honest for implementations that are bound to a run lease (M0):
 * - [advanceTo] makes a newer writer generation current. The in-memory journal takes the generation as a plain argument.
 * - [proof] builds evidence the implementation accepts. The in-memory journal accepts any observation from a trusted issuer;
 *   M0 only accepts a ref that the TrustedEvidenceAuthority verified for the operation's own subject.
 */
abstract class MutationJournalContract {
    /** Fresh journal instance that trusts exactly [TRUSTED_ISSUER]. */
    protected abstract fun newJournal(): MemoryMutationJournalPort

    /** Makes [gen] the current writer generation (the previous one is superseded). */
    protected open suspend fun advanceTo(gen: Long) {}

    /** False when the implementation has no evidence-free route to FAILED_PERMANENT (M0: FAILED needs a REJECTED ref). */
    protected open val supportsFailedPermanent: Boolean get() = true

    /** Evidence the implementation accepts for [opId]. In-memory: a bare observation from [TRUSTED_ISSUER]. */
    protected open suspend fun proof(
        opId: String = "op-1",
        identity: MutationIdentity = alice,
        digest: String = digestA,
        purpose: EvidencePurpose = EvidencePurpose.NOT_SENT,
        observation: ExternalObservation? = null,
    ): MutationEvidence = MutationEvidence(identity, opId, digest, TRUSTED_ISSUER, "ref-$opId", Instant.now())

    protected val alice = MutationIdentity("t1", "alice", "recl", "recl.memory.v1")
    protected val bob = MutationIdentity("t1", "bob", "recl", "recl.memory.v1")
    protected val otherTenant = MutationIdentity("t2", "alice", "recl", "recl.memory.v1")
    protected val digestA = "sha256:" + "a".repeat(64)
    protected val digestB = "sha256:" + "b".repeat(64)

    protected fun intent(
        opId: String = "op-1",
        identity: MutationIdentity = alice,
        requestId: String = "req-1",
        digest: String = digestA,
        gen: Long = 1,
    ) = MutationIntent(opId, identity, MutationKind.SAVE, requestId, digest, 0, gen, "exec-1")

    protected suspend fun prepared(j: MemoryMutationJournalPort, opId: String = "op-1", gen: Long = 1): MutationRecord =
        (j.prepare(intent(opId = opId, gen = gen)) as PrepareResult.Prepared).record

    protected suspend fun inFlight(j: MemoryMutationJournalPort, opId: String = "op-1", gen: Long = 1): DispatchDecision.Won {
        val rec = prepared(j, opId, gen)
        return j.beginDispatch(opId, gen, rec.version) as DispatchDecision.Won
    }

    private fun refusal(d: DispatchDecision): RefusalCode = assertIs<DispatchDecision.Refused>(d).code

    @Test
    fun `prepare is re-entrant for the same binding and adopts the existing row`() = runBlocking {
        val j = newJournal()
        val first = assertIs<PrepareResult.Prepared>(j.prepare(intent()))
        val again = assertIs<PrepareResult.Existing>(j.prepare(intent()))
        assertEquals(first.record.version, again.record.version)
        assertEquals(MutationStatus.PREPARED, again.record.status)
    }

    @Test
    fun `same operation with a different payload digest is a conflict`() = runBlocking {
        val j = newJournal()
        j.prepare(intent())
        assertEquals(PrepareResult.Conflict(ConflictCode.DIGEST_MISMATCH), j.prepare(intent(digest = digestB)))
    }

    @Test
    fun `same operation cannot adopt another execution or expected content revision`() = runBlocking {
        val j = newJournal()
        val original = intent()
        assertIs<PrepareResult.Prepared>(j.prepare(original))
        assertEquals(
            PrepareResult.Conflict(ConflictCode.IDENTITY_MISMATCH),
            j.prepare(original.copy(executionId = "exec-other")),
        )
        assertEquals(
            PrepareResult.Conflict(ConflictCode.IDENTITY_MISMATCH),
            j.prepare(original.copy(expectedRevision = 1L)),
        )
        advanceTo(2)
        assertIs<PrepareResult.Existing>(j.prepare(original.copy(writerGeneration = 2L)))
        assertEquals(original.writerGeneration, j.get(original.operationId)!!.ownerGeneration)
    }

    @Test
    fun `same operation under another tenant or principal is a conflict and never adopted`() = runBlocking {
        val j = newJournal()
        j.prepare(intent())
        assertEquals(PrepareResult.Conflict(ConflictCode.IDENTITY_MISMATCH), j.prepare(intent(identity = bob)))
    }

    @Test
    fun `a request id already bound to another operation of the same tenant and provider is refused`() = runBlocking {
        val j = newJournal()
        j.prepare(intent(opId = "op-1", requestId = "req-1"))
        assertEquals(PrepareResult.Conflict(ConflictCode.REQUEST_ID_REUSED), j.prepare(intent(opId = "op-2", requestId = "req-1")))
        // Another tenant may use the same request id: it is a different binding.
        assertIs<PrepareResult.Prepared>(j.prepare(intent(opId = "op-3", identity = otherTenant, requestId = "req-1")))
    }

    @Test
    fun `exactly one dispatch winner among concurrent replicas of the same generation`() = runBlocking {
        val j = newJournal()
        val rec = prepared(j)
        val decisions = (1..10).map {
            async(Dispatchers.Default) { j.beginDispatch("op-1", 1L, rec.version) }
        }.awaitAll()
        assertEquals(1, decisions.count { it is DispatchDecision.Won })
        assertEquals(9, decisions.count { it is DispatchDecision.Refused })
    }

    @Test
    fun `only the owning generation may settle, and a late result from a superseded writer is refused`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j, gen = 1).record
        assertEquals(
            RefusalCode.STALE_GENERATION,
            assertFailsWith<MutationJournalRejectedException> { j.markConfirmed("op-1", 2, won.version) }.code,
        )
        assertEquals(MutationStatus.IN_FLIGHT, j.get("op-1")!!.status)
        assertEquals(MutationStatus.CONFIRMED, j.markConfirmed("op-1", 1, won.version).status)
    }

    @Test
    fun `a settled operation cannot be dispatched again`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        j.markConfirmed("op-1", 1, won.version)
        assertEquals(RefusalCode.NOT_PREPARED, refusal(j.beginDispatch("op-1", 1, won.version + 1)))
    }

    @Test
    fun `unknown held is never dispatched again, even with a proof`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        val held = j.markUnknownHeld("op-1", 1, won.version, "PROVIDER_OUTCOME_UNKNOWN")
        assertEquals(MutationStatus.UNKNOWN_HELD, held.status)
        assertEquals(RefusalCode.NOT_PREPARED, refusal(j.beginDispatch("op-1", 1, held.version)))
        assertEquals(RefusalCode.NOT_PREPARED, refusal(j.beginDispatch("op-1", 2, held.version, proof())))
    }

    @Test
    fun `a prepared row of another generation is not dispatched without a trusted not-sent proof`() = runBlocking {
        val j = newJournal()
        val rec = prepared(j, gen = 1) // writer 1 crashed after prepare and before dispatch
        advanceTo(2)
        assertEquals(RefusalCode.NEEDS_NOT_SENT_PROOF, refusal(j.beginDispatch("op-1", 2, rec.version)))
        val untrusted = proof().copy(issuer = "unknown-issuer")
        assertEquals(RefusalCode.PROOF_REJECTED, refusal(j.beginDispatch("op-1", 2, rec.version, untrusted)))
        val wrongDigest = proof(digest = digestB)
        assertEquals(RefusalCode.PROOF_REJECTED, refusal(j.beginDispatch("op-1", 2, rec.version, wrongDigest)))
        val won = assertIs<DispatchDecision.Won>(j.beginDispatch("op-1", 2, rec.version, proof()))
        assertEquals(2L, won.record.ownerGeneration)
        assertEquals(MutationStatus.IN_FLIGHT, won.record.status)
    }

    @Test
    fun `a proven not-sent returns the row to PREPARED and it can be dispatched once more`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        val back = j.markNotSent("op-1", 1, won.version, proof())
        assertEquals(MutationStatus.PREPARED, back.status)
        val again = assertIs<DispatchDecision.Won>(j.beginDispatch("op-1", 1, back.version))
        assertEquals(2, again.record.attempts)
    }

    @Test
    fun `an unproven or identity-foreign not-sent does not return a row to PREPARED`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        assertEquals(
            RefusalCode.PROOF_REJECTED,
            assertFailsWith<MutationJournalRejectedException> {
                j.markNotSent("op-1", 1, won.version, proof(identity = bob))
            }.code,
        )
        assertEquals(MutationStatus.IN_FLIGHT, j.get("op-1")!!.status)
    }

    @Test
    fun `restart sweep moves older generations in-flight rows to unknown held exactly once`() = runBlocking {
        val j = newJournal()
        inFlight(j, gen = 1)
        advanceTo(2)
        assertEquals(1, j.sweepStaleInFlight(2))
        val row = j.get("op-1")!!
        assertEquals(MutationStatus.UNKNOWN_HELD, row.status)
        assertEquals("SWEPT_WORKER_LOST", row.reasonCode)
        assertEquals(0, j.sweepStaleInFlight(2))
        advanceTo(3)
        assertEquals(0, j.sweepStaleInFlight(3))
    }

    @Test
    fun `unknown held rows are visible only to their own tenant and principal`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        j.markUnknownHeld("op-1", 1, won.version, "PROVIDER_OUTCOME_UNKNOWN")
        assertEquals(listOf("op-1"), j.unknownHeld(alice).map { it.operationId })
        assertTrue(j.unknownHeld(bob).isEmpty())
        assertTrue(j.unknownHeld(otherTenant).isEmpty())
    }

    @Test
    fun `lookup settles unknown held only on bound evidence`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        val held = j.markUnknownHeld("op-1", 1, won.version, "PROVIDER_OUTCOME_UNKNOWN")
        assertEquals(
            RefusalCode.PROOF_REJECTED,
            assertFailsWith<MutationJournalRejectedException> {
                j.settleFromLookup("op-1", held.version, proof(identity = otherTenant, purpose = EvidencePurpose.RECONCILE_EXISTS, observation = ExternalObservation.EXISTS))
            }.code,
        )
        assertEquals(MutationStatus.UNKNOWN_HELD, j.get("op-1")!!.status)
        val settled = j.settleFromLookup(
            "op-1",
            held.version,
            proof(purpose = EvidencePurpose.RECONCILE_EXISTS, observation = ExternalObservation.EXISTS),
        )
        assertEquals(MutationStatus.CONFIRMED, settled.status)
        assertNull(j.unknownHeld(alice).firstOrNull())
    }

    @Test
    fun `failed permanent is terminal`() = runBlocking {
        if (!supportsFailedPermanent) return@runBlocking
        val j = newJournal()
        val won = inFlight(j).record
        assertEquals(MutationStatus.FAILED_PERMANENT, j.markFailedPermanent("op-1", 1, won.version, "PROVIDER_REJECTED").status)
        assertEquals(RefusalCode.NOT_PREPARED, refusal(j.beginDispatch("op-1", 1, won.version + 1)))
    }

    @Test
    fun `reason codes are machine codes only`() = runBlocking {
        val j = newJournal()
        val won = inFlight(j).record
        assertFailsWith<IllegalArgumentException> { j.markUnknownHeld("op-1", 1, won.version, "free text with spaces") }
        assertEquals(MutationStatus.IN_FLIGHT, j.get("op-1")!!.status)
    }

    companion object {
        /** The issuer the fixtures treat as trusted. The M0 harness registers its observations under this principal. */
        const val TRUSTED_ISSUER = "exkes-adapter"
    }
}
