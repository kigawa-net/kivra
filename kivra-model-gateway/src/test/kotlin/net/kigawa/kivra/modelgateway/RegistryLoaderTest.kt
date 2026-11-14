package net.kigawa.kivra.modelgateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RegistryLoaderTest {
    @Test fun loadsExampleRegistry() {
        val text = File("config/model-registry.example.yaml").readText()
        val r = RegistryLoader.fromYaml(text)
        val p = r.providers.getValue("anthropic")
        assertEquals(TrustZone.EXTERNAL, p.trustZone)
        assertEquals(DataHandlingStatus.UNKNOWN, p.dataHandling.status)
        val m = r.models.first { it.id == "claude-sonnet" }
        assertEquals("claude-sonnet-5-5", m.providerModelName)
        assertEquals(Lifecycle.ACTIVE, m.lifecycle)
        assertEquals(r.resolve(m.ref).provider.id, "anthropic")
        // The on-premises example: LOCAL, plain-HTTP endpoint placeholder, no credential, conservative capabilities.
        val local = r.providers.getValue("local-llm")
        assertEquals(TrustZone.LOCAL, local.trustZone)
        assertEquals("openai", local.adapter)
        assertEquals("none", local.credentialRef)
        val lm = r.models.first { it.id == "local-chat" }
        assertEquals("local-llm", lm.providerId)
        assertEquals(
            setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT, Capability.STRUCTURED_OUTPUT_NATIVE, Capability.TOOL_CALLING),
            lm.capabilities,
        )
        assertFalse(text.contains("sk-"), "registry must not contain credentials")
    }

    private val base = """
        registryVersion: v1
        providers:
          - id: p
            adapter: anthropic
            trustZone: EXTERNAL
            dataHandling: { status: UNKNOWN }
            enabled: true
            endpoint: https://x.test
            credentialRef: P_KEY
        models:
          - id: m
            providerId: p
            providerModelName: name
            lifecycle: ACTIVE
            capabilities: [TEXT]
            contextWindowTokens: 1000
            maxOutputTokens: 100
    """.trimIndent()

    @Test fun minimalRegistryLoadsWithoutPrice() {
        assertEquals(emptyList(), RegistryLoader.fromYaml(base).models.single().prices)
    }

    @Test fun confirmedDataHandlingNeedsConfirmerDateAndSource() {
        val bad = base.replace("{ status: UNKNOWN }", "{ status: CONFIRMED }")
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(bad) }
        val good = base.replace("{ status: UNKNOWN }", "{ status: CONFIRMED, confirmedBy: me, confirmedAt: 2026-10-01, sourceUrl: 'https://t.test' }")
        assertEquals(DataHandlingStatus.CONFIRMED, RegistryLoader.fromYaml(good).providers.getValue("p").dataHandling.status)
    }

    @Test fun malformedInputFailsClosed() {
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml("- not a mapping") }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(base.replace("trustZone: EXTERNAL", "trustZone: MOON")) }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(base.replace("providerId: p", "providerId: ghost")) }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(base.replace("credentialRef: P_KEY\n", "")) }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(base.replace("registryVersion: v1\n", "")) }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(base.replace("capabilities: [TEXT]", "capabilities: [TELEPORT]")) }
    }

    @Test fun unknownPlaceholderPriceLoadsAsUnpricedModel() {
        val placeholder = base + "\n    price: { inputPerMtok: UNKNOWN, outputPerMtok: UNKNOWN }"
        assertEquals(emptyList(), RegistryLoader.fromYaml(placeholder).models.single().prices)
        val scalar = base + "\n    price: UNKNOWN"
        assertEquals(emptyList(), RegistryLoader.fromYaml(scalar).models.single().prices)
    }

    @Test fun disabledByDefaultWhenFlagMissing() {
        assertFalse(RegistryLoader.fromYaml(base.replace(Regex("\\s*enabled: true"), "")).providers.getValue("p").enabled)
    }

    @Test fun nonHttpsOrUserinfoEndpointIsRejected() {
        for (bad in listOf("http://x.test", "https://u:p@x.test", "ftp://x.test", "x.test")) {
            assertFailsWith<RegistryException>(bad) { RegistryLoader.fromYaml(base.replace("endpoint: https://x.test", "endpoint: $bad")) }
        }
    }
}
