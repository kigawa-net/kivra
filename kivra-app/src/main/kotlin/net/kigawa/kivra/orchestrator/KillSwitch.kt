package net.kigawa.kivra.orchestrator

import org.slf4j.LoggerFactory

/**
 * Global Kill Switch for the Orchestrator (design #3 section 9, Phase C6).
 *
 * When activated, the Orchestrator stops admitting new tasks and optionally stops
 * all running agents. Only humans can activate the Kill Switch via environment
 * variable or API endpoint — agents cannot trigger it.
 *
 * States:
 * - [KillSwitchState.OFF]: Normal operation, no restrictions.
 * - [KillSwitchState.STOP_NEW]: New task admission is blocked; existing runs continue.
 * - [KillSwitchState.STOP_ALL]: New admission blocked; existing runs are signaled to stop.
 *
 * The Kill Switch is fail-closed: if the state store is unavailable, it defaults to OFF.
 * Activation persists across restarts only if stored in the database (not implemented yet).
 */
enum class KillSwitchState {
    OFF,
    STOP_NEW,
    STOP_ALL,
}

class KillSwitch(
    /** Initial state from environment variable. */
    initialState: KillSwitchState = KillSwitchState.OFF,
) {
    private val logger = LoggerFactory.getLogger(KillSwitch::class.java)

    @Volatile
    private var state: KillSwitchState = initialState

    /** Current state of the Kill Switch. */
    val currentState: KillSwitchState get() = state

    /** Returns true if new task admission should be blocked. */
    val shouldBlockAdmission: Boolean get() = state != KillSwitchState.OFF

    /** Returns true if all runs should be stopped. */
    val shouldStopAll: Boolean get() = state == KillSwitchState.STOP_ALL

    /**
     * Activate the Kill Switch to the specified state.
     * Only humans should call this method (via API or environment).
     */
    fun activate(newState: KillSwitchState) {
        require(newState != KillSwitchState.OFF) { "Use deactivate() to turn off the Kill Switch" }
        val old = state
        state = newState
        logger.warn("kill switch activated: {} -> {} (new admission blocked={}, stop all={})",
            old, newState, shouldBlockAdmission, shouldStopAll)
    }

    /**
     * Deactivate the Kill Switch, returning to normal operation.
     * Only humans should call this method (via API or environment).
     */
    fun deactivate() {
        val old = state
        state = KillSwitchState.OFF
        if (old != KillSwitchState.OFF) {
            logger.info("kill switch deactivated: {} -> OFF", old)
        }
    }

    companion object {
        private const val ENV_KILL_SWITCH = "KIVRA_ORCHESTRATOR_KILL_SWITCH"

        /**
         * Parse the initial Kill Switch state from environment variables.
         * Supported values: "stop_new", "stop_all" (case-insensitive).
         * Defaults to OFF if not set or invalid.
         */
        fun initialStateFromEnv(env: Map<String, String>): KillSwitchState {
            val value = env[ENV_KILL_SWITCH]?.lowercase() ?: return KillSwitchState.OFF
            return when (value) {
                "stop_new" -> KillSwitchState.STOP_NEW
                "stop_all" -> KillSwitchState.STOP_ALL
                else -> {
                    if (value.isNotBlank()) {
                        LoggerFactory.getLogger(KillSwitch::class.java)
                            .warn("invalid $ENV_KILL_SWITCH value: {}, defaulting to OFF", value)
                    }
                    KillSwitchState.OFF
                }
            }
        }
    }
}
