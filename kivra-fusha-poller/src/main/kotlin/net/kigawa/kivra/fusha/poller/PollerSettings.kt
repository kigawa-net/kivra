package net.kigawa.kivra.fusha.poller

import net.kigawa.kivra.core.TaskFilter
import java.time.Duration

/**
 * Poller settings. The poll scope must be restricted by a view or an assignee (design #2 section 7.1): an unscoped
 * listing would pull in other people's tasks.
 */
data class TaskPollerConfig(
    /** Environment label; must match the TaskProvider's environment (`fusha:{env}:{id}`). */
    val environment: String,
    val filter: TaskFilter,
    /** Base interval between polls. Needs confirmation per environment (design #2 section 13-5). */
    val interval: Duration = Duration.ofSeconds(30),
    /** Random extra delay added to each interval (0..jitter). */
    val jitter: Duration = Duration.ofSeconds(5),
    /** Every N-th attempt also GETs every listed task and compares it with the listing (full rescan). */
    val fullRescanEveryPolls: Int = 20,
    /** Consecutive failed polls before the circuit opens. */
    val breakerFailureThreshold: Int = 3,
    /** How long the circuit stays open. No provider calls are made while it is open. */
    val breakerCooldown: Duration = Duration.ofSeconds(60),
) {
    init {
        require(environment.isNotBlank() && !environment.contains(':')) { "poller environment must be non-blank and contain no ':'" }
        require(filter.viewId != null || !filter.assigneeId.isNullOrBlank()) {
            "poll scope must be restricted by viewId or assigneeId"
        }
        require(!interval.isZero && !interval.isNegative) { "poll interval must be positive" }
        require(!jitter.isNegative) { "poll jitter must not be negative" }
        require(fullRescanEveryPolls >= 1) { "fullRescanEveryPolls must be >= 1" }
        require(breakerFailureThreshold >= 1) { "breakerFailureThreshold must be >= 1" }
        require(!breakerCooldown.isNegative) { "breakerCooldown must not be negative" }
    }
}

/**
 * Mapping between Kivra's execution status and the fusha-task status customField (design #2 sections 5.1 and 13-1).
 *
 * The key and the option values are not confirmed yet, so writes are OFF by default. [writesAllowed] is false unless
 * [writeEnabled] is set AND the key and a complete, one-to-one option mapping are configured. No writer exists in M1
 * (Writeback is held until server-side CAS and machine authentication are confirmed); this only defines the switch.
 */
data class StatusMapping(
    val writeEnabled: Boolean = false,
    /** customField key that holds the status (要確認). */
    val statusFieldKey: String? = null,
    /** Kivra status name -> fusha-task select option. */
    val kivraToFusha: Map<String, String> = emptyMap(),
) {
    init {
        require(kivraToFusha.all { (k, v) -> k.isNotBlank() && v.isNotBlank() }) { "status mapping keys and options must be non-blank" }
        require(kivraToFusha.values.toSet().size == kivraToFusha.size) { "status mapping must be one-to-one (duplicate option)" }
        if (writeEnabled) {
            require(!statusFieldKey.isNullOrBlank() && kivraToFusha.isNotEmpty()) {
                "status writes cannot be enabled without statusFieldKey and an option mapping"
            }
        }
    }

    val fushaToKivra: Map<String, String> = kivraToFusha.entries.associate { (k, v) -> v to k }

    fun writesAllowed(): Boolean = writeEnabled && !statusFieldKey.isNullOrBlank() && kivraToFusha.isNotEmpty()
}
