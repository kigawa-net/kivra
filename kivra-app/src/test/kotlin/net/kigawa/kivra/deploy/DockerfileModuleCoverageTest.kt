package net.kigawa.kivra.deploy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Dockerfile copies the modules one by one, so a module that is in settings.gradle.kts but missing from the Dockerfile
 * builds locally and fails in the image. This pins the three lists to each other: the `include(...)` entries, the build-script
 * COPY lines and the source COPY lines must name exactly the same modules.
 */
class DockerfileModuleCoverageTest {
    private val moduleName = Regex("kivra-[A-Za-z0-9-]+")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
    }

    private fun includedModules(): Set<String> {
        val text = File(repoRoot(), "settings.gradle.kts").readText()
        val block = Regex("include\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?: error("include(...) block not found in settings.gradle.kts")
        return moduleName.findAll(block).map { it.value }.toSet()
    }

    private fun dockerfileLines(): List<String> = File(repoRoot(), "Dockerfile").readLines().map { it.trim() }

    @Test
    fun settingsIncludesAtLeastOneModule() {
        assertTrue(includedModules().isNotEmpty())
    }

    @Test
    fun dockerfileCopiesEveryIncludedModuleBuildScript() {
        val included = includedModules()
        val copied = dockerfileLines().mapNotNull { line ->
            Regex("^COPY (kivra-[A-Za-z0-9-]+)/build\\.gradle\\.kts (kivra-[A-Za-z0-9-]+)/build\\.gradle\\.kts$").find(line)
                ?.takeIf { it.groupValues[1] == it.groupValues[2] }?.groupValues?.get(1)
        }
        assertEquals(included.sorted(), copied.sorted(), "build-script COPY lines must match settings.gradle.kts include(...)")
    }

    @Test
    fun dockerfileCopiesEveryIncludedModuleSource() {
        val included = includedModules()
        val copied = dockerfileLines().mapNotNull { line ->
            Regex("^COPY (kivra-[A-Za-z0-9-]+)/ (kivra-[A-Za-z0-9-]+)/$").find(line)
                ?.takeIf { it.groupValues[1] == it.groupValues[2] }?.groupValues?.get(1)
        }
        assertEquals(included.sorted(), copied.sorted(), "source COPY lines must match settings.gradle.kts include(...)")
    }
}
