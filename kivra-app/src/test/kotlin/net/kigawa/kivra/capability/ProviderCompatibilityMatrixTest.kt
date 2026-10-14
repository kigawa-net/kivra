package net.kigawa.kivra.capability

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks that `docs/provider-compatibility-matrix.md` names every external Provider and every production adapter class.
 *
 * When a new `*Adapter` class is added under any module's `src/main`, this test fails until the class is added to
 * [KNOWN_ADAPTERS] and listed in the matrix. The same happens when an adapter is removed (the matrix would be stale).
 */
class ProviderCompatibilityMatrixTest {

    private val docRelativePath = "docs/provider-compatibility-matrix.md"

    /** Adapter classes that exist in `src/main` today. Update this list together with the matrix. */
    private val KNOWN_ADAPTERS = setOf(
        "ExkesAdapter",
        "ReclAdapter",
        "AiSchedulerAdapter",
        "FushaTaskAdapter",
        "HttpGitHubAdapter",
        "M0MemoryMutationJournalAdapter",
    )

    private val PROVIDERS = listOf("exkes", "recl", "ai-scheduler", "fusha-task", "GitHub", "Anthropic")

    private val MODULES = listOf(
        "kivra-exkes",
        "kivra-memory",
        "kivra-scheduler",
        "kivra-fusha-task",
        "kivra-github",
        "kivra-model-gateway",
    )

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
    }

    private fun matrix(): String {
        val file = File(repoRoot(), docRelativePath)
        assertTrue(file.isFile, "$docRelativePath must exist")
        return file.readText(Charsets.UTF_8)
    }

    /** Adapter class names declared in any module's `src/main` (`class XxxAdapter(`). */
    private fun adapterClassesInMain(): Set<String> {
        val declaration = Regex("""\bclass\s+(\w+Adapter)\s*\(""")
        val root = repoRoot()
        val names = mutableSetOf<String>()
        root.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", "node_modules") }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .forEach { file ->
                declaration.findAll(file.readText(Charsets.UTF_8)).forEach { names += it.groupValues[1] }
            }
        return names
    }

    @Test
    fun `matrix document exists and names every external provider`() {
        val text = matrix()
        for (provider in PROVIDERS) {
            assertTrue(text.contains(provider, ignoreCase = true), "matrix must mention provider $provider")
        }
    }

    @Test
    fun `matrix document names every adapter module`() {
        val text = matrix()
        for (module in MODULES) {
            assertTrue(text.contains(module), "matrix must mention module $module")
        }
    }

    @Test
    fun `every production adapter class in src main is listed and documented`() {
        val found = adapterClassesInMain()
        assertEquals(
            KNOWN_ADAPTERS,
            found,
            "A *Adapter class was added or removed under src/main. Update KNOWN_ADAPTERS and $docRelativePath together.",
        )
        val text = matrix()
        for (name in found) {
            assertTrue(text.contains(name), "matrix must mention adapter $name")
        }
    }
}
