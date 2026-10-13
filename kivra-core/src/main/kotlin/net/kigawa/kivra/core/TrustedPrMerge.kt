package net.kigawa.kivra.core

import java.time.Duration
import java.time.Instant

/*
 * Trusted PR merge Authority (Issue #53; completion port for #43 PR_ONLY and #56 Integration Runner).
 *
 * A PR_ONLY run is completed only with a [VerifiedPrMerge]. That value cannot be built by a caller: its constructor is private
 * and the only producer is [PrMergeVerifier.verify], which compares what the GitHub Authority observed on the PR endpoint with
 * the [PullRequestBinding] the run recorded. The Authority implementation (kivra-github, GitHubPrMergeAuthority) is the
 * production source of observations. The verifier cannot prove that an observation came from GitHub: that trust is the
 * Authority's (transport, token, identity checks), not a property of this type.
 *
 * Closed is not merged; a merge commit SHA is never assumed to equal the PR head SHA (Squash / Rebase merges differ).
 *
 * What is NOT verified here (out of scope for this slice):
 * - [PullRequestBinding.policyRevision] and [PullRequestBinding.reviewManifestDigest] come from the caller at bind time. They
 *   are echoed into [VerifiedPrMerge] and compared against the run; no GitHub fact proves them. Their authoritative generation
 *   and check belong to #49 (Review Evidence Manifest) and #50 (Policy Revision / Eligibility).
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
 * after a push) replaces the whole binding, so a verified merge of an older binding is refused.
 */
data class PullRequestBinding(
    val runId: RunId,
    /** Repository full name `owner/name`, as the run was bound. Compared case-insensitively against GitHub. */
    val repository: String,
    /** GitHub's immutable numeric repository id (base repository). Survives renames. */
    val repositoryId: Long,
    val prNumber: Long,
    /** GitHub GraphQL node id of the pull request. */
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
        require(prNodeId.isNotBlank() && prNodeId.length <= 200) { "prNodeId is missing or too long" }
        require(baseRef.isNotBlank() && baseRef.length <= 255) { "baseRef is missing or too long" }
        require(SHA40.matches(headSha)) { "headSha must be 40 lowercase hex chars" }
        require(policyRevision.isNotBlank() && policyRevision.length <= 255) { "policyRevision is missing or too long" }
        require(reviewManifestDigest.isNotBlank() && reviewManifestDigest.length <= 255) { "reviewManifestDigest is missing or too long" }
    }
}

/**
 * What the GitHub Authority read from the pull request endpoint. Input to [PrMergeVerifier]; holding one grants nothing.
 * Nullable identity fields mean the response did not carry them, and verification fails closed on them.
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
)

/**
 * A merge of the bound pull request, verified against the binding. Only [PrMergeVerifier] creates it. The completion
 * repository compares it again with the run's current binding inside the transaction.
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
        /** Creates a verified merge. Internal to kivra-core: only [PrMergeVerifier] calls it. */
        internal fun issue(
            binding: PullRequestBinding,
            mergeCommitSha: String,
            observedAt: Instant,
        ): VerifiedPrMerge = VerifiedPrMerge(
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

object PrMergeVerifier {
    /**
     * Compares a GitHub observation with the run's binding. Every identity field must match exactly (repository name
     * case-insensitively, repository id, PR number, node id, base ref, head SHA). The PR must be merged with a merge
     * commit SHA. Time checks are the completion's job, because they depend on the repository clock.
     */
    fun verify(binding: PullRequestBinding, observed: ObservedPullRequest, observedAt: Instant): PrMergeVerification {
        fun refuse(why: String) = PrMergeVerification.Refused(why)
        if (!observed.repository.equals(binding.repository, ignoreCase = true)) return refuse("REPOSITORY_NAME_MISMATCH")
        if (observed.repositoryId != binding.repositoryId) return refuse("REPOSITORY_ID_MISMATCH")
        if (observed.number != binding.prNumber) return refuse("PR_NUMBER_MISMATCH")
        if (observed.nodeId != binding.prNodeId) return refuse("PR_NODE_ID_MISMATCH")
        if (observed.baseRef != binding.baseRef) return refuse("BASE_REF_MISMATCH")
        if (!SHA40.matches(observed.headSha) || observed.headSha != binding.headSha) return refuse("HEAD_SHA_MISMATCH")
        if (!observed.merged) return refuse("NOT_MERGED")
        val mergeSha = observed.mergeCommitSha
        if (mergeSha == null || !SHA40.matches(mergeSha)) return refuse("MERGE_COMMIT_SHA_MISSING")
        return PrMergeVerification.Verified(VerifiedPrMerge.issue(binding, mergeSha, observedAt))
    }
}

/**
 * Verifies the merge state of a bound pull request with GitHub as the authority. Like [TrustedEvidenceAuthority], a refusal is
 * returned, and an unavailable Authority throws. Callers must not complete a run on any other outcome.
 */
interface TrustedPrMergeAuthority {
    suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification
}

/** The GitHub Authority could not answer (auth, permission, rate limit, 5xx, I/O, unknown). Nothing is completed. */
class PrMergeAuthorityUnavailableException(message: String) : RuntimeException(message)
