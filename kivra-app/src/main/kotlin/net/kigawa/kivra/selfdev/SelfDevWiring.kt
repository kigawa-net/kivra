package net.kigawa.kivra.selfdev

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.DbRequestLedger
import net.kigawa.kivra.db.DevelopmentRunRepository
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
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.RegistryLoader
import net.kigawa.kivra.modelgateway.StaticKeyAuth
import net.kigawa.kivra.modelgateway.Requirements
import net.kigawa.kivra.routes.SelfDevRouteDeps
import org.jetbrains.exposed.sql.Database

/** The self-development components built by [buildSelfDev]. Built only when [SelfDevSettings.enabled] is true. */
internal class SelfDevParts(val worker: SelfDevWorker, val routes: SelfDevRouteDeps)

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
    val runner = SelfDevRunner(
        config = config,
        runs = DbDevelopmentRunStore(runs),
        leases = SqlLeaseStore(database),
        developer = developer,
        github = github,
        repoReader = reader,
        taskTexts = DbTaskTextSource,
    )
    val worker = SelfDevWorker(
        activeRunIds = { runs.activeSelfDevRunIds() },
        advance = { runId -> runner.advance(runId) },
        interval = settings.advanceInterval,
    )
    return SelfDevParts(
        worker = worker,
        routes = SelfDevRouteDeps(runs, identity, config.repo, settings.maxActiveRuns, github),
    )
}

/** Loads the model registry. The failure message carries no path and no file content. */
private fun loadRegistry(path: String): ModelRegistry = try {
    RegistryLoader.fromYaml(File(path).readText(Charsets.UTF_8))
} catch (_: Exception) {
    throw IllegalStateException("${SelfDevSettings.ENV_MODEL_REGISTRY_PATH} could not be loaded")
}
