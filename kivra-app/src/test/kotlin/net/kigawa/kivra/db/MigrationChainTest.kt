package net.kigawa.kivra.db

import java.nio.file.Files
import java.time.Duration
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.VerifiedEvidenceRef
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationState
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)
private val HASH = "a".repeat(64)

/** Migration files on the classpath, by version. They are copied, never edited, when a partial history is built. */
private val MIGRATION_FILES = mapOf(
    1 to "V1__core_schema.sql",
    2 to "V2__reconciliation_evidence_and_outbox_claim.sql",
    3 to "V3__approvals.sql",
    4 to "V4__evidence_authority_binding.sql",
    5 to "V5__memory_mutation_binding.sql",
    6 to "V6__authority_epoch_fence.sql",
)

/**
 * Migration chain V1 -> V2 -> V3 -> V4 -> V5 -> V6 (Issue #88, #94). Runs on H2 by default and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 *
 * Partial histories are built from copies of the real migration files in a temporary directory. Nothing here repairs,
 * skips or baselines a history: a mismatch must fail.
 */
class MigrationChainTest {

    @Test
    fun `empty database applies V1 to V6 in order and flyway validate passes`() = TestDb().use { db ->
        val flyway = classpathFlyway(db.dataSource)
        flyway.validate()
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), appliedVersions(db.dataSource))
        assertTrue(flyway.info().all().all { it.state == MigrationState.SUCCESS })
        assertEquals(1, db.tableCount("approvals"))
        assertEquals(1, db.columnCount("external_operations", "tenant_id"))
        assertEquals(1, db.columnCount("external_operations", "provider_contract_version"))
    }

    @Test
    fun `existing external_operations rows survive V3 and V4 with NULL tenant and contract binding`() {
        TestDb { ds -> migrateOnly(ds, 1, 2) }.use { db ->
            val run = db.runs.create("task-legacy", "test", RunCompletionMode.RELEASE)
            val opId = db.insertPreV4Row(run.runId.value, key = "legacy-key", status = "IN_FLIGHT", version = 3)
            val before = db.snapshot(opId)

            // The later migrations are applied on top of the existing history, in order.
            runMigrations(db.dataSource)
            classpathFlyway(db.dataSource).validate()
            assertEquals(listOf("1", "2", "3", "4", "5", "6"), appliedVersions(db.dataSource))

            // The row is kept as written: no backfill, no guessed binding.
            assertEquals(before, db.snapshot(opId))
            assertEquals(null to null, db.bindings(opId))
            val entry = assertNotNull(db.journal.get(opId))
            assertNull(entry.tenantId)
            assertNull(entry.providerContractVersion)
            assertNull(entry.evidenceRef)
        }
    }

    @Test
    fun `a V3 added after V4 is refused by validation and never repaired or skipped`() {
        TestDb { ds -> migrateOnly(ds, 1, 2, 4) }.use { db ->
            assertEquals(listOf("1", "2", "4"), appliedVersions(db.dataSource))
            assertEquals(0, db.tableCount("approvals"))

            // V3 is now on the classpath but older than the applied V4: fail-closed.
            assertFailsWith<FlywayException> { runMigrations(db.dataSource) }
            assertFailsWith<FlywayException> { classpathFlyway(db.dataSource).validate() }

            val states = classpathFlyway(db.dataSource).info().all().associate { it.version?.version to it.state }
            assertEquals(MigrationState.SUCCESS, states["4"])
            assertTrue(states["3"] != MigrationState.SUCCESS)
            // No repair and no skip: the history still has exactly V1, V2, V4, and approvals was not created.
            assertEquals(listOf("1", "2", "4"), appliedVersions(db.dataSource))
            assertEquals(0, db.tableCount("approvals"))
        }
    }

    @Test
    fun `a pre-V4 writer row cannot advance without verified evidence`() = TestDb().use { db ->
        val run = db.runs.create("task-old-writer", "test", RunCompletionMode.RELEASE)
        val lease = assertNotNull(db.leases.acquire(run.runId, "old-writer", TTL))
        // Written by a binary that does not know the V4 columns: tenant_id / provider_contract_version stay NULL.
        val inFlight = db.insertPreV4Row(run.runId.value, key = "old-inflight", status = "IN_FLIGHT", version = 1)
        val unknown = db.insertPreV4Row(run.runId.value, key = "old-unknown", status = "UNKNOWN", version = 2)
        val ref = VerifiedEvidenceRef("ev-not-verified-by-authority")

        val notSent = assertFailsWith<InvalidTransitionException> { runBlocking { db.journal.markNotSent(lease, inFlight, 1, ref) } }
        assertTrue(notSent.message!!.contains("no tenant binding"))
        assertFailsWith<InvalidTransitionException> { runBlocking { db.journal.markRejected(lease, inFlight, 1, ref) } }
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, inFlight, 1, OperationStatus.RETRYABLE) }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.reconcile(lease, unknown, 2, OperationStatus.CONFIRMED, ref, externalRef = "ext-1") }
        }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.reconcile(lease, unknown, 2, OperationStatus.RETRYABLE, ref) }
        }

        // A new writer must not adopt the legacy row through the idempotency key either.
        assertFailsWith<OperationBindingMismatchException> {
            db.journal.prepare(
                lease,
                PrepareOperation(
                    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = "old-inflight", externalSystem = "EXKES",
                    stableRef = "kivra-run-legacy", requestHash = HASH, tenantId = "tenant-1",
                    providerContractVersion = "2026-01",
                ),
            )
        }

        // Every refused call left the rows exactly as the old writer wrote them.
        val a = db.journal.get(inFlight)!!
        assertEquals(OperationStatus.IN_FLIGHT, a.status)
        assertEquals(1L, a.version)
        val b = db.journal.get(unknown)!!
        assertEquals(OperationStatus.UNKNOWN, b.status)
        assertEquals(2L, b.version)
        assertNull(b.evidenceRef)
    }

    /** Columns that exist before V4, so it can be read on a V1/V2 database too. */
    private data class Snapshot(val status: String, val version: Long, val requestHash: String)

    private fun TestDb.snapshot(opId: String): Snapshot = transaction(database) {
        execQuery(
            "SELECT status, version, request_hash FROM external_operations WHERE op_id = ?",
            opId,
        ) { rs -> Snapshot(rs.getString(1), rs.getLong(2), rs.getString(3)) }.single()
    }

    /** The V4 binding columns as stored: (tenant_id, provider_contract_version). Only valid after V4. */
    private fun TestDb.bindings(opId: String): Pair<String?, String?> = transaction(database) {
        execQuery(
            "SELECT tenant_id, provider_contract_version FROM external_operations WHERE op_id = ?",
            opId,
        ) { rs -> rs.getString(1) to rs.getString(2) }.single()
    }

    /** Inserts a row shaped like the V2 schema: the columns V4 adds are not named, so they stay NULL. */
    private fun TestDb.insertPreV4Row(runId: String, key: String, status: String, version: Long): String {
        val opId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO external_operations
                    (op_id, run_id, op_type, idempotency_key, external_system, stable_ref, request_hash, status,
                     attempt, max_attempts, lease_generation, version, created_at, updated_at)
                VALUES (?, ?, 'EXKES_CREATE_WORKSPACE', ?, 'EXKES', 'kivra-run-legacy', ?, ?, 1, 3, 1, ?, NOW(6), NOW(6))
                """.trimIndent(),
                opId, runId, key, HASH, status, version,
            )
        }
        return opId
    }

    private fun TestDb.tableCount(table: String): Int = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ${TestDb.schemaSql} AND LOWER(TABLE_NAME) = ?",
            table,
        ) { it.getInt(1) }.single()
    }

    private fun TestDb.columnCount(table: String, column: String): Int = transaction(database) {
        execQuery(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ${TestDb.schemaSql} " +
                "AND LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?",
            table, column,
        ) { it.getInt(1) }.single()
    }
}

private fun classpathFlyway(dataSource: DataSource): Flyway =
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").validateMigrationNaming(true).load()

private fun appliedVersions(dataSource: DataSource): List<String> =
    classpathFlyway(dataSource).info().applied().map { it.version.version }

/** Applies only the given versions, in order, from copies of the real migration files. */
private fun migrateOnly(dataSource: DataSource, vararg versions: Int) {
    val dir = Files.createTempDirectory("kivra-migration-chain")
    try {
        for (v in versions) {
            val name = requireNotNull(MIGRATION_FILES[v]) { "no migration for V$v" }
            val src = requireNotNull(TestDb::class.java.classLoader.getResourceAsStream("db/migration/$name")) { name }
            src.use { Files.copy(it, dir.resolve(name)) }
        }
        Flyway.configure().dataSource(dataSource).locations("filesystem:${dir.toAbsolutePath()}")
            .validateMigrationNaming(true).load().migrate()
    } finally {
        dir.toFile().deleteRecursively()
    }
}
