package net.kigawa.kivra.db

import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import net.kigawa.kivra.config.DatabaseConfig
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.ReserveResult

/**
 * The application user has DML only (SELECT, INSERT, UPDATE, DELETE) on the migrated schema, as in production (Issue #88).
 * Creates the schema with the migration user (the test's root user), then runs the main repository paths as the DML user:
 * run admission and task intake, lease and Operation Journal writes, the Authority epoch copy, and the budget ledger.
 *
 * Needs real MariaDB grants, so it runs with `KIVRA_TEST_DB=mariadb` and returns immediately on H2 (H2 does not model the
 * production grant set here).
 */
class DmlOnlyAppUserTest {

    @Test
    fun `the DML-only application user runs the main repository paths and cannot change the schema`() {
        if (!TestDb.isMariaDb) return
        TestDb().use { admin ->
            val schema = requireNotNull(admin.schema)
            val server = (System.getenv("KIVRA_TEST_MARIADB_URL") ?: "jdbc:mariadb://localhost:3306/").trimEnd('/')
            val rootUser = System.getenv("KIVRA_TEST_MARIADB_USER") ?: "root"
            val rootPassword = System.getenv("KIVRA_TEST_MARIADB_PASSWORD") ?: ""
            val appUser = "kivra_dml_${System.nanoTime()}"
            val appPassword = UUID.randomUUID().toString()

            DriverManager.getConnection("$server/?sessionVariables=time_zone='+00:00'", rootUser, rootPassword).use { c ->
                c.createStatement().use {
                    it.execute("CREATE USER '$appUser'@'%' IDENTIFIED BY '$appPassword'")
                    it.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `$schema`.* TO '$appUser'@'%'")
                }
            }
            try {
                val app = createDataSource(DatabaseConfig("$server/$schema?sessionVariables=time_zone='+00:00'", appUser, appPassword))
                try {
                    exerciseRepositories(connectDatabase(app))

                    // DDL is refused for the application user.
                    assertFailsWith<SQLException> {
                        app.connection.use { c -> c.createStatement().use { it.execute("CREATE TABLE app_probe (x INT)") } }
                    }
                    assertFailsWith<SQLException> {
                        app.connection.use { c -> c.createStatement().use { it.execute("DROP TABLE approvals") } }
                    }
                } finally {
                    closeDataSource(app)
                }
            } finally {
                DriverManager.getConnection("$server/", rootUser, rootPassword).use { c ->
                    c.createStatement().use { it.execute("DROP USER IF EXISTS '$appUser'@'%'") }
                }
            }
        }
    }

    private fun exerciseRepositories(database: org.jetbrains.exposed.sql.Database) {
        val clock = Clock.systemUTC()
        val outbox = OutboxRepository(database)
        val runs = DevelopmentRunRepository(database, outbox, AuditRepository(), FakeTrustedPrMergeAuthority(), clock)

        // Run admission and task intake (INSERT, SELECT, UPDATE of the hold).
        val run = runs.create("task-dml", "dml-test", RunCompletionMode.RELEASE)
        assertNotNull(runs.get(run.runId))
        runs.submitTask("dml-principal", "dml-idem-1", "DML smoke task")
        assertEquals(1, runs.listTasks("dml-principal").size)
        runs.hold(run.runId, "dml-hold", "dml-test")
        runs.clearHold(run.runId, "dml-test")

        // Lease (UPDATE of the run row) and Operation Journal (INSERT, SELECT).
        val lease = assertNotNull(SqlLeaseStore(database, clock).acquire(run.runId, "dml-worker", Duration.ofSeconds(30)))
        val journal = OperationJournalRepository(database, FakeTrustedEvidenceAuthority(), clock, epochs = AuthorityEpochRepository(database, clock))
        val entry = journal.prepare(
            lease,
            PrepareOperation(
                opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = "dml-op-1", externalSystem = "EXKES",
                stableRef = "kivra-run-dml", requestHash = "c".repeat(64), tenantId = "tenant-dml",
                providerContractVersion = "2026-01",
            ),
        )
        assertNotNull(journal.get(entry.opId))
        assertEquals(1, journal.listByRun(run.runId).size)

        // Authority epoch copy (INSERT, then UPDATE on a newer epoch, SELECT).
        val epochs = AuthorityEpochRepository(database, clock)
        epochs.publish("tenant-dml", "EXKES", AuthorityEpochSnapshot("2026-01", ProviderCapability.AUTHORITATIVE_ABSENCE, 1, 1, 1), "dml-authority")
        epochs.publish("tenant-dml", "EXKES", AuthorityEpochSnapshot("2026-01", ProviderCapability.AUTHORITATIVE_ABSENCE, 1, 2, 1), "dml-authority")
        assertEquals(2L, epochs.current("tenant-dml", "EXKES")!!.authorityEpoch)

        // Budget ledger: reserve (INSERT in several tables), commit (UPDATE), and the period row.
        val store = DbBudgetStore(
            database = database,
            tenantScope = "tenant-dml",
            tenantLimit = BudgetLimit(BigDecimal("10000"), soft = BigDecimal("8000"), currency = "JPY"),
            runLimit = BudgetLimit(BigDecimal("1000"), currency = "JPY"),
            clock = clock,
        )
        val amount = BudgetAmount(BigDecimal("500"), "JPY")
        assertEquals(ReserveResult.Reserved, store.reserve("dml-reservation-1", listOf("tenant-dml", "run:dml-run"), amount))
        store.commit("dml-reservation-1", BudgetAmount(BigDecimal("400"), "JPY"))
        val usage = store.usage("tenant-dml")
        assertEquals(0, BigDecimal("400").compareTo(usage.committed))
        assertEquals(0, usage.reserved.signum())
    }
}
