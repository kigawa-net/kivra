package net.kigawa.kivra.selfdev.llm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0021 L3: the parts stay free of wiring. The package depends on no DB, route, MCP, server or approval code. Since L4 exactly one other
 * package, `selfdev.llmwiring` (the job, the DB stores and the wiring), may use it; every other production source still may not.
 */
class LlmStopApprovalArchitectureTest {
    private val main = File("src/main/kotlin")
    private val pkg = File("src/main/kotlin/net/kigawa/kivra/selfdev/llm")
    private val wiring = File("src/main/kotlin/net/kigawa/kivra/selfdev/llmwiring")
    private val referring = Regex("selfdev\\.llm\\b")

    @Test
    fun `the package does not depend on the DB, routes, MCP, the server or approval code`() {
        assertTrue(pkg.isDirectory, "run the test from the kivra-app module directory: ${pkg.absolutePath}")
        val forbidden = Regex(
            "^import (net\\.kigawa\\.kivra\\.(db|routes|mcp|auth|capability|agent|deploy|reaper|coding)\\b|org\\.jetbrains\\.exposed|java\\.sql|javax\\.sql|io\\.ktor\\.server|com\\.zaxxer)",
            RegexOption.MULTILINE,
        )
        val hits = pkg.walkTopDown().filter { it.isFile && it.extension == "kt" && forbidden.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `no production source outside the package and the wiring package refers to the package`() {
        assertTrue(wiring.isDirectory, "run the test from the kivra-app module directory: ${wiring.absolutePath}")
        val hits = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.startsWith(pkg.path) && !it.path.startsWith(wiring.path) }
            // The entry point of the wiring package (Application.kt) names the wiring package, not the parts: `selfdev.llmwiring` is no match of the regex.
            .filter { referring.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the package has no route, no tool and no environment read`() {
        val text = pkg.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(!Regex("\\b(routing|get\\(\"/|post\\(\"/|System\\.getenv)").containsMatchIn(text))
    }
}
