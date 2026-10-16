package net.kigawa.kivra.selfdev

import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.GitHubPrMergeAuthority
import net.kigawa.kivra.modelgateway.BudgetLimit

private const val REGISTRY = """
registryVersion: v1
providers:
  - id: anthropic
    adapter: anthropic
    trustZone: EXTERNAL
    dataHandling: { status: UNKNOWN }
    enabled: true
    endpoint: https://api.anthropic.com
    credentialRef: ANTHROPIC_API_KEY
models:
  - id: m
    providerId: anthropic
    providerModelName: claude-test
    lifecycle: ACTIVE
    capabilities: [TEXT, STRUCTURED_OUTPUT_NATIVE]
    contextWindowTokens: 1000
    maxOutputTokens: 100
"""

/** Covers [buildSelfDev]: it is the place where the production merge Authority is installed (no network is used). */
class SelfDevWiringTest {
    @Test
    fun `building the self-development parts installs the GitHub merge Authority, and only once`() {
        TestDb().use { db ->
            val registry = Files.createTempFile("registry", ".yaml").toFile()
            try {
                registry.writeText(REGISTRY.trimIndent())
                val env = mapOf(
                    "KIVRA_SELFDEV_ENABLED" to "true",
                    "KIVRA_AUTH_MODE" to "oidc-jwt",
                    "KIVRA_SELFDEV_GITHUB_TOKEN" to "ghp_WIRING_TOKEN_NOT_USED",
                    "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "sk-ant-WIRING_KEY_NOT_USED",
                    "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "343288952",
                    "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to registry.absolutePath,
                    "KIVRA_FX_USD_JPY" to "150",
                    "KIVRA_FX_AS_OF" to "2026-10-01",
                )
                val settings = SelfDevSettings.fromEnv(env)
                val deferred = DeferredPrMergeAuthority()
                assertNull(deferred.installed)

                buildSelfDev(
                    settings,
                    env,
                    db.database,
                    db.runs,
                    DbBudgetStore(db.database, "tenant-1", BudgetLimit(hard = BigDecimal("100"), currency = "JPY")),
                    "tenant-1",
                    TaskApiIdentity { null },
                    deferred,
                )
                assertIs<GitHubPrMergeAuthority>(deferred.installed)
                assertFailsWith<IllegalStateException> { deferred.install(deferred.installed!!) }
            } finally {
                registry.delete()
            }
        }
    }
}
