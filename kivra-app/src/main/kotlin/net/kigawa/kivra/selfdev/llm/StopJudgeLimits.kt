package net.kigawa.kivra.selfdev.llm

import java.time.Clock
import java.time.ZoneId

/** Limits of the automatic approval (ADR 0021 P6, design 4). Money is held in milli-yen (1/1000 yen) as an integer. */
data class StopJudgeLimits(
    val maxApprovalsPerHour: Int = 3,
    val maxCallsPerHour: Int = 20,
    val maxInputTokens: Int = 4000,
    val maxOutputTokens: Int = 100,
    val maxMilliYenPerMonth: Long = 500_000,
) {
    init {
        require(maxApprovalsPerHour >= 0 && maxCallsPerHour >= 0 && maxInputTokens >= 0 && maxOutputTokens >= 0 && maxMilliYenPerMonth >= 0) {
            "limits must not be negative"
        }
    }
}

/** Fixed codes for a refusal by a limit. */
enum class LimitCode { LIMIT_APPROVALS, LIMIT_CALLS, LIMIT_TOKENS, LIMIT_COST }

sealed interface LimitDecision {
    data object Allowed : LimitDecision
    data class Denied(val code: LimitCode) : LimitDecision
}

/** What is counted. */
enum class LimitCounter { CALLS, APPROVALS, COST_MILLI_YEN }

/**
 * The state of the limits. One conditional add, atomic: the DB version (L4) is one conditional UPDATE. A [bucket] is an hour or a
 * month key; the store may drop older buckets.
 */
interface LimitsStore {
    /** Adds [amount] to the counter of [bucket] only if the result stays at or under [max]. True when added. */
    fun tryAdd(counter: LimitCounter, bucket: Long, amount: Long, max: Long): Boolean

    fun current(counter: LimitCounter, bucket: Long): Long
}

/** In-memory [LimitsStore] (L3 and tests). */
class InMemoryLimitsStore : LimitsStore {
    private val values = HashMap<Pair<LimitCounter, Long>, Long>()

    @Synchronized
    override fun tryAdd(counter: LimitCounter, bucket: Long, amount: Long, max: Long): Boolean {
        if (amount < 0) return false
        values.keys.removeAll { it.first == counter && it.second < bucket }
        val now = values[counter to bucket] ?: 0L
        if (now > max - amount) return false
        values[counter to bucket] = now + amount
        return true
    }

    @Synchronized
    override fun current(counter: LimitCounter, bucket: Long): Long = values[counter to bucket] ?: 0L
}

/**
 * The limit logic (pure; the clock and the store are injected). An hour is a clock hour in UTC; a month is a calendar month in
 * Japan time (the budget is in yen). A refusal is a fixed [LimitCode]; the model is not called then.
 */
class StopJudgeLimiter(
    private val limits: StopJudgeLimits,
    private val store: LimitsStore,
    private val clock: Clock,
) {
    private fun hourBucket(): Long = clock.instant().epochSecond / 3600

    private fun monthBucket(): Long {
        val d = clock.instant().atZone(JST).toLocalDate()
        return d.year * 100L + d.monthValue
    }

    /** Before a call: the input size, the remaining monthly cost, and one call of the hourly count (taken only when all pass). */
    fun reserveCall(estimatedInputTokens: Int): LimitDecision {
        if (estimatedInputTokens > limits.maxInputTokens) return LimitDecision.Denied(LimitCode.LIMIT_TOKENS)
        if (store.current(LimitCounter.COST_MILLI_YEN, monthBucket()) >= limits.maxMilliYenPerMonth) {
            return LimitDecision.Denied(LimitCode.LIMIT_COST)
        }
        if (!store.tryAdd(LimitCounter.CALLS, hourBucket(), 1, limits.maxCallsPerHour.toLong())) {
            return LimitDecision.Denied(LimitCode.LIMIT_CALLS)
        }
        return LimitDecision.Allowed
    }

    /** After a call: adds the cost. If it would pass the monthly limit it is not added and is refused. */
    fun recordCost(milliYen: Long): LimitDecision =
        if (store.tryAdd(LimitCounter.COST_MILLI_YEN, monthBucket(), milliYen, limits.maxMilliYenPerMonth)) {
            LimitDecision.Allowed
        } else {
            LimitDecision.Denied(LimitCode.LIMIT_COST)
        }

    /** Before an automatic approval: one of the hourly approvals. */
    fun reserveApproval(): LimitDecision =
        if (store.tryAdd(LimitCounter.APPROVALS, hourBucket(), 1, limits.maxApprovalsPerHour.toLong())) {
            LimitDecision.Allowed
        } else {
            LimitDecision.Denied(LimitCode.LIMIT_APPROVALS)
        }

    private companion object {
        val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}

/** Price per million tokens, in milli-yen. Held in settings (no code constant of a real price). */
data class JudgePricing(val inputMilliYenPerMtok: Long, val outputMilliYenPerMtok: Long) {
    init {
        require(inputMilliYenPerMtok >= 0 && outputMilliYenPerMtok >= 0) { "pricing must not be negative" }
    }

    /** Cost of one call in milli-yen, rounded up. */
    fun costMilliYen(usage: JudgeUsage): Long {
        val total = java.math.BigInteger.valueOf(usage.inputTokens).multiply(java.math.BigInteger.valueOf(inputMilliYenPerMtok))
            .add(java.math.BigInteger.valueOf(usage.outputTokens).multiply(java.math.BigInteger.valueOf(outputMilliYenPerMtok)))
        val million = java.math.BigInteger.valueOf(1_000_000)
        return total.add(million).subtract(java.math.BigInteger.ONE).divide(million).min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }
}
