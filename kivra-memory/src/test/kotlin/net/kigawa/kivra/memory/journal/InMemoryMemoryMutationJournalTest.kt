package net.kigawa.kivra.memory.journal

import kotlin.test.Test
import kotlin.test.assertFailsWith

class InMemoryMemoryMutationJournalTest : MutationJournalContract() {
    override fun newJournal(): MemoryMutationJournalPort =
        InMemoryMemoryMutationJournal(trustedIssuers = setOf(TRUSTED_ISSUER), environment = "test")

    @Test
    fun `refuses to start outside dev and test environments`() {
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "production") }
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "staging") }
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "PROD") }
    }

    @Test
    fun `starts only when explicitly dev, test or local`() {
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = null) }
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "") }
        InMemoryMemoryMutationJournal(environment = "dev")
        InMemoryMemoryMutationJournal(environment = "test")
        InMemoryMemoryMutationJournal(environment = "local")
    }
}
