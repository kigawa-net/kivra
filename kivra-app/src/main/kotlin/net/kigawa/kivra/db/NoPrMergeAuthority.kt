package net.kigawa.kivra.db

import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeVerification
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.TrustedPrMergeAuthority

/**
 * Placeholder for deployments without a PR merge Authority (the Task API does not use one). It fails closed: a merge
 * can never be verified through it, so a PR_ONLY run cannot complete here.
 */
object NoPrMergeAuthority : TrustedPrMergeAuthority {
    override suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification =
        throw PrMergeAuthorityUnavailableException("no PR merge Authority is configured in this deployment")
}
