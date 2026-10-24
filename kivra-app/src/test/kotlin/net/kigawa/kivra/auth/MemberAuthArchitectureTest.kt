package net.kigawa.kivra.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `authenticateMember` is defined but not called by any route yet. It is wired in the permission PR (PR 3), which changes this test. */
class MemberAuthArchitectureTest {
    private val main = File("src/main/kotlin")

    @Test
    fun `no production code calls authenticateMember yet (wired in PR 3)`() {
        assertTrue(main.isDirectory, "run from the kivra-app directory")
        val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("\\.authenticateMember\\(").containsMatchIn(it.readText()) }
            .map { it.relativeTo(main).invariantSeparatorsPath }.toList()
        assertEquals(emptyList(), callers)
    }
}
