package net.kigawa.kivra.core

import java.time.Duration

@JvmInline
value class RunId(val value: String) {
    override fun toString(): String = value
}

/**
 * A held Run Lease. [generation] is the fencing token: every write to a Run
 * must be conditional on `lease_owner`, `lease_generation` and `lease_expires_at > NOW`
 * (design #10 section 5.2 / #11 section 3.3).
 */
data class Lease(val runId: RunId, val owner: String, val generation: Long)

/**
 * Run Lease abstraction (design #11 section 3.4). The MVP implementation is MariaDB;
 * a Fortis implementation may replace it later (design #12). Expiry is always judged by
 * the database clock, never by the worker's clock.
 */
interface LeaseStore {
    /** Acquires a lease if the run is non-terminal, not held, and any previous lease expired more than [grace] ago. Bumps the generation. */
    fun acquire(runId: RunId, owner: String, ttl: Duration, grace: Duration = Duration.ZERO): Lease?

    /** Extends a still-valid lease. Never revives an expired lease and never changes the generation. */
    fun renew(lease: Lease, ttl: Duration): Boolean

    /** Gives the lease up. The generation is kept so that it stays monotonic. */
    fun release(lease: Lease): Boolean
}

/** The caller no longer owns the lease (expired, superseded, or the run reached a terminal state). */
class LeaseLostException(message: String) : RuntimeException(message)

/** The expected `version` / state did not match; reload and re-decide. */
class VersionConflictException(message: String) : RuntimeException(message)

class InvalidTransitionException(message: String) : RuntimeException(message)
