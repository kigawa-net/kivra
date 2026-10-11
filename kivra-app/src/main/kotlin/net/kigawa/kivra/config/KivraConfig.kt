package net.kigawa.kivra.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
)

data class LeaseConfig(
    val ttl: Duration,
    val grace: Duration,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "lease ttl must be positive" }
        require(!grace.isNegative) { "lease grace must not be negative" }
    }
}

data class KivraConfig(
    val database: DatabaseConfig,
    val lease: LeaseConfig,
) {
    companion object {
        fun from(config: ApplicationConfig): KivraConfig = KivraConfig(
            database = DatabaseConfig(
                jdbcUrl = config.property("kivra.database.jdbcUrl").getString(),
                user = config.property("kivra.database.user").getString(),
                password = config.property("kivra.database.password").getString(),
            ),
            lease = LeaseConfig(
                ttl = Duration.ofSeconds(config.property("kivra.lease.ttlSeconds").getString().toLong()),
                grace = Duration.ofSeconds(config.property("kivra.lease.graceSeconds").getString().toLong()),
            ),
        )
    }
}
