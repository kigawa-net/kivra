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
        // Application.kt builds it through RolesSettings and does not name the service.
        assertEquals(
            setOf(
                "net/kigawa/kivra/auth/Permissions.kt",
                "net/kigawa/kivra/selfdev/RolesSettings.kt",
                "net/kigawa/kivra/routes/PermissionGuard.kt",
                "net/kigawa/kivra/routes/SelfDevOrderRoutes.kt",
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
}
