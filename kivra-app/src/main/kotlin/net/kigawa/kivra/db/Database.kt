package net.kigawa.kivra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import net.kigawa.kivra.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/** Creates a Hikari pool. The driver is inferred from the URL so MariaDB (production) and H2 (tests) share one path. */
fun createDataSource(config: DatabaseConfig): DataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.user
        password = config.password
        driverClassName = if (config.jdbcUrl.startsWith("jdbc:h2")) "org.h2.Driver" else "org.mariadb.jdbc.Driver"
    }
    return HikariDataSource(hikariConfig)
}

fun runMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate()
}

fun connectDatabase(dataSource: DataSource): Database = Database.connect(dataSource)

fun closeDataSource(dataSource: DataSource) {
    (dataSource as? HikariDataSource)?.close()
}
