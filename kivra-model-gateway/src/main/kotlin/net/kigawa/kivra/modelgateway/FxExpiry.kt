package net.kigawa.kivra.modelgateway

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The expiry state of the FX rate (see [FxConfig.expiry]). [asOf] and [expiresAt] are instants, [daysLeft] is whole days rounded up
 * (0 or less once [expired]). Carries no secret.
 */
data class FxExpiry(val asOf: Instant, val expiresAt: Instant, val daysLeft: Long, val expired: Boolean) {
    /** The as-of date in UTC, the only form in which the rate's age is shown to operators. */
    val asOfDate: LocalDate get() = asOf.atZone(ZoneOffset.UTC).toLocalDate()
}

enum class FxExpiryLevel { WARN, ERROR }

/** A log line about the rate's expiry. [message] is a fixed format with the as-of date and the day count only. */
data class FxExpiryAlert(val level: FxExpiryLevel, val message: String)

object FxExpiryPolicy {
    /** Warn when the rate has this many whole days left or fewer (and has not expired yet). */
    const val WARN_DAYS_LEFT = 2L

    /** null when nothing needs saying: the rate is not configured, or it has more than [WARN_DAYS_LEFT] days left. */
    fun alertFor(expiry: FxExpiry?): FxExpiryAlert? {
        if (expiry == null) return null
        return when {
            expiry.expired -> FxExpiryAlert(FxExpiryLevel.ERROR, "fx rate expired: asOf=${expiry.asOfDate}")
            expiry.daysLeft <= WARN_DAYS_LEFT ->
                FxExpiryAlert(FxExpiryLevel.WARN, "fx rate expires soon: asOf=${expiry.asOfDate} daysLeft=${expiry.daysLeft}")
            else -> null
        }
    }
}
