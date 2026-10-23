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
internal val MIGRATION_FILES = mapOf(
    1 to "V1__core_schema.sql",
    2 to "V2__reconciliation_evidence_and_outbox_claim.sql",
    3 to "V3__approvals.sql",
    4 to "V4__evidence_authority_binding.sql",
    5 to "V5__memory_mutation_binding.sql",
    6 to "V6__authority_epoch_fence.sql",
    7 to "V7__pr_merge_binding.sql",
    8 to "V8__authority_epoch_publisher.sql",
    9 to "V9__dev_task_intake.sql",
    10 to "V10__durable_budget_ledger.sql",
    11 to "V11__task_bindings.sql",
    12 to "V12__budget_acknowledgements.sql",
    13 to "V13__request_ledger.sql",
    14 to "V14__coding_attempts.sql",
    15 to "V15__coding_results.sql",
    16 to "V16__coding_attempt_usage.sql",
    17 to "V17__task_body_and_reference.sql",
    18 to "V18__selfdev_autopilot.sql",
    19 to "V19__selfdev_approval.sql",
    20 to "V20__coding_monitor_events.sql",
    21 to "V21__selfdev_memory.sql",
    23 to "V23__selfdev_orders.sql",
    24 to "V24__selfdev_memory_quota_lock.sql",
    25 to "V25__selfdev_order_children.sql",
    26 to "V26__selfdev_order_cycle_budget.sql",
    27 to "V27__selfdev_order_child_stop.sql",
    28 to "V28__selfdev_order_auto_stop.sql",
    29 to "V29__selfdev_order_cycle_spend.sql",
)

/** The versions applied on the full chain. V22 is never used (reserved gap), so the list skips it. */
internal val EXPECTED_APPLIED_VERSIONS: List<String> = (1..21).map { it.toString() } + listOf("23", "24", "25", "26", "27", "28", "29")

/** Versions left unused on purpose: a migration with one of these numbers is not written on this branch (V22 is reserved). */
internal val RESERVED_MIGRATION_VERSIONS: Set<Int> = setOf(22)

/**
 * Migration chain V1 -> V2 -> ... -> V21 (Issue #88, #94, #53, #100, #112, #26, #56, #229, #277). Runs on H2 by default and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 *
 * Partial histories are built from copies of the real migration files in a temporary directory. Nothing here repairs,
 * skips or baselines a history: a mismatch must fail.
 */
class MigrationChainTest {

    @Test
    fun `empty database applies V1 to V21, V23, V24, V25, V26 and V27 in order and flyway validate passes`() = TestDb().use { db ->
        val flyway = classpathFlyway(db.dataSource)
        flyway.validate()
        assertEquals(EXPECTED_APPLIED_VERSIONS, appliedVersions(db.dataSource))
        assertEquals(1, db.columnCount("selfdev_order_stop_requests", "target_is_root"))
        assertEquals(1, db.columnCount("selfdev_orders", "spent_jpy"))
        assertEquals(1, db.columnCount("selfdev_orders", "cycle_budget_jpy"))
        assertEquals(1, db.columnCount("selfdev_orders", "order_budget_jpy"))
        assertEquals(1, db.columnCount("selfdev_orders", "allocated_jpy"))
        assertEquals(1, db.columnCount("selfdev_orders", "child_count"))
        assertEquals(1, db.columnCount("selfdev_orders", "idempotency_key"))
        assertEquals(1, db.columnCount("selfdev_order_events", "parent_order_id"))
        assertEquals(1, db.tableCount("selfdev_memory_records"))
        assertEquals(1, db.tableCount("selfdev_memory_events"))
        assertEquals(1, db.tableCount("selfdev_plan_memory_refs"))
        assertEquals(1, db.tableCount("selfdev_memory_quota_lock"))
        assertEquals(1, db.count("selfdev_memory_quota_lock"))
        assertEquals(1, db.tableCount("selfdev_orders"))
        assertEquals(1, db.tableCount("selfdev_order_stop_requests"))
        assertEquals(1, db.tableCount("selfdev_order_events"))
        assertEquals(1, db.columnCount("selfdev_goals", "order_id"))
        assertEquals(1, db.columnCount("selfdev_goals", "cycle_no"))
        assertTrue(flyway.info().all().all { it.state == MigrationState.SUCCESS })
        assertEquals(1, db.tableCount("approvals"))
        assertEquals(1, db.tableCount("coding_results"))
        assertEquals(1, db.tableCount("coding_monitor_events"))
        assertEquals(1, db.tableCount("coding_attempt_usage"))
        assertEquals(1, db.tableCount("budget_periods"))
        assertEquals(1, db.tableCount("budget_reservations"))
        assertEquals(1, db.tableCount("budget_reservation_scopes"))
        assertEquals(1, db.tableCount("task_bindings"))
        assertEquals(1, db.columnCount("external_operations", "tenant_id"))
        assertEquals(1, db.columnCount("external_operations", "provider_contract_version"))
        for (column in listOf("pr_number", "pr_node_id", "pr_repository_id", "pr_base_ref", "pr_policy_revision", "pr_review_manifest_digest")) {
            assertEquals(1, db.columnCount("development_runs", column))
        }
        for (column in listOf("task_title", "principal", "idempotency_key", "task_body", "task_source")) {
            assertEquals(1, db.columnCount("development_runs", column))
        }
    }

    /**
     * A stop request and its order written under V26 (before the child stop) stay root requests under V27, and the root's separation CHECK
     * still refuses a self-approval on the upgraded schema (ADR 0015 section 4.2 (b)).
     */
    @Test
    fun `a stop request written before V27 is a root request, and V27 keeps the root's separation CHECK`() {
        val beforeV27 = (1..21).toList() + (23..26).toList()
        TestDb { ds -> migrateOnly(ds, *beforeV27.toIntArray()) }.use { db ->
            val orderId = "01AAAAAAAAAAAAAAAAAAAAAAAA"
            val requestId = "01BBBBBBBBBBBBBBBBBBBBBBBB"
            transaction(db.database) {
                execUpdate(
                    """
                    INSERT INTO selfdev_orders (order_id, parent_order_id, depth, root_slot, title, policy, target_repo, max_cycles,
                        cycles_started, consecutive_failed_cycles, state, expires_at, created_by, version, created_at, updated_at)
                    VALUES (?, NULL, 0, 1, 't', 'p', 'r', 1, 0, 0, 'PAUSED', NOW(6), 'op', 2, NOW(6), NOW(6))
                    """.trimIndent(),
                    orderId,
                )
                execUpdate(
                    """
                    INSERT INTO selfdev_order_stop_requests (request_id, order_id, requested_by, requester_kind, reason_code, order_version,
                        digest, state, active_key, decided_by, decider_kind, decided_at, expires_at, created_at)
                    VALUES (?, ?, 'ai', 'AI', 'OPERATOR_REQUEST', 1, ?, 'CONSUMED', NULL, 'op', 'HUMAN', NOW(6), NOW(6), NOW(6))
                    """.trimIndent(),
                    requestId, orderId, "0".repeat(64),
                )
            }
            runMigrations(db.dataSource)
            classpathFlyway(db.dataSource).validate()
            assertEquals(EXPECTED_APPLIED_VERSIONS, appliedVersions(db.dataSource))
            val targetIsRoot = transaction(db.database) {
                execQuery("SELECT target_is_root FROM selfdev_order_stop_requests WHERE request_id = ?", requestId) { it.getInt(1) }.single()
            }
            assertEquals(1, targetIsRoot)
            assertFailsWith<java.sql.SQLException> {
                transaction(db.database) {
                    execUpdate("UPDATE selfdev_order_stop_requests SET decided_by = requested_by WHERE request_id = ?", requestId)
                }
            }
        }
    }

    @Test
    fun `existing external_operations rows survive V3 and V4 with NULL tenant and contract binding`() {
        TestDb { ds -> migrateOnly(ds, 1, 2) }.use { db ->
            val runId = db.insertLegacyRun("task-legacy")
            val opId = db.insertPreV4Row(runId, key = "legacy-key", status = "IN_FLIGHT", version = 3)
            val before = db.snapshot(opId)

            // The later migrations are applied on top of the existing history, in order.
            runMigrations(db.dataSource)
            classpathFlyway(db.dataSource).validate()
            assertEquals(EXPECTED_APPLIED_VERSIONS, appliedVersions(db.dataSource))

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

    /**
     * Inserts a development run with the V1 / V2 columns only. The Exposed table maps later columns (V7), so the repository
     * cannot be used on a partial history.
     */
    private fun TestDb.insertLegacyRun(taskId: String): String {
        val runId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO development_runs
                    (run_id, task_id, state, completion_mode, attempt, plan_revision, lease_generation, cancel_requested, version, created_at, updated_at)
                VALUES (?, ?, 'CREATED', 'RELEASE', 0, 0, 0, FALSE, 0, NOW(6), NOW(6))
                """.trimIndent(),
                runId, taskId,
            )
        }
        return runId
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

internal fun classpathFlyway(dataSource: DataSource): Flyway =
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").validateMigrationNaming(true).load()

internal fun appliedVersions(dataSource: DataSource): List<String> =
    classpathFlyway(dataSource).info().applied().map { it.version.version }

/** Applies only the given versions, in order, from copies of the real migration files. */
internal fun migrateOnly(dataSource: DataSource, vararg versions: Int) {
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
