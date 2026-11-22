package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretScanTest {
    private val token = "ghp_" + "a".repeat(36)

    @Test fun detectsKnownFormatsAndEntropy() {
        val scan = SecretScan()
        listOf(token, "sk-" + "b".repeat(40), "AKIA" + "A".repeat(16),
            "-----BEGIN RSA PRIVATE KEY-----", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature",
            "api_key=samplecredentialvalue", "Bearer abcdefghijklmnop1234",
            "aB3dE5fG7hI9jK1lM2nO4pQ6rS8tU0vW").forEach {
            assertTrue(scan.detects(request(text = it)), "expected detection of test fixture")
        }
        assertFalse(scan.detects(request(text = "Please review this Kotlin function and explain the result.")))
        assertFalse(scan.detects(request(text = "a".repeat(100))))
    }

    @Test fun detectsTextUsesTheSameRuleAsARequestTextPart() {
        val scan = SecretScan { listOf("", "rotated-value") }
        listOf(token, "aB3dE5fG7hI9jK1lM2nO4pQ6rS8tU0vW", "api_key=samplecredentialvalue", "rotated-value",
            "docs/adr/0004-budget-port-is-authoritative-budgetcheck-observation-only.md", "docs/runbook-selfdev.md",
            "Please review this Kotlin function and explain the result.").forEach {
            assertEquals(scan.detects(request(text = it)), scan.detectsText(it), "rules differ for a text")
        }
        assertTrue(scan.detectsText("docs/adr/0004-budget-port-is-authoritative-budgetcheck-observation-only.md"))
        assertFalse(scan.detectsText("docs/runbook-selfdev.md"))
    }

    @Test fun managedCredentialsAreReadOnEachScan() {
        var value = "short-value"
        val scan = SecretScan { listOf("", value) }
        assertTrue(scan.detects(request(text = "prefix short-value suffix")))
        value = "rotated-value"
        assertFalse(scan.detects(request(text = "short-value")))
        assertTrue(scan.detects(request(text = value)))
    }

    @Test fun scansToolPayloadsDefinitionsSchemasAndStops() {
        val scan = SecretScan()
        val json = buildJsonObject { put("value", token) }
        val parts = listOf(Part.ToolResult("call", token), Part.ToolCall("call", "tool", json))
        parts.forEach { assertTrue(scan.detects(request().copy(messages = listOf(Message(Role.TOOL, listOf(it)))))) }
        assertTrue(scan.detects(request(tools = listOf(ToolSpec("t", token, emptySchema)))))
        assertTrue(scan.detects(request(tools = listOf(ToolSpec("t", "description", json)))))
        assertTrue(scan.detects(request().copy(responseFormat = ResponseFormat.JsonSchema("s", json))))
        assertTrue(scan.detects(request().copy(generation = GenerationParams(stopSequences = listOf(token)))))
        assertFalse(scan.detects(request().copy(messages = listOf(Message(Role.USER,
            listOf(Part.Image("image/png", "aB3dE5fG7hI9jK1lM2nO4pQ6rS8tU0vW")))))))
    }

    @Test fun gatewayRejectsBeforeSendingAndRecordsOnlyMetadataForBothPaths() = runTest {
        for (streaming in listOf(false, true)) {
            val p = provider("p", TrustZone.LOCAL)
            val m = model("m", "p")
            val fake = FakeProvider("p") { okResponse(m.ref) }
            val audit = InMemoryAuditSink()
            val metrics = PrometheusGatewayMetrics()
            val g = ModelGateway(registryOf(listOf(p), listOf(m)), mapOf("p" to fake), budget(),
                audit = audit, metrics = metrics, ledger = InMemoryRequestLedger())
            val req = request(text = token, dataClass = DataClass.RESTRICTED)
            val e = assertFailsWith<GatewayException> {
                if (streaming) g.stream(req).toList() else g.invoke(req)
            }
            assertEquals(ErrorKind.POLICY_DENIED, e.error.kind)
            assertEquals("SECRET_DETECTED", e.error.code)
            assertEquals(0, fake.calls)
            assertEquals("SECRET_DETECTED", audit.records.single().errorCode)
            assertFalse(e.toString().contains(token))
            assertFalse(audit.records.toString().contains(token))
            assertTrue(metrics.renderPrometheus().contains("reason=\"SECRET_DETECTED\""))
            // A refusal did not claim the requestId.
            g.invoke(req.copy(messages = listOf(Message(Role.USER, "safe"))))
            assertEquals(1, fake.calls)
        }
    }
}
