package net.kigawa.kivra.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Permissions.kt is pure: no database package, no Ktor, no logging, and nothing wires it yet. */
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
    fun `no production file other than the settings uses PermissionService yet`() {
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("PermissionService") }
            .map { it.relativeTo(main).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("net/kigawa/kivra/auth/Permissions.kt", "net/kigawa/kivra/selfdev/RolesSettings.kt"), users)
    }
}
