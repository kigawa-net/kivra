package net.kigawa.kivra.github

import java.time.Clock
import net.kigawa.kivra.core.ObservedPullRequest
import net.kigawa.kivra.core.PrMergeAuthorityBase
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeObservation
import net.kigawa.kivra.core.PullRequestBinding

private val SHA40 = Regex("[0-9a-f]{40}")

/**
 * GitHub-backed merge Authority (Issue #53). It reads the bound pull request with `GET /pulls/{n}` through [GitHubPort] and
 * reports the answer. The core base class decides whether that answer matches the binding, so this class cannot produce a
 * verified merge by itself. Nothing comes from a webhook body, a caller's claim, or a search result.
 *
 * - [expectedAuthorId] is the App bot user id that must have opened the PR. A missing author id is a refusal.
 * - A 404, an identity mismatch, a malformed body, a foreign object, or a redirect (never followed, so the bearer token
 *   does not travel to another origin) is a refusal.
 * - Auth, permission, rate limit, 5xx, I/O and unknown outcomes throw [PrMergeAuthorityUnavailableException]. "Not found" is
 *   never read as "not merged", and "closed" is never read as "merged".
 *
 * - [behindBy] is `behind_by` of `GET /compare/{base}...{head}` (see [PrBranchPort.getBehindBy]). It is asked only for a merged PR
 *   whose head moved after the binding: `behindBy(repo, boundHead, mergedHead) == 0` means the bound head is an ancestor of the
 *   merged head, so the branch only moved forward (ADR 0008 追補 2026-11-20). A definitive "not an ancestor" (for example the bound
 *   commit is gone after a force push, a 404) leaves the flag false, so the head mismatch is refused. A transient failure or an
 *   unknown outcome throws [PrMergeAuthorityUnavailableException], so nothing is completed and the check is retried.
 *
 * Reasons and exception messages are fixed codes. Provider response text and tokens are not copied into any result.
 */
class GitHubPrMergeAuthority(
    private val github: GitHubPort,
    expectedAuthorId: Long,
    private val behindBy: suspend (RepoRef, String, String) -> GitHubResult<Int>,
    clock: Clock = Clock.systemUTC(),
) : PrMergeAuthorityBase(expectedAuthorId, clock) {

    override suspend fun observe(binding: PullRequestBinding): PrMergeObservation {
        val repo = parseRepo(binding.repository) ?: return PrMergeObservation.Refused("REPOSITORY_NAME_INVALID")
        val number = binding.prNumber.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: return PrMergeObservation.Refused("PR_NUMBER_INVALID")
        return when (val r = github.getPullRequest(repo, number)) {
            is GitHubResult.Success -> PrMergeObservation.Found(r.value.toObserved(repo, binding.headSha))
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

    private suspend fun PullRequest.toObserved(repo: RepoRef, boundHeadSha: String) = ObservedPullRequest(
        repository = repo.toString(),
        repositoryId = baseRepositoryId,
        number = number.toLong(),
        nodeId = nodeId,
        baseRef = baseRef,
        headSha = headSha,
        merged = merged,
        mergeCommitSha = mergeCommitSha,
        authorId = authorId,
        recordedHeadIsAncestor = merged && headSha != boundHeadSha && SHA40.matches(headSha) &&
            boundHeadIsAncestor(repo, boundHeadSha, headSha),
    )

    /**
     * True only when GitHub answered that [boundHeadSha] has no commits that [mergedHeadSha] lacks. Every other definitive answer
     * (not found, a protocol failure, a count that is not zero) is false. Transient failures and unknown outcomes throw.
     */
    private suspend fun boundHeadIsAncestor(repo: RepoRef, boundHeadSha: String, mergedHeadSha: String): Boolean =
        when (val r = behindBy(repo, boundHeadSha, mergedHeadSha)) {
            is GitHubResult.Success -> r.value == 0
            is GitHubResult.Failure -> when (r.kind) {
                GitHubFailureKind.NOT_FOUND,
                GitHubFailureKind.IDENTITY_MISMATCH,
                GitHubFailureKind.PROTOCOL,
                GitHubFailureKind.FOREIGN_OBJECT,
                GitHubFailureKind.REDIRECT,
                GitHubFailureKind.VALIDATION,
                -> false
                GitHubFailureKind.AUTH,
                GitHubFailureKind.PERMISSION,
                GitHubFailureKind.RATE_LIMITED,
                GitHubFailureKind.SERVER_ERROR,
                GitHubFailureKind.NETWORK,
                GitHubFailureKind.CONFLICT,
                GitHubFailureKind.NOT_APPLIED,
                GitHubFailureKind.TREE_TRUNCATED,
                GitHubFailureKind.TOO_LARGE,
                -> throw PrMergeAuthorityUnavailableException("GitHub merge ancestry unavailable: ${r.kind.code}")
            }
            is GitHubResult.Unknown -> throw PrMergeAuthorityUnavailableException("GitHub merge ancestry unknown")
        }

    private fun parseRepo(fullName: String): RepoRef? {
        val parts = fullName.split('/')
        if (parts.size != 2) return null
        return runCatching { RepoRef(parts[0], parts[1]) }.getOrNull()
    }
}
