package net.kigawa.kivra.selfdev.llm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0021 L3: the parts are not wired. The package depends on no DB, route, MCP, server or approval code, and nothing calls it. */
class LlmStopApprovalArchitectureTest {
    private val main = File("src/main/kotlin")
    private val pkg = File("src/main/kotlin/net/kigawa/kivra/selfdev/llm")

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
    fun `no other production source refers to the package`() {
        val hits = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.startsWith(pkg.path) }
            .filter { it.readText().contains("selfdev.llm") }
            .map { it.name }.toList()
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the package has no route, no tool and no environment read`() {
        val text = pkg.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(!Regex("\\b(routing|get\\(\"/|post\\(\"/|System\\.getenv)").containsMatchIn(text))
    }
}
