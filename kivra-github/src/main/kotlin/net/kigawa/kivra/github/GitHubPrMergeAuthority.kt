package net.kigawa.kivra.github

import java.time.Clock
import net.kigawa.kivra.core.ObservedPullRequest
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeVerification
import net.kigawa.kivra.core.PrMergeVerifier
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.TrustedPrMergeAuthority

/**
 * GitHub-backed [TrustedPrMergeAuthority] (Issue #53). It asks GitHub for the bound pull request (`GET /pulls/{n}`) and hands
 * the observation to [PrMergeVerifier]. Nothing is taken from a webhook body, a caller's claim, or a search result.
 *
 * - A response that verifies is `Verified`. A response that disagrees with the binding (other number, node id, repository id,
 *   base, head, not merged, no merge SHA) is `Refused`.
 * - A GitHub failure that says the PR is not visible or not ours (404, identity mismatch, malformed body, foreign object,
 *   redirect) is `Refused`. Redirects are never followed, so a bearer token does not travel to another origin.
 * - Failures that say nothing about the PR (auth, permission, rate limit, 5xx, I/O, unknown) throw
 *   [PrMergeAuthorityUnavailableException]. "Not found" is never read as "not merged", and "closed" is never read as "merged".
 *
 * Reasons and exception messages are fixed codes. Provider response text and tokens are not copied into any result.
 */
class GitHubPrMergeAuthority(
    private val github: GitHubPort,
    private val clock: Clock = Clock.systemUTC(),
) : TrustedPrMergeAuthority {

    override suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification {
        val repo = parseRepo(binding.repository) ?: return PrMergeVerification.Refused("REPOSITORY_NAME_INVALID")
        val number = binding.prNumber.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: return PrMergeVerification.Refused("PR_NUMBER_INVALID")
        return when (val r = github.getPullRequest(repo, number)) {
            is GitHubResult.Success -> {
                // Observation time is taken after the response arrived; the completion checks its age against this instant.
                val observedAt = clock.instant()
                PrMergeVerifier.verify(binding, r.value.toObserved(repo), observedAt)
            }
            is GitHubResult.Failure -> when (r.kind) {
                GitHubFailureKind.NOT_FOUND,
                GitHubFailureKind.IDENTITY_MISMATCH,
                GitHubFailureKind.PROTOCOL,
                GitHubFailureKind.FOREIGN_OBJECT,
                GitHubFailureKind.REDIRECT,
                GitHubFailureKind.VALIDATION,
                -> PrMergeVerification.Refused(r.kind.code)
                GitHubFailureKind.AUTH,
                GitHubFailureKind.PERMISSION,
                GitHubFailureKind.RATE_LIMITED,
                GitHubFailureKind.SERVER_ERROR,
                GitHubFailureKind.NETWORK,
                GitHubFailureKind.CONFLICT,
                -> throw PrMergeAuthorityUnavailableException("GitHub merge observation unavailable: ${r.kind.code}")
            }
            is GitHubResult.Unknown -> throw PrMergeAuthorityUnavailableException("GitHub merge observation unknown")
        }
    }

    private fun PullRequest.toObserved(repo: RepoRef) = ObservedPullRequest(
        repository = repo.toString(),
        repositoryId = baseRepositoryId,
        number = number.toLong(),
        nodeId = nodeId,
        baseRef = baseRef,
        headSha = headSha,
        merged = merged,
        mergeCommitSha = mergeCommitSha,
    )

    private fun parseRepo(fullName: String): RepoRef? {
        val parts = fullName.split('/')
        if (parts.size != 2) return null
        return runCatching { RepoRef(parts[0], parts[1]) }.getOrNull()
    }
}
