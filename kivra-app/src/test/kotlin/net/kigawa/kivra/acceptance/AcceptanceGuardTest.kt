package net.kigawa.kivra.acceptance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceGuardTest {
    private val githubActions = mapOf(
        "GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "kigawa-net/kivra", "GITHUB_SHA" to "0".repeat(40),
        "GITHUB_RUN_ID" to "1", "GITHUB_WORKFLOW" to "ci", "GITHUB_WORKSPACE" to "/tmp/x", "GITHUB_ENV" to "/tmp/env",
        "GITHUB_OUTPUT" to "/tmp/out", "GITHUB_STEP_SUMMARY" to "/tmp/sum", "GITHUB_EVENT_NAME" to "pull_request",
    )

    @Test
    fun `a GitHub Actions environment with only standard variables passes`() {
        assertEquals(emptyList(), AcceptanceGuard.leakedNames(githubActions + mapOf("PATH" to "/bin", "HOME" to "/h")))
        AcceptanceGuard.require(githubActions)
    }

    @Test
    fun `unknown GITHUB_ variables and test database settings are allowed`() {
        assertEquals(emptyList(), AcceptanceGuard.leakedNames(mapOf("GITHUB_NEW_THING" to "x", "KIVRA_TEST_DB" to "mariadb", "KIVRA_TEST_MARIADB_PASSWORD" to "y")))
    }

    @Test
    fun `secret-looking names are reported whatever their prefix`() {
        val env = mapOf("GITHUB_PAT_TOKEN" to "v1", "GITHUB_TOKEN" to "v2", "ANTHROPIC_API_KEY" to "v3", "EXKES_TOKEN" to "v4",
            "KIVRA_DB_PASSWORD" to "v5", "OPENAI_API_KEY" to "v6", "GITHUB_ACTIONS" to "true")
        assertEquals(
            listOf("ANTHROPIC_API_KEY", "EXKES_TOKEN", "GITHUB_PAT_TOKEN", "GITHUB_TOKEN", "KIVRA_DB_PASSWORD", "OPENAI_API_KEY"),
            AcceptanceGuard.leakedNames(env),
        )
    }

    @Test
    fun `service-prefixed production settings are reported even when not secret-looking`() {
        assertEquals(listOf("EXKES_URL", "KIVRA_FOO"), AcceptanceGuard.leakedNames(mapOf("KIVRA_FOO" to "a", "EXKES_URL" to "b", "PATH" to "/bin")))
    }

    @Test
    fun `the guard fails for a secret and does not print its value`() {
        val secret = "super-secret-value-123"
        val e = assertFailsWith<IllegalStateException> { AcceptanceGuard.require(githubActions + mapOf("GITHUB_PAT_TOKEN" to secret)) }
        assertTrue(e.message.orEmpty().contains("GITHUB_PAT_TOKEN"))
        assertFalse(e.message.orEmpty().contains(secret))
    }

    @Test
    fun `a barrier with no arrival times out instead of hanging`() {
        val e = assertFailsWith<IllegalStateException> { NamedBarriers(timeoutSeconds = 0).awaitReached("never-reached") }
        assertTrue(e.message.orEmpty().contains("never-reached"))
    }
}
