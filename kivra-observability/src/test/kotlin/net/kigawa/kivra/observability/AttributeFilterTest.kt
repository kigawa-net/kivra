package net.kigawa.kivra.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AttributeFilterTest {
    private val filter = AttributeFilter(AttributeFilter.SPAN_ATTRIBUTE_ALLOWLIST)

    @Test
    fun `keys outside the allowlist are dropped`() {
        val out = filter.filter(
            mapOf("runId" to "01HZRUN0000000000000000000", "prompt" to "hello", "userEmail" to "a@example.com"),
        )
        assertEquals(mapOf("runId" to "01HZRUN0000000000000000000"), out)
    }

    @Test
    fun `prompt, response and token keys are never allowed even when they are the only input`() {
        val out = filter.filter(
            mapOf("prompt" to "x", "response" to "y", "body" to "z", "token" to "t", "apiKey" to "k", "authorization" to "a"),
        )
        assertEquals(emptyMap(), out)
    }

    @Test
    fun `allowed key with a secret-looking value is dropped`() {
        val out = filter.filter(
            mapOf(
                "provider" to "openai",
                "taskId" to "sk-abcdefghijklmnop1234",
                "runId" to "Bearer abc.def.ghi",
                "model" to "gho_abcdefghij1234567890",
                "agentId" to "ghp_0123456789abcdef",
            ),
        )
        assertEquals(mapOf("provider" to "openai"), out)
    }

    @Test
    fun `allowed key with a long opaque blob, a multi-line body or an over-long value is dropped`() {
        val blob = "A".repeat(40) + "B".repeat(40)
        val out = filter.filter(
            mapOf(
                "taskId" to blob,
                "runId" to "line1\nline2 with the answer",
                "executionId" to "x".repeat(Redaction.MAX_VALUE_LENGTH + 1),
                "agentId" to "agent-1",
            ),
        )
        assertEquals(mapOf("agentId" to "agent-1"), out)
    }

    @Test
    fun `key=value style credentials inside an allowed value are dropped`() {
        val out = filter.filter(mapOf("errorKind" to "password=hunter2"))
        assertFalse(out.containsKey("errorKind"))
    }
}
