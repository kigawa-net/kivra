package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.automerge.AutoMergeShadowPort
import net.kigawa.kivra.selfdev.automerge.AutoMergeShadowStore
import net.kigawa.kivra.selfdev.automerge.ShadowGlobalSnapshot
import net.kigawa.kivra.selfdev.automerge.ShadowRunSnapshot
import net.kigawa.kivra.selfdev.automerge.buildAutoMergeShadowWatch

/** ADR 0014 S1: the shadow switch and the required check names, read with the autopilot (see [AutopilotSettings]). */
class AutoMergeShadowSettingsTest {

    private val cliSelfDev: SelfDevSettings by lazy { SelfDevSettings.fromEnv(selfDevEnv()) }

    private fun selfDevEnv(): Map<String, String> = mapOf(
        "KIVRA_SELFDEV_ENABLED" to "true",
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
        "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
        "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_RUNTIME" to "cli",
        "KIVRA_AGENT_API_ENABLED" to "true",
    )

    /** The autopilot switch and the CLI cost ledger, which the autopilot needs (ADR 0011 section 5.3). [pairs] are added. */
    private fun autopilotEnv(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(
        AutopilotSettings.ENV_ENABLED to "true",
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
    ) + pairs

    @Test
    fun `the shadow switch is not read while the autopilot is off`() {
        val settings = AutopilotSettings.fromEnv(
            mapOf(AutopilotSettings.ENV_ENABLED to "false", AutopilotSettings.ENV_AUTOMERGE_SHADOW to "maybe"),
            cliSelfDev,
        )
        assertFalse(settings.enabled)
        assertEquals(null, settings.config)
    }

    @Test
    fun `the shadow switch is off by default, on only for true, and off for false or blank`() {
        assertFalse(AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev).config!!.autoMergeShadow)
        assertTrue(AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to "true"), cliSelfDev).config!!.autoMergeShadow)
        for (value in listOf("false", " ", "")) {
            assertFalse(
                AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to value), cliSelfDev).config!!.autoMergeShadow,
                "value '$value'",
            )
        }
    }

    @Test
    fun `any other shadow switch value is a startup failure that names the variable only`() {
        for (value in listOf("yes", "TRUE", "1", "on")) {
            val e = assertFailsWith<IllegalStateException> {
                AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to value), cliSelfDev)
            }
            assertTrue(e.message!!.contains(AutopilotSettings.ENV_AUTOMERGE_SHADOW), e.message)
            assertFalse(e.message!!.contains(value.trim()) && value.isNotBlank(), e.message)
        }
    }

    @Test
    fun `the required checks default to test, build and boot`() {
        val config = AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to "true"), cliSelfDev).config!!
        assertEquals(setOf("test", "build", "boot"), config.autoMergeRequiredChecks)
        val blank = AutopilotSettings.fromEnv(
            autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to "true", AutopilotSettings.ENV_AUTOMERGE_REQUIRED_CHECKS to " "),
            cliSelfDev,
        ).config!!
        assertEquals(setOf("test", "build", "boot"), blank.autoMergeRequiredChecks)
    }

    @Test
    fun `the required checks are a comma separated list, with spaces allowed inside a name`() {
        val config = AutopilotSettings.fromEnv(
            autopilotEnv(
                AutopilotSettings.ENV_AUTOMERGE_SHADOW to "true",
                AutopilotSettings.ENV_AUTOMERGE_REQUIRED_CHECKS to " test, build ,deploy to dev ",
            ),
            cliSelfDev,
        ).config!!
        assertEquals(setOf("test", "build", "deploy to dev"), config.autoMergeRequiredChecks)
    }

    @Test
    fun `bad required check lists are a startup failure, and are not read while the switch is off`() {
        val bad = listOf(
            "test,test",
            ",test",
            "test,,build",
            "bad\$name",
            (1..11).joinToString(",") { "c$it" },
            "x".repeat(101),
        )
        for (list in bad) {
            assertFailsWith<IllegalStateException>("list '${list.take(20)}'") {
                AutopilotSettings.fromEnv(
                    autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to "true", AutopilotSettings.ENV_AUTOMERGE_REQUIRED_CHECKS to list),
                    cliSelfDev,
                )
            }
        }
        // With the switch off the list is not read at all.
        val off = AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_REQUIRED_CHECKS to "a,a"), cliSelfDev)
        assertFalse(off.config!!.autoMergeShadow)
    }

    @Test
    fun `the shadow watch is built only with the autopilot on and the switch on`() {
        val repo = RepoRef("kigawa-net", "kivra")
        fun build(autopilot: AutopilotSettings) = buildAutoMergeShadowWatch(autopilot, NoStore, NoPort, repo, "main", 1L)

        assertNull(build(AutopilotSettings.disabled()))
        assertNull(build(AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev)))
        assertNull(build(AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to "false"), cliSelfDev)))
        assertNotNull(build(AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_AUTOMERGE_SHADOW to "true"), cliSelfDev)))
    }

    /** Stubs for the builder only: the builder never calls them. */
    private object NoStore : AutoMergeShadowStore {
        override fun pullRequestRunIds(limit: Int): List<RunId> = emptyList()
        override fun runSnapshot(runId: RunId): ShadowRunSnapshot? = null
        override fun globalSnapshot(): ShadowGlobalSnapshot = ShadowGlobalSnapshot(selfDevPaused = false, breakerOpen = false)
        override fun recordJudgement(runId: RunId, verdict: String, payload: String) = Unit
        override fun recordPaused(runId: RunId) = Unit
    }

    private object NoPort : AutoMergeShadowPort {
        private val failure = GitHubResult.Failure(GitHubFailureKind.VALIDATION, "unused")
        override suspend fun pullRequest(repo: RepoRef, number: Int) = failure
        override suspend fun pullRequestFiles(repo: RepoRef, number: Int) = failure
        override suspend fun tree(repo: RepoRef, commitSha: String) = failure
        override suspend fun checkRuns(repo: RepoRef, sha: String) = failure
        override suspend fun branchHead(repo: RepoRef, branch: String) = failure
    }
}
