package net.kigawa.kivra.orchestrator

/**
 * ai-scheduler integration settings (design #3 section 11, Phase C5).
 *
 * ai-scheduler is only the "wake-up" role. The Orchestrator keeps its own internal timer
 * and continues working when ai-scheduler is down. ai-scheduler adds extra wake-ups:
 * periodic tick, approval-deadline resume, and periodic Reaper scans.
 *
 * Best-effort: if ai-scheduler is unavailable, the Orchestrator falls back to the internal timer.
 */
data class SchedulerSettings(
    val enabled: Boolean,
    val baseUrl: String?,
    val env: String?,
    val tickCron: String?,
    val reaperCron: String?,
) {
    companion object {
        private const val ENV_ENABLED = "KIVRA_SCHEDULER_ENABLED"
        private const val ENV_BASE_URL = "KIVRA_SCHEDULER_BASE_URL"
        private const val ENV_ENV = "KIVRA_SCHEDULER_ENV"
        private const val ENV_TICK_CRON = "KIVRA_SCHEDULER_TICK_CRON"
        private const val ENV_REAPER_CRON = "KIVRA_SCHEDULER_REAPER_CRON"

        fun fromEnv(env: Map<String, String>): SchedulerSettings {
            val enabled = env[ENV_ENABLED]?.toBooleanStrict() ?: false
            if (!enabled) return SchedulerSettings(enabled = false, null, null, null, null)

            val baseUrl = env[ENV_BASE_URL]
            val envName = env[ENV_ENV] ?: "dev"
            val tickCron = env[ENV_TICK_CRON] ?: "*/5 * * * *"
            val reaperCron = env[ENV_REAPER_CRON] ?: "0 */1 * * *"

            require(baseUrl != null && baseUrl.isNotBlank()) {
                "$ENV_BASE_URL is required when $ENV_ENABLED=true"
            }

            return SchedulerSettings(
                enabled = true,
                baseUrl = baseUrl,
                env = envName,
                tickCron = tickCron,
                reaperCron = reaperCron,
            )
        }
    }
}
