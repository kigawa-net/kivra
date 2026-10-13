package net.kigawa.kivra.memory.journal

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MutationEvidenceRefTest {
    private val identity = MutationIdentity("tenant-a", "alice", "recl", "v1")
    private val digest = "sha256:" + "a".repeat(64)

    private fun evidence(ref: String) =
        MutationEvidence(identity, "op-1", digest, "issuer-1", ref, Instant.parse("2026-10-12T00:00:00Z"))

    @Test
    fun `opaque uuid refs are never rejected by the entropy heuristic`() {
        // The heuristic misfires on some random 40-character refs; the previous behaviour was a random rejection.
        repeat(5_000) { evidence("evd-${UUID.randomUUID()}") }
    }

    @Test
    fun `refs that look like secrets are still rejected`() {
        listOf(
            "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4",
            "AKIAABCDEFGHIJKLMNOP",
            "eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0NTY3.SflKxwRJSMeKKF2QT4",
        ).forEach { ref ->
            assertFailsWith<IllegalArgumentException>(ref) { evidence(ref) }
        }
    }

    @Test
    fun `refs outside the safe character set are still rejected`() {
        assertFailsWith<IllegalArgumentException> { evidence("evd-abc def") }
        assertFailsWith<IllegalArgumentException> { evidence("evd-" + "x".repeat(300)) }
    }
}
