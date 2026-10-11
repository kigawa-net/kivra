package net.kigawa.kivra.db

import java.time.Clock
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/** Consumer-side deduplication (design #11 section 5.4): the first insert of `(consumer, event_id)` wins. */
class InboxRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {

    /** True if this is the first time the event is seen; false for a duplicate. */
    fun tryRecord(consumerName: String, eventId: String, aggregateId: String, aggregateVersion: Long): Boolean =
        try {
            transaction(database) {
                InboxEventsTable.insert {
                    it[InboxEventsTable.consumerName] = consumerName
                    it[InboxEventsTable.eventId] = eventId
                    it[InboxEventsTable.aggregateId] = aggregateId
                    it[InboxEventsTable.aggregateVersion] = aggregateVersion
                    it[processedAt] = utcNow(clock)
                }
                true
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) false else throw e
        }
}
