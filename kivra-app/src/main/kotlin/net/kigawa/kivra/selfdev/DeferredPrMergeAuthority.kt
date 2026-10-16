package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PrMergeVerification
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.TrustedPrMergeAuthority

/**
 * A merge Authority that is installed after the run repository is built. [net.kigawa.kivra.db.DevelopmentRunRepository] takes its
 * Authority at construction, but the GitHub client that the production Authority needs is built later, in [buildSelfDev]. Until
 * [install] is called every verification throws [PrMergeAuthorityUnavailableException], so a merged run waits and is never completed.
 * [buildSelfDev] installs it before the worker starts. It can be installed only once.
 */
class DeferredPrMergeAuthority : TrustedPrMergeAuthority {
    @Volatile
    private var delegate: TrustedPrMergeAuthority? = null

    /** The installed Authority, or null before [install]. */
    val installed: TrustedPrMergeAuthority?
        get() = delegate

    fun install(authority: TrustedPrMergeAuthority) {
        check(delegate == null) { "the merge Authority is already installed" }
        delegate = authority
    }

    override suspend fun verifyMerge(binding: PullRequestBinding): PrMergeVerification {
        val authority = delegate ?: throw PrMergeAuthorityUnavailableException("the PR merge Authority is not installed yet")
        return authority.verifyMerge(binding)
    }
}
