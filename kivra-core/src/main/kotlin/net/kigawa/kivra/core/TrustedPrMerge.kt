package net.kigawa.kivra.core

import java.time.Clock
import java.time.Duration
import java.time.Instant

/*
 * Trusted PR merge Authority (Issue #53; completion path for #43 PR_ONLY and #56 Integration Runner).
 *
 * The caller of a PR_ONLY completion supplies no evidence. The completion repository reads the run's own
 * [PullRequestBinding], hands it to the injected [TrustedPrMergeAuthority], and completes only with the result. Like
 * TrustedEvidenceAuthority (#86), the Authority is the only source of a merge fact.
 *
 * How the trust is enforced (by design, not by reflection):
 * - [VerifiedPrMerge] has a private constructor. Its only factory is internal to kivra-core.
 * - [PrMergeVerifier] is internal. Other modules cannot call it.
 * - An implementation in another module extends [PrMergeAuthorityBase]. It supplies [PrMergeAuthorityBase.observe]
 *   (what the provider reported). The base class's final [PrMergeAuthorityBase.verifyMerge] compares that observation
 *   with the binding and is the only way to obtain a [PrMergeVerification.Verified]. A direct implementation of
 *   [TrustedPrMergeAuthority] can therefore only refuse.
 * - [ObservedPullRequest] is inert data. Holding one grants nothing, because it has to pass through the base class.
 *   Whether an observation really came from GitHub is the trust of the implementation (transport, token, identity).
 *   The type system cannot prove it.
 *
 * Closed is not merged; a merge commit SHA is never assumed to equal the PR head SHA (Squash / Rebase merges differ).
 *
 * Not verified here (out of scope for this slice):
 * - [PullRequestBinding.policyRevision] and [PullRequestBinding.reviewManifestDigest] come from the caller at bind time.
 *   They are echoed into [VerifiedPrMerge] and compared against the run; no provider fact proves them. Their authoritative
 *   generation and check belong to #49 (Review Evidence Manifest) and #50 (Policy Revision / Eligibility).
 * - Webhook signature (HMAC) verification and Merge Queue `merge_group` SHA binding (#53 / #56).
 */

/** Maximum age of a GitHub observation accepted at PR_ONLY completion. Checked against the repository clock. */
object PrMergeObservationPolicy {
    val MAX_OBSERVATION_AGE: Duration = Duration.ofMinutes(10)
}

private val SHA40 = Regex("[0-9a-f]{40}")
private val REPO_FULL_NAME = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")

/**
 * What a PR_ONLY run recorded when it bound its pull request. Every identity field is required. A new bind (for example
 * after a push) replaces the whole binding, so a merge verified against an older binding is refused.
 */
data class PullRequestBinding(
    val runId: RunId,
    /** Repository full name `owner/name`, as the run was bound. Compared case-insensitively against the provider. */
    val repository: String,
    /** The provider's immutable numeric repository id (base repository). Survives renames. */
    val repositoryId: Long,
    val prNumber: Long,
    /** GraphQL node id of the pull request. */
    val prNodeId: String,
    val baseRef: String,
    /** The PR head SHA the run is bound to. */
    val headSha: String,
    val policyRevision: String,
    val reviewManifestDigest: String,
) {
    init {
        require(REPO_FULL_NAME.matches(repository)) { "repository must be owner/name" }
        require(repositoryId > 0) { "repositoryId must be positive" }
        require(prNumber > 0) { "prNumber must be positive" }
        // The completion stores "github-pr:<node id>" in a VARCHAR(255) column, so the node id is capped at 200.
        require(prNodeId.isNotBlank() && prNodeId.length <= 200) { "prNodeId is missing or too long" }
        require(baseRef.isNotBlank() && baseRef.length <= 255) { "baseRef is missing or too long" }
        require(SHA40.matches(headSha)) { "headSha must be 40 lowercase hex chars" }
        require(policyRevision.isNotBlank() && policyRevision.length <= 255) { "policyRevision is missing or too long" }
        require(reviewManifestDigest.isNotBlank() && reviewManifestDigest.length <= 255) { "reviewManifestDigest is missing or too long" }
    }
}

/**
 * What the provider reported for the pull request. Inert input to the base class's verification; holding one grants
 * nothing. Nullable identity fields mean the response did not carry them, and verification fails closed on them.
 * [authorId] is the provider's numeric user id of the pull request author (the App bot, for kivra-created PRs).
 */
data class ObservedPullRequest(
    val repository: String,
    val repositoryId: Long?,
    val number: Long,
    val nodeId: String?,
    val baseRef: String,
    val headSha: String,
    val merged: Boolean,
    val mergeCommitSha: String?,
    val authorId: Long?,
)

/**
 * A merge of the bound pull request, verified against the binding. Only the internal verifier creates it, so it can
 * only come from a [PrMergeAuthorityBase]. The completion repository compares it again with the run's current binding
 * inside the transaction.
 */
class VerifiedPrMerge private constructor(
    val runId: RunId,
    val repository: String,
    val repositoryId: Long,
    val prNumber: Long,
    val prNodeId: String,
    val baseRef: String,
    /** The PR head as merged (equal to the bound head). */
    val headSha: String,
    val merged: Boolean,
    val mergeCommitSha: String,
    val policyRevision: String,
    val reviewManifestDigest: String,
    val observedAt: Instant,
) {
    init {
        require(merged) { "only a merged pull request can be verified as merged" }
        require(SHA40.matches(mergeCommitSha)) { "mergeCommitSha must be 40 lowercase hex chars" }
    }

    override fun toString(): String = "VerifiedPrMerge(runId=${runId.value}, prNumber=$prNumber, observedAt=$observedAt)"

    companion object {
        /** Internal to kivra-core: only [PrMergeVerifier] calls it. */
        internal fun issue(binding: PullRequestBinding, mergeCommitSha: String, observedAt: Instant): VerifiedPrMerge = VerifiedPrMerge(
            runId = binding.runId,
            repository = binding.repository,
            repositoryId = binding.repositoryId,
            prNumber = binding.prNumber,
            prNodeId = binding.prNodeId,
            baseRef = binding.baseRef,
            headSha = binding.headSha,
            merged = true,
            mergeCommitSha = mergeCommitSha,
            policyRevision = binding.policyRevision,
            reviewManifestDigest = binding.reviewManifestDigest,
            observedAt = observedAt,
        )
    }
}

/** Result of [TrustedPrMergeAuthority.verifyMerge]. A refusal carries a fixed reason code, never provider text. */
sealed interface PrMergeVerification {
    data class Verified(val merge: VerifiedPrMerge) : PrMergeVerification

    data class Refused(val reason: String) : PrMergeVerification
}

/** What an implementation reports for one pull request: the observation, or a fixed refusal reason (for example not found). */
sealed interface PrMergeObservation {
    data class Found(val pullRequest: ObservedPullRequest) : PrMergeObservation

    data class Refused(val reason: String) : PrMergeObservation
}

/** Compares an observation with the binding. Internal: callers outside kivra-core reach it only through [PrMergeAuthorityBase]. */
internal object PrMergeVerifier {
    /**
     * Every identity field must match exactly (repository name case-insensitively). The PR must be authored by
     * [expectedAuthorId] (a missing author id is refused), must be merged, and must carry a merge SHA.
     */
    fun verify(
        binding: PullRequestBinding,
        expectedAuthorId: Long,
        observed: ObservedPullRequest,
        observedAt: Instant,
    ): PrMergeVerification {
        fun refuse(why: String) = PrMergeVerification.Refused(why)
        if (!observed.repository.equals(binding.repository, ignoreCase = true)) return refuse("REPOSITORY_NAME_MISMATCH")
        if (observed.repositoryId != binding.repositoryId) return refuse("REPOSITORY_ID_MISMATCH")
        if (observed.number != binding.prNumber) return refuse("PR_NUMBER_MISMATCH")
        if (observed.nodeId != binding.prNodeId) return refuse("PR_NODE_ID_MISMATCH")
        if (observed.baseRef != binding.baseRef) return refuse("BASE_REF_MISMATCH")
        if (!SHA40.matches(observed.headSha) || observed.headSha != binding.headSha) return refuse("HEAD_SHA_MISMATCH")
        if (observed.authorId == null || observed.authorId != expectedAuthorId) return refuse("AUTHOR_MISMATCH")
        if (!observed.merged) return refuse("NOT_MERGED")
        val mergeSha = observed.mergeCommitSha
        if (mergeSha == null || !SHA40.matches(mergeSha)) return refuse("MERGE_COMMIT_SHA_MISSING")
        return PrMergeVerification.Verified(VerifiedPrMerge.issue(binding, mergeSha, observedAt))
    }
}

/**
 * Asks the merge Authority whether the bound pull request is merged. The repository injects an implementation and never
 * supplies evidence itself. A refusal is returned. An unavailable Authority throws, like [TrustedEvidenceAuthority].
 * Callers must not complete a run on any other outcome.
 */
interface TrustedPrMergeAuthority {
    suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification
}

/**
 * Base for production and test Authorities. The implementation only reports what the provider answered ([observe]).
 * [verifyMerge] is final: it runs [PrMergeVerifier] against that report, with the observation time taken from [clock] after
 * [observe] returned. This is the only route to [PrMergeVerification.Verified].
 *
 * [expectedAuthorId] is the provider user id that must have opened the pull request (the kivra App bot). It is supplied
 * by whoever builds the Authority, not stored per run, so it needs no binding column.
 */
abstract class PrMergeAuthorityBase(
    private val expectedAuthorId: Long,
    private val clock: Clock = Clock.systemUTC(),
) : TrustedPrMergeAuthority {
    init {
        require(expectedAuthorId > 0) { "expectedAuthorId must be positive" }
    }

    /**
     * Fetches the current state of the bound pull request from the provider. Return [PrMergeObservation.Refused] for a
     * definitive refusal (not found, identity mismatch, malformed answer). Throw [PrMergeAuthorityUnavailableException]
     * when the provider cannot answer. Do not copy provider response text into the result or into exception messages.
     */
    protected abstract suspend fun observe(binding: PullRequestBinding): PrMergeObservation

    final override suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification =
        when (val o = observe(binding)) {
            is PrMergeObservation.Refused -> PrMergeVerification.Refused(o.reason)
            is PrMergeObservation.Found -> PrMergeVerifier.verify(binding, expectedAuthorId, o.pullRequest, clock.instant())
        }
}

/** The merge Authority could not answer (auth, permission, rate limit, 5xx, I/O, unknown). Nothing is completed. */
class PrMergeAuthorityUnavailableException(message: String) : RuntimeException(message)
