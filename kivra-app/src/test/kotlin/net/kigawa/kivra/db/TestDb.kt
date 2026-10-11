package net.kigawa.kivra.db

import javax.sql.DataSource
import net.kigawa.kivra.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** A fresh in-memory H2 (MODE=MySQL) database migrated with the production Flyway scripts. */
class TestDb : AutoCloseable {
    val dataSource: DataSource = createDataSource(
        DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:kivra_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            user = "sa",
            password = "",
        ),
    )
    val database: Database

    init {
        runMigrations(dataSource)
        database = connectDatabase(dataSource)
    }

    val outbox = OutboxRepository(database)
    val audit = AuditRepository()
    val runs = TaskRunRepository(database, outbox, audit)
    val leases = SqlLeaseStore(database)
    val journal = OperationJournalRepository(database)

    /** Moves the lease expiry [seconds] into the past on the DB clock (simulates a worker that stopped renewing). */
    fun expireLease(runId: String, seconds: Long = 10) = transaction(database) {
        execUpdate("UPDATE task_runs SET lease_expires_at = TIMESTAMPADD(SECOND, -?, NOW(6)) WHERE run_id = ?", seconds, runId)
    }

    fun count(table: String, where: String = "1=1"): Int = transaction(database) {
        execQuery("SELECT COUNT(*) FROM $table WHERE $where") { it.getInt(1) }.single()
    }

    override fun close() = closeDataSource(dataSource)
}
