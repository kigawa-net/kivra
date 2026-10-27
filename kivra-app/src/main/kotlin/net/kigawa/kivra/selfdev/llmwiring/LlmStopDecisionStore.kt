package net.kigawa.kivra.selfdev.llmwiring

import java.time.Clock
import net.kigawa.kivra.db.Ulid
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** The outcome column of `selfdev_llm_stop_decisions` (V35, widened by V37). Shadow writes WOULD_APPROVE, never APPROVED. */
enum class DecisionOutcome { APPROVED, ABSTAINED, SKIPPED, LOST_RACE, ERROR, WOULD_APPROVE }

/** The mode column. */
enum class JudgeMode(val column: String) { SHADOW("shadow"), ACTIVE("active") }

/** One row of the decision record. Fixed fields only: a code, a model name, a hash and numbers. No free text, no owner key, no model output. */
class DecisionRecord(
    val requestId: String,
    val orderId: String,
    val outcome: DecisionOutcome,
    val reasonCode: String,
    val model: String,
    val inputHash: String,
    val mode: JudgeMode,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val costMilliYen: Long = 0,
)

/** The record of the judgements: the claim (one judgement per request and mode), the append, and the sweep's query. */
interface LlmStopDecisionStore {
    /** Takes the right to judge [requestId] in [mode]. True for exactly one caller, across Pods and restarts. */
    fun claim(requestId: String, mode: JudgeMode): Boolean

    /** Appends one row. */
    fun append(record: DecisionRecord)

    /** PENDING, unexpired requests that nobody has claimed in [mode], oldest first. */
    fun unclaimedPending(mode: JudgeMode, limit: Int): List<String>
}

class DbLlmStopDecisionStore(private val database: Database, private val clock: Clock = Clock.systemUTC()) : LlmStopDecisionStore {
    override fun claim(requestId: String, mode: JudgeMode): Boolean = try {
        transaction(database) {
            execUpdate(
                "INSERT INTO selfdev_llm_stop_claims (request_id, mode, claimed_at) VALUES (?, ?, ?)",
                requestId, mode.column, utcNow(clock),
            )
        }
        true
    } catch (e: Exception) {
        if (!e.isDuplicateKey()) throw e
        false
    }

    override fun append(record: DecisionRecord) {
        transaction(database) {
            execUpdate(
                """
                INSERT INTO selfdev_llm_stop_decisions (decision_id, request_id, order_id, outcome, reason_code, model, input_hash, mode,
                    tokens_in, tokens_out, cost_millijpy, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Ulid.generate(clock), record.requestId, record.orderId, record.outcome.name, record.reasonCode, record.model, record.inputHash,
                record.mode.column, record.tokensIn.coerceIn(0, Int.MAX_VALUE.toLong()), record.tokensOut.coerceIn(0, Int.MAX_VALUE.toLong()),
                record.costMilliYen.coerceAtLeast(0), utcNow(clock),
            )
        }
    }

    override fun unclaimedPending(mode: JudgeMode, limit: Int): List<String> = transaction(database) {
        execQuery(
            """
            SELECT r.request_id FROM selfdev_order_stop_requests r
            WHERE r.state = 'PENDING' AND r.expires_at > ?
              AND NOT EXISTS (SELECT 1 FROM selfdev_llm_stop_claims c WHERE c.request_id = r.request_id AND c.mode = ?)
            ORDER BY r.created_at, r.request_id LIMIT ?
            """.trimIndent(),
            utcNow(clock), mode.column, limit,
        ) { it.getString(1) }
    }
}
