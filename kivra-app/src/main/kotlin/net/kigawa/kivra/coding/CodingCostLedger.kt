package net.kigawa.kivra.coding

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.kivra.config.BudgetConfig
import net.kigawa.kivra.db.CodingAttemptUsageRepository
import net.kigawa.kivra.db.CodingUsageRow
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.SpendOutcome
import net.kigawa.kivra.db.SpendResult
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostConverter
import org.slf4j.LoggerFactory

/*
 * The cost of the CLI coding runtime in the monthly budget (ADR 0010 addendum 4, Issue #230).
 *
 * The local agent reports the usage of one attempt with its result or fail (`usage`, numbers only). The cost is booked once per
 * (attempt, lease generation), in yen, through the same budget store as the API path. An attempt that ends without a usage report
 * (fail without usage, ORPHANED, EXPIRED, a result without usage) is booked at the per-attempt reserve and flagged unpriced, so an
 * operator must acknowledge it (ADR 0003). Before a claim, the monthly budget must hold one reserve, or the claim is refused.
 *
 * Logs carry identifiers, fixed codes and counts only. Never a goal, a title, a summary, a change or the response text.
 */

/** Usage the agent reports for one attempt. Numbers and the model name only. */
data class CliUsage(
    val totalCostUsd: BigDecimal,
    val model: String,
    val numTurns: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
)

/**
 * Reads the optional `usage` of a result or fail body. The contract (fixed): exactly the seven fields below, the cost as a decimal string
 * (0 to 100, at most six decimals), the model name in the lower-case form, and the counts as JSON numbers in their ranges. Anything else
 * is ignored: the result or fail itself is still accepted, and the attempt counts as if no usage was reported. The log names only the
 * attempt id and a fixed reason.
 */
object CliUsageParser {
    private val logger = LoggerFactory.getLogger(CliUsageParser::class.java)
    private val COST = Regex("^(0|[1-9][0-9]{0,2})(\\.[0-9]{1,6})?$")
    private val MODEL = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
    private val FIELDS = setOf("totalCostUsd", "model", "numTurns", "inputTokens", "outputTokens", "cacheCreationTokens", "cacheReadTokens")
    private val MAX_COST_USD = BigDecimal("100")
    private const val MAX_TURNS = 1000
    private const val MAX_TOKENS = 10_000_000_000L

    /** The usage, or null when absent or invalid. An invalid usage is logged by [attemptId] only. */
    fun parse(attemptId: String, element: JsonElement?): CliUsage? {
        if (element == null || element is JsonNull) return null
        val usage = runCatching { parseObject(element) }.getOrNull()
        if (usage == null) logger.warn("coding usage ignored: attemptId={} reason=USAGE_INVALID", attemptId)
        return usage
    }

    private fun parseObject(element: JsonElement): CliUsage? {
        val o = element as? JsonObject ?: return null
        if (o.keys != FIELDS) return null
        val cost = o.text("totalCostUsd")?.takeIf { COST.matches(it) }?.let { BigDecimal(it) }?.takeIf { it <= MAX_COST_USD } ?: return null
        val model = o.text("model")?.takeIf { MODEL.matches(it) } ?: return null
        return CliUsage(
            totalCostUsd = cost,
            model = model,
            numTurns = o.number("numTurns", MAX_TURNS.toLong())?.toInt() ?: return null,
            inputTokens = o.number("inputTokens", MAX_TOKENS) ?: return null,
            outputTokens = o.number("outputTokens", MAX_TOKENS) ?: return null,
            cacheCreationTokens = o.number("cacheCreationTokens", MAX_TOKENS) ?: return null,
            cacheReadTokens = o.number("cacheReadTokens", MAX_TOKENS) ?: return null,
        )
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number (not a string) in 0..max. */
    private fun JsonObject.number(name: String, max: Long): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it in 0..max }
}

/** The claim-time, report-time and expiry-time cost rules the coding service calls. Implemented by [DbCodingCostLedger]. */
interface CodingCostPort {
    /** Null when a claim may be handed out. Otherwise the fixed denial code (BUDGET, UNKNOWN_COST, BUDGET_STORE_UNAVAILABLE, ...). */
    fun claimDenial(): String?

    /** Books the reported usage of (attempt, generation). Returns false when nothing could be recorded, so the caller must not complete. */
    fun recordReported(attemptId: String, leaseGeneration: Long, usage: CliUsage): Boolean

    /** Books the per-attempt reserve as an unpriced estimate for (attempt, generation). Idempotent. Returns false when nothing could be recorded. */
    fun recordEstimated(attemptId: String, leaseGeneration: Long): Boolean
}

/** The ledger could not record a cost, so the holder's fail is refused (the route answers 503). Carries no identifiers. */
class CodingCostUnavailableException : RuntimeException("coding cost ledger is unavailable")

/**
 * The yen ledger over [DbBudgetStore]. Idempotency key: `coding:<attemptId>:<leaseGeneration>`. A reported usage and an estimate for the same
 * (attempt, generation) share the key, so whichever is booked first is the only charge. A reported usage that arrives after an estimate is
 * refused by the attempt's own state checks, so the estimate stands (the safe side).
 *
 * - Reported, model priced, FX usable: charged at the converted cost, committed.
 * - Reported, model not in the price table: charged at the converted cost, unpriced.
 * - Reported, FX unusable: charged 0, unpriced (the cost cannot be converted; ADR 0003 acknowledges it).
 * - Estimate: charged at the converted reserve, unpriced. FX unusable: charged 0, unpriced.
 *
 * [modelPriced] answers whether the model has a price in force at the given instant (from the registry).
 */
class DbCodingCostLedger(
    private val budget: DbBudgetStore,
    private val usage: CodingAttemptUsageRepository,
    private val converter: CostConverter,
    private val reserveUsd: BigDecimal,
    private val tenantScope: String,
    private val modelPriced: (model: String, now: Instant) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : CodingCostPort {
    private val logger = LoggerFactory.getLogger(DbCodingCostLedger::class.java)

    override fun claimDenial(): String? {
        val reserve = converter.toYen(reserveUsd, USD, clock.instant())
        if (reserve == null) {
            logger.warn("coding claim refused: code={} costReason=FX_UNUSABLE", BudgetDenial.UNKNOWN_COST)
            return BudgetDenial.UNKNOWN_COST
        }
        val denied = budget.admission(listOf(tenantScope), yen(reserve.amount)) ?: return null
        logger.warn("coding claim refused: code={} costReason={}", denied.code, denied.costReason ?: "-")
        return denied.code
    }

    override fun recordReported(attemptId: String, leaseGeneration: Long, usage: CliUsage): Boolean {
        val now = clock.instant()
        val converted = converter.toYen(usage.totalCostUsd, USD, now)
        val priced = modelPriced(usage.model, now)
        if (converted == null) logger.warn("coding cost not convertible: attemptId={} code=FX_UNUSABLE", attemptId)
        if (!priced) logger.warn("coding cost model not priced: attemptId={} code=MODEL_UNPRICED", attemptId)
        return post(
            attemptId, leaseGeneration,
            amount = converted?.amount ?: BigDecimal.ZERO,
            unpriced = converted == null || !priced,
            row = CodingUsageRow(
                attemptId = attemptId,
                leaseGeneration = leaseGeneration,
                costUsd = usage.totalCostUsd,
                source = SOURCE_REPORTED,
                model = usage.model,
                numTurns = usage.numTurns,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cacheCreationTokens = usage.cacheCreationTokens,
                cacheReadTokens = usage.cacheReadTokens,
            ),
        )
    }

    override fun recordEstimated(attemptId: String, leaseGeneration: Long): Boolean {
        val converted = converter.toYen(reserveUsd, USD, clock.instant())
        if (converted == null) logger.warn("coding reserve not convertible: attemptId={} code=FX_UNUSABLE", attemptId)
        return post(
            attemptId, leaseGeneration,
            amount = converted?.amount ?: BigDecimal.ZERO,
            unpriced = true,
            row = CodingUsageRow(attemptId, leaseGeneration, reserveUsd, SOURCE_ESTIMATED),
        )
    }

    /**
     * Books the ledger row first (the money, idempotent by key), then the usage row. A crash between the two leaves the money booked
     * without its usage row, which is safe: the key still blocks a second charge. The usage row is never written for a key that was
     * already booked, so it always matches the charge it describes.
     */
    private fun post(attemptId: String, leaseGeneration: Long, amount: BigDecimal, unpriced: Boolean, row: CodingUsageRow): Boolean {
        val outcome = budget.recordSpend(reservationKey(attemptId, leaseGeneration), yen(amount), unpriced)
        return when (outcome.result) {
            SpendResult.FAILURE -> {
                logger.error("coding cost not recorded: attemptId={} code=LEDGER_UNAVAILABLE", attemptId)
                false
            }
            SpendResult.ALREADY_RECORDED -> {
                logger.debug("coding cost already recorded: attemptId={} generation={}", attemptId, leaseGeneration)
                true
            }
            SpendResult.RECORDED -> {
                logCostRecorded(attemptId, leaseGeneration, row, unpriced, outcome)
                try {
                    usage.insert(row)
                } catch (_: Exception) {
                    // The money is already booked under its key; only the usage record is missing. Logged by id only.
                    logger.error("coding usage row not recorded: attemptId={} code=USAGE_WRITE_FAILED", attemptId)
                }
                true
            }
        }
    }

    /** One INFO line per booked cost. Identifiers, fixed words and numbers only (no model name, text or path). */
    private fun logCostRecorded(attemptId: String, leaseGeneration: Long, row: CodingUsageRow, unpriced: Boolean, outcome: SpendOutcome) {
        val month = outcome.monthCommittedMicroYen?.let { " monthCommittedMicroYen=$it" }.orEmpty()
        logger.info(
            "coding cost recorded: attemptId={} generation={} source={} unpriced={} costUsd={} amountMicroYen={} month={}{}",
            attemptId, leaseGeneration, row.source, unpriced, row.costUsd.toPlainString(), outcome.amountMicroYen, outcome.period, month,
        )
    }

    private fun yen(amount: BigDecimal): BudgetAmount = BudgetAmount(amount, JPY, Cost.UNIT_CURRENCY_UNITS)

    companion object {
        const val USD = "USD"
        const val JPY = BudgetConfig.JPY
        const val SOURCE_REPORTED = "REPORTED"
        const val SOURCE_ESTIMATED = "ESTIMATED_RESERVE"

        /** The budget reservation key of one attempt generation. The same key for a report and an estimate. */
        fun reservationKey(attemptId: String, leaseGeneration: Long): String = "coding:$attemptId:$leaseGeneration"
    }
}
