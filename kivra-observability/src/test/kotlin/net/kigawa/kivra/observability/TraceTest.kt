package net.kigawa.kivra.observability

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceTest {
    private val fixed = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)
    private val correlation = Correlation(
        traceId = TraceId.parse("4bf92f3577b34da6a3ce929d0e0e4736")!!,
        taskId = "fusha-123",
        runId = "run-1",
        executionId = "exec-1",
        agentId = "agent-1",
    )

    @Test
    fun `trace id is 32 lowercase hex and parse rejects malformed values`() {
        val generated = TraceId.generate().value
        assertTrue(Regex("^[0-9a-f]{32}$").matches(generated))
        assertNull(TraceId.parse("ABCDEF"))
        assertNull(TraceId.parse("4BF92F3577B34DA6A3CE929D0E0E4736"))
        assertNull(TraceId.parse("4bf92f3577b34da6a3ce929d0e0e473z"))
    }

    @Test
    fun `span carries correlation keys and drops disallowed attributes`() {
        val exported = mutableListOf<SpanRecord>()
        val tracer = Tracer(SpanExporter { exported += it }, clock = fixed)

        val result = tracer.withSpan("exec", correlation, mapOf("prompt" to "secret body", "provider" to "p1")) { 42 }

        assertEquals(42, result)
        val span = exported.single()
        assertEquals(SpanStatus.OK, span.status)
        assertEquals(
            mapOf(
                "traceId" to "4bf92f3577b34da6a3ce929d0e0e4736",
                "taskId" to "fusha-123",
                "runId" to "run-1",
                "executionId" to "exec-1",
                "agentId" to "agent-1",
                "provider" to "p1",
            ),
            span.attributes,
        )
    }

    @Test
    fun `failing block is recorded as ERROR and rethrown`() {
        val exported = mutableListOf<SpanRecord>()
        val tracer = Tracer(SpanExporter { exported += it }, clock = fixed)

        assertFailsWith<IllegalStateException> {
            tracer.withSpan("exec", correlation) { error("boom") }
        }
        assertEquals(SpanStatus.ERROR, exported.single().status)
    }

    @Test
    fun `exporter failure does not break the operation`() {
        val tracer = Tracer(SpanExporter { error("backend down") }, clock = fixed)
        assertEquals("ok", tracer.withSpan("exec", correlation) { "ok" })
    }
}
