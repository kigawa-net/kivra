package net.kigawa.kivra.agent

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import net.kigawa.kivra.config.DatabaseConfig

/** Checks whether a TCP endpoint accepts connections. */
fun interface EndpointProbe {
    fun isReachable(host: String, port: Int, timeoutMillis: Int): Boolean
}

/** Default [EndpointProbe]: a plain TCP connect with a timeout. */
object SocketEndpointProbe : EndpointProbe {
    override fun isReachable(host: String, port: Int, timeoutMillis: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMillis) }
        true
    } catch (_: Exception) {
        false
    }
}

/** Loads a class by name; used to check that a JDBC driver is on the classpath. */
fun interface DriverLoader {
    fun isPresent(className: String): Boolean
}

object ClasspathDriverLoader : DriverLoader {
    override fun isPresent(className: String): Boolean = try {
        Class.forName(className, false, ClasspathDriverLoader::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * Detects configuration and environment issues from the process environment, the database
 * configuration and a list of endpoints that must be reachable.
 *
 * It never mutates configuration: [applyFix] re-checks an issue and returns true only when it is
 * resolved (for example a network endpoint that recovered, or an environment variable that was set
 * in the meantime). Messages carry only names and host:port, never values, users or passwords.
 */
class EnvConfigIssueScanner(
    private val env: Map<String, String>,
    private val database: DatabaseConfig?,
    private val requiredEnvVars: List<String> = emptyList(),
    private val endpoints: List<String> = emptyList(),
    private val probe: EndpointProbe = SocketEndpointProbe,
    private val drivers: DriverLoader = ClasspathDriverLoader,
    private val probeTimeoutMillis: Int = 2_000,
) : ConfigIssueScanner {

    override fun getDatabaseConfigIssues(): List<ConfigIssue> {
        val db = database ?: return emptyList()
        val issues = mutableListOf<ConfigIssue>()
        if (!db.jdbcUrl.startsWith("jdbc:")) {
            issues += ConfigIssue(ID_DB_URL, ConfigIssueType.DATABASE_CONFIG, "JDBC URL is blank or does not start with jdbc:", IssueSeverity.CRITICAL)
        }
        if (db.user.isBlank()) {
            issues += ConfigIssue(ID_DB_USER, ConfigIssueType.DATABASE_CONFIG, "Database user is blank", IssueSeverity.ERROR)
        }
        if (db.password.isBlank() || db.password == DEFAULT_DB_PASSWORD) {
            issues += ConfigIssue(ID_DB_PASSWORD, ConfigIssueType.DATABASE_CONFIG, "Database password is blank or still the placeholder default", IssueSeverity.CRITICAL)
        }
        return issues
    }

    override fun getMissingEnvVars(): List<ConfigIssue> = requiredEnvVars
        .filter { env[it].isNullOrBlank() }
        .map { name ->
            ConfigIssue("$ID_ENV_PREFIX$name", ConfigIssueType.MISSING_ENV_VAR, "Required environment variable $name is not set", IssueSeverity.ERROR)
        }

    override fun getDependencyConflicts(): List<ConfigIssue> {
        val db = database ?: return emptyList()
        val scheme = jdbcScheme(db.jdbcUrl) ?: return emptyList()
        val driver = DRIVERS[scheme]
        val issues = mutableListOf<ConfigIssue>()
        if (driver == null) {
            issues += ConfigIssue("$ID_DRIVER_UNKNOWN_PREFIX$scheme", ConfigIssueType.DEPENDENCY_CONFLICT, "No known JDBC driver for scheme $scheme", IssueSeverity.WARNING)
        } else if (!drivers.isPresent(driver)) {
            issues += ConfigIssue("$ID_DRIVER_MISSING_PREFIX$scheme", ConfigIssueType.DEPENDENCY_CONFLICT, "JDBC driver for scheme $scheme is not on the classpath", IssueSeverity.CRITICAL)
        }
        return issues
    }

    override fun getNetworkIssues(): List<ConfigIssue> = allEndpoints().mapNotNull { (host, port) ->
        if (probe.isReachable(host, port, probeTimeoutMillis)) null
        else ConfigIssue(networkId(host, port), ConfigIssueType.NETWORK_CONNECTIVITY, "Endpoint $host:$port is unreachable", IssueSeverity.ERROR)
    }

    /** Re-checks [issue]; true when it no longer reproduces. Nothing is changed by this call. */
    override suspend fun applyFix(issue: ConfigIssue): Boolean {
        val current = when (issue.type) {
            ConfigIssueType.DATABASE_CONFIG -> getDatabaseConfigIssues()
            ConfigIssueType.MISSING_ENV_VAR -> getMissingEnvVars()
            ConfigIssueType.DEPENDENCY_CONFLICT -> getDependencyConflicts()
            ConfigIssueType.NETWORK_CONNECTIVITY -> getNetworkIssues()
        }
        return current.none { it.id == issue.id }
    }

    private fun allEndpoints(): List<Pair<String, Int>> {
        val result = linkedSetOf<Pair<String, Int>>()
        database?.let { parseJdbcEndpoint(it.jdbcUrl) }?.let { result += it }
        endpoints.mapNotNullTo(result) { parseUrlEndpoint(it) }
        return result.toList()
    }

    companion object {
        const val ID_DB_URL = "db-url-invalid"
        const val ID_DB_USER = "db-user-blank"
        const val ID_DB_PASSWORD = "db-password-insecure"
        const val ID_ENV_PREFIX = "env-missing-"
        const val ID_DRIVER_MISSING_PREFIX = "dep-driver-missing-"
        const val ID_DRIVER_UNKNOWN_PREFIX = "dep-driver-unknown-"
        const val DEFAULT_DB_PASSWORD = "change-this-in-production"

        private val DRIVERS = mapOf(
            "mariadb" to "org.mariadb.jdbc.Driver",
            "mysql" to "com.mysql.cj.jdbc.Driver",
            "h2" to "org.h2.Driver",
            "postgresql" to "org.postgresql.Driver",
        )
        private val JDBC_HOST = Regex("^jdbc:[a-z0-9]+://([^/:?]+)(?::(\\d+))?", RegexOption.IGNORE_CASE)
        private val DEFAULT_PORTS = mapOf("mariadb" to 3306, "mysql" to 3306, "postgresql" to 5432)

        fun networkId(host: String, port: Int) = "net-$host-$port"

        fun jdbcScheme(url: String): String? =
            Regex("^jdbc:([a-z0-9]+):", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()

        /** host/port of a network JDBC URL; null for embedded ones such as jdbc:h2:mem. */
        fun parseJdbcEndpoint(url: String): Pair<String, Int>? {
            val m = JDBC_HOST.find(url) ?: return null
            val port = m.groupValues[2].toIntOrNull() ?: DEFAULT_PORTS[jdbcScheme(url)] ?: return null
            return m.groupValues[1] to port
        }

        fun parseUrlEndpoint(raw: String): Pair<String, Int>? {
            val uri = try { URI(raw.trim()) } catch (_: Exception) { return null }
            val host = uri.host ?: return null
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", true) -> 443
                uri.scheme.equals("http", true) -> 80
                else -> return null
            }
            return host to port
        }
    }
}
