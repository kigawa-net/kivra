package net.kigawa.kivra.scheduler

/**
 * Supplies the Bearer token for ai-scheduler. ai-scheduler has no authentication today (Phase 1), so the default is
 * [NoAuth]; the injection point exists so enabling auth later needs no Adapter change. Implementations must not
 * log the token.
 */
interface ScheduleTokenProvider {
    /** Null means "send no Authorization header". */
    suspend fun token(): String?

    /** Called after a 401 so a cached token is not reused. */
    fun invalidate() {}

    object NoAuth : ScheduleTokenProvider {
        override suspend fun token(): String? = null
    }
}

class StaticScheduleTokenProvider(private val value: String) : ScheduleTokenProvider {
    override suspend fun token(): String = value
    override fun toString() = "StaticScheduleTokenProvider(token=***)"
}
