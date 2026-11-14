package net.kigawa.kivra.chat

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AI's tools: the classification, the set offered per permission, and the argument checks (ADR 0023 追補 5). */
class ChatToolsTest {
    private val order = "01HZX5K3M2Q8WTX7Y0B9C4D6E8"
    private val run = "01HZX5K3M2Q8WTX7Y0B9C4D6E9"

    @Test
    fun `the reads run at once, the safe operations run at once, and the destructive ones are only proposed`() {
        assertEquals(setOf(ChatTool.ORDER_GET, ChatTool.ORDER_LIST, ChatTool.RUN_GET, ChatTool.RUN_LIST, ChatTool.TASK_LIST, ChatTool.PROJECT_GET,
            ChatTool.MEMORY_SEARCH, ChatTool.MEMORY_GET),
            ChatTool.entries.filter { it.disposition == ChatToolDisposition.READ }.toSet())
        assertEquals(setOf(ChatTool.ORDER_START_CYCLE, ChatTool.ORDER_PAUSE, ChatTool.ORDER_RESUME, ChatTool.PROJECT_UPDATE),
            ChatTool.entries.filter { it.disposition == ChatToolDisposition.IMMEDIATE }.toSet())
        assertEquals(setOf(ChatTool.ORDER_ABANDON_CYCLE, ChatTool.ORDER_STOP_REQUEST, ChatTool.MEMBER_ADD, ChatTool.MEMBER_REMOVE),
            ChatTool.entries.filter { it.disposition == ChatToolDisposition.PROPOSE }.toSet())
    }

    @Test
    fun `an operator without the order-management permission is offered the reads only`() {
        val offered = ChatTool.offeredTo(canManageOrders = false)
        assertTrue(offered.all { !it.needsOrderManage })
        assertTrue(ChatTool.ORDER_GET in offered)
        assertFalse(ChatTool.ORDER_START_CYCLE in offered)
        assertFalse(ChatTool.ORDER_STOP_REQUEST in offered)
        assertFalse(ChatTool.MEMBER_REMOVE in offered)
        assertTrue(offered.all { it.disposition == ChatToolDisposition.READ })
        assertEquals(ChatTool.entries.toSet(), ChatTool.offeredTo(canManageOrders = true, memoryEnabled = true).toSet())
    }

    @Test
    fun `the memory tools are offered only where the memory is enabled`() {
        assertFalse(ChatTool.offeredTo(canManageOrders = true).any { it.needsMemory })
        assertTrue(ChatTool.MEMORY_SEARCH in ChatTool.offeredTo(canManageOrders = false, memoryEnabled = true))
        assertTrue(ChatTool.MEMORY_GET in ChatTool.offeredTo(canManageOrders = false, memoryEnabled = true))
    }

    @Test
    fun `the model calls a tool by its lower-case wire name, and an unknown name is not guessed`() {
        assertEquals(ChatTool.MEMORY_SEARCH, ChatTool.fromWireName("memory_search"))
        assertEquals(ChatTool.ORDER_PAUSE, ChatTool.fromWireName("order_pause"))
        assertNull(ChatTool.fromWireName("ORDER_PAUSE"))
        assertNull(ChatTool.fromWireName("task_create"))
    }

    @Test
    fun `each tool's schema allows only its own arguments and requires its IDs`() {
        val add = ChatToolArguments.schemaOf(ChatTool.MEMBER_ADD)
        assertEquals("false", add["additionalProperties"].toString())
        assertEquals(setOf("\"assigneeId\"", "\"role\""), add["required"]!!.jsonArray.map { it.toString() }.toSet())
        val search = ChatToolArguments.schemaOf(ChatTool.MEMORY_SEARCH)
        assertTrue(search["required"]!!.jsonArray.isEmpty())
        assertEquals(setOf("query", "kind"), search["properties"]!!.jsonObject.keys)
        assertEquals("order_get", ChatTool.ORDER_GET.spec().name)
    }

    @Test
    fun `the model's arguments must be a JSON object of strings`() {
        assertEquals(mapOf("orderId" to order), ChatToolArguments.parse(buildJsonObject { put("orderId", order) }))
        assertNull(ChatToolArguments.parse(buildJsonObject { put("orderId", 1) }))
        assertNull(ChatToolArguments.parse(JsonPrimitive("x")))
        assertNull(ChatToolArguments.parse(buildJsonObject { put("orderId", JsonNull) }))
    }

    @Test
    fun `an unknown tool name is not guessed`() {
        assertEquals(ChatTool.ORDER_PAUSE, ChatTool.fromName("ORDER_PAUSE"))
        assertNull(ChatTool.fromName("order_pause"))
        assertNull(ChatTool.fromName("DELETE_EVERYTHING"))
    }

    @Test
    fun `an ID must be a ULID and a text must be bounded and free of control characters`() {
        assertTrue(ChatToolArguments.isId(order))
        assertFalse(ChatToolArguments.isId(order.lowercase()))
        assertFalse(ChatToolArguments.isId("short"))
        assertFalse(ChatToolArguments.isId("$order/../x"))
        assertFalse(ChatToolArguments.isId(null))
        assertTrue(ChatToolArguments.isText("ok", 10))
        assertFalse(ChatToolArguments.isText("   ", 10))
        assertFalse(ChatToolArguments.isText("abcdefghijk", 10))
        assertFalse(ChatToolArguments.isText("a\nb", 10))
    }

    @Test
    fun `the arguments of a call are exactly the ones its tool takes`() {
        assertTrue(ChatToolArguments.isValid(ChatTool.ORDER_PAUSE, mapOf("orderId" to order)))
        assertFalse(ChatToolArguments.isValid(ChatTool.ORDER_PAUSE, mapOf("orderId" to "bad")))
        assertFalse(ChatToolArguments.isValid(ChatTool.ORDER_PAUSE, mapOf("orderId" to order, "extra" to "x")), "an unexpected key is refused")
        assertFalse(ChatToolArguments.isValid(ChatTool.ORDER_PAUSE, emptyMap()), "a missing ID is refused")
        assertTrue(ChatToolArguments.isValid(ChatTool.RUN_GET, mapOf("runId" to run)))
        assertTrue(ChatToolArguments.isValid(ChatTool.PROJECT_UPDATE, mapOf("name" to "新しい名前")))
        assertFalse(ChatToolArguments.isValid(ChatTool.PROJECT_UPDATE, mapOf("name" to "x".repeat(ChatToolArguments.NAME_MAX + 1))))
        assertTrue(ChatToolArguments.isValid(ChatTool.TASK_LIST, emptyMap()))
        assertFalse(ChatToolArguments.isValid(ChatTool.PROJECT_UPDATE, emptyMap()), "an update changes something")
        assertFalse(ChatToolArguments.isValid(ChatTool.PROJECT_UPDATE, mapOf("description" to "x".repeat(ChatToolArguments.DESCRIPTION_MAX + 1))))
        assertTrue(ChatToolArguments.isValid(ChatTool.MEMBER_ADD, mapOf("assigneeId" to order, "role" to "VIEWER")))
        assertFalse(ChatToolArguments.isValid(ChatTool.MEMBER_ADD, mapOf("assigneeId" to order)), "the role is required")
        assertFalse(ChatToolArguments.isValid(ChatTool.MEMBER_ADD, mapOf("assigneeId" to order, "role" to "OWNER")))
        assertTrue(ChatToolArguments.isValid(ChatTool.MEMORY_SEARCH, emptyMap()))
        assertTrue(ChatToolArguments.isValid(ChatTool.MEMORY_SEARCH, mapOf("query" to "deploy", "kind" to "OPERATOR_NOTE")))
        assertFalse(ChatToolArguments.isValid(ChatTool.MEMORY_SEARCH, mapOf("kind" to "SECRET")))
        assertFalse(ChatToolArguments.isValid(ChatTool.MEMORY_GET, mapOf("recordId" to "../$order")))
        assertFalse(ChatToolArguments.isValid(ChatTool.ORDER_STOP_REQUEST, mapOf("orderId" to order, "reason" to "x")), "no free text")
    }
}
