package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import net.kigawa.kivra.core.ObservedPullRequest
import net.kigawa.kivra.core.PrMergeAuthorityBase
import net.kigawa.kivra.core.PrMergeObservation
import net.kigawa.kivra.core.PullRequestBinding

/**
 * TEST ONLY merge Authority. It extends [PrMergeAuthorityBase] like the production GitHub Authority, so every Verified result
 * goes through the same core verification. [answer] plays GitHub's reply for a binding (null = not found).
 */
class FakeTrustedPrMergeAuthority(val clock: AdjustableClock = AdjustableClock()) : PrMergeAuthorityBase(clock) {
    var answer: (PullRequestBinding) -> ObservedPullRequest? = { merged(it) }

    /** When set, [observe] throws this instead of answering. */
    var failure: Throwable? = null

    /** Runs after the binding was read and before the answer. Used to simulate a push or re-bind during verification. */
    var beforeAnswer: (PullRequestBinding) -> Unit = {}

    /** Number of observations requested, to show when the Authority was (not) asked. */
    var calls: Int = 0

    override suspend fun observe(binding: PullRequestBinding): PrMergeObservation {
        calls++
        failure?.let { throw it }
        beforeAnswer(binding)
        val observed = answer(binding) ?: return PrMergeObservation.Refused("GITHUB_NOT_FOUND")
        return PrMergeObservation.Found(observed)
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

/** A clock whose reading can be moved, to produce future or old observation times. */
class AdjustableClock(var offset: Duration = Duration.ZERO) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.now().plus(offset)
}
