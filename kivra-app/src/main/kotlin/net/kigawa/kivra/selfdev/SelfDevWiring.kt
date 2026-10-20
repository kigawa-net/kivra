package net.kigawa.kivra.selfdev

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.time.Duration
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.DbRequestLedger
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SqlLeaseStore
import net.kigawa.kivra.developer.DeveloperConfig
import net.kigawa.kivra.developer.LlmDeveloper
import net.kigawa.kivra.github.GitHubAdapterConfig
import net.kigawa.kivra.github.GitHubPrMergeAuthority
import net.kigawa.kivra.github.GitHubAppTokenMinter
import net.kigawa.kivra.github.HttpGitHubAdapter
import net.kigawa.kivra.github.InstallationTokenProvider
import net.kigawa.kivra.github.RsaJwtSigner
import net.kigawa.kivra.github.StaticTokenProvider
import net.kigawa.kivra.github.TokenProvider
import net.kigawa.kivra.github.githubHttpClient
import net.kigawa.kivra.modelgateway.AnthropicAuth
import net.kigawa.kivra.modelgateway.AnthropicProvider
import net.kigawa.kivra.modelgateway.AnthropicWifAuth
import net.kigawa.kivra.modelgateway.AnthropicWifConfig
import net.kigawa.kivra.modelgateway.BoundCredentialSource
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.CostConverter
import net.kigawa.kivra.modelgateway.CredentialBinding
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.RegistryLoader
import net.kigawa.kivra.modelgateway.StaticKeyAuth
import net.kigawa.kivra.modelgateway.Requirements
import net.kigawa.kivra.routes.SelfDevRouteDeps
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.jetbrains.exposed.sql.Database

/** The self-development components built by [buildSelfDev]. Built only when [SelfDevSettings.enabled] is true. */
internal class SelfDevParts(
    val worker: SelfDevWorker,
    val routes: SelfDevRouteDeps,
    /** The autopilot Planner's loop. Non-null only when [AutopilotSettings.enabled] (ADR 0011). */
    val planWorker: PlannerWorker? = null,
)

/** The principal the developer's model calls are made under. Not a user identity. */
internal const val SELF_DEV_PRINCIPAL = "selfdev-runner"
private const val PROVIDER_ID = "anthropic"
private const val ANTHROPIC_HOST = "api.anthropic.com"
private const val ANTHROPIC_CREDENTIAL_REF = "ANTHROPIC_API_KEY"
private const val DEVELOPER_MAX_OUTPUT_TOKENS = 8_000

/**
 * Builds the self-development components (ADR 0008 part 7). Called only when [settings] is enabled: it is the only place that creates
 * the GitHub and model clients and that reads the secrets. [env] is the same environment map the settings were read from.
 *
 * It also installs the production merge Authority ([GitHubPrMergeAuthority], expecting the configured bot user id as the PR author)
 * into [prMergeAuthority]. This happens before the caller starts the worker, so no merged run is advanced without it.
 *
 * [coding] is the change-set source of the CLI runtime (ADR 0010). It is required when the runtime is `cli` (the runner refuses it otherwise)
 * and unused for `api`. The caller builds it from the agent API's coding service.
 *
 * The developer's data class is INTERNAL: repository content goes to the model, so the provider must have a CONFIRMED data-handling
 * status in the registry before the feature is enabled (see docs/provider-compatibility-matrix.md).
 */
internal fun buildSelfDev(
    settings: SelfDevSettings,
    env: Map<String, String>,
    database: Database,
    runs: DevelopmentRunRepository,
    budget: BudgetPort,
    tenantId: String,
    identity: TaskApiIdentity,
    prMergeAuthority: DeferredPrMergeAuthority,
    coding: CodingPatchSource? = null,
    autopilot: AutopilotSettings = AutopilotSettings.disabled(),
    /** The shadow record of the self-development memory (ADR 0016 P1). Used by the Planner only. */
    memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
): SelfDevParts {
    val config = checkNotNull(settings.config) { "self-development settings are not enabled" }
    val auth = checkNotNull(settings.githubAuth) { "self-development GitHub authentication is missing" }
    val fx = checkNotNull(settings.fx) { "fx settings are missing" }
    val botUserId = checkNotNull(settings.githubBotUserId) { "self-development bot user id is missing" }

    val githubConfig = GitHubAdapterConfig()
    val githubClient = githubHttpClient(CIO.create(), githubConfig)
    // The adapter asks for a token on every request; a GitHub App token is cached and refreshed inside the provider.
    val tokens: TokenProvider = when (auth) {
        is GitHubAuth.Pat -> StaticTokenProvider(auth.token.reveal())
        is GitHubAuth.App -> InstallationTokenProvider(
            appId = auth.appId,
            installationId = auth.installationId,
            signer = RsaJwtSigner(auth.privateKeyPem.reveal()),
            minter = GitHubAppTokenMinter(githubClient, githubConfig.apiBase),
        )
    }
    val github = HttpGitHubAdapter(githubClient, tokens, githubConfig)
    prMergeAuthority.install(GitHubPrMergeAuthority(github, botUserId.reveal().toLong()))
    val reader = GitHubRepoReader(github)

    val registry: ModelRegistry = loadRegistry(checkNotNull(settings.modelRegistryPath))
    val anthropicClient = HttpClient(CIO) { AnthropicProvider.installDefaults(this) }
    // Workload Identity Federation when configured; otherwise the static key, released only to the bound host.
    val anthropicAuth: AnthropicAuth = settings.anthropicWif?.let { wif ->
        AnthropicWifAuth(
            anthropicClient,
            AnthropicWifConfig(
                federationRuleId = wif.federationRuleId.reveal(),
                organizationId = wif.organizationId.reveal(),
                serviceAccountId = wif.serviceAccountId.reveal(),
                workspaceId = wif.workspaceId?.reveal(),
                identityTokenFile = File(wif.identityTokenFile.reveal()).toPath(),
            ),
        )
    } ?: StaticKeyAuth(
        BoundCredentialSource(
            listOf(CredentialBinding(PROVIDER_ID, ANTHROPIC_HOST, ANTHROPIC_CREDENTIAL_REF, checkNotNull(settings.anthropicApiKeyEnvVar))),
            env,
        ),
    )
    val gateway = ModelGateway(
        registry = registry,
        providers = mapOf(PROVIDER_ID to AnthropicProvider(anthropicClient, anthropicAuth)),
        budget = budget,
        ledger = DbRequestLedger(database),
        devAllowInMemoryLedger = false,
        costConverter = CostConverter(fx),
    )
    val developer = LlmDeveloper(
        gateway,
        DeveloperConfig(
            principal = SELF_DEV_PRINCIPAL,
            dataClass = DataClass.INTERNAL,
            budgetScopes = listOf(tenantId),
            requirements = Requirements(
                requiredCapabilities = setOf(Capability.TEXT, Capability.STRUCTURED_OUTPUT_NATIVE),
                allowedProviders = setOf(PROVIDER_ID),
            ),
            maxOutputTokens = DEVELOPER_MAX_OUTPUT_TOKENS,
        ),
    )
    // Off unless KIVRA_SELFDEV_PR_REBASE_ENABLED=true (issue #276). When off, the runner gets no rebaser and makes no extra call.
    val prRebaser = if (settings.prRebaseEnabled) {
        PrBranchRebaser(
            repo = config.repo,
            baseBranch = config.baseBranch,
            botUserId = botUserId.reveal().toLong(),
            port = github,
            github = github,
            runs = DbDevelopmentRunStore(runs),
        )
    } else {
        null
    }
    val runner = SelfDevRunner(
        config = config,
        runs = DbDevelopmentRunStore(runs),
        leases = SqlLeaseStore(database),
        developer = developer,
        github = github,
        repoReader = reader,
        taskTexts = DbTaskTextSource,
        coding = coding,
        references = TaskReferenceResolvers(listOf(GitHubIssueResolver(github, config.repo))),
        autoScope = PlanTaskAutoScopeGuard(SelfDevPlanRepository(database)),
        prRebaser = prRebaser,
    )
    val worker = SelfDevWorker(
        activeRunIds = { runs.activeSelfDevRunIds() },
        advance = { runId -> runner.advance(runId) },
        interval = settings.advanceInterval,
    )
    // Built only when the autopilot is enabled. Nothing about planning exists otherwise (ADR 0011, PR B).
    val planWorker = autopilot.config?.takeIf { autopilot.enabled }?.let { autopilotConfig ->
        // The Planner's file list reads the same GitHub adapter, on the configured base branch (Issue #293). Off when the switch is off.
        val repoFiles = if (autopilotConfig.plannerRepoTree) PlannerRepoFiles(github, config.repo, config.baseBranch) else null
        buildPlanWorker(autopilotConfig, registry, gateway, SelfDevGoalRepository(database), tenantId, repoFiles, memory)
    }
    return SelfDevParts(
        worker = worker,
        routes = SelfDevRouteDeps(runs, identity, config.repo, settings.maxActiveRuns, github),
        planWorker = planWorker,
    )
}

/**
 * The Planner, pinned to [PLANNER_MODEL_ID]. Startup fails when that model is not in the registry or not active, so the feature never runs
 * on an unlisted model. The budget is the tenant's (the same scope as the developer). The worker starts only if the caller starts it.
 */
private fun buildPlanWorker(
    config: AutopilotConfig,
    registry: ModelRegistry,
    gateway: ModelGateway,
    goals: SelfDevGoalRepository,
    tenantId: String,
    repoFiles: PlannerRepoFiles?,
    memory: SelfDevMemoryRecorder,
): PlannerWorker {
    check(registry.models.any { it.id == PLANNER_MODEL_ID && it.lifecycle == Lifecycle.ACTIVE }) {
        "the autopilot planner model is not an active model in the registry"
    }
    val planner = AutopilotPlanner(
        goals = goals,
        gateway = gateway,
        config = PlannerConfig(budgetScopes = listOf(tenantId), autopilot = config),
        repoFiles = repoFiles,
        memory = memory,
    )
    return PlannerWorker(goals, planner, Duration.ofSeconds(config.planIntervalSeconds))
}

/** Loads the model registry. The failure message carries no path and no file content. */
private fun loadRegistry(path: String): ModelRegistry = try {
    RegistryLoader.fromYaml(File(path).readText(Charsets.UTF_8))
} catch (_: Exception) {
    throw IllegalStateException("${SelfDevSettings.ENV_MODEL_REGISTRY_PATH} could not be loaded")
}
