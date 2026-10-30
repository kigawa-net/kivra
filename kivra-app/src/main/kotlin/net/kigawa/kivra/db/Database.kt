package net.kigawa.kivra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import net.kigawa.kivra.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Creates the application pool (application credentials). The driver is inferred from the URL so MariaDB (production)
 * and H2 (tests) share one path.
 */
fun createDataSource(config: DatabaseConfig): DataSource = createPool(config.jdbcUrl, config.user, config.password)

private fun createPool(jdbcUrl: String, user: String, password: String): DataSource {
    val hikariConfig = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        username = user
        this.password = password
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
    val migrationDataSource = createPool(config.jdbcUrl, user, password)
    try {
        migrate(migrationDataSource)
    } finally {
        closeDataSource(migrationDataSource)
    }
}

fun runMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        // V40 was added after V41–V43 had already shipped to dev. Apply it on
        // those databases instead of rejecting the history as out of order.
        .outOfOrder(true)
        .load()
        .migrate()
}

fun connectDatabase(dataSource: DataSource): Database = Database.connect(dataSource)

fun closeDataSource(dataSource: DataSource) {
    (dataSource as? HikariDataSource)?.close()
}
