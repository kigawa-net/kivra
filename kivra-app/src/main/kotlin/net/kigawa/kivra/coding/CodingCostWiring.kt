package net.kigawa.kivra.coding

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import net.kigawa.kivra.db.CodingAttemptUsageRepository
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.modelgateway.FxConfig
import net.kigawa.kivra.modelgateway.CostConverter
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.RegistryLoader
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.Database

/**
 * Builds the CLI runtime's cost ledger from the environment. Called only when the agent API is enabled, so nothing here is read otherwise.
 *
 * - The FX settings are read with [FxConfig.fromEnv] (the same variables as the API path). Absent, the reserve cannot be converted and every
 *   claim is refused (fail closed).
 * - The price table is the model registry at `KIVRA_SELFDEV_MODEL_REGISTRY_PATH`, when it is set. Without it, every model is unpriced, so
 *   every reported cost is flagged for an operator (ADR 0003).
 *
 * Failures name the variable only, never a value or a path.
 */
fun buildCodingCostLedger(
    env: Map<String, String>,
    reserveUsd: BigDecimal,
    budget: DbBudgetStore,
    tenantScope: String,
    database: Database,
): DbCodingCostLedger {
    val converter = CostConverter(FxConfig.fromEnv(env))
    val registry = env[SelfDevSettings.ENV_MODEL_REGISTRY_PATH]?.trim()?.takeIf { it.isNotEmpty() }?.let { loadRegistry(it) }
    return DbCodingCostLedger(
        budget = budget,
        usage = CodingAttemptUsageRepository(database),
        converter = converter,
        reserveUsd = reserveUsd,
        tenantScope = tenantScope,
        modelPriced = pricedIn(registry),
    )
}

/** Whether [registry] has a price in force at [now] for a model named by its id or by its provider-side name. No registry: never priced. */
internal fun pricedIn(registry: ModelRegistry?): (String, Instant) -> Boolean = { model, now ->
    registry?.models?.any { (it.id == model || it.providerModelName == model) && it.priceAt(now) != null } == true
}

private fun loadRegistry(path: String): ModelRegistry = try {
    RegistryLoader.fromYaml(File(path).readText(Charsets.UTF_8))
} catch (_: Exception) {
    throw IllegalStateException("${SelfDevSettings.ENV_MODEL_REGISTRY_PATH} could not be loaded")
}
