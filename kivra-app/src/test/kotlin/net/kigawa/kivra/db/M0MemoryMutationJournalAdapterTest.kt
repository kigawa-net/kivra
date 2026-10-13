package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VerifiedEvidenceRef
import net.kigawa.kivra.memory.journal.DispatchDecision
import net.kigawa.kivra.memory.journal.MutationEvidence
import net.kigawa.kivra.memory.journal.MutationIdentity
import net.kigawa.kivra.memory.journal.MutationIntent
import net.kigawa.kivra.memory.journal.MutationJournalRejectedException
import net.kigawa.kivra.memory.journal.MutationKind
import net.kigawa.kivra.memory.journal.MutationStatus
import net.kigawa.kivra.memory.journal.RefusalCode
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * M0-specific behaviour of [M0MemoryMutationJournalAdapter] that the shared contract does not cover: the Authority decides
 * (unavailable / revoked), a superseded replica is fenced, a held run halts the writer, the attempt budget is enforced, and the
 * restart sweep touches memory rows only.
 */
class M0MemoryMutationJournalAdapterTest {
    private val alice = MutationIdentity("t1", "alice", "recl", "recl.memory.v1")
    private val digest = "sha256:" + "c".repeat(64)

    private inner class Fixture : AutoCloseable {
        val db = TestDb()
        val runId: RunId = db.runs.create("task-91-adapter", "test", RunCompletionMode.RELEASE).runId

        init {
            db.evidence.setProviderContract("recl", "recl.memory.v1", ProviderCapability.LOOKUP_BY_STABLE_REF)
        }

        fun adapter(owner: String) = M0MemoryMutationJournalAdapter(db.database, db.evidence, runId, owner)

        fun acquire(owner: String): Lease =
            checkNotNull(db.leases.acquire(runId, owner, Duration.ofHours(1))) { "lease not acquired by $owner" }

        /** A NOT_SENT or RECONCILE proof the Authority verified for [opId]'s own subject. */
        suspend fun proof(
            a: M0MemoryMutationJournalAdapter,
            opId: String,
            purpose: EvidencePurpose = EvidencePurpose.NOT_SENT,
            observation: ExternalObservation? = null,
        ): MutationEvidence {
            val subject = checkNotNull(a.evidenceSubject(opId))
            val now = utcNow(Clock.systemUTC())
            val obsRef = EvidenceObservationRef("obs-${UUID.randomUUID()}")
            db.evidence.register(
                obsRef,
                FakeTrustedEvidenceAuthority.Observation(
                    subject, TRUSTED_ISSUER, purpose, observation, ProviderCapability.LOOKUP_BY_STABLE_REF, now, now.plusHours(1),
                ),
            )
            val verified = assertIs<EvidenceVerification.Verified>(db.evidence.verify(subject, obsRef, purpose))
            return MutationEvidence(alice, opId, digest, TRUSTED_ISSUER, verified.ref.value, Instant.now())
        }

        fun rowCount(): Int = db.count("external_operations", "run_id = '${runId.value}'")

        override fun close() = db.close()
    }

    private fun intent(opId: String = "mop-1", gen: Long = 1) =
        MutationIntent(opId, alice, MutationKind.SAVE, "req-1", digest, 0, gen, "exec-1")

    @Test
    fun `an unavailable authority keeps an in-flight row in flight and an unknown row unknown`() = Fixture().use { f ->
        runBlocking {
            val a = f.adapter("worker-a")
            f.acquire("worker-a")
            a.prepare(intent())
            val won = assertIs<DispatchDecision.Won>(a.beginDispatch("mop-1", 1, 0)).record
            val notSent = f.proof(a, "mop-1")
            f.db.evidence.available = false
            assertEquals(
                RefusalCode.PROOF_REJECTED,
                assertFailsWith<MutationJournalRejectedException> { a.markNotSent("mop-1", 1, won.version, notSent) }.code,
            )
            assertEquals(MutationStatus.IN_FLIGHT, a.get("mop-1")!!.status)

            f.db.evidence.available = true
            val held = a.markUnknownHeld("mop-1", 1, won.version, "PROVIDER_OUTCOME_UNKNOWN")
            val lookup = f.proof(a, "mop-1", EvidencePurpose.RECONCILE_EXISTS, ExternalObservation.EXISTS)
            f.db.evidence.available = false
            assertEquals(
                RefusalCode.PROOF_REJECTED,
                assertFailsWith<MutationJournalRejectedException> { a.settleFromLookup("mop-1", held.version, lookup) }.code,
            )
            assertEquals(MutationStatus.UNKNOWN_HELD, a.get("mop-1")!!.status)
            f.db.evidence.available = true
        }
    }

    @Test
    fun `a revoked proof is refused at the transition and changes nothing`() = Fixture().use { f ->
        runBlocking {
            val a = f.adapter("worker-a")
            f.acquire("worker-a")
            a.prepare(intent())
            val won = assertIs<DispatchDecision.Won>(a.beginDispatch("mop-1", 1, 0)).record
            val notSent = f.proof(a, "mop-1")
            f.db.evidence.revoke(VerifiedEvidenceRef(notSent.evidenceRef))
            assertEquals(
                RefusalCode.PROOF_REJECTED,
                assertFailsWith<MutationJournalRejectedException> { a.markNotSent("mop-1", 1, won.version, notSent) }.code,
            )
            assertEquals(MutationStatus.IN_FLIGHT, a.get("mop-1")!!.status)
        }
    }

    @Test
    fun `a superseded replica cannot settle, the new owner never re-sends an unknown operation, and no second row appears`() =
        Fixture().use { f ->
            runBlocking {
                val a = f.adapter("worker-a")
                f.acquire("worker-a")
                a.prepare(intent())
                val won = assertIs<DispatchDecision.Won>(a.beginDispatch("mop-1", 1, 0)).record

                // worker-a stops renewing; worker-b takes the run over. The M0 acquire sweeps the in-flight row to UNKNOWN.
                f.db.expireLease(f.runId.value)
                assertEquals(2L, f.acquire("worker-b").generation)
                val b = f.adapter("worker-b")

                assertEquals(
                    RefusalCode.STALE_GENERATION,
                    assertFailsWith<MutationJournalRejectedException> { a.markConfirmed("mop-1", 1, won.version) }.code,
                )
                val held = checkNotNull(b.get("mop-1"))
                assertEquals(MutationStatus.UNKNOWN_HELD, held.status)
                assertEquals(RefusalCode.NOT_PREPARED, assertIs<DispatchDecision.Refused>(b.beginDispatch("mop-1", 2, held.version)).code)
                assertEquals(1, f.rowCount())
            }
        }

    @Test
    fun `a run on hold halts the writer instead of returning a refusal`() = Fixture().use { f ->
        runBlocking {
            val a = f.adapter("worker-a")
            f.acquire("worker-a")
            a.prepare(intent())
            transaction(f.db.database) {
                execUpdate("UPDATE development_runs SET hold_reason = 'OPERATOR' WHERE run_id = ?", f.runId.value)
            }
            assertFailsWith<RunHaltedException> { a.beginDispatch("mop-1", 1, 0) }
            assertEquals(MutationStatus.PREPARED, a.get("mop-1")!!.status)
        }
    }

    @Test
    fun `a row that used its attempt budget settles as FAILED instead of being sent again`() = Fixture().use { f ->
        runBlocking {
            val a = f.adapter("worker-a")
            f.acquire("worker-a")
            a.prepare(intent())
            var rec = assertIs<DispatchDecision.Won>(a.beginDispatch("mop-1", 1, 0)).record
            repeat(2) {
                rec = a.markNotSent("mop-1", 1, rec.version, f.proof(a, "mop-1"))
                rec = assertIs<DispatchDecision.Won>(a.beginDispatch("mop-1", 1, rec.version)).record
            }
            rec = a.markNotSent("mop-1", 1, rec.version, f.proof(a, "mop-1"))
            val refused = assertIs<DispatchDecision.Refused>(a.beginDispatch("mop-1", 1, rec.version))
            assertEquals(RefusalCode.NOT_PREPARED, refused.code)
            val settled = checkNotNull(a.get("mop-1"))
            assertEquals(MutationStatus.FAILED_PERMANENT, settled.status)
            assertEquals("MAX_ATTEMPTS_EXCEEDED", settled.reasonCode)
        }
    }

    @Test
    fun `the restart sweep moves memory rows only and leaves other operations in flight`() = Fixture().use { f ->
        runBlocking {
            val a = f.adapter("worker-a")
            val lease1 = f.acquire("worker-a")
            val exkes = f.db.journal.prepare(
                lease1,
                PrepareOperation(
                    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = "ws-1", externalSystem = "EXKES", stableRef = "kivra-run",
                    requestHash = "a".repeat(64), tenantId = "t1", providerContractVersion = "2026-01",
                ),
            )
            f.db.journal.transition(lease1, exkes.opId, exkes.version, OperationStatus.IN_FLIGHT)
            a.prepare(intent())
            assertIs<DispatchDecision.Won>(a.beginDispatch("mop-1", 1, 0))

            // Same owner, next generation, no acquire: the sweep is the only thing that moves the memory row.
            transaction(f.db.database) {
                execUpdate("UPDATE development_runs SET lease_generation = 2 WHERE run_id = ?", f.runId.value)
            }
            assertEquals(1, f.adapter("worker-a").sweepStaleInFlight(2))
            assertEquals(MutationStatus.UNKNOWN_HELD, a.get("mop-1")!!.status)
            assertEquals("SWEPT_WORKER_LOST", a.get("mop-1")!!.reasonCode)
            assertEquals(OperationStatus.IN_FLIGHT, f.db.journal.get(exkes.opId)!!.status)
        }
    }

    companion object {
        private const val TRUSTED_ISSUER = "exkes-adapter"
    }
}
