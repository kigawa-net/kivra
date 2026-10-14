package net.kigawa.kivra.capability

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.config.KivraConfig
import net.kigawa.kivra.config.TaskPollerConfigLoader
import net.kigawa.kivra.memory.journal.InMemoryMemoryMutationJournal
import net.kigawa.kivra.memory.recl.ReclConfig
import net.kigawa.kivra.reaper.DenyAllReapApprovals
import net.kigawa.kivra.reaper.ReapApprovalRequest
import net.kigawa.kivra.reaper.ReaperConfig
import net.kigawa.kivra.routes.isDevPreviewEnabled
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the production defaults of every dangerous capability (Issue #71, M8-0).
 *
 * The shipped `application.conf` is resolved WITHOUT system environment variables, so these assertions check the
 * defaults that a production deployment gets when no `KIVRA_*` override is set. A developer's local environment cannot
 * change the result.
 */
class ProductionCapabilityDefaultsTest {

    private fun shippedConfig(): ApplicationConfig {
        val resolved = ConfigFactory.parseResources("application.conf")
            .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
        return HoconApplicationConfig(resolved)
    }

    @Test
    fun `fusha status write-back is off and not allowed in the shipped configuration`() {
        val mapping = TaskPollerConfigLoader.statusMappingFrom(shippedConfig())
        assertFalse(mapping.writeEnabled)
        assertFalse(mapping.writesAllowed())
    }

    @Test
    fun `metrics endpoint is off in the shipped configuration`() {
        assertFalse(KivraConfig.from(shippedConfig()).metrics.enabled)
    }

    @Test
    fun `reaper is detection-only by default and approves nothing`() {
        val config = ReaperConfig()
        assertTrue(config.dryRun)
        assertFalse(config.autoDeleteEnabled)
        assertSame(DenyAllReapApprovals, config.approvals)
        val request = ReapApprovalRequest(
            workspaceId = "ws-1",
            workspaceName = "kivra-run-1",
            runId = "run-1",
            action = "DELETE",
            digest = "sha256:0",
        )
        assertFalse(runBlocking { DenyAllReapApprovals.isApproved(request) })
    }

    @Test
    fun `recl mutations, redaction and idempotent save are off by default`() {
        val config = ReclConfig("https://recl.example.test")
        assertFalse(config.mutationsEnabled)
        assertFalse(config.redactSupported)
        assertFalse(config.idempotentSaveConfirmed)
    }

    @Test
    fun `dev demo routes are off without an explicit development environment`() {
        assertFalse(isDevPreviewEnabled(emptyMap()))
        assertFalse(isDevPreviewEnabled(mapOf("KIVRA_RUNTIME_ENV" to "server")))
        assertFailsWith<IllegalArgumentException> {
            isDevPreviewEnabled(mapOf("KIVRA_DEV_DEMO_ENABLED" to "true", "KIVRA_RUNTIME_ENV" to "server"))
        }
    }

    @Test
    fun `in-memory mutation journal refuses to start outside dev, test and local`() {
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = null) }
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "") }
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "server") }
        assertFailsWith<IllegalStateException> { InMemoryMemoryMutationJournal(environment = "production") }
        // Sanity check: the explicit dev opt-in is accepted, so the refusals above are meaningful.
        assertNotNull(InMemoryMemoryMutationJournal(environment = "dev"))
    }
}
