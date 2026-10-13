package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import net.kigawa.kivra.core.ObservedPullRequest
import net.kigawa.kivra.core.PrMergeVerification
import net.kigawa.kivra.core.PrMergeVerifier
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.TrustedPrMergeAuthority

/**
 * Test double for the GitHub merge Authority. [observe] plays the part of GitHub's answer for a binding (null = not found).
 * The real verification path is [PrMergeVerifier]; this class only chooses what GitHub reported.
 */
class FakeTrustedPrMergeAuthority(private val clock: Clock = Clock.systemUTC()) : TrustedPrMergeAuthority {
    var observe: (PullRequestBinding) -> ObservedPullRequest? = { merged(it) }

    /** Added to the observation time, to simulate an observation in the future or an old one. */
    var observedAtOffset: Duration = Duration.ZERO

    /** When set, [verifyMerge] throws this instead of answering. */
    var failure: Throwable? = null

    override suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification {
        failure?.let { throw it }
        val observed = observe(binding) ?: return PrMergeVerification.Refused("GITHUB_NOT_FOUND")
        return PrMergeVerifier.verify(binding, observed, clock.instant().plus(observedAtOffset))
    }

    companion object {
        /** A merged PR as GitHub reports it for [b]. The merge commit differs from the head (squash-like) by default. */
        fun merged(b: PullRequestBinding, mergeSha: String? = MERGE_SHA) = ObservedPullRequest(
            repository = b.repository,
            repositoryId = b.repositoryId,
            number = b.prNumber,
            nodeId = b.prNodeId,
            baseRef = b.baseRef,
            headSha = b.headSha,
            merged = true,
            mergeCommitSha = mergeSha,
        )

        const val MERGE_SHA = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"
    }
}
