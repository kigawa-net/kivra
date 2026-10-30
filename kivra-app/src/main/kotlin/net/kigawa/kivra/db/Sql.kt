package net.kigawa.kivra.db

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.datetime

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

/** Exposed mirror of V40 `selfdev_tasks`. */
internal object SelfDevTasksTable : Table("selfdev_tasks") {
    val taskId = char("task_id", 26)
    val createdBy = varchar("created_by", 255)
    val idempotencyKey = varchar("idempotency_key", 128)
    val title = varchar("title", 200)
    val body = text("body").nullable()
    val targetRepo = varchar("target_repo", 200)
    val state = varchar("state", 32)
    val stateReason = varchar("state_reason", 64).nullable()
    val resumeState = varchar("resume_state", 32).nullable()
    val resumeReason = varchar("resume_reason", 64).nullable()
    val revision = integer("revision").default(0)
    val claimToken = varchar("claim_token", 64).nullable()
    val claimedUntil = datetime("claimed_until").nullable()
    val dependencies = text("dependencies")
    val requiresApproval = bool("requires_approval").default(false)
    val runId = char("run_id", 26).nullable()
    val attempts = integer("attempts").default(0)
    val version = long("version").default(0)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val approvedBy = varchar("approved_by", 255).nullable()
    override val primaryKey = PrimaryKey(taskId)
}
