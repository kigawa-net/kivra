package net.kigawa.kivra.github

import java.time.Clock
import net.kigawa.kivra.core.ObservedPullRequest
import net.kigawa.kivra.core.PrMergeAuthorityBase
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeObservation
import net.kigawa.kivra.core.PullRequestBinding

/**
 * GitHub-backed merge Authority (Issue #53). It reads the bound pull request with `GET /pulls/{n}` through [GitHubPort] and
 * reports the answer. The core base class decides whether that answer matches the binding, so this class cannot produce a
 * verified merge by itself. Nothing comes from a webhook body, a caller's claim, or a search result.
 *
 * - A 404, an identity mismatch, a malformed body, a foreign object, or a redirect (never followed, so the bearer token
 *   does not travel to another origin) is a refusal.
 * - Auth, permission, rate limit, 5xx, I/O and unknown outcomes throw [PrMergeAuthorityUnavailableException]. "Not found" is
 *   never read as "not merged", and "closed" is never read as "merged".
 *
 * Reasons and exception messages are fixed codes. Provider response text and tokens are not copied into any result.
 */
class GitHubPrMergeAuthority(
    private val github: GitHubPort,
    clock: Clock = Clock.systemUTC(),
) : PrMergeAuthorityBase(clock) {

    override suspend fun observe(binding: PullRequestBinding): PrMergeObservation {
        val repo = parseRepo(binding.repository) ?: return PrMergeObservation.Refused("REPOSITORY_NAME_INVALID")
        val number = binding.prNumber.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: return PrMergeObservation.Refused("PR_NUMBER_INVALID")
        return when (val r = github.getPullRequest(repo, number)) {
            is GitHubResult.Success -> PrMergeObservation.Found(r.value.toObserved(repo))
            is GitHubResult.Failure -> when (r.kind) {
                GitHubFailureKind.NOT_FOUND,
                GitHubFailureKind.IDENTITY_MISMATCH,
                GitHubFailureKind.PROTOCOL,
                GitHubFailureKind.FOREIGN_OBJECT,
                GitHubFailureKind.REDIRECT,
                GitHubFailureKind.VALIDATION,
                -> PrMergeObservation.Refused(r.kind.code)
                GitHubFailureKind.AUTH,
                GitHubFailureKind.PERMISSION,
                GitHubFailureKind.RATE_LIMITED,
                GitHubFailureKind.SERVER_ERROR,
                GitHubFailureKind.NETWORK,
                GitHubFailureKind.CONFLICT,
                GitHubFailureKind.NOT_APPLIED,
                GitHubFailureKind.TREE_TRUNCATED,
                GitHubFailureKind.TOO_LARGE,
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
