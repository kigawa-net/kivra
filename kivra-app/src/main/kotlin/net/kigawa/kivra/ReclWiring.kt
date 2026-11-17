package net.kigawa.kivra

import io.ktor.client.engine.cio.CIO
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryService
import net.kigawa.kivra.memory.recl.ReclAdapter
import net.kigawa.kivra.memory.recl.ReclConfig
import net.kigawa.kivra.memory.recl.ReclCredentials
import java.time.Duration

/** Startup settings. Disabled deployments ignore connection settings and allocate no HTTP client. */
class ReclSettings private constructor(
    val enabled: Boolean,
    val baseUrl: String? = null,
    private val token: String? = null,
    val readTimeout: Duration = Duration.ofSeconds(2),
    val circuitBreakerThreshold: Int = 3,
    val circuitBreakerCooldown: Duration = Duration.ofSeconds(30),
) {
    internal fun credentials() = ReclCredentials { requireNotNull(token) }

    companion object {
        fun fromEnv(env: Map<String, String>): ReclSettings {
            val enabled = env["KIVRA_RECL_ENABLED"]?.toBooleanStrict() ?: false
            if (!enabled) return ReclSettings(enabled = false)

            fun positiveLong(name: String, default: Long): Long {
                val value = env[name]?.let { raw ->
                    requireNotNull(raw.toLongOrNull()) { "$name must be an integer" }
                } ?: default
                require(value > 0) { "$name must be positive" }
                return value
            }
            val url = requireNotNull(env["KIVRA_RECL_BASE_URL"]) { "KIVRA_RECL_BASE_URL is required" }
            ReclConfig(url) // Validate https and URL shape before any network or database work.
            val token = requireNotNull(env["KIVRA_RECL_TOKEN"]) { "KIVRA_RECL_TOKEN is required" }
            require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() }) {
                "KIVRA_RECL_TOKEN must be a nonblank bearer token"
            }
            val timeout = positiveLong("KIVRA_RECL_READ_TIMEOUT_MS", 2_000)
            val threshold = positiveLong("KIVRA_RECL_CIRCUIT_BREAKER_THRESHOLD", 3)
            require(threshold <= Int.MAX_VALUE) { "KIVRA_RECL_CIRCUIT_BREAKER_THRESHOLD is too large" }
            val cooldown = positiveLong("KIVRA_RECL_CIRCUIT_BREAKER_COOLDOWN_MS", 30_000)
            return ReclSettings(true, url, token, Duration.ofMillis(timeout), threshold.toInt(), Duration.ofMillis(cooldown))
        }
    }
}

object ReclWiring {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): MemoryService? =
        fromEnvironment(ReclSettings.fromEnv(env))

    /** The factory keeps tests independent of the unprovided recl REST server. The service owns the provider. */
    internal fun fromEnvironment(
        settings: ReclSettings,
        providerFactory: (ReclSettings) -> MemoryProvider = { config ->
            ReclAdapter(
                config = ReclConfig(requireNotNull(config.baseUrl), requestTimeoutMs = config.readTimeout.toMillis()),
                engine = CIO.create(),
                credentials = config.credentials(),
            )
        },
    ): MemoryService? {
        if (!settings.enabled) return null
        return MemoryService(
            provider = providerFactory(settings),
            readTimeout = settings.readTimeout,
            breakerThreshold = settings.circuitBreakerThreshold,
            breakerCooldown = settings.circuitBreakerCooldown,
        )
    }
}
