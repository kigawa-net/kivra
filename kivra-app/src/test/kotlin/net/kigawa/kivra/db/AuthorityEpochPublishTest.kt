package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.transactions.transaction
import net.kigawa.kivra.core.ProviderCapability

private const val TENANT = "tenant-1"
private const val PROVIDER = "EXKES"
private const val CONTRACT = "2026-01"
private const val ISSUER = "authority-sync-a"

private fun snap(
    epoch: Long,
    grant: Long = 1,
    capRev: Long = 1,
    contract: String = CONTRACT,
    capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
) = AuthorityEpochSnapshot(contract, capability, capRev, epoch, grant)

private fun TestDb.publish(s: AuthorityEpochSnapshot, issuer: String = ISSUER) = epochs.publish(TENANT, PROVIDER, s, issuer)

private fun TestDb.stored(): AuthorityEpochSnapshot = assertNotNull(epochs.current(TENANT, PROVIDER))

/** The recorded issuer and publish time, read straight from the table. */
private fun TestDb.publishMeta(): Pair<String?, LocalDateTime?> = transaction(database) {
    execQuery(
        "SELECT published_by, published_at FROM evidence_authority_epochs WHERE tenant_id = ? AND provider_id = ?",
        TENANT, PROVIDER,
    ) { rs -> rs.getString(1) to rs.getObject(2, LocalDateTime::class.java) }.single()
}

/**
 * Issue #100: monotonic publication of the Authority Epoch copy, keyed by (tenant, provider). Every rejected publish must
 * leave the recorded values untouched.
 */
class AuthorityEpochPublishTest {

    @Test
    fun `an older authority epoch is refused and the recorded values do not change`() = TestDb().use { db ->
        db.publish(snap(epoch = 5, grant = 3, capRev = 2))
        val before = db.stored()
        assertFailsWith<StaleAuthorityEpochException> { db.publish(snap(epoch = 4, grant = 3, capRev = 2)) }
        assertEquals(before, db.stored())
    }

    @Test
    fun `an identical snapshot at the same epoch is accepted idempotently`() = TestDb().use { db ->
        db.publish(snap(epoch = 2, grant = 2, capRev = 1))
        val before = db.stored()
        db.publish(snap(epoch = 2, grant = 2, capRev = 1))
        db.publish(snap(epoch = 2, grant = 2, capRev = 1))
        assertEquals(before, db.stored())
    }

    @Test
    fun `a different snapshot at the same epoch is refused as a conflict and the record is kept`() = TestDb().use { db ->
        db.publish(snap(epoch = 2, grant = 2, capRev = 1))
        val before = db.stored()
        assertFailsWith<ConflictingAuthorityEpochException> { db.publish(snap(epoch = 2, grant = 1, capRev = 1)) }
        assertFailsWith<ConflictingAuthorityEpochException> { db.publish(snap(epoch = 2, grant = 2, capRev = 1, contract = "2026-02")) }
        assertEquals(before, db.stored())
    }

    @Test
    fun `a grant generation rollback is refused even when the epoch moves forward`() = TestDb().use { db ->
        db.publish(snap(epoch = 2, grant = 2))
        val before = db.stored()
        assertFailsWith<StaleAuthorityEpochException> { db.publish(snap(epoch = 3, grant = 1)) }
        assertEquals(before, db.stored())
    }

    @Test
    fun `a capability revision rollback is refused even when the epoch moves forward`() = TestDb().use { db ->
        db.publish(snap(epoch = 2, capRev = 3))
        val before = db.stored()
        assertFailsWith<StaleAuthorityEpochException> { db.publish(snap(epoch = 3, capRev = 2)) }
        assertEquals(before, db.stored())
    }

    @Test
    fun `a contract or capability change must advance the capability revision`() = TestDb().use { db ->
        db.publish(snap(epoch = 1, capRev = 1))
        val before = db.stored()
        // Epoch moves, capability changes, revision does not: refused.
        assertFailsWith<ConflictingAuthorityEpochException> {
            db.publish(snap(epoch = 2, capRev = 1, capability = ProviderCapability.LOOKUP_BY_STABLE_REF))
        }
        assertEquals(before, db.stored())
        // Same change with the revision advanced: accepted.
        db.publish(snap(epoch = 2, capRev = 2, capability = ProviderCapability.LOOKUP_BY_STABLE_REF))
        assertEquals(snap(epoch = 2, capRev = 2, capability = ProviderCapability.LOOKUP_BY_STABLE_REF), db.stored())
    }

    @Test
    fun `a forward epoch with non-decreasing revisions is accepted`() = TestDb().use { db ->
        db.publish(snap(epoch = 1, grant = 1, capRev = 1))
        db.publish(snap(epoch = 2, grant = 2, capRev = 1))
        db.publish(snap(epoch = 3, grant = 2, capRev = 2, contract = "2026-02"))
        assertEquals(snap(epoch = 3, grant = 2, capRev = 2, contract = "2026-02"), db.stored())
    }

    @Test
    fun `the issuer and the publish time are recorded`() = TestDb().use { db ->
        db.publish(snap(epoch = 1), issuer = "authority-sync-a")
        val (by, at) = db.publishMeta()
        assertEquals("authority-sync-a", by)
        assertNotNull(at)
        assertFailsWith<IllegalArgumentException> { db.publish(snap(epoch = 2), issuer = " ") }
        assertFailsWith<IllegalArgumentException> { db.publish(snap(epoch = 2), issuer = "x".repeat(129)) }
    }

    @Test
    fun `a restart replays the same snapshot idempotently and still refuses older ones`() = TestDb().use { db ->
        db.publish(snap(epoch = 4, grant = 2, capRev = 2))
        val before = db.stored()
        // A fresh repository instance on the same database, as after a process restart.
        val restarted = AuthorityEpochRepository(db.database)
        restarted.publish(TENANT, PROVIDER, snap(epoch = 4, grant = 2, capRev = 2), ISSUER)
        assertEquals(before, restarted.current(TENANT, PROVIDER))
        assertFailsWith<StaleAuthorityEpochException> { restarted.publish(TENANT, PROVIDER, snap(epoch = 3), ISSUER) }
        assertEquals(before, restarted.current(TENANT, PROVIDER))
    }

    @Test
    fun `two concurrent first publishes converge to the newest snapshot`() = TestDb().use { db ->
        // The locked read of a missing key (SELECT ... FOR UPDATE) serializes the two first publishes on both H2 and MariaDB,
        // so the loser normally re-reads the winner's row instead of hitting a unique conflict. The conflict retry in publish
        // is a fallback for databases or isolation levels that do not serialize that read; these tests do not reach it.
        val repo = AuthorityEpochRepository(db.database)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = listOf(snap(epoch = 1), snap(epoch = 2)).map { s ->
                pool.submit<Throwable?> { runCatching { repo.publish(TENANT, PROVIDER, s, ISSUER) }.exceptionOrNull() }
            }.map { it.get(60, TimeUnit.SECONDS) }
            // The older snapshot either landed first and was superseded, or was refused as stale once the newer one won.
            outcomes.filterNotNull().forEach { assertTrue(it is StaleAuthorityEpochException, "unexpected failure: $it") }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(snap(epoch = 2), db.stored())
    }

    @Test
    fun `a copy published longer ago than the freshness bound is refused by requireFresh`() = TestDb().use { db ->
        val t0 = Instant.parse("2026-10-13T00:00:00Z")
        AuthorityEpochRepository(db.database, Clock.fixed(t0, java.time.ZoneOffset.UTC))
            .publish(TENANT, PROVIDER, snap(epoch = 1), ISSUER)
        val withinBound = AuthorityEpochRepository(db.database, Clock.fixed(t0.plus(Duration.ofMinutes(4)), java.time.ZoneOffset.UTC))
        assertEquals(snap(epoch = 1), withinBound.requireFresh(TENANT, PROVIDER))
        val beyondBound = AuthorityEpochRepository(db.database, Clock.fixed(t0.plus(Duration.ofMinutes(6)), java.time.ZoneOffset.UTC))
        assertFailsWith<AuthorityEpochNotFreshException> { beyondBound.requireFresh(TENANT, PROVIDER) }
        // The record itself is untouched by the read.
        assertEquals(snap(epoch = 1), beyondBound.current(TENANT, PROVIDER))
    }

    @Test
    fun `requireFresh fails closed for a missing copy, a copy with no publish time, and a publish time in the future`() = TestDb().use { db ->
        val t0 = Instant.parse("2026-10-13T00:00:00Z")
        val at = AuthorityEpochRepository(db.database, Clock.fixed(t0, java.time.ZoneOffset.UTC))
        assertFailsWith<AuthorityEpochNotFreshException> { at.requireFresh(TENANT, PROVIDER) }

        // A row written before V8 has no publish time.
        transaction(db.database) {
            execUpdate(
                """
                INSERT INTO evidence_authority_epochs
                    (tenant_id, provider_id, provider_contract_version, capability, capability_revision,
                     authority_epoch, grant_generation, updated_at)
                VALUES (?, ?, ?, ?, 1, 1, 1, NOW(6))
                """.trimIndent(),
                TENANT, PROVIDER, CONTRACT, ProviderCapability.AUTHORITATIVE_ABSENCE.name,
            )
        }
        assertFailsWith<AuthorityEpochNotFreshException> { at.requireFresh(TENANT, PROVIDER) }

        // Published "in the future" relative to the checking clock: refused.
        AuthorityEpochRepository(db.database, Clock.fixed(t0.plus(Duration.ofMinutes(1)), java.time.ZoneOffset.UTC))
            .publish(TENANT, PROVIDER, snap(epoch = 2), ISSUER)
        assertFailsWith<AuthorityEpochNotFreshException> { at.requireFresh(TENANT, PROVIDER) }
    }

    @Test
    fun `a republish refreshes the freshness reference`() = TestDb().use { db ->
        val t0 = Instant.parse("2026-10-13T00:00:00Z")
        AuthorityEpochRepository(db.database, Clock.fixed(t0, java.time.ZoneOffset.UTC))
            .publish(TENANT, PROVIDER, snap(epoch = 1), ISSUER)
        val later = t0.plus(Duration.ofMinutes(10))
        AuthorityEpochRepository(db.database, Clock.fixed(later, java.time.ZoneOffset.UTC))
            .publish(TENANT, PROVIDER, snap(epoch = 1), ISSUER)
        assertEquals(snap(epoch = 1), AuthorityEpochRepository(db.database, Clock.fixed(later.plusSeconds(60), java.time.ZoneOffset.UTC))
            .requireFresh(TENANT, PROVIDER))
    }

    @Test
    fun `a missing copy reads as null, not as a zero epoch`() = TestDb().use { db ->
        assertNull(db.epochs.current(TENANT, PROVIDER))
    }
}
