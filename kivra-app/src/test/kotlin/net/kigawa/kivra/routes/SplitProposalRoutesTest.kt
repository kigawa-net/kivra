package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.ChildProposalState
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.CycleStarted
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.PlanCommit
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SplitProposal
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.NewChildOrder
import net.kigawa.kivra.selfdev.NewTask
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.transactions.transaction

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val OPERATOR = "1:i:oper"
private const val AI_PRINCIPAL = "1:i:ai-agent"
private const val ROOT_APPROVER = "1:i:root"
private const val TARGET = "owner/repo"
private const val CAP = 3

/**
 * The Planner's child proposals as the operator lists and decides them (ADR 0015 addendum 2026-11-22, PR2): the list, approve, reject, their
 * refusals, and the concurrent approval. H2 with the real repositories. A proposal is written by [SelfDevOperatorRepository.commitPlan], as the
 * Planner's plan does.
 */
class SplitProposalRoutesTest {
    private val openDbs = mutableListOf<TestDb>()

    @AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth =
            headers[PRINCIPAL_HEADER]?.takeIf { it.isNotBlank() }?.let { OperatorAuth.Authenticated(it) } ?: OperatorAuth.Unauthenticated
    }

    private fun enabledAutopilot(): AutopilotSettings = AutopilotSettings.fromEnv(
        mapOf(
            AutopilotSettings.ENV_ENABLED to "true",
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        ),
        SelfDevSettings.fromEnv(
            mapOf(
                "KIVRA_SELFDEV_ENABLED" to "true",
                "KIVRA_AUTH_MODE" to "oidc-jwt",
                "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
                "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
                "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
                "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
                "KIVRA_FX_USD_JPY" to "150",
                "KIVRA_FX_AS_OF" to "2026-10-18",
                "KIVRA_SELFDEV_RUNTIME" to "cli",
                "KIVRA_AGENT_API_ENABLED" to "true",
            ),
        ),
    )

    /** The route deps on [db]; [env] overrides the order settings (the tree limits). */
    private fun deps(db: TestDb, env: Map<String, String> = emptyMap()): SelfDevOrderRouteDeps {
        val autopilot = enabledAutopilot()
        return SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = OrderSettings.fromEnv(
                mapOf(
                    OrderSettings.ENV_ENABLED to "true",
                    OrderSettings.ENV_ROOT_APPROVER to ROOT_APPROVER,
                    "KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP" to "$CAP",
                    // Children are on (a depth of 2 allows the root's children); the defaults keep them off.
                    "KIVRA_SELFDEV_ORDER_MAX_DEPTH" to "2",
                ) + env,
                autopilot,
                aiPrincipal = AI_PRINCIPAL,
            ),
            aiPrincipal = AI_PRINCIPAL,
            targetRepo = TARGET,
            orders = SelfDevOrderRepository(db.database),
            operations = SelfDevOperatorRepository(db.database),
            projects = ProjectRepository(db.database, Clock.systemUTC()),
        )
    }

    private class Scenario(val db: TestDb, val deps: SelfDevOrderRouteDeps) {
        val orders: SelfDevOrderRepository get() = deps.orders
        val ops: SelfDevOperatorRepository get() = deps.operations
        val projectId: String = ProjectRepository(db.database, Clock.systemUTC()).create("Proposals", null).projectId
        lateinit var root: SelfDevOrder
        lateinit var cycle: CycleStarted

        /** A root whose first cycle's plan proposed two children: ordinal 1 (one cycle) and ordinal 2 (two cycles). */
        fun proposeTwo(): List<SplitProposal> {
            root = (orders.createRoot(OPERATOR, "Root title", "Improve the docs", TARGET, 1, projectId) as CreateRootResult.Created).order
            cycle = (orders.startCycle(root.orderId, root.version, OPERATOR) as OrderResult.Moved).value
            // The cycle start moved the root's version: the scenario keeps the current row.
            root = assertNotNull(orders.get(root.orderId))
            val fence = assertNotNull(ops.claimForPlanning(cycle.goalId, "token-${cycle.goalId}", Duration.ofMinutes(10))).fence
            val task = NewTask(
                title = "Docs", body = "body", targetPaths = listOf("docs/a.md"), sizeEstimate = "S", dependsOn = emptyList(),
                requiresApproval = false, initialState = TaskState.QUEUED,
            )
            val children = listOf(
                NewChildOrder(ordinal = 1, title = "Workstream one", instruction = "Do the first part", maxCycles = 1),
                NewChildOrder(ordinal = 2, title = "Workstream two", instruction = "Do the second part", maxCycles = 2),
            )
            assertIs<PlanCommit.Committed>(ops.commitPlan(fence, listOf(task), children, root.orderId))
            return ops.splitProposalsOf(root.orderId, 10)
        }

        fun childrenOf(parentId: String): Int = transaction(db.database) {
            execQuery("SELECT COUNT(*) FROM selfdev_orders WHERE parent_order_id = ?", parentId) { it.getInt(1) }.single()
        }
    }

    private fun scenario(db: TestDb, env: Map<String, String> = emptyMap()): Scenario = Scenario(db, deps(db, env))

    private fun ApplicationTestBuilder.mount(deps: SelfDevOrderRouteDeps) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevOrderRoutes(deps) }
        }
    }

    /** A POST always carries a JSON content type (the same origin check as the other order routes); an empty body is the approval's. */
    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String? = null, principal: String = OPERATOR): HttpResponse =
        client.request(path) {
            this.method = method
            header(PRINCIPAL_HEADER, principal)
            if (body != null || method == HttpMethod.Post) {
                contentType(ContentType.Application.Json)
                setBody(body ?: "")
            }
        }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private fun approvePath(orderId: String, proposalId: String) = "/api/selfdev/orders/$orderId/split-proposals/$proposalId/approve"
    private fun rejectPath(orderId: String, proposalId: String) = "/api/selfdev/orders/$orderId/split-proposals/$proposalId/reject"
    private fun listPath(orderId: String) = "/api/selfdev/orders/$orderId/split-proposals"

    @Test
    fun `the AI principal may read the proposals but cannot approve or reject them, and nothing is written`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val proposals = s.proposeTwo()
        mount(s.deps)

        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, listPath(s.root.orderId), principal = AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, approvePath(s.root.orderId, proposals[0].proposalId), principal = AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, rejectPath(s.root.orderId, proposals[0].proposalId), principal = AI_PRINCIPAL).status)
        assertEquals(0, s.childrenOf(s.root.orderId))
        assertTrue(s.ops.splitProposalsOf(s.root.orderId, 10).all { it.state == ChildProposalState.PROPOSED })
    }

    @Test
    fun `an operator lists the proposals with their text, and the state filter and the order are checked`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        s.proposeTwo()
        mount(s.deps)

        val items = send(HttpMethod.Get, listPath(s.root.orderId)).array()
        assertEquals(2, items.size)
        val first = items[0].jsonObject
        assertEquals("Workstream one", first["title"]!!.jsonPrimitive.content)
        assertEquals("Do the first part", first["instruction"]!!.jsonPrimitive.content)
        assertEquals(1, first["maxCycles"]!!.jsonPrimitive.content.toInt())
        assertEquals("PROPOSED", first["state"]!!.jsonPrimitive.content)

        assertEquals(0, send(HttpMethod.Get, listPath(s.root.orderId) + "?state=APPROVED").array().size)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, listPath(s.root.orderId) + "?state=OPEN").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, listPath("01AAAAAAAAAAAAAAAAAAAAAAAA")).status)
    }

    @Test
    fun `approve creates the child from the proposal, and a repeat returns that same child`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val proposals = s.proposeTwo()
        mount(s.deps)
        val second = proposals.single { it.ordinal == 2 }

        val created = send(HttpMethod.Post, approvePath(s.root.orderId, second.proposalId))
        assertEquals(HttpStatusCode.Created, created.status)
        val childId = created.obj()["orderId"]!!.jsonPrimitive.content
        val child = assertNotNull(s.orders.get(childId))
        // The title, the instruction and the cycle cap are the proposal's; the budget is the default cycle budget per cycle, set by the server.
        assertEquals("Workstream two", child.title)
        assertEquals("Do the second part", child.policy)
        assertEquals(2, child.maxCycles)
        assertEquals(2_000L, child.orderBudgetJpy)
        assertEquals(s.root.orderId, child.parentOrderId)
        val row = s.ops.splitProposalsOf(s.root.orderId, 10).single { it.ordinal == 2 }
        assertEquals(ChildProposalState.APPROVED, row.state)
        assertEquals(childId, row.createdChildOrderId)

        val again = send(HttpMethod.Post, approvePath(s.root.orderId, second.proposalId))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(childId, again.obj()["orderId"]!!.jsonPrimitive.content)
        assertEquals(1, s.childrenOf(s.root.orderId))
    }

    @Test
    fun `a reject is final - a repeat reject gives the same answer, and an approval after it is 409 PROPOSAL_REJECTED`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val proposals = s.proposeTwo()
        mount(s.deps)
        val first = proposals.single { it.ordinal == 1 }

        val rejected = send(HttpMethod.Post, rejectPath(s.root.orderId, first.proposalId))
        assertEquals(HttpStatusCode.OK, rejected.status)
        assertEquals("REJECTED", rejected.obj()["state"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, rejectPath(s.root.orderId, first.proposalId)).status)

        val approve = send(HttpMethod.Post, approvePath(s.root.orderId, first.proposalId))
        assertEquals(HttpStatusCode.Conflict, approve.status)
        assertEquals("PROPOSAL_REJECTED", approve.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(0, s.childrenOf(s.root.orderId))
    }

    @Test
    fun `a refusal by the tree limit uses the children endpoint's code, and the proposal stays open`() = testApplication {
        val db = TestDb().also { openDbs += it }
        // A tree of one order: any child is over the size limit (TREE_LIMIT, as POST .../children answers).
        val s = scenario(db, mapOf("KIVRA_SELFDEV_ORDER_MAX_TREE" to "1"))
        val proposals = s.proposeTwo()
        mount(s.deps)
        val first = proposals.single { it.ordinal == 1 }

        val refused = send(HttpMethod.Post, approvePath(s.root.orderId, first.proposalId))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("TREE_LIMIT", refused.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(0, s.childrenOf(s.root.orderId))
        assertEquals(ChildProposalState.PROPOSED, s.ops.splitProposalsOf(s.root.orderId, 10).single { it.ordinal == 1 }.state)
    }

    @Test
    fun `a second approval over the parent's child limit is CHILD_LIMIT, and the first child stays`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db, mapOf("KIVRA_SELFDEV_ORDER_MAX_CHILDREN" to "1"))
        val proposals = s.proposeTwo()
        mount(s.deps)

        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, approvePath(s.root.orderId, proposals.single { it.ordinal == 1 }.proposalId)).status)
        val refused = send(HttpMethod.Post, approvePath(s.root.orderId, proposals.single { it.ordinal == 2 }.proposalId))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("CHILD_LIMIT", refused.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(1, s.childrenOf(s.root.orderId))
    }

    @Test
    fun `an approval after the child was made under its key (a stop between the two writes) adopts that child`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val proposals = s.proposeTwo()
        mount(s.deps)
        val first = proposals.single { it.ordinal == 1 }
        // The child made by an earlier approval whose mark was never written: the same key and content, as approve creates it.
        val earlier = assertIs<ChildCreateResult.Created>(
            s.orders.createChild(
                s.root.orderId, "Workstream one", "Do the first part", 1_000, "plan-split:${first.planId}:1", OPERATOR,
                ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 50), 1,
            ),
        ).order

        val adopted = send(HttpMethod.Post, approvePath(s.root.orderId, first.proposalId))
        assertEquals(HttpStatusCode.OK, adopted.status)
        assertEquals(earlier.orderId, adopted.obj()["orderId"]!!.jsonPrimitive.content)
        val row = s.ops.splitProposalsOf(s.root.orderId, 10).single { it.ordinal == 1 }
        assertEquals(ChildProposalState.APPROVED, row.state)
        assertEquals(earlier.orderId, row.createdChildOrderId)
    }

    @Test
    fun `a paused order expires its open proposals, and approving one is 409 PROPOSAL_EXPIRED`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val proposals = s.proposeTwo()
        mount(s.deps)
        assertTrue(s.orders.pauseByOperator(s.root.orderId, s.root.version, OPERATOR) is OrderResult.Moved<*>)

        val items = send(HttpMethod.Get, listPath(s.root.orderId)).array()
        assertTrue(items.all { it.jsonObject["state"]!!.jsonPrimitive.content == "EXPIRED" })
        val refused = send(HttpMethod.Post, approvePath(s.root.orderId, proposals[0].proposalId))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("PROPOSAL_EXPIRED", refused.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(0, s.childrenOf(s.root.orderId))
    }

    @Test
    fun `a re-planned root supersedes the proposals of the older plan`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val proposals = s.proposeTwo()
        mount(s.deps)
        // The root task's revision moves past the plan's "r{revision}" when the root is planned again.
        transaction(db.database) {
            execUpdate("UPDATE selfdev_tasks SET revision = revision + 1 WHERE task_id = ?", s.cycle.goalId)
        }

        val refused = send(HttpMethod.Post, approvePath(s.root.orderId, proposals[0].proposalId))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("PROPOSAL_EXPIRED", refused.obj()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `two approvals at the same time make one child, and both answer with it`() {
        val db = TestDb().also { openDbs += it }
        val s = scenario(db)
        val first = s.proposeTwo().single { it.ordinal == 1 }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = (1..2).map {
                pool.submit<SplitApproval> {
                    start.await()
                    s.deps.approveSplitProposalAsOperator(s.root.orderId, first.proposalId, OPERATOR)
                }
            }
            start.countDown()
            val ids = futures.map { future ->
                when (val r = future.get(60, TimeUnit.SECONDS)) {
                    is SplitApproval.Created -> r.order.orderId
                    is SplitApproval.Replayed -> r.order.orderId
                    else -> error("an approval must succeed, got $r")
                }
            }
            assertEquals(1, ids.distinct().size)
            assertEquals(1, futures.count { (it.get() as? SplitApproval.Created) != null })
            assertEquals(1, s.childrenOf(s.root.orderId))
            assertEquals(ChildProposalState.APPROVED, s.ops.splitProposalsOf(s.root.orderId, 10).single { it.ordinal == 1 }.state)
        } finally {
            pool.shutdownNow()
        }
    }
}
