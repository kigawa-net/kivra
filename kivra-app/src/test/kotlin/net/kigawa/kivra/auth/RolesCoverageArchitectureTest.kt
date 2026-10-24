package net.kigawa.kivra.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole-server architecture test of the roles (ADR 0020, PR 3d). A new route, a new MCP tool or a new authentication call must be
 * placed in the permission table on purpose, or the build fails:
 *
 *  - no identity rule of its own in `routes/` and `mcp/` (no comparison with the AI principal, no `requireHuman(`);
 *  - every route registration is in [ROUTE_PERMISSIONS] (the permission it needs) or in [OPEN_ROUTES] (open on purpose, with the reason);
 *  - every `kivra_` MCP tool is in [MCP_TOOL_PERMISSIONS] and passes the same permission to `McpTool(permission = ...)`;
 *  - the authentication entrances that exist today are fixed in [AUTH_CALLS]. PR 3e replaces them with `authenticateMember` and updates it.
 *
 * It reads the source, like [PermissionsArchitectureTest]; it runs from the kivra-app directory.
 */
class RolesCoverageArchitectureTest {
    private val main = File("src/main/kotlin")
    private val base = File(main, "net/kigawa/kivra")

    private fun kotlinFiles(dir: String): List<File> {
        val root = File(base, dir)
        assertTrue(root.isDirectory, "run from the kivra-app directory: $root")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    private fun File.rel(): String = relativeTo(base).invariantSeparatorsPath

    // ---- (a) no identity rule of its own ---------------------------------------------------------------------------------

    /**
     * Files that may name the AI principal or the human-only rule. PermissionGuard.kt is the one entrance that applies the rules. The roles'
     * settings (selfdev/RolesSettings.kt, SelfDevOrderSettings.kt) and the Application.kt wiring are outside routes/ and mcp/, so they are not
     * scanned at all. Nothing in routes/ or mcp/ is allowed today.
     */
    private val IDENTITY_RULE_ALLOWED = setOf("routes/PermissionGuard.kt")

    private val identityRules = listOf(
        Regex("\\baiPrincipal\\s*[!=]="),
        Regex("[!=]=\\s*(?:\\w+\\.)*aiPrincipal\\b"),
        Regex("\\.equals\\([^)]*aiPrincipal"),
        Regex("\\brequireHuman\\("),
    )

    @Test
    fun `routes and mcp keep no identity rule of their own`() {
        val offenders = (kotlinFiles("routes") + kotlinFiles("mcp")).filter { it.rel() !in IDENTITY_RULE_ALLOWED }.flatMap { file ->
            file.readLines().withIndex().flatMap { (i, line) ->
                identityRules.filter { it.containsMatchIn(line) }.map { "${file.rel()}:${i + 1}: ${line.trim()}" }
            }
        }
        assertEquals(emptyList(), offenders, "use PermissionGuard (a Permission), not a comparison with the AI principal")
    }

    // ---- (b) every route has a permission --------------------------------------------------------------------------------

    /** The permission each registered route needs. A route here must also name that permission in its handler (or its shared gate). */
    private val ROUTE_PERMISSIONS: Map<String, Permission> = mapOf(
        "POST /api/tasks" to Permission.SPEND_START,
        "GET /api/runs/{id}" to Permission.READ,
        "GET /api/runs" to Permission.READ,
        "GET /api/runs/{id}/operations" to Permission.READ,
        "POST /api/selfdev/tasks" to Permission.SPEND_START,
        "GET /api/selfdev/runs/{id}" to Permission.READ,
        "GET /api/selfdev/runs" to Permission.READ,
        "GET /api/selfdev/status" to Permission.READ,
        "GET /api/admin/runs" to Permission.READ,
        "GET /api/admin/runs/{id}" to Permission.READ,
        "GET /api/admin/runs/{runId}/execution" to Permission.READ,
        "GET /api/admin/runs/{runId}/execution/events" to Permission.READ,
        "GET /api/admin/runs/{runId}/execution/stream" to Permission.READ,
        "GET /api/admin/budget/status" to Permission.READ,
        "GET /api/admin/budget/fx" to Permission.READ,
        "POST /api/admin/budget/acknowledge" to Permission.BUDGET_OVERRIDE,
        "POST /api/admin/selfdev/runs/{id}/release" to Permission.BUDGET_OVERRIDE,
        "POST /api/admin/selfdev/runs/{id}/release-unbound-pr" to Permission.BUDGET_OVERRIDE,
        "GET /api/selfdev/orders" to Permission.READ,
        "POST /api/selfdev/orders" to Permission.ORDER_MANAGE,
        "GET /api/selfdev/orders/{id}" to Permission.READ,
        "GET /api/selfdev/orders/{id}/cycles" to Permission.READ,
        "POST /api/selfdev/orders/{id}/cycles" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/orders/{id}/children" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/orders/{id}/pause" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/orders/{id}/resume" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/orders/{id}/stop-requests" to Permission.STOP_REQUEST,
        "GET /api/selfdev/stop-requests" to Permission.STOP_DECIDE,
        "POST /api/selfdev/stop-requests/{id}/approve" to Permission.STOP_DECIDE,
        "POST /api/selfdev/stop-requests/{id}/reject" to Permission.STOP_DECIDE,
        "POST /api/selfdev/stop-requests/{id}/cancel" to Permission.STOP_REQUEST,
        "GET /api/selfdev/autopilot" to Permission.READ,
        "GET /api/selfdev/autopilot/promotion" to Permission.READ,
        "POST /api/selfdev/autopilot/pause" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/autopilot/resume" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/autopilot/auto-merge/pause" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/autopilot/auto-merge/resume" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/autopilot/reset-breaker" to Permission.ORDER_MANAGE,
        "POST /api/selfdev/goals" to Permission.GOAL_WRITE,
        "GET /api/selfdev/goals" to Permission.READ,
        "GET /api/selfdev/goals/{id}" to Permission.READ,
        "POST /api/selfdev/goals/{id}/approve-plan" to Permission.GOAL_WRITE,
        "POST /api/selfdev/goals/{id}/replan" to Permission.GOAL_WRITE,
        "POST /api/selfdev/goals/{id}/stop" to Permission.GOAL_STOP,
        "POST /api/selfdev/tasks/{id}/approve" to Permission.GOAL_WRITE,
        "POST /api/selfdev/tasks/{id}/reject" to Permission.GOAL_WRITE,
        "GET /api/selfdev/memory" to Permission.READ_TEXT,
        "POST /api/selfdev/memory/{id}/delete" to Permission.GOAL_WRITE,
        "POST /api/selfdev/memory/delete-by-condition" to Permission.GOAL_WRITE,
    )

    /** Routes that need no permission, on purpose: public pages and probes, the agent's own API (its own token), and the dev-only routes. */
    private val OPEN_ROUTES: Map<String, String> = buildMap {
        for (r in listOf("GET /health", "GET /ready", "GET /metrics", "GET /", "GET /ui", "GET /ui/", "GET /ui/app.js", "GET /ui/app.css")) put(r, "PUBLIC")
        for (r in listOf(
            "POST /api/agent/v1/claim",
            "POST /api/agent/v1/attempts/{attemptId}/heartbeat",
            "POST /api/agent/v1/attempts/{attemptId}/fail",
            "POST /api/agent/v1/attempts/{attemptId}/result",
            "POST /api/agent/v1/attempts/{attemptId}/events",
            "GET /api/agent/v1/attempts/{attemptId}/status",
        )) put(r, "AGENT")
        for (r in listOf("GET /api/dev/tasks", "GET /api/dev/runs/preview", "POST /api/dev/tasks", "GET /api/dev/runs/{id}")) put(r, "DEV")
    }

    private class Registration(val file: File, val key: String, val body: String)

    private val registration = Regex("(?m)^\\s*(get|post|patch|put|delete)\\(\"(/[^\"]*)\"")

    private fun registrations(): List<Registration> = kotlinFiles("routes").flatMap { file ->
        val text = file.readText()
        val found = registration.findAll(text).toList()
        found.mapIndexed { i, m ->
            val end = found.getOrNull(i + 1)?.range?.first ?: text.length
            Registration(file, "${m.groupValues[1].uppercase()} ${m.groupValues[2]}", text.substring(m.range.first, end))
        }
    }

    @Test
    fun `every route registration is in the permission table or the open list`() {
        val found = registrations()
        assertTrue(found.size > 50, "the scan found only ${found.size} registrations")
        val unlisted = found.map { it.key }.filter { it !in ROUTE_PERMISSIONS && it !in OPEN_ROUTES }
        assertEquals(emptyList(), unlisted, "a new route needs a row in ROUTE_PERMISSIONS (or OPEN_ROUTES, with its reason)")
        val both = ROUTE_PERMISSIONS.keys.intersect(OPEN_ROUTES.keys)
        assertEquals(emptySet(), both)
        // A row that no route registers is a stale row.
        val registered = found.map { it.key }.toSet()
        assertEquals(emptySet(), (ROUTE_PERMISSIONS.keys + OPEN_ROUTES.keys) - registered, "a row without a route")
    }

    @Test
    fun `a route's handler names the permission its row says`() {
        val permissionName = Regex("Permission\\.([A-Z_]+)")
        // The execution routes check READ in one shared gate (RunExecutionRoutes.kt), not in each handler.
        val sharedGate = setOf("routes/RunExecutionRoutes.kt")
        for (r in registrations()) {
            val expected = ROUTE_PERMISSIONS[r.key] ?: continue
            val named = permissionName.find(r.body)?.groupValues?.get(1)
            if (r.file.rel() in sharedGate) {
                assertTrue(r.file.readText().contains("Permission.${expected.name}"), "${r.key}: ${r.file.rel()} must check ${expected.name}")
            } else {
                assertEquals(expected.name, named, "${r.key} (${r.file.rel()}): the handler's first permission is not the row's")
            }
        }
        // The open routes name no permission at all.
        for (r in registrations().filter { it.key in OPEN_ROUTES }) {
            assertTrue(!r.body.contains("guard.permit") && !r.body.contains("Permission."), "${r.key} is open on purpose but checks a permission")
        }
    }

    // ---- (c) every MCP tool has a permission -----------------------------------------------------------------------------

    private val MCP_TOOL_PERMISSIONS: Map<String, Permission> = mapOf(
        "kivra_run_list" to Permission.READ,
        "kivra_run_get" to Permission.READ,
        "kivra_order_list" to Permission.READ,
        "kivra_order_get" to Permission.READ,
        "kivra_goal_list" to Permission.READ,
        "kivra_goal_get" to Permission.READ,
        "kivra_goal_get_titles" to Permission.READ_TEXT,
        "kivra_selfdev_start" to Permission.SPEND_START,
        "kivra_order_request_stop" to Permission.STOP_REQUEST,
        "kivra_goal_stop" to Permission.GOAL_STOP,
        "kivra_goal_submit" to Permission.GOAL_WRITE,
    )

    @Test
    fun `every MCP tool is in the permission table and passes the same permission to McpTool`() {
        val constructor = Regex("=\\s*McpTool\\(")
        val found = mutableMapOf<String, String?>()
        for (file in kotlinFiles("mcp")) {
            val text = file.readText()
            val starts = constructor.findAll(text).toList()
            starts.forEachIndexed { i, m ->
                val end = starts.getOrNull(i + 1)?.range?.first ?: text.length
                val body = text.substring(m.range.first, end)
                val permission = Regex("\\bpermission\\s*=\\s*Permission\\.([A-Z_]+)").find(body)?.groupValues?.get(1)
                // Every McpTool( names its permission (no allow-list), whether or not it is a kivra_ tool.
                assertTrue(permission != null, "${file.rel()}: a McpTool( without an explicit permission = Permission.X")
                val name = Regex("name\\s*=\\s*\"(kivra_[a-z_]+)\"").find(body)?.groupValues?.get(1)
                assertTrue(name != null, "${file.rel()}: a McpTool without a kivra_ name")
                found[name!!] = permission
            }
        }
        assertEquals(MCP_TOOL_PERMISSIONS.keys, found.keys, "a tool needs a row in MCP_TOOL_PERMISSIONS, and a row needs a tool")
        for ((name, permission) in found) {
            assertEquals(MCP_TOOL_PERMISSIONS.getValue(name).name, permission, "$name: McpTool(permission = ...) must be the row's permission")
        }
    }

    // ---- (d) the authentication entrances are fixed ----------------------------------------------------------------------

    /**
     * Each file and how many direct calls of an authentication entrance it makes today. These are the entrances PR 3e replaces with
     * `authenticateMember`; it updates this table. A new file, or a new call, fails the test until it is added here on purpose. Nothing
     * outside routes/ and mcp/ may make such a call (the implementations are the entrances themselves).
     *  - SelfDevOrderRoutes, SelfDevGoalRoutes, SelfDevMemoryRoutes: `operators.authenticate(` (the roles' routes, then `guard.resolve`).
     *  - the Task API routes (TaskApi, OperationApi, RunView, SelfDev, SelfDevAdmin, BudgetAdmin): `identity.principal(`.
     *  - RunExecutionRoutes: the gate of the execution routes (`identity.principal(`).
     *  - AgentApiRoutes: the agent's own identity (`identity.authenticate(`), not a member.
     *  - McpRoutes: `verifier.authenticate(`, the MCP token (then the dispatcher's guard).
     *  - MetricsRoutes makes none: it has no member authentication.
     */
    private val AUTH_CALLS: Map<String, Int> = mapOf(
        "routes/SelfDevOrderRoutes.kt" to 1,
        "routes/SelfDevGoalRoutes.kt" to 1,
        "routes/SelfDevMemoryRoutes.kt" to 1,
        "routes/TaskApiRoutes.kt" to 3,
        "routes/OperationApiRoutes.kt" to 1,
        "routes/RunViewRoutes.kt" to 3,
        "routes/SelfDevRoutes.kt" to 3,
        "routes/SelfDevAdminRoutes.kt" to 2,
        "routes/BudgetAdminRoutes.kt" to 3,
        "routes/RunExecutionRoutes.kt" to 1,
        "routes/AgentApiRoutes.kt" to 1,
        "mcp/McpRoutes.kt" to 1,
    )

    private val authCall = Regex("\\b(?:operators|identity|verifier)\\.(?:authenticate|principal)\\(")

    @Test
    fun `the direct authentication calls are exactly the fixed list`() {
        val actual = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(base).invariantSeparatorsPath to authCall.findAll(it.readText()).count() }
            .filter { it.second > 0 }
            .sortedBy { it.first }
            .toMap()
        assertEquals(AUTH_CALLS.toSortedMap(), actual.toSortedMap(), "update AUTH_CALLS only with the change that moves an entrance (PR 3e)")
    }
}
