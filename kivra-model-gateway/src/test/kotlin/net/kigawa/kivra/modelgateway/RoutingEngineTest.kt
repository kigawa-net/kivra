package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingEngineTest {
    private val ext = provider("ext")
    private val extUnconfirmed = provider("extu", confirmed = false)
    private val intl = provider("intl", TrustZone.INTERNAL)
    private val local = provider("local", TrustZone.LOCAL)

    private fun engine(models: List<ModelEntry>, providers: List<ProviderEntry> = listOf(ext, extUnconfirmed, intl, local),
                       b: BudgetController = budget(), health: HealthTracker = AlwaysHealthy, maxAttempts: Int = 3) =
        RoutingEngine(registryOf(providers, models), b, health, providers.map { it.id }.toSet(), maxAttempts = maxAttempts)

    private fun ids(d: RoutingDecision) = d.selected.map { it.modelId }

    @Test fun secretDataIsNeverRouted() {
        val d = engine(listOf(model("m-ext", "ext"), model("m-local", "local"))).route(request(DataClass.SECRET))
        assertTrue(d.selected.isEmpty())
        assertEquals(ErrorKind.POLICY_DENIED, d.rejection!!.kind)
    }

    @Test fun restrictedGoesOnlyToLocal() {
        val d = engine(listOf(model("m-ext", "ext"), model("m-intl", "intl"), model("m-local", "local"))).route(request(DataClass.RESTRICTED))
        assertEquals(listOf("m-local"), ids(d))
        assertEquals(ExclusionReason.TRUST_ZONE_DENIED, d.candidates.first { it.model.modelId == "m-ext" }.exclusion)
    }

    @Test fun restrictedWithOnlyExternalIsRejectedNotRelaxed() {
        val d = engine(listOf(model("m-ext", "ext"))).route(request(DataClass.RESTRICTED))
        assertTrue(d.selected.isEmpty())
        assertEquals("NO_ROUTE", d.rejection!!.code)
    }

    @Test fun internalDataRequiresConfirmedDataHandlingForExternal() {
        val d = engine(listOf(model("m-extu", "extu"), model("m-ext", "ext"))).route(request(DataClass.INTERNAL))
        assertEquals(listOf("m-ext"), ids(d))
        assertEquals(ExclusionReason.DATA_HANDLING_UNCONFIRMED, d.candidates.first { it.model.modelId == "m-extu" }.exclusion)
    }

    @Test fun publicDataMayUseUnconfirmedProvider() {
        assertEquals(listOf("m-extu"), ids(engine(listOf(model("m-extu", "extu"))).route(request(DataClass.PUBLIC))))
    }

    @Test fun localOnlyRequirementExcludesNonLocal() {
        val d = engine(listOf(model("m-ext", "ext"), model("m-local", "local"))).route(request(reqs = Requirements(locality = Locality.LOCAL_ONLY)))
        assertEquals(listOf("m-local"), ids(d))
    }

    @Test fun missingCapabilityIsHardFilterAndReportedAsUnsupported() {
        val noTools = model("m1", "ext", caps = setOf(Capability.TEXT))
        val d = engine(listOf(noTools)).route(request(tools = listOf(ToolSpec("t", "d", emptySchema))))
        assertTrue(d.selected.isEmpty())
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, d.rejection!!.kind)
    }

    @Test fun capabilityIsNotTradedAgainstQuality() {
        val strong = model("strong", "ext", caps = setOf(Capability.TEXT), tier = QualityTier.S)
        val capable = model("capable", "ext", tier = QualityTier.C)
        val d = engine(listOf(strong, capable)).route(request(tools = listOf(ToolSpec("t", "d", emptySchema)), reqs = Requirements(preference())))
        assertEquals(listOf("capable"), ids(d))
    }

    private fun preference() = setOf(Capability.TOOL_CALLING)

    @Test fun contextTooSmallExcluded() {
        val d = engine(listOf(model("small", "ext", context = 500), model("big", "ext"))).route(request(maxOut = 400, text = "x".repeat(900)))
        assertEquals(listOf("big"), ids(d))
    }

    @Test fun deniedAndAllowedProvidersAreHonoured() {
        val models = listOf(model("a", "ext"), model("b", "intl"))
        assertEquals(listOf("b"), ids(engine(models).route(request(reqs = Requirements(deniedProviders = setOf("ext"))))))
        assertEquals(listOf("a"), ids(engine(models).route(request(reqs = Requirements(allowedProviders = setOf("ext"))))))
    }

    @Test fun lifecycleRetireAndDisabledAreExcluded() {
        val past = LocalDate.of(2020, 1, 1)
        val models = listOf(
            model("preview", "ext", lifecycle = Lifecycle.PREVIEW), model("retired", "ext", retireAt = past),
            model("deprecated", "ext", lifecycle = Lifecycle.DEPRECATED), model("ok", "ext"),
        )
        assertEquals(listOf("ok"), ids(engine(models).route(request())))
        val disabled = listOf(provider("ext", enabled = false))
        assertTrue(engine(listOf(model("ok", "ext")), disabled).route(request()).selected.isEmpty())
    }

    @Test fun providerWithoutAdapterIsExcluded() {
        val e = RoutingEngine(registryOf(listOf(ext), listOf(model("m", "ext"))), budget(), AlwaysHealthy, emptySet())
        assertTrue(e.route(request()).selected.isEmpty())
    }

    @Test fun modelWithoutPriceIsExcludedFailClosed() {
        val d = engine(listOf(model("nop", "ext", price = null))).route(request())
        assertEquals(ExclusionReason.PRICE_UNKNOWN, d.candidates.single().exclusion)
    }

    @Test fun budgetExceededRejectsAsBudgetPolicyDenial() {
        val d = engine(listOf(model("m", "ext")), b = budget(hard = "0.0001")).route(request())
        assertTrue(d.selected.isEmpty())
        assertEquals(ErrorKind.POLICY_DENIED, d.rejection!!.kind)
        assertEquals("BUDGET", d.rejection!!.code)
    }

    @Test fun cheaperModelThatFitsBudgetIsStillSelected() {
        val cheap = model("cheap", "ext", price = Price(BigDecimal("0.01"), BigDecimal("0.01")))
        val pricey = model("pricey", "ext", price = Price(BigDecimal("1000"), BigDecimal("1000")))
        val d = engine(listOf(pricey, cheap), b = budget(hard = "1")).route(request())
        assertEquals(listOf("cheap"), ids(d))
    }

    @Test fun unhealthyProviderIsExcluded() {
        val h = object : HealthTracker {
            override fun isAvailable(providerId: String) = providerId != "ext"
            override fun recordSuccess(providerId: String) {}
            override fun recordFailure(providerId: String, kind: ErrorKind) {}
        }
        assertEquals(listOf("b"), ids(engine(listOf(model("a", "ext"), model("b", "intl")), health = h).route(request())))
    }

    @Test fun minQualityTierFilters() {
        val d = engine(listOf(model("lo", "ext", tier = QualityTier.C), model("none", "ext", tier = null), model("hi", "ext", tier = QualityTier.A)))
            .route(request(reqs = Requirements(minQualityTier = QualityTier.B)))
        assertEquals(listOf("hi"), ids(d))
    }

    @Test fun scoringIsDeterministicAndPrefersQualityThenCostThenId() {
        val models = listOf(model("b", "ext"), model("a", "ext"), model("c", "ext", tier = QualityTier.S))
        val d = engine(models).route(request())
        assertEquals(ids(d), ids(engine(models.reversed()).route(request())))
        assertEquals(listOf("c", "a", "b"), ids(d))
    }

    @Test fun softBudgetExceededShiftsToCost() {
        val b = BudgetController(mapOf("task" to BudgetLimit(BigDecimal("100"), soft = BigDecimal("0.0001"))))
        b.reserve("k", listOf("task"), BigDecimal("0.001")); b.commit("k", null)
        val cheap = model("cheap", "ext", tier = QualityTier.C, price = Price(BigDecimal("0.1"), BigDecimal("0.1")))
        val strong = model("strong", "ext", tier = QualityTier.S)
        val d = engine(listOf(strong, cheap), b = b).route(request())
        assertTrue(d.softBudgetExceeded)
        assertEquals("cheap", ids(d).first())
    }

    // ---- fallback must never widen the trust zone ----

    @Test fun fallbackNeverMovesToMoreOutwardZone() {
        val models = listOf(model("loc", "local", tier = QualityTier.S), model("int", "intl", tier = QualityTier.A), model("ex", "ext", tier = QualityTier.A))
        val d = engine(models).route(request(DataClass.PUBLIC))
        assertEquals("loc", ids(d).first())
        assertEquals(listOf("loc"), ids(d), "external/internal candidates must not follow a LOCAL primary")
    }

    @Test fun fallbackFromInternalPrimaryExcludesExternal() {
        val models = listOf(model("int", "intl", tier = QualityTier.S), model("ex", "ext", tier = QualityTier.A), model("loc", "local", tier = QualityTier.B))
        val d = engine(models).route(request(DataClass.PUBLIC))
        assertEquals(listOf("int", "loc"), ids(d))
    }

    @Test fun fallbackMayMoveInwards() {
        val models = listOf(model("ex", "ext", tier = QualityTier.S), model("loc", "local", tier = QualityTier.B))
        assertEquals(listOf("ex", "loc"), ids(engine(models).route(request())))
    }

    @Test fun fallbackReappliesHardFiltersSoRestrictedNeverGetsExternalFallback() {
        val models = listOf(model("loc", "local"), model("ex", "ext", tier = QualityTier.S))
        assertEquals(listOf("loc"), ids(engine(models).route(request(DataClass.RESTRICTED))))
    }

    @Test fun maxAttemptsLimitsFallbackList() {
        val models = (1..5).map { model("m$it", "ext") }
        assertEquals(2, engine(models, maxAttempts = 2).route(request()).selected.size)
    }

    @Test fun decisionRecordsExclusionReasonsAndRegistryVersion() {
        val d = engine(listOf(model("x", "ext", lifecycle = Lifecycle.PREVIEW), model("y", "ext"))).route(request())
        assertEquals("test-v1", d.registryVersion)
        assertNotNull(d.candidates.first { it.model.modelId == "y" }.score)
        assertNull(d.rejection)
    }
}
