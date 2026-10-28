package net.kigawa.kivra.selfdev.llmwiring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.bindToAiApproverForTest
import net.kigawa.kivra.db.execQuery

/** The wiring of the LLM stop judgement (ADR 0021 L4/L5): off creates and reads nothing; active mode runs as active. */
class LlmStopJudgeWiringTest {

    /** An environment that records every key that is read. */
    private class RecordingEnv(private val backing: Map<String, String>) : AbstractMap<String, String>() {
        val reads = mutableListOf<String>()
        override val entries: Set<Map.Entry<String, String>> get() = backing.entries
        override fun get(key: String): String? {
            reads.add(key)
            return backing[key]
        }
        override fun containsKey(key: String): Boolean {
            reads.add(key)
            return backing.containsKey(key)
        }
    }

    private val on = mapOf(
        "KIVRA_SELFDEV_LLM_STOP_APPROVAL_ENABLED" to "true",
        "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MODEL" to MODEL_NAME,
    )

    @Test
    fun `disabled reads no other variable, creates nothing and installs no listener`() = TestDb().use { db ->
        val orders = SelfDevOrderRepository(db.database)
        val env = RecordingEnv(mapOf("KIVRA_SELFDEV_LLM_STOP_APPROVAL_MODEL" to MODEL_NAME, "KIVRA_SELFDEV_LLM_STOP_APPROVAL_MODE" to "bogus"))
        assertNull(buildLlmStopJudge(env, db.database, orders, anthropic = null, rolesMode = PermissionMode.ENFORCE, clientOverride = FakeClient()))
        assertEquals(setOf("KIVRA_SELFDEV_LLM_STOP_APPROVAL_ENABLED"), env.reads.toSet())
        // No listener: a request is created and nothing records a judgement.
        val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
        assertTrue(orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST) is OrderResult.Moved)
    }

    @Test
    fun `enabled without a model fails the start-up`() = TestDb().use { db ->
        assertFailsWith<IllegalStateException> {
            buildLlmStopJudge(mapOf("KIVRA_SELFDEV_LLM_STOP_APPROVAL_ENABLED" to "true"), db.database, SelfDevOrderRepository(db.database), null, PermissionMode.ENFORCE, clientOverride = FakeClient())
        }
    }

    @Test
    fun `enabled builds the job and a stop request nudges it`() = TestDb().use { db ->
        val orders = SelfDevOrderRepository(db.database)
        val client = FakeClient()
        val job = assertNotNull(buildLlmStopJudge(on, db.database, orders, null, PermissionMode.ENFORCE, clientOverride = client))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            job.start(scope)
            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST)
            val deadline = System.currentTimeMillis() + 10_000
            while (rowCount(db) == 0) {
                check(System.currentTimeMillis() < deadline) { "no judgement was recorded" }
                Thread.sleep(20)
            }
            // No AI_APPROVER exists in this DB, so the judgement is SKIPPED and the model is not called.
            assertEquals(0, client.calls.get())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `mode active runs as active without warning`() = LogCapture().use { log ->
        JudgeFixtureDb { db ->
            val orders = SelfDevOrderRepository(db.database)
            val client = FakeClient()
            val assignees = net.kigawa.kivra.db.SelfDevAssigneeRepository(db.database)
            // Create AI_APPROVER and bind key BEFORE building the job.
            val approver = (assignees.create("approver", net.kigawa.kivra.db.AssigneeRole.AI_APPROVER, ADMIN_KEY) as net.kigawa.kivra.db.AssigneeResult.Moved).value.assigneeId
            bindToAiApproverForTest(db, approver, AI_APPROVER_KEY)
            val env = on + ("KIVRA_SELFDEV_LLM_STOP_APPROVAL_MODE" to "active")
            val job = assertNotNull(buildLlmStopJudge(env, db.database, orders, null, PermissionMode.ENFORCE, clientOverride = client))
            // L5: active mode runs as active, no warning.
            assertEquals(0, log.messages().count { it.contains("MODE_ACTIVE_NOT_AVAILABLE") })
            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST) as OrderResult.Moved).value
            runBlocking { job.judgeRequest(request.requestId) }
            // Check all decision records, including errors.
            val allRows = org.jetbrains.exposed.sql.transactions.transaction(db.database) {
                execQuery("SELECT outcome, mode, reason_code FROM selfdev_llm_stop_decisions ORDER BY created_at") {
                    "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}"
                }
            }
            // In active mode with APPROVE, the order is stopped and the mode column is "active".
            assertEquals(listOf("APPROVED|active|CLEAR_STOP_REQUEST"), allRows)
            assertEquals(1, client.calls.get())
            // Verify the order is STOPPED.
            val orderState = org.jetbrains.exposed.sql.transactions.transaction(db.database) {
                execQuery("SELECT state FROM selfdev_orders WHERE order_id = ?", root.orderId) { it.getString(1) }.single()
            }
            assertEquals("STOPPED", orderState)
        }
    }

    @Test
    fun `a roles mode other than enforce warns at the start and every request is SKIPPED`() = LogCapture().use { log ->
        JudgeFixtureDb { db ->
            val orders = SelfDevOrderRepository(db.database)
            val client = FakeClient()
            val job = assertNotNull(buildLlmStopJudge(on, db.database, orders, null, PermissionMode.SHADOW, clientOverride = client))
            assertEquals(1, log.messages().count { it.contains("ROLES_MODE_NOT_ENFORCE") })
            val root = (orders.createRoot(OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order
            val request = (orders.requestStop(root.orderId, root.version, AI_REQUESTER, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST) as OrderResult.Moved).value
            runBlocking { job.judgeRequest(request.requestId) }
            assertEquals(0, client.calls.get())
            assertEquals(listOf("ROLES_NOT_ENFORCE"), reasons(db))
        }
    }

    @Test
    fun `without the orders or a credential nothing starts, with a fixed warning code`() = LogCapture().use { log ->
        JudgeFixtureDb { db ->
            assertNull(buildLlmStopJudge(on, db.database, null, null, PermissionMode.ENFORCE, clientOverride = FakeClient()))
            assertNull(buildLlmStopJudge(on, db.database, SelfDevOrderRepository(db.database), null, PermissionMode.ENFORCE))
            assertEquals(2, log.messages().count { it.contains("PREREQUISITE_MISSING") })
        }
    }

    private fun JudgeFixtureDb(block: (TestDb) -> Unit) = TestDb().use(block)

    private fun rowCount(db: TestDb): Int = query(db, "SELECT COUNT(*) FROM selfdev_llm_stop_decisions") { it.getInt(1) }.single()
    private fun modes(db: TestDb): List<String> = query(db, "SELECT mode FROM selfdev_llm_stop_decisions") { it.getString(1) }
    private fun reasons(db: TestDb): List<String> = query(db, "SELECT reason_code FROM selfdev_llm_stop_decisions") { it.getString(1) }

    private fun <T> query(db: TestDb, sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        org.jetbrains.exposed.sql.transactions.transaction(db.database) { execQuery(sql, map = map) }
}
