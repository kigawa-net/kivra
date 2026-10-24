package net.kigawa.kivra.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Permissions.kt is pure: no database package, no Ktor, no logging. Only the guard (and the order routes' wiring) reaches it. */
class PermissionsArchitectureTest {
    private val main = File("src/main/kotlin")
    private val file = File(main, "net/kigawa/kivra/auth/Permissions.kt")

    @Test
    fun `Permissions does not depend on db, Ktor, SQL or logging`() {
        assertTrue(file.isFile, "run from the kivra-app directory")
        val imports = file.readLines().filter { it.startsWith("import ") }
        assertEquals(emptyList(), imports.filter { Regex("kivra\\.db|io\\.ktor|java\\.sql|org\\.slf4j|org\\.jetbrains\\.exposed").containsMatchIn(it) })
        assertTrue(!file.readText().contains("LoggerFactory"))
    }

    @Test
    fun `only the settings and the guard use PermissionService`() {
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("PermissionService") }
            .map { it.relativeTo(main).invariantSeparatorsPath }.toSet()
        // PermissionGuard.kt is the routes' one entrance and does the logging Permissions.kt must not. SelfDevOrderRoutes.kt names the
        // service only to build the default guard of its deps (roles off, no store), so that a test that does not pass one gets today's rules.
        // SelfDevGoalRoutes.kt and SelfDevMemoryRoutes.kt (PR 3b) name it for the same reason and for no other.
        // PR 3c: the REST routes take the guard (a PermissionGuard), and none of them names the service; the default guard comes from
        // offPermissionGuard() in PermissionGuard.kt, so no further file joins this set.
        // Application.kt builds it through RolesSettings and does not name the service.
        assertEquals(
            setOf(
                "net/kigawa/kivra/auth/Permissions.kt",
                "net/kigawa/kivra/selfdev/RolesSettings.kt",
                "net/kigawa/kivra/routes/PermissionGuard.kt",
                "net/kigawa/kivra/routes/SelfDevOrderRoutes.kt",
                "net/kigawa/kivra/routes/SelfDevGoalRoutes.kt",
                "net/kigawa/kivra/routes/SelfDevMemoryRoutes.kt",
            ),
            users,
        )
    }

    @Test
    fun `the order routes keep no identity rule of their own`() {
        val text = File(main, "net/kigawa/kivra/routes/SelfDevOrderRoutes.kt").readText()
        for (banned in listOf("isHuman(", "isRootApprover(", "== aiPrincipal", "aiPrincipal ==", "!= aiPrincipal", "aiPrincipal !=")) {
            assertTrue(!text.contains(banned), "SelfDevOrderRoutes.kt must not contain '$banned'; use PermissionGuard")
        }
        // The routes are reached only through the guard.
        assertTrue(text.contains("guard.check("))
    }

    @Test
    fun `the goal and memory routes keep no identity rule of their own`() {
        for (name in listOf("SelfDevGoalRoutes.kt", "SelfDevMemoryRoutes.kt")) {
            val text = File(main, "net/kigawa/kivra/routes/$name").readText()
            for (banned in listOf("requireHuman(", "== aiPrincipal", "aiPrincipal ==", "!= aiPrincipal", "aiPrincipal !=")) {
                assertTrue(!text.contains(banned), "$name must not contain '$banned'; use PermissionGuard")
            }
            assertTrue(text.contains("guard.check("), "$name reaches the permissions only through the guard")
        }
    }

    @Test
    fun `the REST routes of PR 3c are reached only through the guard`() {
        val files = listOf(
            "SelfDevAdminRoutes.kt", "BudgetAdminRoutes.kt", "TaskApiRoutes.kt", "SelfDevRoutes.kt",
            "OperationApiRoutes.kt", "RunViewRoutes.kt", "RunExecutionRoutes.kt",
        )
        for (name in files) {
            val text = File(main, "net/kigawa/kivra/routes/$name").readText()
            assertTrue(text.contains("guard.permits("), "$name must check a permission through the guard")
            for (banned in listOf("PermissionService", "aiPrincipal", "rootApprover")) {
                assertTrue(!text.contains(banned), "$name must not contain '$banned'; use PermissionGuard")
            }
        }
        // The routes that stay open on purpose: the agent's own routes, the dev-only routes and the public ones.
        for (name in listOf("AgentApiRoutes.kt", "DevPreviewRoutes.kt", "DevTaskSubmitRoutes.kt", "HealthRoutes.kt", "MetricsRoutes.kt", "UiRoutes.kt")) {
            assertTrue(!File(main, "net/kigawa/kivra/routes/$name").readText().contains("guard.permits("), name)
        }
    }
}
