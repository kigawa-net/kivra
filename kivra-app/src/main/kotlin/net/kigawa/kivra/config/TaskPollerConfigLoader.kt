package net.kigawa.kivra.config

import io.ktor.server.config.ApplicationConfig
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.fusha.poller.StatusMapping
import net.kigawa.kivra.fusha.poller.TaskPollerConfig
import java.time.Duration

/**
 * Reads `kivra.fusha.poller.*` and `kivra.fusha.statusMapping.*` (kept apart from [FushaTaskConfigLoader]).
 * The environment is shared with `kivra.fusha.environment`.
 */
object TaskPollerConfigLoader {
    fun from(config: ApplicationConfig): TaskPollerConfig = TaskPollerConfig(
        environment = config.property("kivra.fusha.environment").getString(),
        filter = TaskFilter(
            assigneeId = config.property("kivra.fusha.poller.assigneeId").getString().ifBlank { null },
            viewId = config.property("kivra.fusha.poller.viewId").getString().ifBlank { null }?.toLong(),
        ),
        interval = Duration.ofMillis(config.property("kivra.fusha.poller.intervalMillis").getString().toLong()),
        jitter = Duration.ofMillis(config.property("kivra.fusha.poller.jitterMillis").getString().toLong()),
        fullRescanEveryPolls = config.property("kivra.fusha.poller.fullRescanEveryPolls").getString().toInt(),
        breakerFailureThreshold = config.property("kivra.fusha.poller.breakerFailureThreshold").getString().toInt(),
        breakerCooldown = Duration.ofMillis(config.property("kivra.fusha.poller.breakerCooldownMillis").getString().toLong()),
    )

    /** Status writes stay disabled unless explicitly switched on (issue #28: disabled until the mapping is confirmed). */
    fun statusMappingFrom(config: ApplicationConfig): StatusMapping {
        val base = "kivra.fusha.statusMapping"
        val mapping = config.propertyOrNull("$base.kivraToFusha")?.let { _ ->
            config.config("$base.kivraToFusha").keys().associateWith { key ->
                config.property("$base.kivraToFusha.$key").getString()
            }
        }.orEmpty()
        return StatusMapping(
            writeEnabled = config.property("$base.writeEnabled").getString().toBoolean(),
            statusFieldKey = config.property("$base.statusFieldKey").getString().ifBlank { null },
            kivraToFusha = mapping,
        )
    }
}
