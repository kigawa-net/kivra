package net.kigawa.kivra.db

import java.sql.DriverManager
import javax.sql.DataSource
import net.kigawa.kivra.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A fresh, Flyway-migrated database per test. Default: in-memory H2 (MODE=MySQL).
 *
 * With `KIVRA_TEST_DB=mariadb` it runs against a real MariaDB instead (CI service container, or
 * `docker run mariadb:11` locally; see README): each test creates its own schema `kivra_t_<nanos>` and drops it on close.
 * Connection: `KIVRA_TEST_MARIADB_URL` (server URL without a schema, default `jdbc:mariadb://localhost:3306/`),
 * `KIVRA_TEST_MARIADB_USER` (default root; needs CREATE/DROP DATABASE), `KIVRA_TEST_MARIADB_PASSWORD`.
 */
class TestDb : AutoCloseable {
    private val schema: String? = if (isMariaDb) "kivra_t_${System.nanoTime()}" else null
    val dataSource: DataSource

    init {
        val config = if (schema != null) {
            val server = System.getenv("KIVRA_TEST_MARIADB_URL") ?: "jdbc:mariadb://localhost:3306/"
            val user = System.getenv("KIVRA_TEST_MARIADB_USER") ?: "root"
            val password = System.getenv("KIVRA_TEST_MARIADB_PASSWORD") ?: ""
            DriverManager.getConnection("$server?sessionVariables=time_zone='+00:00'", user, password).use {
                it.createStatement().use { st -> st.execute("CREATE DATABASE $schema") }
            }
            DatabaseConfig("${server.trimEnd('/')}/$schema?sessionVariables=time_zone='+00:00'", user, password)
        } else {
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:kivra_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            )
        }
        dataSource = createDataSource(config)
    }
    val database: Database

    init {
        runMigrations(dataSource)
        database = connectDatabase(dataSource)
    }

    val outbox = OutboxRepository(database)
    val audit = AuditRepository()
    val runs = DevelopmentRunRepository(database, outbox, audit)
    val leases = SqlLeaseStore(database)
    /** TEST ONLY Authority. Register observations and provider contracts on it before evidence-gated transitions. */
    val evidence = FakeTrustedEvidenceAuthority()
    val journal = OperationJournalRepository(database, evidence)

    /** Moves the lease expiry [seconds] into the past on the DB clock (simulates a worker that stopped renewing). */
    fun expireLease(runId: String, seconds: Long = 10) = transaction(database) {
        execUpdate("UPDATE development_runs SET lease_expires_at = TIMESTAMPADD(SECOND, -?, NOW(6)) WHERE run_id = ?", seconds, runId)
    }

    /** Moves an outbox claim into the past on the DB clock (simulates a publisher that crashed or stalled). */
    fun expireClaim(eventId: String) = transaction(database) {
        execUpdate("UPDATE outbox_events SET claim_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE event_id = ?", eventId)
    }

    /** Makes a backed-off outbox event due again. */
    fun makeDue(eventId: String) = transaction(database) {
        execUpdate("UPDATE outbox_events SET next_attempt_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE event_id = ?", eventId)
    }

    /** Test-only shortcut to put a run into [state] regardless of the state machine. */
    fun forceState(runId: String, state: net.kigawa.kivra.core.DevelopmentRunState) = transaction(database) {
        execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", state.name, runId)
    }

    fun count(table: String, where: String = "1=1"): Int = transaction(database) {
        execQuery("SELECT COUNT(*) FROM $table WHERE $where") { it.getInt(1) }.single()
    }

    /**
     * Every column of every row of every persisted table, flattened to text. Redaction regression tests assert that a
     * secret injected through a provider answer appears nowhere in it (journal, audit, outbox, run / agent state).
     */
    fun allPersistedText(): String = transaction(database) {
        listOf(
            "development_runs", "stage_executions", "agents", "execution_sessions", "external_operations",
            "outbox_events", "inbox_events", "audit_events",
        ).flatMap { table ->
            execQuery("SELECT * FROM $table") { rs -> (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() } }
        }.joinToString("\n")
    }

    override fun close() {
        closeDataSource(dataSource)
        if (schema != null) {
            val server = System.getenv("KIVRA_TEST_MARIADB_URL") ?: "jdbc:mariadb://localhost:3306/"
            DriverManager.getConnection(server, System.getenv("KIVRA_TEST_MARIADB_USER") ?: "root", System.getenv("KIVRA_TEST_MARIADB_PASSWORD") ?: "").use {
                it.createStatement().use { st -> st.execute("DROP DATABASE IF EXISTS $schema") }
            }
        }
    }

    companion object {
        /** SQL expression for the current schema in INFORMATION_SCHEMA queries. */
        val schemaSql: String get() = if (isMariaDb) "DATABASE()" else "'PUBLIC'"
        val isMariaDb: Boolean = System.getenv("KIVRA_TEST_DB").equals("mariadb", ignoreCase = true)
    }
}
