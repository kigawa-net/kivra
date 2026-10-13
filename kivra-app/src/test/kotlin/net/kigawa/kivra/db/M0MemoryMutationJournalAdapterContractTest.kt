package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.memory.journal.MemoryMutationJournalPort
import net.kigawa.kivra.memory.journal.MutationJournalContract
import net.kigawa.kivra.memory.journal.MutationEvidence
import net.kigawa.kivra.memory.journal.MutationIdentity
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach

/**
 * Runs the shared [MutationJournalContract] against the M0 adapter on a migrated database (H2 by default, MariaDB with
 * `KIVRA_TEST_DB=mariadb`). The run lease is the writer fence: [advanceTo] makes a newer lease generation current.
 * [proof] registers an observation with the test Authority and asks it to verify a ref for the operation's own subject.
 */
class M0MemoryMutationJournalAdapterContractTest : MutationJournalContract() {
    private val owner = "worker-contract"
    private val dbs = mutableListOf<TestDb>()
    private lateinit var db: TestDb
    private var runId: RunId = RunId("unset")
    private lateinit var adapter: M0MemoryMutationJournalAdapter

    override fun newJournal(): MemoryMutationJournalPort {
        db = TestDb().also { dbs += it }
        db.evidence.setProviderContract("recl", "recl.memory.v1", ProviderCapability.LOOKUP_BY_STABLE_REF)
        val run = db.runs.create("task-91-contract", "test", RunCompletionMode.RELEASE)
        runId = run.runId
        checkNotNull(db.leases.acquire(runId, owner, Duration.ofHours(1))) { "could not acquire the run lease" }
        adapter = M0MemoryMutationJournalAdapter(db.database, db.evidence, runId, owner)
        return adapter
    }

    override suspend fun advanceTo(gen: Long) {
        val moved = transaction(db.database) {
            execUpdate(
                "UPDATE development_runs SET lease_generation = ?, lease_expires_at = TIMESTAMPADD(SECOND, 3600, NOW(6)) WHERE run_id = ? AND lease_owner = ?",
                gen, runId.value, owner,
            )
        }
        check(moved == 1) { "could not advance the lease to generation $gen" }
    }

    override val supportsFailedPermanent: Boolean get() = false

    override suspend fun proof(
        opId: String,
        identity: MutationIdentity,
        digest: String,
        purpose: EvidencePurpose,
        observation: ExternalObservation?,
    ): MutationEvidence {
        val subject = checkNotNull(adapter.evidenceSubject(opId)) { "no operation $opId" }
        val now = utcNow(Clock.systemUTC())
        val obsRef = EvidenceObservationRef("obs-${UUID.randomUUID()}")
        db.evidence.register(
            obsRef,
            FakeTrustedEvidenceAuthority.Observation(
                subject, TRUSTED_ISSUER, purpose, observation, ProviderCapability.LOOKUP_BY_STABLE_REF, now, now.plusHours(1),
            ),
        )
        val verified = db.evidence.verify(subject, obsRef, purpose) as? EvidenceVerification.Verified
            ?: error("the test Authority refused its own observation")
        return MutationEvidence(identity, opId, digest, TRUSTED_ISSUER, verified.ref.value, Instant.now())
    }

    @AfterEach
    fun closeDatabases() {
        dbs.forEach { it.close() }
        dbs.clear()
    }
}
