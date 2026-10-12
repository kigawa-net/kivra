package net.kigawa.kivra.memory.recl

import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.FakeMemoryPort
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryProviderContract

/**
 * The same contract as the Fake, exercised through ReclAdapter against the mock recl (no real API exists).
 * Passing here is NOT production verification of a recl Provider (see recl#20 / kivra#71).
 */
class ReclAdapterContractTest : MemoryProviderContract() {
    override fun newBackend() = object : Backend {
        private val fake = FakeMemoryPort()
        override fun view(ctx: AccessContext): MemoryProvider =
            ReclAdapter(
                ReclConfig("https://recl.example.test", idempotentSaveConfirmed = true, redactSupported = true, mutationsEnabled = true),
                MockRecl(fake, ctx).engine, { "test-token" }, sleep = {},
            )
    }
}
