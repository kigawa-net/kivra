package net.kigawa.kivra.fx

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.modelgateway.FxConfig
import net.kigawa.kivra.modelgateway.FxConfigException
import net.kigawa.kivra.modelgateway.FxExpiryAlert
import net.kigawa.kivra.modelgateway.FxExpiryLevel
import net.kigawa.kivra.modelgateway.FxExpiryPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.kigawa.kivra.fx.FxExpiryWatcher")

/**
 * The FX settings for the expiry warning, or null when USD is not configured (no rate or no as-of). Invalid settings
 * also give null, with a fixed WARN line, so a bad FX value does not stop the startup of a deployment that does not use it.
 */
fun fxSettingsForWarning(env: Map<String, String>): FxConfig? {
    val config = try {
        FxConfig.fromEnv(env)
    } catch (_: FxConfigException) {
        logger.warn("fx settings are invalid; the fx expiry warning is off")
        return null
    }
    if (config.usdJpyRate == null || config.fxAsOf == null) return null
    return config
}

/**
 * Warns the operator before the FX rate expires. [check] logs at most one line per call: WARN when two days or fewer are left,
 * ERROR once expired. [start] runs [check] at startup and then once a day. The line holds the as-of date and the day count only.
 */
class FxExpiryWatcher(
    private val config: FxConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = Duration.ofDays(1),
) {
    fun check(): FxExpiryAlert? {
        val alert = FxExpiryPolicy.alertFor(config.expiry(clock)) ?: return null
        when (alert.level) {
            FxExpiryLevel.WARN -> logger.warn(alert.message)
            FxExpiryLevel.ERROR -> logger.error(alert.message)
        }
        return alert
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("fx expiry check failed: {}", e::class.java.simpleName)
            }
            delay(interval.toMillis())
        }
    }
}
