package net.kigawa.kivra.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kigawa.kivra.auth.Permission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpProtocolTest {
    private val echoTool = McpTool(
        name = "echo",
        description = "test",
        inputSchema = objectSchema("text" to buildJsonObject { put("type", "string") }),
        scope = "kivra.runs.read",
        permission = Permission.READ,
    ) { _, args ->
        args.requireOnly("text")
        ToolOutcome(false, "ok", buildJsonObject { put("echo", args.requiredString("text")) }, "OK")
    }
    private val dispatcher = McpDispatcher(listOf(echoTool))
    private val reader = McpCaller("owner", setOf("kivra.runs.read"))
    private val none = McpCaller("owner", emptySet())

    private fun msg(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun run(text: String, caller: McpCaller = reader): McpDispatch = runBlocking { dispatcher.dispatch(msg(text), caller) }

    private fun errorCode(d: McpDispatch): Int = d.response!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `initialize returns the requested version when it is supported`() {
        for (v in listOf("2025-06-18", "2025-03-26")) {
            val r = run("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$v"}}""")
            assertEquals(v, r.response!!.jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `initialize answers the latest supported version for an unknown one`() {
        val r = run("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
        assertEquals("2025-06-18", r.response!!.jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize without protocolVersion is invalid params`() {
        assertEquals(-32602, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")))
    }

    @Test
    fun `initialize declares only the tools capability and never a null field`() {
        val result = run("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""").response!!
            .jsonObject["result"]!!.jsonObject
        assertEquals(setOf("tools"), result["capabilities"]!!.jsonObject.keys)
        assertFalse(result.toString().contains("null"))
    }

    @Test
    fun `ping returns an empty result with the same id`() {
        val r = run("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""").response!!.jsonObject
        assertEquals("abc", r["id"]!!.jsonPrimitive.content)
        assertEquals(0, r["result"]!!.jsonObject.size)
    }

    @Test
    fun `a notification gets no response`() {
        assertNull(run("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").response)
        assertNull(run("""{"jsonrpc":"2.0","id":7,"method":"notifications/cancelled"}""").response)
        assertNull(run("""{"jsonrpc":"2.0","method":"something/unknown"}""").response)
    }

    @Test
    fun `a client response is accepted and gets no response`() {
        assertNull(run("""{"jsonrpc":"2.0","id":3,"result":{}}""").response)
    }

    @Test
    fun `an unknown method is method not found`() {
        assertEquals(-32601, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")))
    }

    @Test
    fun `a wrong jsonrpc version or a missing method is an invalid request`() {
        assertEquals(-32600, errorCode(run("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")))
        assertEquals(-32600, errorCode(run("""{"jsonrpc":"2.0","id":1}""")))
        assertEquals(-32600, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":7}""")))
    }

    @Test
    fun `an id that is an object or null is an invalid request with a null id`() {
        val obj = run("""{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}""")
        assertEquals(-32600, errorCode(obj))
        assertEquals("null", obj.response!!.jsonObject["id"].toString())
        assertEquals(-32600, errorCode(run("""{"jsonrpc":"2.0","id":null,"method":"ping"}""")))
    }

    @Test
    fun `tools list shows only the tools the caller holds the scope for`() {
        val withScope = run("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", reader).response!!.jsonObject
        assertEquals(1, withScope["result"]!!.jsonObject["tools"]!!.jsonArray.size)
        val without = run("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", none).response!!.jsonObject
        assertEquals(0, without["result"]!!.jsonObject["tools"]!!.jsonArray.size)
    }

    @Test
    fun `tools call with an unknown tool is invalid params`() {
        assertEquals(-32602, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nope"}}""")))
    }

    @Test
    fun `tools call without the scope is reported as insufficient scope with the id`() {
        val d = run("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"echo","arguments":{"text":"x"}}}""", none)
        assertEquals("kivra.runs.read", d.requiredScope)
        assertEquals(-32003, errorCode(d))
        assertEquals("9", d.response!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("insufficient_scope", d.outcome)
    }

    @Test
    fun `tools call runs the handler and wraps the outcome in content and structuredContent`() {
        val d = run("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""")
        val result = d.response!!.jsonObject["result"]!!.jsonObject
        assertEquals("hi", result["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals("text", result["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an unknown argument, a wrong type or an out of range value is invalid params`() {
        assertEquals(-32602, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"other":"x"}}}""")))
        assertEquals(-32602, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":3}}}""")))
        assertEquals(-32602, errorCode(run("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":"x"}}""")))
    }

    @Test
    fun `an invalid argument message never echoes the value`() {
        val marker = "SECRET-VALUE-MARKER"
        val d = run("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":{"v":"$marker"}}}}""")
        assertFalse(d.response.toString().contains(marker))
    }

    @Test
    fun `the audit line holds the method, tool and a principal hash, never an argument`() {
        val line = mcpAuditLine("tools/call", "kivra_run_get", "5:https://x:sub-alice", "OK")
        assertTrue(line.contains("method=tools/call tool=kivra_run_get"))
        assertTrue(line.contains("principal=" + principalHash("5:https://x:sub-alice")))
        assertEquals(12, principalHash("anything").length)
        assertFalse(line.contains("sub-alice"))
    }
}
