package net.kigawa.kivra.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustedPrMergeTest {
    private val now = Instant.parse("2026-10-13T10:00:00Z")
    private val head = "a1".repeat(20)
    private val merge = "c3".repeat(20)
    private val binding = PullRequestBinding(
        runId = RunId("01HZRUN0000000000000000001"),
        repository = "kigawa-net/kivra",
        repositoryId = 42L,
        prNumber = 7L,
        prNodeId = "PR_node_7",
        baseRef = "main",
        headSha = head,
        policyRevision = "policy-1",
        reviewManifestDigest = "manifest-1",
    )

    private fun observed(
        repository: String = "kigawa-net/kivra",
        repositoryId: Long? = 42L,
        number: Long = 7L,
        nodeId: String? = "PR_node_7",
        baseRef: String = "main",
        headSha: String = head,
        merged: Boolean = true,
        mergeCommitSha: String? = merge,
    ) = ObservedPullRequest(repository, repositoryId, number, nodeId, baseRef, headSha, merged, mergeCommitSha)

    private fun verify(o: ObservedPullRequest) = PrMergeVerifier.verify(binding, o, now)

    private fun refusedReason(o: ObservedPullRequest): String =
        assertIs<PrMergeVerification.Refused>(verify(o)).reason

    @Test
    fun `a merge that matches the binding verifies, and a squash merge keeps its own merge commit SHA`() {
        val v = assertIs<PrMergeVerification.Verified>(verify(observed())).merge
        assertEquals(binding.runId, v.runId)
        assertEquals(merge, v.mergeCommitSha)
        assertEquals(head, v.headSha)
        assertEquals(now, v.observedAt)
        assertEquals("policy-1", v.policyRevision)
        assertEquals("manifest-1", v.reviewManifestDigest)
        assertTrue(v.merged)
    }

    @Test
    fun `repository name is compared case-insensitively, identity is the repository id`() {
        assertIs<PrMergeVerification.Verified>(verify(observed(repository = "Kigawa-Net/KIVRA")))
    }

    @Test
    fun `every identity mismatch is refused with a fixed reason`() {
        assertEquals("REPOSITORY_NAME_MISMATCH", refusedReason(observed(repository = "kigawa-net/other")))
        assertEquals("REPOSITORY_ID_MISMATCH", refusedReason(observed(repositoryId = 43L)))
        assertEquals("REPOSITORY_ID_MISMATCH", refusedReason(observed(repositoryId = null)))
        assertEquals("PR_NUMBER_MISMATCH", refusedReason(observed(number = 8L)))
        assertEquals("PR_NODE_ID_MISMATCH", refusedReason(observed(nodeId = "PR_other")))
        assertEquals("PR_NODE_ID_MISMATCH", refusedReason(observed(nodeId = null)))
        assertEquals("BASE_REF_MISMATCH", refusedReason(observed(baseRef = "release")))
        assertEquals("HEAD_SHA_MISMATCH", refusedReason(observed(headSha = "d4".repeat(20))))
        assertEquals("HEAD_SHA_MISMATCH", refusedReason(observed(headSha = "not-a-sha")))
    }

    @Test
    fun `closed without merge, and a missing or malformed merge commit SHA, are refused`() {
        assertEquals("NOT_MERGED", refusedReason(observed(merged = false, mergeCommitSha = null)))
        assertEquals("NOT_MERGED", refusedReason(observed(merged = false, mergeCommitSha = merge)))
        assertEquals("MERGE_COMMIT_SHA_MISSING", refusedReason(observed(mergeCommitSha = null)))
        assertEquals("MERGE_COMMIT_SHA_MISSING", refusedReason(observed(mergeCommitSha = "")))
        assertEquals("MERGE_COMMIT_SHA_MISSING", refusedReason(observed(mergeCommitSha = "XYZ")))
    }

    @Test
    fun `the verified merge type has no source-visible public constructor, so callers cannot build a merged=true value`() {
        // Kotlin adds a synthetic accessor constructor for the companion's use of the private one; it is not callable from source.
        assertEquals(0, VerifiedPrMerge::class.java.constructors.count { !it.isSynthetic })
    }

    @Test
    fun `binding rejects blank or malformed identity fields`() {
        assertFailsWith<IllegalArgumentException> { binding.copy(repository = "no-slash") }
        assertFailsWith<IllegalArgumentException> { binding.copy(repositoryId = 0L) }
        assertFailsWith<IllegalArgumentException> { binding.copy(prNumber = 0L) }
        assertFailsWith<IllegalArgumentException> { binding.copy(prNodeId = " ") }
        assertFailsWith<IllegalArgumentException> { binding.copy(headSha = "short") }
        assertFailsWith<IllegalArgumentException> { binding.copy(policyRevision = "") }
        assertFailsWith<IllegalArgumentException> { binding.copy(reviewManifestDigest = "") }
    }

    @Test
    fun `the observation age policy is ten minutes`() {
        assertEquals(java.time.Duration.ofMinutes(10), PrMergeObservationPolicy.MAX_OBSERVATION_AGE)
    }
}
