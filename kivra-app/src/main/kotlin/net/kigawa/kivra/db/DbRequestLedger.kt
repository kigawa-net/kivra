package net.kigawa.kivra.db

import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostBasis
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.GatewayWarning
import net.kigawa.kivra.modelgateway.LedgerClaim
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.RequestLedger
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.Usage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Durable requestId ledger in Kivra MariaDB (ADR 0008 step 6a, Issue #56). The production implementation of
 * [RequestLedger]; the contract is the one documented on [RequestLedger] and checked by the shared contract tests.
 *
 * State machine (table `request_ledger`, V13). A row is never deleted.
 * - new requestId: created as RELEASED and claimed in the same transaction, so it reaches RESERVED.
 * - RESERVED -> SENDING (markSending, before each provider call) -> COMPLETED (complete) or UNKNOWN (markOutcomeUnknown).
 * - RESERVED or SENDING -> RELEASED (releaseUnsent). SENDING is written before each provider call, so a failure proven to be
 *   NOT_SENT lands there and must be releasable (the same contract as [net.kigawa.kivra.modelgateway.InMemoryRequestLedger]).
 *   RELEASED -> RESERVED (a new claim). COMPLETED and UNKNOWN cannot be released.
 * - COMPLETED and UNKNOWN are terminal. Every transition is a conditional UPDATE, so a terminal row is never moved back.
 *
 * Each operation is one transaction. [begin] inserts with `ON DUPLICATE KEY UPDATE request_id = request_id` and only then
 * takes `SELECT ... FOR UPDATE` (the same order as [DbRunAdmission]): a duplicate INSERT followed by FOR UPDATE deadlocks
 * under concurrency on MariaDB 11 (observed with DbBudgetStore).
 *
 * Failures are fail-closed. [begin] returns Rejected(STORE_UNAVAILABLE). [markSending] and [complete] throw
 * [RequestLedgerWriteException] so the Gateway's existing settlement path runs (no send before a SENDING mark; an unrecorded
 * response is sealed). [markOutcomeUnknown] and [releaseUnsent] log and return: a row left in RESERVED or SENDING still refuses
 * re-claim, so no request is re-sent.
 *
 * Capacity: a new requestId is refused with LEDGER_FULL when the table already holds [capacity] rows. The count is read
 * before the insert without a lock, so concurrent new requestIds may overshoot by at most the number of concurrent callers.
 *
 * Logs and exceptions never contain the response, the prompt or a full requestId (the first 8 characters only).
 */
class DbRequestLedger(
    private val database: Database,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.systemUTC(),
) : RequestLedger {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val logger = LoggerFactory.getLogger(DbRequestLedger::class.java)

    override fun begin(requestId: String, digest: String): LedgerClaim = try {
        transaction(database) { beginLocked(requestId, digest) }
    } catch (e: Exception) {
        logFailure("begin", requestId, e)
        LedgerClaim.Rejected(STORE_UNAVAILABLE, "requestId ledger is unavailable; request refused")
    }

    private fun Transaction.beginLocked(requestId: String, digest: String): LedgerClaim {
        val now = utcNow(clock)
        if (!exists(requestId)) {
            if (countUpTo(capacity) >= capacity) {
                return LedgerClaim.Rejected("LEDGER_FULL", "requestId ledger is full; new requests are refused")
            }
            execUpdate(
                """
                INSERT INTO request_ledger (request_id, digest, state, response_json, response_replayable, created_at, updated_at)
                VALUES (?, ?, 'RELEASED', NULL, FALSE, ?, ?)
                ON DUPLICATE KEY UPDATE request_id = request_id
                """.trimIndent(),
                requestId, digest, now, now,
            )
        }
        val row = selectForUpdate(requestId) ?: error("request_ledger row vanished after insert")
        if (row.digest != digest) {
            return LedgerClaim.Rejected("REQUEST_ID_REUSED", "requestId was already used for a different request")
        }
        return when (row.state) {
            "RELEASED" -> {
                execUpdate("UPDATE request_ledger SET state = 'RESERVED', updated_at = ? WHERE request_id = ?", now, requestId)
                LedgerClaim.Claimed
            }
            "COMPLETED" -> LedgerClaim.Completed(restore(requestId, row))
            "RESERVED", "SENDING", "UNKNOWN" ->
                LedgerClaim.Rejected("DUPLICATE_REQUEST", "requestId is already in flight or its outcome is unknown")
            else -> error("unknown request_ledger state for requestId ${prefix(requestId)}")
        }
    }

    override fun markSending(requestId: String) {
        try {
            transaction(database) {
                execUpdate(
                    "UPDATE request_ledger SET state = 'SENDING', updated_at = ? WHERE request_id = ? AND state IN ('RESERVED', 'SENDING')",
                    utcNow(clock), requestId,
                )
            }
        } catch (e: Exception) {
            logFailure("markSending", requestId, e)
            throw RequestLedgerWriteException("markSending")
        }
    }

    override fun complete(requestId: String, response: ModelResponse?) {
        val encoded = response?.let { ModelResponseCodec.encodeWithinLimit(it) }
        try {
            transaction(database) {
                execUpdate(
                    """
                    UPDATE request_ledger SET state = 'COMPLETED', response_json = ?, response_replayable = ?, updated_at = ?
                    WHERE request_id = ? AND state IN ('RESERVED', 'SENDING')
                    """.trimIndent(),
                    encoded, encoded != null, utcNow(clock), requestId,
                )
            }
        } catch (e: Exception) {
            logFailure("complete", requestId, e)
            throw RequestLedgerWriteException("complete")
        }
    }

    override fun markOutcomeUnknown(requestId: String) {
        try {
            transaction(database) {
                execUpdate(
                    "UPDATE request_ledger SET state = 'UNKNOWN', updated_at = ? WHERE request_id = ? AND state IN ('RESERVED', 'SENDING')",
                    utcNow(clock), requestId,
                )
            }
        } catch (e: Exception) {
            logFailure("markOutcomeUnknown", requestId, e)
        }
    }

    override fun releaseUnsent(requestId: String) {
        try {
            transaction(database) {
                execUpdate(
                    "UPDATE request_ledger SET state = 'RELEASED', updated_at = ? WHERE request_id = ? AND state IN ('RESERVED', 'SENDING')",
                    utcNow(clock), requestId,
                )
            }
        } catch (e: Exception) {
            logFailure("releaseUnsent", requestId, e)
        }
    }

    private data class Row(val digest: String, val state: String, val responseJson: String?, val replayable: Boolean)

    private fun Transaction.exists(requestId: String): Boolean =
        execQuery("SELECT 1 FROM request_ledger WHERE request_id = ?", requestId) { it.getInt(1) }.isNotEmpty()

    /** Counts rows up to [limit]; the scan is bounded by the capacity, not by the table size. */
    private fun Transaction.countUpTo(limit: Int): Long =
        execQuery("SELECT COUNT(*) FROM (SELECT request_id FROM request_ledger LIMIT ?) AS capped", limit) { it.getLong(1) }.single()

    private fun Transaction.selectForUpdate(requestId: String): Row? =
        execQuery(
            "SELECT digest, state, response_json, response_replayable FROM request_ledger WHERE request_id = ? FOR UPDATE",
            requestId,
        ) { rs -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) }.singleOrNull()

    /**
     * The stored response, or null when it is not replayable. A stored response that cannot be decoded is also null: the
     * Gateway then refuses the replay (DUPLICATE_REQUEST) instead of returning something different from what was stored.
     */
    private fun restore(requestId: String, row: Row): ModelResponse? {
        if (!row.replayable) return null
        val json = row.responseJson ?: return null
        return try {
            ModelResponseCodec.decode(json)
        } catch (e: Exception) {
            logger.error("request ledger response for {} cannot be decoded; replay refused ({})", prefix(requestId), e::class.simpleName)
            null
        }
    }

    private fun logFailure(operation: String, requestId: String, e: Exception) {
        val sql = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        logger.error(
            "request ledger {} failed for {} ({}, sqlState={}, errorCode={})",
            operation, prefix(requestId), e::class.simpleName, sql?.sqlState, sql?.errorCode,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 1_000_000
        const val STORE_UNAVAILABLE = "STORE_UNAVAILABLE"
        /** Upper bound on the stored response (UTF-8 bytes). Larger responses are sealed but not replayable. */
        const val MAX_RESPONSE_BYTES = 256 * 1024

        private fun prefix(requestId: String): String = requestId.take(8)
    }
}

/** A ledger write that must stop the caller (no send, or an unrecorded response). The message carries no request data. */
class RequestLedgerWriteException(operation: String) : RuntimeException("request ledger $operation failed")

/**
 * JSON form of a [ModelResponse] for the `response_json` column. Every field is kept so that a replayed response equals the
 * stored one. Decimal amounts are written with [BigDecimal.toString] (scale preserved), so they come back unchanged.
 * Absent optional fields are omitted; a present JSON null (structured output) stays distinct from an absent one.
 */
internal object ModelResponseCodec {
    private const val VERSION = 1

    /** Null when the encoded form exceeds [DbRequestLedger.MAX_RESPONSE_BYTES]. */
    fun encodeWithinLimit(response: ModelResponse): String? {
        val text = encode(response)
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= DbRequestLedger.MAX_RESPONSE_BYTES }
    }

    fun encode(r: ModelResponse): String = buildJsonObject {
        put("v", VERSION)
        put("requestId", r.requestId)
        put("attemptId", r.attemptId)
        put("selected", ref(r.selected))
        put("message", message(r.message))
        r.structured?.let { put("structured", it) }
        put("finishReason", r.finishReason.name)
        put("usage", buildJsonObject {
            put("inputTokens", r.usage.inputTokens)
            put("outputTokens", r.usage.outputTokens)
            put("cachedInputTokens", r.usage.cachedInputTokens)
        })
        put("cost", cost(r.cost))
        put("latencyMs", r.latencyMs)
        put("routingDecisionId", r.routingDecisionId)
        put("warnings", buildJsonArray {
            r.warnings.forEach { w -> add(buildJsonObject { put("code", w.code); put("message", w.message) }) }
        })
        put("usageComplete", r.usageComplete)
    }.toString()

    fun decode(text: String): ModelResponse {
        val o = Json.parseToJsonElement(text).jsonObject
        require(o.getValue("v").jsonPrimitive.long == VERSION.toLong()) { "unsupported response version" }
        return ModelResponse(
            requestId = o.str("requestId"),
            attemptId = o.str("attemptId"),
            selected = decodeRef(o.getValue("selected").jsonObject),
            message = decodeMessage(o.getValue("message").jsonObject),
            structured = o["structured"],
            finishReason = FinishReason.valueOf(o.str("finishReason")),
            usage = o.getValue("usage").jsonObject.let {
                Usage(it.long("inputTokens"), it.long("outputTokens"), it.long("cachedInputTokens"))
            },
            cost = decodeCost(o.getValue("cost").jsonObject),
            latencyMs = o.long("latencyMs"),
            routingDecisionId = o.str("routingDecisionId"),
            warnings = o.getValue("warnings").jsonArray.map { el ->
                el.jsonObject.let { GatewayWarning(it.str("code"), it.str("message")) }
            },
            usageComplete = o.getValue("usageComplete").jsonPrimitive.boolean,
        )
    }

    private fun ref(m: ModelRef) = buildJsonObject {
        put("providerId", m.providerId); put("modelId", m.modelId); put("providerModelName", m.providerModelName)
    }

    private fun decodeRef(o: JsonObject) = ModelRef(o.str("providerId"), o.str("modelId"), o.str("providerModelName"))

    private fun cost(c: Cost) = buildJsonObject {
        put("amount", c.amount.toString())
        put("currency", c.currency)
        put("basis", c.basis.name)
        put("unit", c.unit)
        c.priceRevision?.let { put("priceRevision", it) }
        c.model?.let { put("model", ref(it)) }
        c.requestId?.let { put("requestId", it) }
    }

    private fun decodeCost(o: JsonObject) = Cost(
        amount = BigDecimal(o.str("amount")),
        currency = o.str("currency"),
        basis = CostBasis.valueOf(o.str("basis")),
        unit = o.str("unit"),
        priceRevision = o.optStr("priceRevision"),
        model = o["model"]?.jsonObject?.let(::decodeRef),
        requestId = o.optStr("requestId"),
    )

    private fun message(m: Message) = buildJsonObject {
        put("role", m.role.name)
        put("parts", buildJsonArray { m.parts.forEach { add(part(it)) } })
    }

    private fun part(p: Part): JsonObject = buildJsonObject {
        when (p) {
            is Part.Text -> { put("type", "text"); put("text", p.text) }
            is Part.Image -> { put("type", "image"); put("mediaType", p.mediaType); put("base64Data", p.base64Data) }
            is Part.ToolCall -> { put("type", "tool_call"); put("id", p.id); put("name", p.name); put("arguments", p.arguments) }
            is Part.ToolResult -> {
                put("type", "tool_result"); put("callId", p.callId); put("content", p.content); put("isError", p.isError)
            }
        }
    }

    private fun decodeMessage(o: JsonObject) = Message(
        role = Role.valueOf(o.str("role")),
        parts = o.getValue("parts").jsonArray.map { decodePart(it.jsonObject) },
    )

    private fun decodePart(o: JsonObject): Part = when (val type = o.str("type")) {
        "text" -> Part.Text(o.str("text"))
        "image" -> Part.Image(o.str("mediaType"), o.str("base64Data"))
        "tool_call" -> Part.ToolCall(o.str("id"), o.str("name"), o.getValue("arguments"))
        "tool_result" -> Part.ToolResult(o.str("callId"), o.str("content"), o.getValue("isError").jsonPrimitive.boolean)
        else -> throw IllegalArgumentException("unknown part type: $type")
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.optStr(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
}
