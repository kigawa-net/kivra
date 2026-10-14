package net.kigawa.kivra.db

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource
import net.kigawa.kivra.config.DatabaseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MigrationCredentialsTest {
    // No DB_CLOSE_DELAY: H2 rejects it for non-admin users. The admin pool from createUsers() keeps the database alive.
    private val url = "jdbc:h2:mem:mig_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE"

    private fun <T> DataSource.use(block: (Connection) -> T): T = connection.use(block)

    /** Creates the two H2 users with an admin (sa) connection. The migration user has DDL (ADMIN); the app user has none. */
    private fun createUsers(): DataSource {
        val admin = createDataSource(DatabaseConfig(url, "sa", ""))
        admin.use { c ->
            c.createStatement().use {
                it.execute("CREATE USER IF NOT EXISTS kivra_mig PASSWORD 'mig-secret-value' ADMIN")
                it.execute("CREATE USER IF NOT EXISTS kivra_app PASSWORD 'app-secret-value'")
            }
        }
        return admin
    }

    private fun userOf(ds: DataSource): String = ds.use { it.metaData.userName }

    @Test
    fun `without migration credentials the migration runs on the application data source`() {
        val config = DatabaseConfig(url, "sa", "")
        val app = createDataSource(config)
        var seen: DataSource? = null
        try {
            migrateDatabase(config, app) { seen = it }
            assertSame(app, seen)
        } finally {
            closeDataSource(app)
        }
    }

    @Test
    fun `with migration credentials Flyway uses them and the application pool keeps its own`() {
        val admin = createUsers()
        val config = DatabaseConfig(url, "kivra_app", "app-secret-value", "kivra_mig", "mig-secret-value")
        val app = createDataSource(DatabaseConfig(url, "kivra_app", "app-secret-value"))
        var migrationDs: DataSource? = null
        var migrationUser: String? = null
        try {
            // The username is read inside the callback: the migration pool is closed after it returns.
            migrateDatabase(config, app) { migrationDs = it; migrationUser = userOf(it) }
            val used = migrationDs ?: error("migration did not run")
            assertFalse(used === app)
            assertEquals("KIVRA_MIG", migrationUser!!.uppercase())
            assertEquals("KIVRA_APP", userOf(app).uppercase())
            assertTrue((used as HikariDataSource).isClosed)
        } finally {
            closeDataSource(app)
            closeDataSource(admin)
        }
    }

    @Test
    fun `the migration pool is closed even when the migration fails, and the error has no password`() {
        val admin = createUsers()
        val config = DatabaseConfig(url, "kivra_app", "app-secret-value", "kivra_mig", "mig-secret-value")
        val app = createDataSource(DatabaseConfig(url, "kivra_app", "app-secret-value"))
        var migrationDs: DataSource? = null
        try {
            val error = assertFailsWith<IllegalStateException> {
                migrateDatabase(config, app) { migrationDs = it; error("boom") }
            }
            assertFalse(error.message!!.contains("mig-secret-value"))
            assertTrue((migrationDs as HikariDataSource).isClosed)
        } finally {
            closeDataSource(app)
            closeDataSource(admin)
        }
    }

    @Test
    fun `real Flyway run with migration credentials creates the schema, and the DML-only app user can read it`() {
        val admin = createUsers()
        val config = DatabaseConfig(url, "kivra_app", "app-secret-value", "kivra_mig", "mig-secret-value")
        val app = createDataSource(DatabaseConfig(url, "kivra_app", "app-secret-value"))
        try {
            migrateDatabase(config, app)
            // Grant DML only (no DDL), as the production Secret / GRANT setup does.
            admin.use { c ->
                val tables = c.createStatement().use { st ->
                    st.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'").use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
                assertTrue(tables.any { it.equals("DEVELOPMENT_RUNS", ignoreCase = true) })
                c.createStatement().use { st ->
                    for (t in tables) st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON \"$t\" TO kivra_app")
                }
            }
            val count = app.use { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM development_runs").use { rs -> rs.next(); rs.getInt(1) } }
            }
            assertEquals(0, count)
            // The DML-only user must not be able to change the schema.
            assertFailsWith<java.sql.SQLException> {
                app.use { c -> c.createStatement().use { it.execute("CREATE TABLE app_probe (x INT)") } }
            }
        } finally {
            closeDataSource(app)
            closeDataSource(admin)
        }
    }
}
