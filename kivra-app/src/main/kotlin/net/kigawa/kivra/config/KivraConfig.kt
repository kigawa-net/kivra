package net.kigawa.kivra.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

/**
 * `/metrics` is off by default. When enabled it is served on the API listener and requires a
 * fixed bearer token (fail-closed: enabling without a token refuses to start).
 */
data class MetricsConfig(
    val enabled: Boolean,
    val bearerToken: String?,
) {
    init {
        require(!enabled || !bearerToken.isNullOrBlank()) {
            "kivra.metrics.bearerToken (KIVRA_METRICS_BEARER_TOKEN) is required when kivra.metrics.enabled is true"
        }
    }

    companion object {
        fun from(config: ApplicationConfig): MetricsConfig = MetricsConfig(
            enabled = config.propertyOrNull("kivra.metrics.enabled")?.getString()?.toBooleanStrict() ?: false,
            bearerToken = config.propertyOrNull("kivra.metrics.bearerToken")?.getString()?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * [user] / [password] are the application credentials (DML only in production). [migrationUser] / [migrationPassword]
 * are optional: when both are set, Flyway runs with them (DDL) and the application pool keeps [user]. Blank values count
 * as unset. Setting only one of the pair fails at startup, so a typo cannot silently run the migration with the wrong
 * credentials or leave both roles identical. Messages never contain a password.
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val migrationUser: String? = null,
    val migrationPassword: String? = null,
) {
    init {
        require((migrationUser == null) == (migrationPassword == null)) {
            "kivra.database.migrationUser (KIVRA_DB_MIGRATION_USER) and kivra.database.migrationPassword " +
                "(KIVRA_DB_MIGRATION_PASSWORD) must be set together or both left unset"
        }
    }

    /** Keeps passwords out of logs and exception messages that print the config. */
    override fun toString(): String =
        "DatabaseConfig(jdbcUrl=$jdbcUrl, user=***, password=***, migrationUser=${migrationUser?.let { "***" }}, migrationPassword=${migrationPassword?.let { "***" }})"
}

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
    val metrics: MetricsConfig,
) {
    companion object {
        fun from(config: ApplicationConfig): KivraConfig = KivraConfig(
            database = DatabaseConfig(
                jdbcUrl = config.property("kivra.database.jdbcUrl").getString(),
                user = config.property("kivra.database.user").getString(),
                password = config.property("kivra.database.password").getString(),
                migrationUser = config.propertyOrNull("kivra.database.migrationUser")?.getString()?.takeIf { it.isNotBlank() },
                migrationPassword = config.propertyOrNull("kivra.database.migrationPassword")?.getString()?.takeIf { it.isNotBlank() },
            ),
            lease = LeaseConfig(
                ttl = Duration.ofSeconds(config.property("kivra.lease.ttlSeconds").getString().toLong()),
                grace = Duration.ofSeconds(config.property("kivra.lease.graceSeconds").getString().toLong()),
            ),
            metrics = MetricsConfig.from(config),
        )
    }
}
