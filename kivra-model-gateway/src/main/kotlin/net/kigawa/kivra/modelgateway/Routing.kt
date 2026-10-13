package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Hard-filter reasons, declared in evaluation order. The deepest reason reached by any candidate
 * decides the error kind when no candidate remains.
 */
enum class ExclusionReason {
    SECRET_DATA, TRUST_ZONE_DENIED, DATA_HANDLING_UNCONFIRMED, LOCALITY,
    CAPABILITY_MISSING, CONTEXT_TOO_SMALL, PROVIDER_NOT_ALLOWED, LIFECYCLE,
    /** The cost cannot be established (unpriced model, currency/unit mismatch, or unpriced usage recorded). */
    UNKNOWN_COST,
    BUDGET_POLICY_MISSING, BUDGET, UNHEALTHY, QUALITY_BELOW_MIN,
}

data class CandidateTrace(val model: ModelRef, val exclusion: ExclusionReason?, val score: Double? = null) {
    val passed: Boolean get() = exclusion == null
}

data class RoutingDecision(
    val decisionId: String,
    val requestId: String,
    val registryVersion: String,
    val candidates: List<CandidateTrace>,
    /** Primary first, then fallbacks. Empty means the request is rejected. */
    val selected: List<ModelRef>,
    val softBudgetExceeded: Boolean,
    val rejection: GatewayError?,
)

/**
 * Constrained routing (design 05 section 6). Sensitivity, capability, context, allow-list, lifecycle,
 * budget and health are hard filters; scoring only orders survivors. The engine never relaxes a
 * constraint to find a candidate; zero candidates means rejection.
 */
class RoutingEngine(
    private val registry: ModelRegistry,
    private val budget: BudgetController,
    private val health: HealthTracker,
    /** Provider ids that have a usable adapter wired in. Others are excluded as disabled. */
    private val availableProviders: Set<String>,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = 3,
) {
    fun route(req: ModelRequest, streaming: Boolean = false): RoutingDecision {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val required = requiredCapabilities(req, streaming)
        val inTokens = Estimator.inputTokens(req)
        val softExceeded = budget.softExceeded(req.budgetScopes)

        val traces = ArrayList<CandidateTrace>()
        val survivors = ArrayList<Pair<ModelEntry, BigDecimal>>()
        for (m in registry.models) {
            val provider = registry.providers.getValue(m.providerId)
            val reason = exclusion(req, m, provider, required, inTokens, today)
            if (reason == null) survivors.add(m to (Estimator.maxCost(req, m) ?: BigDecimal.ZERO)) else traces.add(CandidateTrace(m.ref, reason))
        }

        val maxCost = survivors.maxOfOrNull { it.second } ?: BigDecimal.ZERO
        val pref = if (softExceeded) RoutingPreference(quality = 0.0, cost = 1.0) else req.preference
        val scored = survivors.map { (m, cost) ->
            val q = (m.qualityTier?.let { it.ordinal + 1 } ?: 0).toDouble() / QualityTier.entries.size
            val c = if (maxCost.signum() == 0) 0.0 else cost.divide(maxCost, 6, java.math.RoundingMode.HALF_UP).toDouble()
            Triple(m, cost, pref.quality * q - pref.cost * c)
        }.sortedWith(
            compareByDescending<Triple<ModelEntry, BigDecimal, Double>> { it.third }
                .thenBy { it.second }.thenBy { it.first.id },
        )
        scored.forEach { traces.add(CandidateTrace(it.first.ref, null, it.third)) }

        val selected = ArrayList<ModelRef>()
        if (scored.isNotEmpty()) {
            val primaryZone = registry.providers.getValue(scored.first().first.providerId).trustZone
            for ((m, _, _) in scored) {
                if (selected.size >= maxAttempts) break
                // Fallback must never move to a more outward trust zone than the primary.
                if (registry.providers.getValue(m.providerId).trustZone.outwardness > primaryZone.outwardness) continue
                selected.add(m.ref)
            }
        }

        val rejection = if (selected.isEmpty()) rejectionFor(traces) else null
        return RoutingDecision(UUID.randomUUID().toString(), req.requestId, registry.registryVersion, traces, selected, softExceeded, rejection)
    }

    private fun requiredCapabilities(req: ModelRequest, streaming: Boolean): Set<Capability> {
        val c = req.requirements.requiredCapabilities.toMutableSet()
        c.add(Capability.TEXT)
        if (req.tools.isNotEmpty()) c.add(Capability.TOOL_CALLING)
        if (req.responseFormat is ResponseFormat.JsonSchema) c.add(Capability.STRUCTURED_OUTPUT_NATIVE)
        if (streaming) c.add(Capability.STREAMING)
        if (req.messages.any { m -> m.role == Role.SYSTEM }) c.add(Capability.SYSTEM_PROMPT)
        if (req.messages.any { m -> m.parts.any { it is Part.Image } }) c.add(Capability.VISION_INPUT)
        return c
    }

    private fun exclusion(
        req: ModelRequest, m: ModelEntry, p: ProviderEntry, required: Set<Capability>, inTokens: Long, today: LocalDate,
    ): ExclusionReason? {
        // 1. Sensitivity x trust zone / locality.
        when (req.dataClass) {
            DataClass.SECRET -> return ExclusionReason.SECRET_DATA
            DataClass.RESTRICTED -> if (p.trustZone != TrustZone.LOCAL) return ExclusionReason.TRUST_ZONE_DENIED
            DataClass.INTERNAL -> if (p.trustZone == TrustZone.EXTERNAL && p.dataHandling.status != DataHandlingStatus.CONFIRMED)
                return ExclusionReason.DATA_HANDLING_UNCONFIRMED
            DataClass.PUBLIC -> {}
        }
        if (req.requirements.locality == Locality.LOCAL_ONLY && p.trustZone != TrustZone.LOCAL) return ExclusionReason.LOCALITY
        // 2. Capability.
        if (!m.capabilities.containsAll(required)) return ExclusionReason.CAPABILITY_MISSING
        // 3. Context / output length.
        val need = inTokens + Estimator.outputTokens(req, m)
        if (need > m.contextWindowTokens || (req.requirements.minContextTokens ?: 0) > m.contextWindowTokens) return ExclusionReason.CONTEXT_TOO_SMALL
        // 4. Allow / deny lists, lifecycle, adapter wired.
        val allowed = req.requirements.allowedProviders
        if (p.id in req.requirements.deniedProviders || (allowed != null && p.id !in allowed)) return ExclusionReason.PROVIDER_NOT_ALLOWED
        if (!p.enabled || p.id !in availableProviders || m.lifecycle != Lifecycle.ACTIVE || (m.retireAt != null && !m.retireAt.isAfter(today)))
            return ExclusionReason.LIFECYCLE
        // 5. Budget. The cost must be known in the budget's currency and unit: an unknown cost is never treated as zero.
        val amount = Estimator.maxBudgetAmount(req, m, registry.registryVersion) ?: return ExclusionReason.UNKNOWN_COST
        budget.admission(req.budgetScopes, amount)?.let { denied ->
            return when (denied.code) {
                BudgetDenial.UNKNOWN_COST -> ExclusionReason.UNKNOWN_COST
                BudgetDenial.BUDGET_POLICY_MISSING -> ExclusionReason.BUDGET_POLICY_MISSING
                else -> ExclusionReason.BUDGET
            }
        }
        // 6. Health.
        if (!health.isAvailable(p.id)) return ExclusionReason.UNHEALTHY
        // 7. Quality floor.
        val minTier = req.requirements.minQualityTier
        if (minTier != null && (m.qualityTier == null || m.qualityTier < minTier)) return ExclusionReason.QUALITY_BELOW_MIN
        return null
    }

    private fun rejectionFor(traces: List<CandidateTrace>): GatewayError {
        // Any candidate that reached the cost check and could not be priced makes the cost unknown: reported as such.
        if (traces.any { it.exclusion == ExclusionReason.UNKNOWN_COST }) {
            return GatewayError(ErrorKind.POLICY_DENIED, "cost is unknown; provider not called", code = "UNKNOWN_COST")
        }
        val deepest = traces.mapNotNull { it.exclusion }.maxOrNull()
        return when (deepest) {
            ExclusionReason.BUDGET_POLICY_MISSING ->
                GatewayError(ErrorKind.POLICY_DENIED, "no budget policy for the request's budget scopes", code = "BUDGET_POLICY_MISSING")
            ExclusionReason.CAPABILITY_MISSING ->
                GatewayError(ErrorKind.CAPABILITY_UNSUPPORTED, "no model supports the required capabilities", code = "NO_ROUTE")
            ExclusionReason.BUDGET -> GatewayError(ErrorKind.POLICY_DENIED, "budget exceeded", code = "BUDGET")
            null -> GatewayError(ErrorKind.POLICY_DENIED, "no models registered", code = "NO_ROUTE")
            else -> GatewayError(ErrorKind.POLICY_DENIED, "no route satisfies the constraints ($deepest)", code = "NO_ROUTE")
        }
    }
}
