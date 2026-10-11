package net.kigawa.kivra.memory.recl

import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.FakeMemoryPort
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryProviderContract

/** The same contract as the Fake, exercised through ReclAdapter against the mock recl (no real API exists). */
class ReclAdapterContractTest : MemoryProviderContract() {
    override fun newBackend() = object : Backend {
        private val fake = FakeMemoryPort()
        override fun view(ctx: AccessContext): MemoryProvider =
            ReclAdapter(
                ReclConfig("https://recl.example.test", idempotentSaveConfirmed = true, redactSupported = true),
                MockRecl(fake, ctx).engine, { "test-token" }, sleep = {},
            )
    }
}
