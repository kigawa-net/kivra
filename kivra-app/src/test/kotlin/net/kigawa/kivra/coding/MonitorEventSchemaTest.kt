package net.kigawa.kivra.coding

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The monitor's request schema (ADR 0012 section 1): accepted shapes, and every refusal the agent API depends on. */
class MonitorEventSchemaTest {

    private fun batch(vararg events: String, generation: String = "1") =
        """{"generation":$generation,"events":[${events.joinToString(",")}]}"""

    private fun accepts(text: String): MonitorBatch = assertNotNull(MonitorBatch.parseOrNull(text), text)

    private fun refuses(text: String) = assertNull(MonitorBatch.parseOrNull(text), text)

    @Test
    fun `every kind the agent may send is accepted with its own fields`() {
        val parsed = accepts(
            batch(
                """{"seq":1,"kind":"ATTEMPT_STARTED"}""",
                """{"seq":2,"kind":"CLI_INIT"}""",
                """{"seq":3,"kind":"PHASE","phase":"EDITING"}""",
                """{"seq":4,"kind":"TOOL_USE","toolName":"Read"}""",
                """{"seq":5,"kind":"TOOL_RESULT","count":2}""",
                """{"seq":6,"kind":"TURN","count":0}""",
                """{"seq":7,"kind":"PERMISSION_DENIED","count":1}""",
                """{"seq":8,"kind":"API_RETRY","code":"RATE_LIMIT"}""",
                """{"seq":9,"kind":"COST","costUsd":1.5}""",
                """{"seq":10,"kind":"STOP_REQUESTED"}""",
                """{"seq":11,"kind":"PROCESS_EXIT_CONFIRMED","code":"TIMEOUT"}""",
                """{"seq":12,"kind":"WARNING","count":10000}""",
                """{"seq":13,"kind":"DROPPED","count":3}""",
            ),
        )
        assertEquals((1..13).toList(), parsed.events.map { it.seq })
        assertEquals(1L, parsed.generation)
        assertEquals(MonitorEvent(9, MonitorKind.COST, costUsd = BigDecimal("1.500000")), parsed.events[8])
        assertEquals(MonitorEvent(4, MonitorKind.TOOL_USE, toolName = "Read"), parsed.events[3])
    }

    @Test
    fun `the batch is one run of consecutive seq values from any start`() {
        assertEquals(listOf(7, 8), accepts(batch("""{"seq":7,"kind":"TURN","count":1}""", """{"seq":8,"kind":"TURN","count":1}""")).events.map { it.seq })
        refuses(batch("""{"seq":1,"kind":"TURN","count":1}""", """{"seq":3,"kind":"TURN","count":1}"""))
        refuses(batch("""{"seq":2,"kind":"TURN","count":1}""", """{"seq":1,"kind":"TURN","count":1}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":1}""", """{"seq":1,"kind":"TURN","count":1}"""))
    }

    @Test
    fun `an unknown field is refused, even next to a valid one`() {
        refuses(batch("""{"seq":1,"kind":"TURN","count":1,"note":"x"}"""))
        refuses(batch("""{"seq":1,"kind":"ATTEMPT_STARTED","extra":1}"""))
        refuses(batch("""{"seq":1,"kind":"PHASE","phase":"EDITING","prompt":"p"}"""))
        refuses("""{"generation":1,"events":[],"extra":true}""")
        refuses("""{"generation":1}""")
    }

    @Test
    fun `a field the kind does not take is refused, and a required field may not be missing`() {
        refuses(batch("""{"seq":1,"kind":"TURN"}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":1,"toolName":"Read"}"""))
        refuses(batch("""{"seq":1,"kind":"PHASE"}"""))
        refuses(batch("""{"seq":1,"kind":"COST"}"""))
        refuses(batch("""{"seq":1,"kind":"STOP_REQUESTED","count":0}"""))
    }

    @Test
    fun `a free string in a fixed field is refused`() {
        refuses(batch("""{"seq":1,"kind":"TOOL_USE","toolName":"Bash(rm -rf /)"}"""))
        refuses(batch("""{"seq":1,"kind":"TOOL_USE","toolName":"mcp__github__create_issue"}"""))
        refuses(batch("""{"seq":1,"kind":"PHASE","phase":"editing"}"""))
        refuses(batch("""{"seq":1,"kind":"API_RETRY","code":"Too many requests, see https://example"}"""))
        refuses(batch("""{"seq":1,"kind":"PROCESS_EXIT_CONFIRMED","code":"ERROR_WITH_SECRET"}"""))
        // Each kind takes its own list: an API error kind is not a container outcome.
        refuses(batch("""{"seq":1,"kind":"PROCESS_EXIT_CONFIRMED","code":"RATE_LIMIT"}"""))
        refuses(batch("""{"seq":1,"kind":"API_RETRY","code":"EXITED"}"""))
    }

    @Test
    fun `a value of the wrong JSON type is refused`() {
        refuses(batch("""{"seq":"1","kind":"TURN","count":1}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":"1"}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":1.0}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":1e2}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":true}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":null}"""))
        refuses(batch("""{"seq":1,"kind":"PHASE","phase":5}"""))
        refuses(batch("""{"seq":1,"kind":"COST","costUsd":"1.5"}"""))
        refuses(batch("""{"seq":1.5,"kind":"TURN","count":1}"""))
        refuses("""{"generation":"1","events":[{"seq":1,"kind":"TURN","count":1}]}""")
        refuses("""{"generation":1.0,"events":[{"seq":1,"kind":"TURN","count":1}]}""")
        refuses("""{"generation":0,"events":[{"seq":1,"kind":"TURN","count":1}]}""")
        refuses("""{"generation":1,"events":{"seq":1,"kind":"TURN","count":1}}""")
    }

    @Test
    fun `a value outside its range is refused`() {
        refuses(batch("""{"seq":1,"kind":"TURN","count":10001}"""))
        refuses(batch("""{"seq":1,"kind":"TURN","count":-1}"""))
        refuses(batch("""{"seq":0,"kind":"TURN","count":1}"""))
        refuses(batch("""{"seq":1,"kind":"COST","costUsd":100.000001}"""))
        refuses(batch("""{"seq":1,"kind":"COST","costUsd":101}"""))
        refuses(batch("""{"seq":1,"kind":"COST","costUsd":-0.5}"""))
        refuses(batch("""{"seq":1,"kind":"COST","costUsd":1.1234567}"""))
        accepts(batch("""{"seq":1,"kind":"COST","costUsd":100}"""))
        accepts(batch("""{"seq":1,"kind":"COST","costUsd":0}"""))
        accepts(batch("""{"seq":1,"kind":"TURN","count":10000}"""))
        refuses(batch("""{"seq":2147483648,"kind":"TURN","count":1}"""))
    }

    @Test
    fun `a kind the agent may not send is refused, and so is an unknown kind`() {
        refuses(batch("""{"seq":1,"kind":"TRUNCATED"}"""))
        refuses(batch("""{"seq":1,"kind":"CLAIMED"}"""))
        refuses(batch("""{"seq":1,"kind":"SELFHEAL_DIAGNOSIS"}"""))
        refuses(batch("""{"seq":1,"kind":"turn","count":1}"""))
    }

    @Test
    fun `a tool name outside the list is refused, and OTHER is the catch-all`() {
        for (name in listOf("Read", "Edit", "Write", "Bash", "Grep", "Glob", "Task", "TodoWrite", "OTHER")) {
            accepts(batch("""{"seq":1,"kind":"TOOL_USE","toolName":"$name"}"""))
        }
        refuses(batch("""{"seq":1,"kind":"TOOL_USE","toolName":"WebFetch"}"""))
    }

    @Test
    fun `the batch is refused above fifty events, and an event above 256 bytes is refused`() {
        val fifty = (1..50).map { """{"seq":$it,"kind":"TURN","count":0}""" }
        accepts(batch(*fifty.toTypedArray()))
        val fiftyOne = (1..51).map { """{"seq":$it,"kind":"TURN","count":0}""" }
        refuses(batch(*fiftyOne.toTypedArray()))
        refuses("""{"generation":1,"events":[]}""")
        // A valid event is far below 256 bytes (its fields are fixed codes and bounded numbers), so the byte check is a backstop. An
        // oversized value is refused by the field's own rule.
        assertEquals(256, MonitorBatch.MAX_EVENT_BYTES)
        refuses(batch("""{"seq":1,"kind":"TURN","count":${"9".repeat(300)}}"""))
    }

    @Test
    fun `text that is not JSON, or a JSON value of another shape, is refused without an exception`() {
        refuses("")
        refuses("not json")
        refuses("[]")
        refuses("""{"generation":1,"events":[1]}""")
        refuses("""{"generation":1,"events":[{"seq":1,"kind":"TURN","count":1}]""")
        refuses("""{"generation":1,"events":[{"seq":1,"kind":null}]}""")
    }

    @Test
    fun `the fixed lists match the agent's own codes`() {
        assertEquals(ProgressPhase.entries.map { it.name }.toSet(), MonitorEventSchema.PHASES)
        // ApiErrorKind and DockerRunOutcome live in kivra-agent, which kivra-app does not depend on. These are copies. A change to
        // either side must change this list with it (see docs/runbook-selfdev.md section 6.16).
        assertEquals(setOf("AUTHENTICATION", "RATE_LIMIT", "OVERLOADED", "BILLING", "OTHER"), MonitorEventSchema.API_ERROR_KINDS)
        assertEquals(setOf("EXITED", "TIMEOUT", "CANCELLED", "OUTPUT_LIMIT"), MonitorEventSchema.DOCKER_RUN_OUTCOMES)
    }

    @Test
    fun `the rate limiter allows five requests in a second per attempt and refuses the sixth`() {
        val now = java.time.Instant.parse("2026-10-18T00:00:00Z")
        var offsetMillis = 0L
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): java.time.Instant = now.plusMillis(offsetMillis)
        }
        val limiter = MonitorRateLimiter(clock)
        repeat(5) { assertTrue(limiter.allow("A")) }
        assertEquals(false, limiter.allow("A"))
        assertTrue(limiter.allow("B"), "another attempt has its own window")
        offsetMillis = 1_000
        assertTrue(limiter.allow("A"), "a new second opens a new window")
    }
}
