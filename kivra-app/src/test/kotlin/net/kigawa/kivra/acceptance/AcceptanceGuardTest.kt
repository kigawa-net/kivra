package net.kigawa.kivra.acceptance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard reports only names that Kivra's code can read (see [AcceptanceGuard]). The unrelated developer secrets below pass
 * on purpose: they are not read by Kivra, so failing on them would only make local runs fail.
 */
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
    fun `unrelated developer secrets pass because Kivra does not read them`() {
        val shell = mapOf(
            "BWS_ACCESS_TOKEN" to "v1", "CLOUDFLARE_API_TOKEN" to "v2", "OP_SERVICE_ACCOUNT_TOKEN" to "v3",
            "KUTIL_SSH_KEY" to "v4", "AWS_SECRET_ACCESS_KEY" to "v5", "CLAUDE_CODE_MESSAGING_TOKEN" to "v6",
            // GitHub credentials pass too: no src/main code reads them from the environment yet (see AcceptanceGuard KDoc).
            "GITHUB_TOKEN" to "v7", "GH_TOKEN" to "v8", "GITHUB_PAT_TOKEN" to "v9", "GITHUB_APP_PRIVATE_KEY" to "v10",
            // FUSHA_* belongs to developer tools; Kivra reads KIVRA_FUSHA_*.
            "FUSHA_TOKEN" to "v11",
        )
        assertEquals(emptyList(), AcceptanceGuard.leakedNames(shell + githubActions))
        AcceptanceGuard.require(shell)
    }

    @Test
    fun `test database settings are allowed`() {
        assertEquals(emptyList(), AcceptanceGuard.leakedNames(mapOf("KIVRA_TEST_DB" to "mariadb", "KIVRA_TEST_MARIADB_PASSWORD" to "y")))
    }

    @Test
    fun `production-scoped names are reported`() {
        val env = mapOf(
            "KIVRA_DB_PASSWORD" to "v1", "KIVRA_FOO" to "v2", "ANTHROPIC_API_KEY" to "v3", "OPENAI_API_KEY" to "v4",
            "EXKES_URL" to "v5", "RECL_ENDPOINT" to "v6", "AI_SCHEDULER_URL" to "v7",
        )
        assertEquals(
            listOf(
                "AI_SCHEDULER_URL", "ANTHROPIC_API_KEY", "EXKES_URL", "KIVRA_DB_PASSWORD", "KIVRA_FOO", "OPENAI_API_KEY",
                "RECL_ENDPOINT",
            ),
            AcceptanceGuard.leakedNames(env),
        )
    }

    @Test
    fun `the guard fails for a production name and does not print its value`() {
        val secret = "super-secret-value-123"
        val e = assertFailsWith<IllegalStateException> { AcceptanceGuard.require(githubActions + mapOf("KIVRA_DB_PASSWORD" to secret)) }
        assertTrue(e.message.orEmpty().contains("KIVRA_DB_PASSWORD"))
        assertFalse(e.message.orEmpty().contains(secret))
    }

    @Test
    fun `a barrier with no arrival times out instead of hanging`() {
        val e = assertFailsWith<IllegalStateException> { NamedBarriers(timeoutSeconds = 0).awaitReached("never-reached") }
        assertTrue(e.message.orEmpty().contains("never-reached"))
    }
}
