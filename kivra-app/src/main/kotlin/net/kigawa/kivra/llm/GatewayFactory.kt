package net.kigawa.kivra.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import net.kigawa.kivra.db.DbRequestLedger
import net.kigawa.kivra.modelgateway.AnthropicProvider
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.modelgateway.BoundCredentialSource
import net.kigawa.kivra.modelgateway.CostConverter
import net.kigawa.kivra.modelgateway.CredentialBinding
import net.kigawa.kivra.modelgateway.FxConfig
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.OpenAiCompatProvider
import org.jetbrains.exposed.sql.Database

/** Shared construction for a gateway backed by the production request ledger. */
object GatewayFactory {
    private const val PROVIDER_ID = "anthropic"
    private const val OPENAI_ADAPTER = "openai"
    private const val ENV_LOCAL_LLM_HOST = "KIVRA_SELFDEV_LOCAL_LLM_HOST"

    fun create(
        registry: ModelRegistry,
        anthropicClient: HttpClient,
        anthropicAuth: net.kigawa.kivra.modelgateway.AnthropicAuth,
        budget: BudgetPort,
        database: Database,
        fx: FxConfig,
        env: Map<String, String>,
    ): ModelGateway {
        val localProviders = registry.providers.values.filter { it.adapter == OPENAI_ADAPTER }
        check(localProviders.size <= 1) { "at most one openai-adapter provider is supported" }
        check(localProviders.none { it.id == PROVIDER_ID }) { "an openai-adapter provider must not use the id $PROVIDER_ID" }
        val localProviderMap: Map<String, ModelProvider> = if (localProviders.isEmpty()) emptyMap() else {
            val host = checkNotNull(env[ENV_LOCAL_LLM_HOST]?.trim()?.takeIf { it.isNotEmpty() }) {
                "$ENV_LOCAL_LLM_HOST is required when the registry has an openai-adapter provider"
            }
            val credentials = BoundCredentialSource(localProviders.map { CredentialBinding(it.id, host, it.credentialRef, null) }, env)
            val localClient = HttpClient(CIO) { AnthropicProvider.installDefaults(this) }
            localProviders.associate { it.id to OpenAiCompatProvider(localClient, credentials, it.id) }
        }
        return ModelGateway(
            registry = registry,
            providers = mapOf<String, ModelProvider>(PROVIDER_ID to AnthropicProvider(anthropicClient, anthropicAuth)) + localProviderMap,
            budget = budget,
            ledger = DbRequestLedger(database),
            devAllowInMemoryLedger = false,
            costConverter = CostConverter(fx),
        )
    }
}
