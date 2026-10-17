package net.kigawa.kivra.coding

/** Test double for tests that are not about cost: every claim is admitted and nothing is booked. The ledger itself is tested in CodingCostLedgerTest. */
object UnmeteredCodingCost : CodingCostPort {
    override fun claimDenial(): String? = null
    override fun recordReported(attemptId: String, leaseGeneration: Long, usage: CliUsage): Boolean = true
    override fun recordEstimated(attemptId: String, leaseGeneration: Long): Boolean = true
}
