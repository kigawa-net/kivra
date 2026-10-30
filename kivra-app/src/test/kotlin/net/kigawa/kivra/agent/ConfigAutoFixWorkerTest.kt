package net.kigawa.kivra.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ConfigAutoFixWorker tests.
 */
class ConfigAutoFixWorkerTest {

    @Test
    fun `tick returns zero counts when no issues`() = runBlocking {
        // Given
        val scanner = NoOpConfigIssueScanner
        val config = ConfigAutoFixConfig()
        val worker = ConfigAutoFixWorker(scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(0, pass.issuesScanned)
        assertEquals(0, pass.fixesAttempted)
        assertEquals(0, pass.fixesSucceeded)
        assertEquals(0, pass.fixesFailed)
    }

    @Test
    fun `tick processes database config issues`() = runBlocking {
        // Given
        val scanner = FakeConfigIssueScanner(
            databaseIssues = listOf(
                ConfigIssue(
                    id = "db-1",
                    type = ConfigIssueType.DATABASE_CONFIG,
                    message = "Database connection timeout",
                    severity = IssueSeverity.ERROR,
                ),
            ),
            envVarIssues = emptyList(),
            dependencyIssues = emptyList(),
            networkIssues = emptyList(),
            fixSuccess = true,
        )
        val config = ConfigAutoFixConfig()
        val worker = ConfigAutoFixWorker(scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(1, pass.issuesScanned)
        assertEquals(1, pass.fixesAttempted)
        assertEquals(1, pass.fixesSucceeded)
        assertEquals(0, pass.fixesFailed)
    }

    @Test
    fun `tick processes missing env vars`() = runBlocking {
        // Given
        val scanner = FakeConfigIssueScanner(
            databaseIssues = emptyList(),
            envVarIssues = listOf(
                ConfigIssue(
                    id = "env-1",
                    type = ConfigIssueType.MISSING_ENV_VAR,
                    message = "Missing KIVRA_DB_URL",
                    severity = IssueSeverity.WARNING,
                ),
            ),
            dependencyIssues = emptyList(),
            networkIssues = emptyList(),
            fixSuccess = true,
        )
        val config = ConfigAutoFixConfig()
        val worker = ConfigAutoFixWorker(scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(1, pass.issuesScanned)
        assertEquals(1, pass.fixesAttempted)
        assertEquals(1, pass.fixesSucceeded)
        assertEquals(0, pass.fixesFailed)
    }

    @Test
    fun `tick respects maxIssuesPerTick limit`() = runBlocking {
        // Given
        val scanner = FakeConfigIssueScanner(
            databaseIssues = listOf(
                ConfigIssue(id = "1", type = ConfigIssueType.DATABASE_CONFIG, message = "Error 1"),
                ConfigIssue(id = "2", type = ConfigIssueType.DATABASE_CONFIG, message = "Error 2"),
                ConfigIssue(id = "3", type = ConfigIssueType.DATABASE_CONFIG, message = "Error 3"),
            ),
            envVarIssues = emptyList(),
            dependencyIssues = emptyList(),
            networkIssues = emptyList(),
            fixSuccess = true,
        )
        val config = ConfigAutoFixConfig(maxIssuesPerTick = 2)
        val worker = ConfigAutoFixWorker(scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(2, pass.issuesScanned)
        assertEquals(2, pass.fixesAttempted)
    }

    @Test
    fun `tick counts failed fixes when fix fails`() = runBlocking {
        // Given
        val scanner = FakeConfigIssueScanner(
            databaseIssues = listOf(
                ConfigIssue(id = "1", type = ConfigIssueType.DATABASE_CONFIG, message = "Error 1"),
            ),
            envVarIssues = emptyList(),
            dependencyIssues = emptyList(),
            networkIssues = emptyList(),
            fixSuccess = false,
        )
        val config = ConfigAutoFixConfig()
        val worker = ConfigAutoFixWorker(scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(1, pass.issuesScanned)
        assertEquals(1, pass.fixesAttempted)
        assertEquals(0, pass.fixesSucceeded)
        assertEquals(1, pass.fixesFailed)
    }

    @Test
    fun `tick skips disabled scans`() = runBlocking {
        // Given
        val scanner = FakeConfigIssueScanner(
            databaseIssues = listOf(
                ConfigIssue(id = "1", type = ConfigIssueType.DATABASE_CONFIG, message = "Error 1"),
            ),
            envVarIssues = emptyList(),
            dependencyIssues = emptyList(),
            networkIssues = emptyList(),
            fixSuccess = true,
        )
        val config = ConfigAutoFixConfig(
            scanDatabaseConfig = false,
            scanEnvVars = false,
            scanDependencies = false,
            scanNetwork = false,
        )
        val worker = ConfigAutoFixWorker(scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(0, pass.issuesScanned)
        assertEquals(0, pass.fixesAttempted)
    }
}

/** Fake implementation of ConfigIssueScanner for testing. */
class FakeConfigIssueScanner(
    private val databaseIssues: List<ConfigIssue> = emptyList(),
    private val envVarIssues: List<ConfigIssue> = emptyList(),
    private val dependencyIssues: List<ConfigIssue> = emptyList(),
    private val networkIssues: List<ConfigIssue> = emptyList(),
    private val fixSuccess: Boolean = true,
) : ConfigIssueScanner {
    override fun getDatabaseConfigIssues(): List<ConfigIssue> = databaseIssues
    override fun getMissingEnvVars(): List<ConfigIssue> = envVarIssues
    override fun getDependencyConflicts(): List<ConfigIssue> = dependencyIssues
    override fun getNetworkIssues(): List<ConfigIssue> = networkIssues
    override suspend fun applyFix(issue: ConfigIssue): Boolean = fixSuccess
}
