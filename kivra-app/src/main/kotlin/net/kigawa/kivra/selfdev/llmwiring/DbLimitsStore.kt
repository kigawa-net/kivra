package net.kigawa.kivra.selfdev.llmwiring

import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.selfdev.llm.LimitCounter
import net.kigawa.kivra.selfdev.llm.LimitsStore
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The DB version of [LimitsStore] (ADR 0021 P6; V37 `selfdev_llm_stop_limits`). [tryAdd] is atomic across Pods: the add is one conditional
 * UPDATE (`amount + ? <= max`), and the first add of a bucket is an INSERT whose primary key arbitrates two Pods that insert together (the
 * loser goes back to the UPDATE). Plain SQL shared by MariaDB and H2. Older buckets of the same counter are dropped after a bucket is created.
 */
class DbLimitsStore(private val database: Database) : LimitsStore {
    override fun tryAdd(counter: LimitCounter, bucket: Long, amount: Long, max: Long): Boolean {
        if (amount < 0 || max < 0) return false
        if (amount > max) return false
        // Two rounds are enough: the UPDATE, then (row missing) the INSERT, and when another Pod inserted first, the UPDATE once more.
        repeat(2) {
            if (update(counter, bucket, amount, max)) return true
            if (exists(counter, bucket)) return false
            if (insert(counter, bucket, amount)) {
                purgeOlder(counter, bucket)
                return true
            }
        }
        return update(counter, bucket, amount, max)
    }

    override fun current(counter: LimitCounter, bucket: Long): Long = transaction(database) {
        execQuery("SELECT amount FROM selfdev_llm_stop_limits WHERE counter_name = ? AND bucket_key = ?", counter.name, bucket) { it.getLong(1) }
            .firstOrNull() ?: 0L
    }

    private fun update(counter: LimitCounter, bucket: Long, amount: Long, max: Long): Boolean = transaction(database) {
        // amount <= max - amount avoids an overflow of amount + ?. The WHERE and the SET are one statement, so the check and the add are one step.
        execUpdate(
            "UPDATE selfdev_llm_stop_limits SET amount = amount + ? WHERE counter_name = ? AND bucket_key = ? AND amount <= ? - ?",
            amount, counter.name, bucket, max, amount,
        ) == 1
    }

    private fun exists(counter: LimitCounter, bucket: Long): Boolean = transaction(database) {
        execQuery("SELECT 1 FROM selfdev_llm_stop_limits WHERE counter_name = ? AND bucket_key = ?", counter.name, bucket) { it.getInt(1) }.isNotEmpty()
    }

    private fun insert(counter: LimitCounter, bucket: Long, amount: Long): Boolean = try {
        transaction(database) {
            execUpdate("INSERT INTO selfdev_llm_stop_limits (counter_name, bucket_key, amount) VALUES (?, ?, ?)", counter.name, bucket, amount)
        }
        true
    } catch (e: Exception) {
        if (!e.isDuplicateKey()) throw e
        false
    }

    private fun purgeOlder(counter: LimitCounter, bucket: Long) {
        try {
            transaction(database) {
                execUpdate("DELETE FROM selfdev_llm_stop_limits WHERE counter_name = ? AND bucket_key < ?", counter.name, bucket)
            }
        } catch (_: Exception) {
            // Housekeeping only: an old bucket left behind changes no limit.
        }
    }
}
