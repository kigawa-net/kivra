package net.kigawa.kivra.modelgateway

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * A token-tier boundary. The rate applies while the request's total input tokens (input + cached) are at most
 * [inputTokensUpTo]. Tiers are ordered by bound; the top-level rates of [Price] apply above the largest bound.
 */
data class PriceTier(
    val inputTokensUpTo: Long,
    val inputPerMtok: BigDecimal,
    val outputPerMtok: BigDecimal,
    val cachedInputPerMtok: BigDecimal? = null,
)

/** Per-million-token rates with the cached rate resolved (defaults to the input rate). */
data class Rate(val inputPerMtok: BigDecimal, val outputPerMtok: BigDecimal, val cachedInputPerMtok: BigDecimal)

/**
 * One price version of a model (design 13 B2). [effectiveFrom] is the first JST date on which this version applies;
 * null means it has always applied. A version with a later [effectiveFrom] is not used before that date.
 */
data class Price(
    val inputPerMtok: BigDecimal,
    val outputPerMtok: BigDecimal,
    val cachedInputPerMtok: BigDecimal? = null,
    val currency: String = "USD",
    val sourceRef: String? = null,
    val effectiveFrom: LocalDate? = null,
    val tiers: List<PriceTier> = emptyList(),
) {
    init {
        if (currency.isBlank()) throw RegistryException("price currency is required")
        val rates = listOf(inputPerMtok, outputPerMtok, cachedInputPerMtok) + tiers.flatMap {
            listOf(it.inputPerMtok, it.outputPerMtok, it.cachedInputPerMtok)
        }
        if (rates.any { it != null && it.signum() < 0 }) throw RegistryException("price rates must not be negative")
        for (i in 1 until tiers.size) {
            if (tiers[i].inputTokensUpTo <= tiers[i - 1].inputTokensUpTo) throw RegistryException("price tiers must be strictly ascending by inputTokensUpTo")
        }
        if (tiers.any { it.inputTokensUpTo <= 0 }) throw RegistryException("inputTokensUpTo must be positive")
    }

    /** Rate for a request whose total input tokens are known. Null [totalInputTokens] means the tier is unknown: the highest rate per component is used. */
    fun rateFor(totalInputTokens: Long?): Rate {
        if (totalInputTokens == null) {
            val all = listOf(topRate()) + tiers.map { tierRate(it) }
            // The input-side rate also covers cached reads, which may be priced above the input rate in theory.
            return Rate(
                inputPerMtok = all.maxOf { maxOf(it.inputPerMtok, it.cachedInputPerMtok) },
                outputPerMtok = all.maxOf { it.outputPerMtok },
                cachedInputPerMtok = all.maxOf { it.cachedInputPerMtok },
            )
        }
        val tier = tiers.firstOrNull { totalInputTokens <= it.inputTokensUpTo }
        return if (tier != null) tierRate(tier) else topRate()
    }

    /** True when every rate is explicitly zero (a free model). */
    fun isExplicitlyZero(): Boolean =
        (listOf(inputPerMtok, outputPerMtok, cachedInputPerMtok) + tiers.flatMap { listOf(it.inputPerMtok, it.outputPerMtok, it.cachedInputPerMtok) })
            .all { it == null || it.signum() == 0 }

    private fun topRate() = Rate(inputPerMtok, outputPerMtok, cachedInputPerMtok ?: inputPerMtok)
    private fun tierRate(t: PriceTier) = Rate(t.inputPerMtok, t.outputPerMtok, t.cachedInputPerMtok ?: t.inputPerMtok)
}

/** Token-count and price-version timing use the JST calendar, matching the monthly budget period (design 13 section 2). */
val PRICE_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

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
    /** Price versions. Empty means unpriced (fail closed). */
    val prices: List<Price>,
    val qualityTier: QualityTier?,
) {
    init {
        if (prices.count { it.effectiveFrom == null } > 1 || prices.mapNotNull { it.effectiveFrom }.let { it.size != it.toSet().size })
            throw RegistryException("model $id: two price versions share an effective date")
    }

    val ref: ModelRef get() = ModelRef(providerId, id, providerModelName)

    /**
     * The price version in force at [now]: the one with the latest [Price.effectiveFrom] not after today (JST). Null when
     * no version is in force, which the caller treats as an unknown cost.
     */
    fun priceAt(now: Instant): Price? {
        val today = now.atZone(PRICE_ZONE).toLocalDate()
        return prices.filter { it.effectiveFrom == null || !it.effectiveFrom.isAfter(today) }
            .maxByOrNull { it.effectiveFrom ?: LocalDate.MIN }
    }
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
        val endpoint = req(m, "endpoint", ctx)
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull()
        if (uri == null || !"https".equals(uri.scheme, ignoreCase = true) || uri.host == null || uri.userInfo != null) {
            throw RegistryException("$ctx: endpoint must be an https URL without userinfo")
        }
        return ProviderEntry(
            id = id,
            adapter = req(m, "adapter", ctx),
            trustZone = enumOf(TrustZone::class.java, req(m, "trustZone", ctx), ctx),
            dataHandling = handling,
            enabled = m["enabled"] as? Boolean ?: false,
            endpoint = endpoint,
            credentialRef = req(m, "credentialRef", ctx),
        )
    }

    private fun isUnknownPlaceholder(v: Any?): Boolean = v?.toString()?.trim().equals("UNKNOWN", ignoreCase = true)

    private fun dec(v: Any?, ctx: String): BigDecimal =
        runCatching { BigDecimal(v.toString()) }.getOrNull() ?: throw RegistryException("$ctx: invalid decimal value")

    /**
     * `price` is absent, the UNKNOWN placeholder (unpriced), a single version mapping, or a list of versions. A placeholder
     * inside a list is rejected because it would make the price history ambiguous.
     */
    private fun prices(m: Map<*, *>, ctx: String): List<Price> = when (val raw = m["price"]) {
        null -> emptyList()
        is String -> if (isUnknownPlaceholder(raw)) emptyList() else throw RegistryException("$ctx: price must be a mapping, a list, or UNKNOWN")
        is Map<*, *> -> listOfNotNull(price(raw, ctx))
        is List<*> -> raw.map {
            val p = it as? Map<*, *> ?: throw RegistryException("$ctx: price versions must be mappings")
            if (isUnknownPlaceholder(p["inputPerMtok"]) || isUnknownPlaceholder(p["outputPerMtok"])) {
                throw RegistryException("$ctx: UNKNOWN is not allowed in a price version list")
            }
            price(p, ctx)!!
        }
        else -> throw RegistryException("$ctx: price must be a mapping, a list, or UNKNOWN")
    }

    /** Null for an UNKNOWN placeholder (the version is unpriced). */
    private fun price(p: Map<*, *>, ctx: String): Price? {
        if (isUnknownPlaceholder(p["inputPerMtok"]) || isUnknownPlaceholder(p["outputPerMtok"])) return null
        val tiers = (p["tiers"] as? List<*>)?.map {
            val t = it as? Map<*, *> ?: throw RegistryException("$ctx: price tiers must be mappings")
            PriceTier(
                inputTokensUpTo = req(t, "inputTokensUpTo", ctx).toLongOrNull() ?: throw RegistryException("$ctx: inputTokensUpTo must be an integer"),
                inputPerMtok = dec(req(t, "inputPerMtok", ctx), ctx),
                outputPerMtok = dec(req(t, "outputPerMtok", ctx), ctx),
                cachedInputPerMtok = t["cachedInputPerMtok"]?.let { dec(it, ctx) },
            )
        } ?: emptyList()
        if (p["tiers"] != null && p["tiers"] !is List<*>) throw RegistryException("$ctx: tiers must be a list")
        return Price(
            inputPerMtok = dec(req(p, "inputPerMtok", ctx), ctx),
            outputPerMtok = dec(req(p, "outputPerMtok", ctx), ctx),
            cachedInputPerMtok = p["cachedInputPerMtok"]?.let { dec(it, ctx) },
            currency = p["currency"]?.toString() ?: "USD",
            sourceRef = p["sourceRef"]?.toString(),
            effectiveFrom = p["effectiveFrom"]?.let { date(it, ctx) },
            tiers = tiers,
        )
    }

    private fun model(m: Map<*, *>): ModelEntry {
        val id = req(m, "id", "model")
        val ctx = "model $id"
        val caps = (m["capabilities"] as? List<*> ?: throw RegistryException("$ctx: capabilities is required"))
            .map { enumOf(Capability::class.java, it.toString(), ctx) }.toSet()
        return ModelEntry(
            id = id,
            providerId = req(m, "providerId", ctx),
            providerModelName = req(m, "providerModelName", ctx),
            lifecycle = enumOf(Lifecycle::class.java, req(m, "lifecycle", ctx), ctx),
            retireAt = m["retireAt"]?.let { date(it, ctx) },
            capabilities = caps,
            contextWindowTokens = req(m, "contextWindowTokens", ctx).toInt(),
            maxOutputTokens = req(m, "maxOutputTokens", ctx).toInt(),
            prices = prices(m, ctx),
            qualityTier = m["qualityTier"]?.toString()?.let { enumOf(QualityTier::class.java, it, ctx) },
        )
    }
}
