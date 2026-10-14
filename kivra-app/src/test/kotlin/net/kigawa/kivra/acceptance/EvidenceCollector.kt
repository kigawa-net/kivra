package net.kigawa.kivra.acceptance

import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.JournalEntry
import net.kigawa.kivra.db.OperationJournalRepository

/**
 * Reads what the provider and the Journal say after a scenario step. Exkes has no idempotency key and the adapter sends no
 * OperationId, so a provider request is correlated with an operation through its stable reference (the runtime label
 * `kivra.run-id`, which is the operation's stableRef).
 */
class EvidenceCollector(private val fake: FakeExkes, private val journal: OperationJournalRepository) {
    val externalRuntimeCount: Int get() = fake.externalRuntimeCount

    /** Every mutating request that reached the provider for [stableRef] (before faults). */
    fun postCountFor(stableRef: String): Int = fake.posts.count { it.stableRef == stableRef }

    /** Every effect the provider persisted, across all operations. */
    val externalSideEffectCount: Int get() = fake.effects.size

    fun journalOf(runId: RunId): List<JournalEntry> = journal.listByRun(runId)

    /**
     * Provider requests that were not covered by a journal dispatch. Each IN_FLIGHT transition increments `attempt`, so a
     * request beyond `attempt` was sent without the Journal's permission: an automatic replay.
     */
    fun automaticReplayCount(entry: JournalEntry): Int = maxOf(0, postCountFor(entry.stableRef) - entry.attempt)
}
