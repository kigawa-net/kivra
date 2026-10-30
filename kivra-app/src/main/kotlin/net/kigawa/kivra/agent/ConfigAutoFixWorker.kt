package net.kigawa.kivra.agent

import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Result of one [ConfigAutoFixWorker] pass. Counts only. */
data class ConfigAutoFixPass(
    val issuesScanned: Int,
    val fixesAttempted: Int,
    val fixesSucceeded: Int,
    val fixesFailed: Int,
)

/**
 * Periodically scans for configuration and environment issues and attempts to fix them automatically.
 *
 * Fix actions:
 * 1. **Database connection issues**: When database configuration is invalid, attempts to fix the connection settings.
 * 2. **Missing environment variables**: When required environment variables are missing, generates suggestions.
 * 3. **Dependency version conflicts**: When dependency versions conflict, proposes version updates.
 * 4. **Network connectivity issues**: When external services are unreachable, attempts to restore connectivity.
 *
 * The worker logs configuration issues and their resolution status.
 * Logs carry counts and fixed codes only. Never a result, a path, a title or an exception message.
 */
class ConfigAutoFixWorker(
    private val configScanner: ConfigIssueScanner,
    private val config: ConfigAutoFixConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One pass. Never throws (except cancellation): a failing step is logged with a fixed code. */
    suspend fun tick(): ConfigAutoFixPass {
        return try {
            val pass = scanAndFix()
            if (pass.fixesAttempted > 0) {
                logger.info(
                    "config-auto-fix pass: issuesScanned={} fixesAttempted={} fixesSucceeded={} fixesFailed={}",
                    pass.issuesScanned, pass.fixesAttempted, pass.fixesSucceeded, pass.fixesFailed,
                )
            }
            pass
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("config-auto-fix failed: step=SCAN exception={}", e.javaClass.simpleName)
            ConfigAutoFixPass(0, 0, 0, 0)
        }
    }

    /** Scans for configuration issues and attempts to fix them. */
    private suspend fun scanAndFix(): ConfigAutoFixPass {
        var issuesScanned = 0
        var fixesAttempted = 0
        var fixesSucceeded = 0
        var fixesFailed = 0

        // Collect issues from scanner. Scanners block (sockets, files), so keep them off the caller's thread.
        val issues = withContext(Dispatchers.IO) {
            val found = mutableListOf<ConfigIssue>()
            if (config.scanDatabaseConfig) {
                found.addAll(configScanner.getDatabaseConfigIssues())
            }
            if (config.scanEnvVars) {
                found.addAll(configScanner.getMissingEnvVars())
            }
            if (config.scanDependencies) {
                found.addAll(configScanner.getDependencyConflicts())
            }
            if (config.scanNetwork) {
                found.addAll(configScanner.getNetworkIssues())
            }
            found
        }

        // Process up to maxIssuesPerTick
        val issuesToProcess = issues.take(config.maxIssuesPerTick)
        for (issue in issuesToProcess) {
            issuesScanned++

            // Attempt to fix the issue
            fixesAttempted++
            val fixed = attemptFix(issue)
            if (fixed) {
                fixesSucceeded++
            } else {
                fixesFailed++
            }
        }

        return ConfigAutoFixPass(issuesScanned, fixesAttempted, fixesSucceeded, fixesFailed)
    }

    /** Attempts to fix a configuration issue. */
    private suspend fun attemptFix(issue: ConfigIssue): Boolean = try {
            val fixed = configScanner.applyFix(issue)
            if (fixed) {
                logger.info("config-auto-fix: issue={}, type={}, fixed", issue.id, issue.type.name)
            } else {
                logger.warn("config-auto-fix: issue={}, type={}, fix failed", issue.id, issue.type.name)
            }
            fixed
        } catch (e: Exception) {
            logger.warn("config-auto-fix: issue={}, fix failed exception={}", issue.id, e.javaClass.simpleName)
            false
        }

    /** Starts the loop in [scope]: a pass, then one every [config.interval]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info(
            "config-auto-fix worker started: intervalSeconds={} maxIssues={}",
            config.interval.seconds, config.maxIssuesPerTick,
        )
        while (isActive) {
            tick()
            delay(config.interval.toMillis())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ConfigAutoFixWorker::class.java)
    }
}

/** Represents a configuration or environment issue that needs to be fixed. */
data class ConfigIssue(
    val id: String,
    val type: ConfigIssueType,
    val message: String,
    val severity: IssueSeverity = IssueSeverity.WARNING,
)

/** Types of configuration issues. */
enum class ConfigIssueType {
    DATABASE_CONFIG,
    MISSING_ENV_VAR,
    DEPENDENCY_CONFLICT,
    NETWORK_CONNECTIVITY,
}

/** Severity levels for configuration issues. */
enum class IssueSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}

/** Interface for scanning configuration issues. */
interface ConfigIssueScanner {
    /** Get database configuration issues. */
    fun getDatabaseConfigIssues(): List<ConfigIssue>

    /** Get missing environment variables. */
    fun getMissingEnvVars(): List<ConfigIssue>

    /** Get dependency version conflicts. */
    fun getDependencyConflicts(): List<ConfigIssue>

    /** Get network connectivity issues. */
    fun getNetworkIssues(): List<ConfigIssue>

    /** Apply a fix to a configuration issue. Returns true if the fix was successful. */
    suspend fun applyFix(issue: ConfigIssue): Boolean
}

/**
 * A no-op implementation of [ConfigIssueScanner] that returns empty lists.
 * Used when configuration scanning is not available or disabled.
 */
object NoOpConfigIssueScanner : ConfigIssueScanner {
    override fun getDatabaseConfigIssues(): List<ConfigIssue> = emptyList()
    override fun getMissingEnvVars(): List<ConfigIssue> = emptyList()
    override fun getDependencyConflicts(): List<ConfigIssue> = emptyList()
    override fun getNetworkIssues(): List<ConfigIssue> = emptyList()
    override suspend fun applyFix(issue: ConfigIssue): Boolean = false
}
