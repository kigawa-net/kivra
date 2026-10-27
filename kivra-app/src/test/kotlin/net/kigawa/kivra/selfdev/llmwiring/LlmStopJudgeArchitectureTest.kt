package net.kigawa.kivra.selfdev.llmwiring

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0021 L4/L5: the judgement job has no route, no tool and no environment read of its own.
 * In L5 the port includes an approval function, and the mode is taken from settings. */
class LlmStopJudgeArchitectureTest {
    private val pkg = File("src/main/kotlin/net/kigawa/kivra/selfdev/llmwiring")
    private fun sources(): List<File> {
        assertTrue(pkg.isDirectory, "run the test from the kivra-app module directory: ${pkg.absolutePath}")
        return pkg.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `the order port of the job has reads and the LLM approval function`() {
        val text = File(pkg, "LlmStopJudgeJob.kt").readText()
        val port = text.substringAfter("interface StopJudgeOrders {").substringBefore("}")
        val functions = Regex("fun (\\w+)").findAll(port).map { it.groupValues[1] }.toList()
        assertEquals(listOf("stopRequest", "order", "approveStopByLlm"), functions)
    }

    @Test
    fun `the package has no route, no tool and no environment read`() {
        val text = sources().joinToString("\n") { it.readText() }
        assertTrue(!Regex("\\b(routing|get\\(\"/|post\\(\"/|System\\.getenv|registerTool)").containsMatchIn(text))
    }

    @Test
    fun `the job mode comes from settings, not hardcoded`() {
        val text = File(pkg, "LlmStopJudgeJob.kt").readText()
        assertTrue(text.contains("LlmStopApprovalMode.ACTIVE"))
        assertTrue(text.contains("LlmStopApprovalMode.SHADOW"))
    }
}
