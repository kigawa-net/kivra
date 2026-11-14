package net.kigawa.kivra.modelgateway

import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Routing with an on-premises OpenAI-compatible (LOCAL) model that declares only TEXT, STREAMING and SYSTEM_PROMPT.
 * Such a model must never receive a request that needs tools or structured output, RESTRICTED data must go only to LOCAL,
 * and a LOCAL primary must never fall back to a more outward zone.
 */
class LocalRoutingTest {
    private val ext = provider("ext")
    private val local = provider("local", TrustZone.LOCAL)
    private val providers = listOf(ext, local)
    private val conservative = setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT)
    private val localModel = model("lm", "local", caps = conservative, price = Price(BigDecimal.ZERO, BigDecimal.ZERO))
    private val extModel = model("em", "ext", caps = setOf(Capability.TEXT, Capability.TOOL_CALLING, Capability.STRUCTURED_OUTPUT_NATIVE, Capability.STREAMING, Capability.SYSTEM_PROMPT))

    private fun engine(models: List<ModelEntry>) =
        RoutingEngine(registryOf(providers, models), budget(), AlwaysHealthy, providers.map { it.id }.toSet())

    private fun ids(d: RoutingDecision) = d.selected.map { it.modelId }
    private val tool = ToolSpec("t", "d", JsonObject(emptyMap()))

    @Test fun aToolRequestGoesLocalOnlyWhenTheLocalModelDeclaresToolCalling() {
        val toolLocal = model("tl", "local", caps = conservative + Capability.TOOL_CALLING, price = Price(BigDecimal.ZERO, BigDecimal.ZERO))
        val d = engine(listOf(toolLocal, extModel)).route(request(DataClass.INTERNAL, tools = listOf(tool)))
        assertEquals(listOf("tl"), ids(d))
    }

    @Test fun toolRequiredRequestIsNotRoutedToTheConservativeLocalModel() {
        val d = engine(listOf(localModel, extModel)).route(request(DataClass.INTERNAL, tools = listOf(tool)), streaming = true)
        assertEquals(listOf("em"), ids(d))
        assertEquals(ExclusionReason.CAPABILITY_MISSING, d.candidates.first { it.model.modelId == "lm" }.exclusion)
    }

    @Test fun restrictedToolRequestIsRejectedNotRelaxedToExternal() {
        val d = engine(listOf(localModel, extModel)).route(request(DataClass.RESTRICTED, tools = listOf(tool)))
        assertTrue(d.selected.isEmpty())
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, d.rejection!!.kind)
    }

    @Test fun structuredOutputRequestIsNotRoutedToTheLocalModel() {
        val req = request(DataClass.INTERNAL).copy(responseFormat = ResponseFormat.JsonSchema("s", JsonObject(emptyMap())))
        val d = engine(listOf(localModel, extModel)).route(req)
        assertEquals(listOf("em"), ids(d))
    }

    @Test fun restrictedDataGoesOnlyToTheLocalModel() {
        val d = engine(listOf(localModel, extModel)).route(request(DataClass.RESTRICTED))
        assertEquals(listOf("lm"), ids(d))
    }

    @Test fun localOnlyLocalityRoutesOnlyToLocal() {
        val req = request(DataClass.INTERNAL, reqs = Requirements(locality = Locality.LOCAL_ONLY))
        assertEquals(listOf("lm"), ids(engine(listOf(localModel, extModel)).route(req)))
    }

    @Test fun aLocalPrimaryNeverFallsBackToAnOuterZone() {
        // The free local model scores first; the external model would be a fallback into a more outward zone and is excluded.
        val d = engine(listOf(localModel, extModel)).route(request(DataClass.INTERNAL))
        assertEquals(listOf("lm"), ids(d))
    }

    @Test fun plainTextInternalRequestStillRoutesWhenLocalIsPresent() {
        val d = engine(listOf(extModel, localModel)).route(request(DataClass.INTERNAL, text = "hi"))
        assertTrue(d.selected.isNotEmpty())
        assertEquals(null, d.rejection)
    }

    // ---- the explicit opt-in: LOCAL primary may fall back to EXTERNAL (design 05 section 6.5 exception) ----------------------

    /** A request whose primary is the free local model: cost-only preference makes it score first, deterministically. */
    private fun optIn(dataClass: DataClass, locality: Locality = Locality.ANY, allow: Boolean = true) = request(dataClass).copy(
        requirements = Requirements(locality = locality, allowExternalFallbackFromLocal = allow),
        preference = RoutingPreference(quality = 0.0, cost = 1.0),
    )

    @Test fun theOptInLetsAnExternalProviderFollowALocalPrimary() {
        val d = engine(listOf(localModel, extModel)).route(optIn(DataClass.INTERNAL))
        assertEquals(listOf("lm", "em"), ids(d))
    }

    @Test fun withoutTheOptInTheLocalPrimaryHasNoExternalFallback() {
        val d = engine(listOf(localModel, extModel)).route(optIn(DataClass.INTERNAL, allow = false))
        assertEquals(listOf("lm"), ids(d))
    }

    @Test fun theOptInIsNeverAllowedForRestrictedData() {
        val d = engine(listOf(localModel, extModel)).route(optIn(DataClass.RESTRICTED))
        assertEquals(listOf("lm"), ids(d))
    }

    @Test fun theOptInDoesNotBypassLocalityOrTheDataHandlingCheck() {
        val onlyLocal = engine(listOf(localModel, extModel)).route(optIn(DataClass.INTERNAL, locality = Locality.LOCAL_ONLY))
        assertEquals(listOf("lm"), ids(onlyLocal))
        val unconfirmed = model("emu", "extu", caps = extModel.capabilities)
        val d = RoutingEngine(
            registryOf(listOf(local, provider("extu", confirmed = false)), listOf(localModel, unconfirmed)), budget(), AlwaysHealthy,
            setOf("local", "extu"),
        ).route(optIn(DataClass.INTERNAL))
        assertEquals(listOf("lm"), ids(d))
    }

    @Test fun thePublicDataMayUseTheOptInToo() {
        val d = engine(listOf(localModel, extModel)).route(optIn(DataClass.PUBLIC))
        assertEquals(listOf("lm", "em"), ids(d))
    }

    @Test fun aStructuredRequestGoesToALocalModelThatDeclaresStructuredOutput() {
        val structuredLocal = model("sl", "local", caps = conservative + Capability.STRUCTURED_OUTPUT_NATIVE, price = Price(BigDecimal.ZERO, BigDecimal.ZERO))
        val req = request(DataClass.INTERNAL).copy(responseFormat = ResponseFormat.JsonSchema("s", JsonObject(emptyMap())))
        assertEquals(listOf("sl"), ids(engine(listOf(structuredLocal, extModel)).route(req)))
    }

    @Test fun aToolRequestNeverGoesToLocalEvenWhenItDeclaresStructuredOutput() {
        val structuredLocal = model("sl", "local", caps = conservative + Capability.STRUCTURED_OUTPUT_NATIVE, price = Price(BigDecimal.ZERO, BigDecimal.ZERO))
        val d = engine(listOf(structuredLocal, extModel)).route(request(DataClass.INTERNAL, tools = listOf(tool)))
        assertEquals(listOf("em"), ids(d))
    }
}
