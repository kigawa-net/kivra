package net.kigawa.kivra.db

import java.sql.Connection
import javax.sql.DataSource
import org.slf4j.LoggerFactory

/** The last version before V40 (flatten_tasks). Until it is applied, the Goal/Plan tables still exist. */
internal const val PRE_FLATTEN_VERSION = "39"

/** The Goal/Plan tables that V40 drops. V40 is applied as written (Flyway checksum), so the rows are copied out before it runs. */
internal val LEGACY_GOAL_PLAN_TABLES = listOf("selfdev_goals", "selfdev_plans", "selfdev_plan_tasks")

private val archiveLogger = LoggerFactory.getLogger("net.kigawa.kivra.db.LegacyGoalPlanArchive")

/**
 * Copies every row of the Goal/Plan tables into `<table>_pre_v40` before V40 drops them, and checks that each copy has the same
 * row count as its source. Runs only while the source table exists, so it is a no-op on a fresh database and on any database V40
 * has already been applied to (the source is gone; an existing archive is left alone).
 *
 * This keeps the data; it does not map it into selfdev_tasks. A count mismatch throws, which stops the migration before V40 drops
 * anything. A re-run after a partial failure finds the archive and compares the counts again; it never overwrites an archive.
 */
internal fun archiveLegacyGoalPlanTables(dataSource: DataSource) {
    dataSource.connection.use { conn ->
        val existing = conn.metaData.getTables(conn.catalog, conn.schema, "%", arrayOf("TABLE")).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
        }
        for (table in LEGACY_GOAL_PLAN_TABLES.filter { it in existing }) {
            val archive = "${table}_pre_v40"
            val sourceCount = conn.countRows(table)
            if (archive !in existing) {
                conn.createStatement().use { it.execute("CREATE TABLE $archive AS SELECT * FROM $table") }
            }
            val archiveCount = conn.countRows(archive)
            check(sourceCount == archiveCount) {
                "archive of $table is incomplete: source=$sourceCount archive=$archiveCount. V40 is not run."
            }
            archiveLogger.info("archived {} rows of {} into {} before V40", archiveCount, table, archive)
        }
    }
}

private fun Connection.countRows(table: String): Long =
    createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) } }
