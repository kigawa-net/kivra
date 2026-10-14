package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Invalid FX / tax settings. The message names the setting but never prints a value. */
class FxConfigException(message: String) : IllegalArgumentException(message)

/**
 * Settings for converting external-API cost into the JPY budget (design 13 B2). Immutable: a rate change takes effect when
 * a new config is built (restart or reload). Values come from the environment through [fromEnv]; none are code constants.
 *
 * - [usdJpyRate]: null when not configured. Out-of-range values are accepted here and refused at conversion time.
 * - [fxAsOf]: the reference time of [usdJpyRate]. Null means unknown age, so the rate is refused.
 * - [fxMarginRatio] / [taxMarginRatio]: conservative headroom added on top of the rate and of the tax.
 * - [maxAgeDays]: a rate older than this is refused.
 */
data class FxConfig(
    val usdJpyRate: BigDecimal? = null,
    val fxAsOf: Instant? = null,
    val fxMarginRatio: BigDecimal = DEFAULT_MARGIN,
    val taxMarginRatio: BigDecimal = DEFAULT_MARGIN,
    val maxAgeDays: Long = DEFAULT_MAX_AGE_DAYS,
) {
    init {
        if (fxMarginRatio.signum() < 0 || fxMarginRatio > BigDecimal.ONE) throw FxConfigException("$ENV_FX_MARGIN must be between 0 and 1")
        if (taxMarginRatio.signum() < 0 || taxMarginRatio > BigDecimal.ONE) throw FxConfigException("$ENV_TAX_MARGIN must be between 0 and 1")
        if (maxAgeDays < 1) throw FxConfigException("$ENV_MAX_AGE_DAYS must be a positive integer")
    }

    companion object {
        const val ENV_USD_JPY = "KIVRA_FX_USD_JPY"
        const val ENV_AS_OF = "KIVRA_FX_AS_OF"
        const val ENV_FX_MARGIN = "KIVRA_FX_MARGIN_RATIO"
        const val ENV_TAX_MARGIN = "KIVRA_TAX_MARGIN_RATIO"
        const val ENV_MAX_AGE_DAYS = "KIVRA_FX_MAX_AGE_DAYS"

        val DEFAULT_MARGIN: BigDecimal = BigDecimal("0.10")
        const val DEFAULT_MAX_AGE_DAYS = 7L

        /**
         * Reads the settings from [env] (a map of environment variables). Blank or absent values take the defaults; the rate
         * and as-of stay null when absent. A malformed value throws [FxConfigException] (fail at startup, not at charge time).
         */
        fun fromEnv(env: Map<String, String>): FxConfig {
            fun raw(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun decimal(name: String): BigDecimal? = raw(name)?.let {
                runCatching { BigDecimal(it) }.getOrNull() ?: throw FxConfigException("$name is not a decimal number")
            }
            val asOf = raw(ENV_AS_OF)?.let { v ->
                runCatching { Instant.parse(v) }.recoverCatching { LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant() }
                    .getOrNull() ?: throw FxConfigException("$ENV_AS_OF must be an ISO-8601 instant or date")
            }
            val maxAge = raw(ENV_MAX_AGE_DAYS)?.let { it.toLongOrNull() ?: throw FxConfigException("$ENV_MAX_AGE_DAYS must be an integer") }
            return FxConfig(
                usdJpyRate = decimal(ENV_USD_JPY),
                fxAsOf = asOf,
                fxMarginRatio = decimal(ENV_FX_MARGIN) ?: DEFAULT_MARGIN,
                taxMarginRatio = decimal(ENV_TAX_MARGIN) ?: DEFAULT_MARGIN,
                maxAgeDays = maxAge ?: DEFAULT_MAX_AGE_DAYS,
            )
        }
    }
}

/** A converted amount in yen with the evidence needed to explain it later (rate, margins, as-of). Not secret. */
data class YenConversion(val amount: BigDecimal, val evidence: String)

/**
 * Converts a provider cost into yen, conservatively (design 13 B2):
 *
 *     yen = ceil_micro(foreign × usdJpy × (1 + fxMargin) × (1 + taxMargin))   for USD
 *     yen = ceil_micro(foreign × (1 + taxMargin))                              for JPY (no FX)
 *
 * Rounding is up to micro-yen (the budget store's unit). Returns null (fail closed) when the currency is not USD or JPY,
 * the rate is missing or outside [MIN_RATE, MAX_RATE], the rate is absent or older than [FxConfig.maxAgeDays], or the
 * as-of is in the future.
 */
class CostConverter(private val config: FxConfig, private val clock: Clock = Clock.systemUTC()) {
    fun toYen(amount: BigDecimal, currency: String, now: Instant = clock.instant()): YenConversion? {
        val taxFactor = BigDecimal.ONE + config.taxMarginRatio
        return when (currency) {
            YEN -> YenConversion(
                roundUp(amount * taxFactor),
                "fx=none taxMargin=${config.taxMarginRatio.toPlainString()}",
            )
            "USD" -> {
                val rate = config.usdJpyRate ?: return null
                if (rate < MIN_RATE || rate > MAX_RATE) return null
                val asOf = config.fxAsOf ?: return null
                if (asOf.isAfter(now) || Duration.between(asOf, now) > Duration.ofDays(config.maxAgeDays)) return null
                val fxFactor = BigDecimal.ONE + config.fxMarginRatio
                YenConversion(
                    roundUp(amount * rate * fxFactor * taxFactor),
                    "fx=USD/JPY:${rate.toPlainString()} fxMargin=${config.fxMarginRatio.toPlainString()} " +
                        "taxMargin=${config.taxMarginRatio.toPlainString()} fxAsOf=$asOf",
                )
            }
            else -> null
        }
    }

    private fun roundUp(v: BigDecimal): BigDecimal = v.setScale(MICRO_YEN_SCALE, RoundingMode.UP)

    companion object {
        const val YEN = "JPY"
        const val MICRO_YEN_SCALE = 6
        val MIN_RATE: BigDecimal = BigDecimal.ONE
        val MAX_RATE: BigDecimal = BigDecimal(1000)
    }
}
