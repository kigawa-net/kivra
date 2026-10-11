package net.kigawa.kivra.db

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Transaction

/*
 * Raw SQL helpers. Lease and fencing statements must compare against the DB clock
 * (NOW(6)) and use one atomic statement, which the Exposed DSL cannot express portably.
 * The SQL below is limited to constructs shared by MariaDB and H2 (MODE=MySQL).
 */

/** DB-clock expressions shared by MariaDB and H2. */
object DbTime {
    const val NOW = "NOW(6)"
    fun nowPlusSeconds() = "TIMESTAMPADD(SECOND, ?, NOW(6))"
    fun nowMinusSeconds() = "TIMESTAMPADD(SECOND, -?, NOW(6))"
}

private fun Transaction.jdbc(): Connection = connection.connection as Connection

fun Transaction.execUpdate(sql: String, vararg params: Any?): Int =
    jdbc().prepareStatement(sql).use { ps ->
        params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
        ps.executeUpdate()
    }

fun <T> Transaction.execQuery(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    jdbc().prepareStatement(sql).use { ps ->
        params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

/** App-side UTC time truncated to DATETIME(6) precision so values round-trip exactly. */
fun utcNow(clock: java.time.Clock): LocalDateTime =
    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS)

/** True for a unique / primary key violation on MariaDB (1062) or H2 (23505). */
fun ExposedSQLException.isUniqueViolation(): Boolean {
    val sql = (cause as? SQLException) ?: return false
    return sql.sqlState == "23505" || sql.errorCode == 1062 || sql.errorCode == 23505
}
