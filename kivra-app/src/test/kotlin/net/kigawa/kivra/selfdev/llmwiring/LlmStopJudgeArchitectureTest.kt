package net.kigawa.kivra.selfdev.llmwiring

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0021 L4: the shadow judgement has no way to approve, and adds no route, no tool and no environment read of its own. */
class LlmStopJudgeArchitectureTest {
    private val pkg = File("src/main/kotlin/net/kigawa/kivra/selfdev/llmwiring")
    private fun sources(): List<File> {
        assertTrue(pkg.isDirectory, "run the test from the kivra-app module directory: ${pkg.absolutePath}")
        return pkg.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no source of the package calls a function that approves, rejects, cancels or changes an order or a stop request`() {
        val forbidden = Regex("\\b(approveStop|endStopRequest|autoStopChild|requestStop|expireDueStopRequests|pauseByOperator|pauseBySystem|resumeByOperator|startCycle\\w*|createRoot|createChild|updateState|StopEnd)\\b")
        val hits = sources().filter { forbidden.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the order port of the job has only the two reads`() {
        val text = File(pkg, "LlmStopJudgeJob.kt").readText()
        val port = text.substringAfter("interface StopJudgeOrders {").substringBefore("}")
        val functions = Regex("fun (\\w+)").findAll(port).map { it.groupValues[1] }.toList()
        assertEquals(listOf("stopRequest", "order"), functions)
    }

    @Test
    fun `the package has no route, no tool and no environment read`() {
        val text = sources().joinToString("\n") { it.readText() }
        assertTrue(!Regex("\\b(routing|get\\(\"/|post\\(\"/|System\\.getenv|registerTool)").containsMatchIn(text))
    }

    @Test
    fun `the job always records the shadow mode`() {
        val text = File(pkg, "LlmStopJudgeJob.kt").readText()
        assertTrue(text.contains("private val mode = JudgeMode.SHADOW"))
        assertTrue(!text.contains("DecisionOutcome.APPROVED"))
    }
}
