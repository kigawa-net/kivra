package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Leader lease row from the DB. Represents the current orchestrator leader.
 */
data class LeaderLease(
    val owner: String,
    val expiresAt: LocalDateTime,
    val generation: Long,
)

/**
 * Repository for the orchestrator leader lease (V41, design #3 section 5.5).
 *
 * Uses a single-row table. Acquisition is atomic: INSERT ... ON DUPLICATE KEY UPDATE (via upsert semantics)
 * succeeds only if the current lease has expired or the row does not exist.
 *
 * The DB clock (NOW(6)) is the sole judge of expiry.
 */
class LeaderLeaseRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val leaseTtl: Duration = Duration.ofSeconds(60),
) {
    /**
     * Try to acquire the leader lease.
     *
     * Returns the new [LeaderLease] if acquired, or `null` if another instance holds a valid lease.
     * The acquisition is atomic: the UPDATE affects only rows where `expires_at < NOW(6)`.
     */
    fun tryAcquire(owner: String): LeaderLease? = transaction(database) {
        val now = utcNow(clock)
        val expiresAt = now.plusSeconds(leaseTtl.seconds)
        val gen = System.currentTimeMillis()

        // Try to update the existing row only if it has expired or owned by us or empty.
        val updated = LeaderLeasesTable.update({
            (LeaderLeasesTable.id eq 0) and (
                (LeaderLeasesTable.expiresAt less now) or
                (LeaderLeasesTable.owner eq owner) or
                (LeaderLeasesTable.owner eq "")
            )
        }) {
            it[LeaderLeasesTable.owner] = owner
            it[LeaderLeasesTable.expiresAt] = expiresAt
            it[LeaderLeasesTable.generation] = gen
            it[LeaderLeasesTable.updatedAt] = now
        }

        // If the row didn't exist, insert it.
        if (updated == 0) {
            val exists = LeaderLeasesTable.selectAll().where { LeaderLeasesTable.id eq 0 }.count() > 0
            if (!exists) {
                LeaderLeasesTable.insert {
                    it[LeaderLeasesTable.id] = 0
                    it[LeaderLeasesTable.owner] = owner
                    it[LeaderLeasesTable.expiresAt] = expiresAt
                    it[LeaderLeasesTable.generation] = gen
                    it[LeaderLeasesTable.updatedAt] = now
                }
            }
        }

        // Read back the row to verify we own it.
        LeaderLeasesTable.selectAll()
            .where { LeaderLeasesTable.id eq 0 }
            .firstOrNull()
            ?.let { row: org.jetbrains.exposed.sql.ResultRow ->
                val rowOwner: String = row[LeaderLeasesTable.owner]
                val rowExpiresAt: LocalDateTime = row[LeaderLeasesTable.expiresAt]
                val rowGen: Long = row[LeaderLeasesTable.generation]
                if (rowOwner == owner && rowExpiresAt.isAfter(now)) {
                    LeaderLease(owner, rowExpiresAt, rowGen)
                } else {
                    null
                }
            }
    }

    /**
     * Renew the leader lease if this instance still owns it.
     *
     * Returns `true` if renewed, `false` if the lease is owned by another instance.
     */
    fun renew(owner: String): Boolean = transaction(database) {
        val now = utcNow(clock)
        val expiresAt = now.plusSeconds(leaseTtl.seconds)
        val updated = LeaderLeasesTable.update({
            (LeaderLeasesTable.id eq 0) and (LeaderLeasesTable.owner eq owner)
        }) {
            it[LeaderLeasesTable.expiresAt] = expiresAt
            it[LeaderLeasesTable.updatedAt] = now
        }
        updated > 0
    }

    /**
     * Read the current leader lease from the DB.
     * Returns `null` if no lease exists or if the lease has expired.
     */
    fun getCurrent(): LeaderLease? = transaction(database) {
        LeaderLeasesTable.selectAll()
            .where { LeaderLeasesTable.id eq 0 }
            .firstOrNull()
            ?.let { row: org.jetbrains.exposed.sql.ResultRow ->
                val expiresAt: LocalDateTime = row[LeaderLeasesTable.expiresAt]
                // Check expiry against DB clock.
                if (expiresAt < utcNow(clock)) {
                    null
                } else {
                    LeaderLease(
                        owner = row[LeaderLeasesTable.owner],
                        expiresAt = expiresAt,
                        generation = row[LeaderLeasesTable.generation],
                    )
                }
            }
    }

    /**
     * Release the leader lease by clearing the owner. Used for graceful shutdown.
     */
    fun release(owner: String) {
        transaction(database) {
            val now = utcNow(clock)
            LeaderLeasesTable.update({
                (LeaderLeasesTable.id eq 0) and (LeaderLeasesTable.owner eq owner)
            }) {
                it[LeaderLeasesTable.owner] = ""
                it[LeaderLeasesTable.updatedAt] = now
            }
        }
    }
}
