package net.kigawa.kivra.db

import net.kigawa.kivra.modelgateway.InMemoryRequestLedger

class InMemoryRequestLedgerContractTest : RequestLedgerContract() {
    override fun withLedger(capacity: Int?, block: (LedgerFixture) -> Unit) {
        val ledger = if (capacity == null) InMemoryRequestLedger() else InMemoryRequestLedger(capacity)
        block(LedgerFixture(ledger, replica = { ledger }))
    }
}
