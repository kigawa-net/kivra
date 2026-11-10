package net.kigawa.kivra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import net.kigawa.kivra.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/**
 * Creates the application pool (application credentials). The driver is inferred from the URL so MariaDB (production)
 * and H2 (tests) share one path.
 */
fun createDataSource(config: DatabaseConfig): DataSource = createPool(config.jdbcUrl, config.user, config.password)

/**
 * The migration user's pool is small: migrations run one after another, and the migration user has a low `max_user_connections`
 * (5 in dev). The Hikari default (10 connections, all kept idle) tries to open more than the user may, and logs
 * `1226 max_user_connections` errors at every start.
 */
internal const val MIGRATION_POOL_SIZE = 2

private fun createPool(jdbcUrl: String, user: String, password: String, maxPoolSize: Int? = null): DataSource {
    val hikariConfig = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        username = user
        this.password = password
        if (maxPoolSize != null) {
            maximumPoolSize = maxPoolSize
            minimumIdle = maxPoolSize
        }
        driverClassName = if (jdbcUrl.startsWith("jdbc:h2")) "org.h2.Driver" else "org.mariadb.jdbc.Driver"
    }
    return HikariDataSource(hikariConfig)
}

/**
 * Runs the migrations. With migration credentials, Flyway uses its own pool, closed before this returns (also on
 * failure), so the migration user never stays in the application pool. Without them, Flyway uses [appDataSource]
 * as before. [migrate] is injectable for tests.
 */
fun migrateDatabase(
    config: DatabaseConfig,
    appDataSource: DataSource,
    migrate: (DataSource) -> Unit = ::runMigrations,
) {
    val user = config.migrationUser
    val password = config.migrationPassword
    if (user == null || password == null) {
        migrate(appDataSource)
        return
    }
    val migrationDataSource = createPool(config.jdbcUrl, user, password, MIGRATION_POOL_SIZE)
    try {
        migrate(migrationDataSource)
    } finally {
        closeDataSource(migrationDataSource)
    }
}

/**
 * Runs the migrations, with the ADR 0022 PR-F gate. V52 (NOT NULL on the project columns) is applied only when no row of
 * the two project-scoped tables (tasks and memory) has a NULL project. While such a row exists the gate stops at V51 and logs the counts:
 * the app still starts (the V51 schema accepts every write it makes), and V52 runs on a later start, after the backfill. A failed
 * migration would stop the Pod, which is what the gate avoids. Nothing is deleted, guessed, or repaired.
 */
fun runMigrations(dataSource: DataSource) {
    // Before V40 drops the Goal/Plan tables, their rows are archived (LegacyGoalPlanArchive.kt).
    migrationConfig(dataSource, target = PRE_FLATTEN_VERSION).migrate()
    archiveLegacyGoalPlanTables(dataSource)
    migrationConfig(dataSource, target = PROJECT_CONTRACT_PREVIOUS_VERSION).migrate()
    if (migrationConfig(dataSource, target = null).info().pending().isEmpty()) return
    val unassigned = countUnassignedScopedRows(dataSource)
    if (unassigned.values.any { it > 0 }) {
        migrationLogger.warn(
            "project scope contract held at V{}: unassigned rows tasks={} memory={}. " +
                "Run the backfill dry-run and the backfill (docs/runbook-selfdev.md 4.20); V52 applies on a later start once both are 0.",
            PROJECT_CONTRACT_PREVIOUS_VERSION,
            unassigned.getValue("tasks"),
            unassigned.getValue("memory"),
        )
        return
    }
    migrationConfig(dataSource, target = null).migrate()
}

private val migrationLogger = LoggerFactory.getLogger("net.kigawa.kivra.db.Migration")

private fun migrationConfig(dataSource: DataSource, target: String?): Flyway {
    val builder = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        // V40 was added after V41–V43 had already shipped to dev. Apply it on
        // those databases instead of rejecting the history as out of order.
        .outOfOrder(true)
    if (target != null) builder.target(MigrationVersion.fromVersion(target))
    return builder.load()
}

fun connectDatabase(dataSource: DataSource): Database = Database.connect(dataSource)

fun closeDataSource(dataSource: DataSource) {
    (dataSource as? HikariDataSource)?.close()
}
