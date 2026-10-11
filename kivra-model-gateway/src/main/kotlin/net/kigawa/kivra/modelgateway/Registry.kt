package net.kigawa.kivra.modelgateway

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

enum class DataHandlingStatus { CONFIRMED, UNKNOWN }

/** Provider terms. CONFIRMED requires who/when/source; UNKNOWN is treated as unusable for INTERNAL+ data. */
data class DataHandling(
    val status: DataHandlingStatus,
    val confirmedBy: String? = null,
    val confirmedAt: LocalDate? = null,
    val sourceUrl: String? = null,
)

data class ProviderEntry(
    val id: String,
    val adapter: String,
    val trustZone: TrustZone,
    val dataHandling: DataHandling,
    val enabled: Boolean,
    val endpoint: String,
    /** Name (env var / secret ref) of the credential. The value never lives in the registry. */
    val credentialRef: String,
)

enum class Lifecycle { PREVIEW, ACTIVE, DEPRECATED, RETIRED }

data class Price(
    val inputPerMtok: BigDecimal,
    val outputPerMtok: BigDecimal,
    val cachedInputPerMtok: BigDecimal? = null,
    val currency: String = "USD",
    val sourceRef: String? = null,
)

data class ModelEntry(
    val id: String,
    val providerId: String,
    /** Provider-side identifier. Always a configured value, never hard-coded in code. */
    val providerModelName: String,
    val lifecycle: Lifecycle,
    val retireAt: LocalDate?,
    val capabilities: Set<Capability>,
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val price: Price?,
    val qualityTier: QualityTier?,
) {
    val ref: ModelRef get() = ModelRef(providerId, id, providerModelName)
}

data class ResolvedTarget(val provider: ProviderEntry, val model: ModelEntry)

class RegistryException(message: String) : RuntimeException(message)

class ModelRegistry(
    val registryVersion: String,
    providers: List<ProviderEntry>,
    models: List<ModelEntry>,
) {
    val providers: Map<String, ProviderEntry> = providers.associateBy { it.id }
    val models: List<ModelEntry> = models

    init {
        if (providers.size != this.providers.size) throw RegistryException("duplicate provider id")
        if (models.map { it.id }.toSet().size != models.size) throw RegistryException("duplicate model id")
        for (m in models) {
            if (m.providerId !in this.providers) throw RegistryException("model ${m.id} references unknown provider ${m.providerId}")
        }
    }

    fun resolve(ref: ModelRef): ResolvedTarget {
        val m = models.firstOrNull { it.id == ref.modelId && it.providerId == ref.providerId }
            ?: throw RegistryException("unknown model $ref")
        return ResolvedTarget(providers.getValue(m.providerId), m)
    }
}

/** Loads the Git-managed YAML registry. Anything malformed or unspecified fails the load (fail closed). */
object RegistryLoader {
    fun fromYaml(text: String): ModelRegistry {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *>
            ?: throw RegistryException("registry root must be a mapping")
        val version = root["registryVersion"]?.toString() ?: throw RegistryException("registryVersion is required")
        val providers = list(root, "providers").map(::provider)
        val models = list(root, "models").map(::model)
        return ModelRegistry(version, providers, models)
    }

    private fun list(m: Map<*, *>, key: String): List<Map<*, *>> =
        (m[key] as? List<*>)?.map { it as? Map<*, *> ?: throw RegistryException("$key entries must be mappings") }
            ?: throw RegistryException("$key is required")

    private fun req(m: Map<*, *>, key: String, ctx: String): String =
        m[key]?.toString()?.takeIf { it.isNotBlank() } ?: throw RegistryException("$ctx: $key is required")

    private fun <E : Enum<E>> enumOf(cls: Class<E>, v: String, ctx: String): E =
        cls.enumConstants.firstOrNull { it.name == v } ?: throw RegistryException("$ctx: invalid value '$v'")

    // SnakeYAML turns bare ISO dates into java.util.Date; quoted ones stay strings.
    private fun date(v: Any?, ctx: String): LocalDate = when (v) {
        is java.util.Date -> v.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
        is String -> runCatching { LocalDate.parse(v) }.getOrNull()
        else -> null
    } ?: throw RegistryException("$ctx: invalid date value")

    private fun provider(m: Map<*, *>): ProviderEntry {
        val id = req(m, "id", "provider")
        val ctx = "provider $id"
        val dh = m["dataHandling"] as? Map<*, *> ?: throw RegistryException("$ctx: dataHandling is required")
        val status = enumOf(DataHandlingStatus::class.java, req(dh, "status", ctx), ctx)
        val handling = if (status == DataHandlingStatus.CONFIRMED) {
            DataHandling(
                status,
                req(dh, "confirmedBy", ctx),
                date(dh["confirmedAt"], ctx),
                req(dh, "sourceUrl", ctx),
            )
        } else DataHandling(status)
        return ProviderEntry(
            id = id,
            adapter = req(m, "adapter", ctx),
            trustZone = enumOf(TrustZone::class.java, req(m, "trustZone", ctx), ctx),
            dataHandling = handling,
            enabled = m["enabled"] as? Boolean ?: false,
            endpoint = req(m, "endpoint", ctx),
            credentialRef = req(m, "credentialRef", ctx),
        )
    }

    private fun model(m: Map<*, *>): ModelEntry {
        val id = req(m, "id", "model")
        val ctx = "model $id"
        val caps = (m["capabilities"] as? List<*> ?: throw RegistryException("$ctx: capabilities is required"))
            .map { enumOf(Capability::class.java, it.toString(), ctx) }.toSet()
        val price = (m["price"] as? Map<*, *>)?.let {
            Price(
                inputPerMtok = BigDecimal(req(it, "inputPerMtok", ctx)),
                outputPerMtok = BigDecimal(req(it, "outputPerMtok", ctx)),
                cachedInputPerMtok = it["cachedInputPerMtok"]?.toString()?.let(::BigDecimal),
                currency = it["currency"]?.toString() ?: "USD",
                sourceRef = it["sourceRef"]?.toString(),
            )
        }
        return ModelEntry(
            id = id,
            providerId = req(m, "providerId", ctx),
            providerModelName = req(m, "providerModelName", ctx),
            lifecycle = enumOf(Lifecycle::class.java, req(m, "lifecycle", ctx), ctx),
            retireAt = m["retireAt"]?.let { date(it, ctx) },
            capabilities = caps,
            contextWindowTokens = req(m, "contextWindowTokens", ctx).toInt(),
            maxOutputTokens = req(m, "maxOutputTokens", ctx).toInt(),
            price = price,
            qualityTier = m["qualityTier"]?.toString()?.let { enumOf(QualityTier::class.java, it, ctx) },
        )
    }
}
