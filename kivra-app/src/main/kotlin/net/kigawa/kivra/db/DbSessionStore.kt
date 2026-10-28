package net.kigawa.kivra.db

import net.kigawa.kivra.auth.SessionRecord
import net.kigawa.kivra.auth.SessionStore
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A persisted login session. */
data class LoginSessionRecord(
    val sessionId: String,
    override val principal: String,
    val expiresAt: Instant,
) : SessionRecord

/** Database-backed session store for OIDC login sessions. Implements [SessionStore] for the identity layer. */
class DbSessionStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : SessionStore {
    companion object {
        private val random = SecureRandom()
    }

    /** Generate a random 16-byte hex session ID. */
    private fun generateSessionId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Create a new session and return the session ID. */
    fun create(principal: String, maxAge: Duration): String {
        val sessionId = generateSessionId()
        val now = Instant.now(clock)
        val expiresAt = now.plus(maxAge)
        transaction(database) {
            SessionStoreTable.insert {
                it[SessionStoreTable.sessionId] = sessionId
                it[SessionStoreTable.principal] = principal
                it[SessionStoreTable.createdAt] = now.atZone(ZoneOffset.UTC).toLocalDateTime()
                it[SessionStoreTable.expiresAt] = expiresAt.atZone(ZoneOffset.UTC).toLocalDateTime()
            }
        }
        return sessionId
    }

    /** Look up a session by ID. Returns null if not found or expired. */
    override fun get(sessionId: String): LoginSessionRecord? {
        val now = Instant.now(clock)
        val nowLocal = now.atZone(ZoneOffset.UTC).toLocalDateTime()
        return transaction(database) {
            SessionStoreTable.selectAll().where {
                (SessionStoreTable.sessionId eq sessionId) and (SessionStoreTable.expiresAt greater nowLocal)
            }.firstOrNull()
                ?.let { row: ResultRow ->
                    LoginSessionRecord(
                        sessionId = row[SessionStoreTable.sessionId],
                        principal = row[SessionStoreTable.principal],
                        expiresAt = row[SessionStoreTable.expiresAt].atZone(ZoneOffset.UTC).toInstant(),
                    )
                }
        }
    }

    /** Delete a session by ID. */
    fun delete(sessionId: String) {
        transaction(database) {
            SessionStoreTable.deleteWhere { SessionStoreTable.sessionId eq sessionId }
        }
    }

    /** Purge expired sessions. Returns the number of deleted rows. */
    fun purgeExpired(): Int {
        val now = Instant.now(clock)
        val nowLocal = now.atZone(ZoneOffset.UTC).toLocalDateTime()
        return transaction(database) {
            SessionStoreTable.deleteWhere { SessionStoreTable.expiresAt lessEq nowLocal }
        }
    }
}
