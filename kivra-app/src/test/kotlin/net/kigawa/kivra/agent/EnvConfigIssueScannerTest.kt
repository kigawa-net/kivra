package net.kigawa.kivra.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.config.DatabaseConfig

class EnvConfigIssueScannerTest {

    private fun db(
        url: String = "jdbc:mariadb://db.internal:3306/kivra",
        user: String = "kivra",
        password: String = "s3cret-pass",
    ) = DatabaseConfig(url, user, password)

    private val allPresent = DriverLoader { true }
    private val allReachable = EndpointProbe { _, _, _ -> true }

    private fun scanner(
        env: Map<String, String> = emptyMap(),
        database: DatabaseConfig? = db(),
        required: List<String> = emptyList(),
        endpoints: List<String> = emptyList(),
        probe: EndpointProbe = allReachable,
        drivers: DriverLoader = allPresent,
    ) = EnvConfigIssueScanner(env, database, required, endpoints, probe, drivers)

    @Test
    fun `healthy configuration has no issues`() {
        val s = scanner()
        assertTrue(s.getDatabaseConfigIssues().isEmpty())
        assertTrue(s.getMissingEnvVars().isEmpty())
        assertTrue(s.getDependencyConflicts().isEmpty())
        assertTrue(s.getNetworkIssues().isEmpty())
    }

    @Test
    fun `detects placeholder password and blank user without leaking values`() {
        val s = scanner(database = db(user = " ", password = "change-this-in-production"))
        val ids = s.getDatabaseConfigIssues().map { it.id }
        assertTrue(EnvConfigIssueScanner.ID_DB_USER in ids)
        assertTrue(EnvConfigIssueScanner.ID_DB_PASSWORD in ids)
        s.getDatabaseConfigIssues().forEach { assertFalse(it.message.contains("change-this-in-production")) }
    }

    @Test
    fun `detects non jdbc url`() {
        val ids = scanner(database = db(url = "mariadb://x/y")).getDatabaseConfigIssues().map { it.id }
        assertTrue(EnvConfigIssueScanner.ID_DB_URL in ids)
    }

    @Test
    fun `detects missing and blank required env vars`() {
        val s = scanner(env = mapOf("A" to "x", "B" to "  "), required = listOf("A", "B", "C"))
        assertEquals(
            listOf("${EnvConfigIssueScanner.ID_ENV_PREFIX}B", "${EnvConfigIssueScanner.ID_ENV_PREFIX}C"),
            s.getMissingEnvVars().map { it.id },
        )
    }

    @Test
    fun `detects missing jdbc driver and unknown scheme`() {
        val missing = scanner(drivers = { false }).getDependencyConflicts()
        assertEquals(listOf("${EnvConfigIssueScanner.ID_DRIVER_MISSING_PREFIX}mariadb"), missing.map { it.id })
        val unknown = scanner(database = db(url = "jdbc:foo://h/db")).getDependencyConflicts()
        assertEquals(listOf("${EnvConfigIssueScanner.ID_DRIVER_UNKNOWN_PREFIX}foo"), unknown.map { it.id })
    }

    @Test
    fun `probes database and configured endpoints`() {
        val probed = mutableListOf<Pair<String, Int>>()
        val s = scanner(
            endpoints = listOf("https://api.example.com", "http://fusha:8081/x", "not a url"),
            probe = { h, p, _ -> probed += h to p; h != "fusha" },
        )
        val issues = s.getNetworkIssues()
        assertEquals(
            listOf("db.internal" to 3306, "api.example.com" to 443, "fusha" to 8081),
            probed,
        )
        assertEquals(listOf(EnvConfigIssueScanner.networkId("fusha", 8081)), issues.map { it.id })
    }

    @Test
    fun `embedded database has no network endpoint`() {
        assertNull(EnvConfigIssueScanner.parseJdbcEndpoint("jdbc:h2:mem:test"))
        assertEquals("localhost" to 3306, EnvConfigIssueScanner.parseJdbcEndpoint("jdbc:mariadb://localhost/kivra"))
    }

    @Test
    fun `applyFix is true only when the issue no longer reproduces`() = runBlocking {
        var up = false
        val s = scanner(endpoints = listOf("http://fusha:8081"), probe = { h, _, _ -> h != "fusha" || up })
        val issue = s.getNetworkIssues().single()
        assertFalse(s.applyFix(issue))
        up = true
        assertTrue(s.applyFix(issue))
    }
}
