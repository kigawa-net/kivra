package net.kigawa.kivra.config

import io.ktor.server.config.ApplicationConfig
import net.kigawa.kivra.fusha.FushaTaskConfig
import java.time.Duration

/** Reads `kivra.fusha.*` (kept apart from [KivraConfig] so the M0 config class is untouched). */
object FushaTaskConfigLoader {
    fun from(config: ApplicationConfig): FushaTaskConfig = FushaTaskConfig(
        baseUrl = config.property("kivra.fusha.baseUrl").getString(),
        environment = config.property("kivra.fusha.environment").getString(),
        staticToken = config.property("kivra.fusha.token").getString().ifBlank { null },
        requestTimeout = Duration.ofMillis(config.property("kivra.fusha.requestTimeoutMillis").getString().toLong()),
        connectTimeout = Duration.ofMillis(config.property("kivra.fusha.connectTimeoutMillis").getString().toLong()),
        maxRetries = config.property("kivra.fusha.maxRetries").getString().toInt(),
        initialBackoff = Duration.ofMillis(config.property("kivra.fusha.initialBackoffMillis").getString().toLong()),
        maxBackoff = Duration.ofMillis(config.property("kivra.fusha.maxBackoffMillis").getString().toLong()),
        allowInsecureHttp = config.propertyOrNull("kivra.fusha.allowInsecureHttp")?.getString()?.toBoolean() ?: false,
    )
}
