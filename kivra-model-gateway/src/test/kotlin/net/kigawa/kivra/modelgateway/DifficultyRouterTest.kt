package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The difficulty routing: the hard conditions, the one-word label, the fail-closed cases, apply, and the end-to-end route. */
class DifficultyRouterTest {
    private val local = provider("local", TrustZone.LOCAL)
    private val hosted = provider("anthropic")
    private val localModel = model(
        "lm", "local",
        caps = setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT, Capability.STRUCTURED_OUTPUT_NATIVE, Capability.TOOL_CALLING),
        price = Price(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO),
    )
    private val hostedModel = model("hm", "anthropic")
    private val registry = registryOf(listOf(local, hosted), listOf(localModel, hostedModel))
    private val tool = ToolSpec("t", "d", kotlinx.serialization.json.JsonObject(emptyMap()))

    /** The local fake answers the classifier with [label] (its first call), then the reply. */
    private fun localFake(label: () -> String, reply: String = "local-answer") = FakeProvider("local") { attempt ->
        if (attempt == 1) okResponse(ModelRef("local", "lm", "lm-name"), message = Message(Role.ASSISTANT, label()))
        else okResponse(ModelRef("local", "lm", "lm-name"), message = Message(Role.ASSISTANT, reply))
    }

    private fun gateway(localFake: FakeProvider, hostedFake: FakeProvider = FakeProvider("anthropic") {
        okResponse(ModelRef("anthropic", "hm", "hm-name"), message = Message(Role.ASSISTANT, "hosted-answer"))
    }) = ModelGateway(
        registry = registry,
        providers = mapOf("local" to localFake, "anthropic" to hostedFake),
        budget = budget(),
        ledger = InMemoryRequestLedger(),
    )

    private fun call(text: String = "fix the typo in the readme", dataClass: DataClass = DataClass.INTERNAL) =
        request(dataClass, text = text, reqs = Requirements(allowedProviders = setOf("anthropic")))

    private fun router(gw: ModelGateway, enabled: Boolean = true, localId: String? = "local") =
        DifficultyRouter(gw, registry, localId, "anthropic", enabled)

    @Test fun easyIsLocalFirstAndHardIsHosted() = runTest {
        val easy = localFake({ "EASY" })
        assertEquals(RouteTier.LOCAL_FIRST, router(gateway(easy)).decide("chat", "what time is it", call()).tier)
        assertEquals(1, easy.calls)
        val hard = localFake({ "HARD" })
        val d = router(gateway(hard)).decide("chat", "design the migration", call())
        assertEquals(TierDecision(RouteTier.HOSTED, "LABEL_HARD"), d)
    }

    @Test fun theLabelIsReadLeniently() = runTest {
        assertEquals("LABEL_EASY", router(gateway(localFake({ " easy. " }))).decide("chat", "a", call()).reason)
    }

    @Test fun anyOtherAnswerIsHostedAsUnparseable() = runTest {
        for (answer in listOf("maybe", "EASY, because it is short", "")) {
            assertEquals(TierDecision(RouteTier.HOSTED, "LABEL_UNPARSEABLE"), router(gateway(localFake({ answer }))).decide("chat", "a", call()), answer)
        }
    }

    @Test fun aClassifierTimeoutOrFailureIsHosted() = runTest {
        // A provider timeout after sending is reported by the gateway as OUTCOME_UNKNOWN (not retried): a failure, not a timeout reason.
        val timeout = FakeProvider("local") { attempt -> if (attempt == 1) fail(ErrorKind.TIMEOUT) else okResponse(ModelRef("local", "lm", "x")) }
        assertEquals(TierDecision(RouteTier.HOSTED, "CLASSIFIER_FAILED"), router(gateway(timeout)).decide("chat", "a", call()))
        val failed = FakeProvider("local") { attempt -> if (attempt == 1) fail(ErrorKind.INVALID_REQUEST) else okResponse(ModelRef("local", "lm", "x")) }
        assertEquals(TierDecision(RouteTier.HOSTED, "CLASSIFIER_FAILED"), router(gateway(failed)).decide("chat", "a", call()))
    }

    @Test fun theHardConditionsDecideWithoutAnyClassifierCall() = runTest {
        val fake = localFake({ "EASY" })
        val gw = gateway(fake)
        assertEquals("DISABLED", router(gw, enabled = false).decide("chat", "a", call()).reason)
        assertEquals("NO_LOCAL", router(gw, localId = null).decide("chat", "a", call()).reason)
        assertEquals("DATA_CLASS", router(gw).decide("chat", "a", call(dataClass = DataClass.RESTRICTED)).reason)
        assertEquals("DATA_CLASS", router(gw).decide("chat", "a", call(dataClass = DataClass.SECRET)).reason)
        assertEquals("PROVIDER_LIST", router(gw).decide("chat", "a", request(DataClass.INTERNAL, reqs = Requirements(allowedProviders = setOf("local")))).reason)
        assertEquals("NO_PROVIDER_LIST", router(gw).decide("chat", "a", request(DataClass.INTERNAL)).reason)
        assertEquals("NO_SUMMARY", router(gw).decide("chat", "   ", call()).reason)
        assertEquals(0, fake.calls, "no hard condition may call the classifier")
    }

    @Test fun aLocalModelWithoutTheRequiredCapabilityIsNeverClassified() = runTest {
        val textOnly = model("lm", "local", caps = setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT),
            price = Price(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO))
        val fake = localFake({ "EASY" })
        val gw = ModelGateway(
            registry = registryOf(listOf(local, hosted), listOf(textOnly, hostedModel)),
            providers = mapOf("local" to fake, "anthropic" to FakeProvider("anthropic") { okResponse(ModelRef("anthropic", "hm", "x")) }),
            budget = budget(), ledger = InMemoryRequestLedger(),
        )
        val r = DifficultyRouter(gw, registryOf(listOf(local, hosted), listOf(textOnly, hostedModel)), "local", "anthropic", true)
        assertEquals(TierDecision(RouteTier.HOSTED, "NO_LOCAL_CAPABILITY"), r.decide("selfdev.planner", "a", request(DataClass.INTERNAL, tools = listOf(tool), reqs = Requirements(allowedProviders = setOf("anthropic")))))
        assertEquals(0, fake.calls)
    }

    @Test fun applyAddsTheLocalProviderWithTheFallbackOptInAndCostOnlyPreference() {
        val gw = gateway(localFake({ "EASY" }))
        val base = call()
        val local = router(gw).apply(base, TierDecision(RouteTier.LOCAL_FIRST, "LABEL_EASY"))
        assertEquals(setOf("anthropic", "local"), local.requirements.allowedProviders)
        assertTrue(local.requirements.allowExternalFallbackFromLocal)
        assertEquals(RoutingPreference(quality = 0.0, cost = 1.0), local.preference)
        assertEquals(base, router(gw).apply(base, TierDecision(RouteTier.HOSTED, "LABEL_HARD")))
        // RESTRICTED never opts in, even if a decision said local-first.
        val restricted = router(gw).apply(call(dataClass = DataClass.RESTRICTED), TierDecision(RouteTier.LOCAL_FIRST, "LABEL_EASY"))
        assertEquals(false, restricted.requirements.allowExternalFallbackFromLocal)
    }

    @Test fun anEasyCallIsServedByTheLocalModelAndAHardOneByTheHostedModel() = runTest {
        val hostedFake = FakeProvider("anthropic") { okResponse(ModelRef("anthropic", "hm", "x"), message = Message(Role.ASSISTANT, "hosted-answer")) }
        val easy = localFake({ "EASY" })
        val gw = gateway(easy, hostedFake)
        val r = router(gw)
        val req = r.apply(call(), r.decide("chat", "what time", call()))
        val completed = assertIs<ModelEvent.Completed>(gw.stream(req).toList().last())
        assertEquals("local", completed.response.selected.providerId)
        assertEquals(0, hostedFake.calls)

        val hardFake = localFake({ "HARD" })
        val hostedFake2 = FakeProvider("anthropic") { okResponse(ModelRef("anthropic", "hm", "x"), message = Message(Role.ASSISTANT, "hosted-answer")) }
        val gw2 = gateway(hardFake, hostedFake2)
        val r2 = router(gw2)
        val req2 = r2.apply(call(), r2.decide("chat", "design it", call()))
        val done2 = assertIs<ModelEvent.Completed>(gw2.stream(req2).toList().last())
        assertEquals("anthropic", done2.response.selected.providerId)
        assertEquals(0, hardFake.calls - 1, "the HARD label is the only local call")
    }

    @Test fun theSettingsDefaultToOnAndOnlyFalseSwitchesOff() {
        val s = DifficultyRoutingSettings.fromEnv(emptyMap())
        assertEquals(DifficultyRoutingSettings(chatEnabled = true, selfDevEnabled = true), s)
        assertEquals(false, DifficultyRoutingSettings.fromEnv(mapOf(DifficultyRoutingSettings.ENV_SELFDEV to "false")).selfDevEnabled)
        assertEquals(true, DifficultyRoutingSettings.fromEnv(mapOf(DifficultyRoutingSettings.ENV_CHAT to "")).chatEnabled)
        assertFailsWith<IllegalStateException> { DifficultyRoutingSettings.fromEnv(mapOf(DifficultyRoutingSettings.ENV_CHAT to "yes")) }
    }

    @Test fun aCallPinnedToAHostedModelIdStillReachesTheOnPremisesModelWhenEasy() = runTest {
        val easy = localFake({ "EASY" })
        val gw = gateway(easy)
        val r = router(gw)
        val pinned = call().copy(requirements = Requirements(allowedProviders = setOf("anthropic"), allowedModelIds = setOf("hm")))
        val routed = r.apply(pinned, r.decide("selfdev.planner", "plan it", pinned))
        assertEquals(setOf("hm", "lm"), routed.requirements.allowedModelIds)
        val completed = assertIs<ModelEvent.Completed>(gw.stream(routed).toList().last())
        assertEquals("local", completed.response.selected.providerId)
    }
}
