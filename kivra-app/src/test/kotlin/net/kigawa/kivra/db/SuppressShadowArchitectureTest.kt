package net.kigawa.kivra.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0016 P3 (shadow): the read of the suppression shadow touches fact columns only, the rules are pure, and the new sources hold no
 * invisible or bidirectional character written literally. These tests read the sources, so a change that breaks one fails the build.
 */
class SuppressShadowArchitectureTest {

    private val mainSources: File = File("src/main/kotlin")
    private val repository = File(mainSources, "net/kigawa/kivra/db/SelfDevMemoryRepository.kt")
    private val memoryPackage = File(mainSources, "net/kigawa/kivra/selfdev/memory")

    @Test
    fun `the shadow's read block names no text column`() {
        assertTrue(repository.isFile, "run the test from the kivra-app module directory")
        val text = repository.readText()
        val begin = text.indexOf("// SHADOW-READ-BEGIN")
        val end = text.indexOf("// SHADOW-READ-END")
        assertTrue(begin >= 0 && end > begin, "the read block is marked")
        val block = text.substring(begin, end).lowercase()
        for (forbidden in listOf("body", "title", "summary", "target_paths", "task_body", "outputs", "patch")) {
            assertTrue(!block.contains(forbidden), "the shadow read block must not name '$forbidden'")
        }
        assertTrue(block.contains("identity_sha256") && block.contains("code"))
    }

    @Test
    fun `the rules are pure - no database, no transaction, no repository`() {
        val rules = File(memoryPackage, "SuppressShadowRules.kt").readText()
        assertTrue(!rules.contains("net.kigawa.kivra.db"))
        assertTrue(!rules.contains("transaction("))
        assertTrue(!rules.contains("SelfDevMemoryRepository"))
    }

    @Test
    fun `the new sources hold no invisible or bidirectional character written literally`() {
        val files = listOf(
            File(memoryPackage, "SuppressShadowRules.kt"),
            File(memoryPackage, "SuppressShadow.kt"),
            File(memoryPackage, "SuppressShadowSettings.kt"),
            File("src/main/resources/db/migration/V32__selfdev_memory_suppress_shadow.sql"),
            File("src/test/kotlin/net/kigawa/kivra/selfdev/SuppressShadowFlowTest.kt"),
            File("src/test/kotlin/net/kigawa/kivra/db/SelfDevMemorySuppressShadowRepositoryTest.kt"),
            File("src/test/kotlin/net/kigawa/kivra/db/SuppressShadowArchitectureTest.kt"),
        )
        for (file in files) {
            assertTrue(file.isFile, "${file.path} exists")
            val bad = file.readText().codePoints().toArray().filter { cp ->
                cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0xFEFF || cp in 0xE0000..0xE007F
            }
            assertEquals(emptyList(), bad, "${file.name} holds an invisible or bidi character")
        }
    }

    @Test
    fun `the shadow never touches the Planner's prompt or the plan check`() {
        val planner = File(mainSources, "net/kigawa/kivra/selfdev/AutopilotPlanner.kt").readText()
        val prompt = File(mainSources, "net/kigawa/kivra/selfdev/PlannerPrompt.kt").readText()
        assertTrue(!prompt.contains("SuppressShadow"))
        assertTrue(!prompt.contains("shadowFacts"))
        // The shadow is called only after the plan is committed, and its result is never returned.
        val call = planner.indexOf("suppress.planStored(")
        val commit = planner.indexOf("is CommitResult.Committed")
        assertTrue(call > commit && commit > 0, "the shadow is called only in the committed branch")
        assertEquals(1, Regex("suppress\\.planStored\\(").findAll(planner).count())
    }
}
