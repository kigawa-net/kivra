package net.kigawa.kivra.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0016 section 3.1: the record and event tables are written in one place only, and the write goes through the validator.
 * These tests read the production sources, so a new write path (an INSERT, an UPDATE or a DELETE in another file, or a second
 * INSERT in the repository) fails the build.
 */
class SelfDevMemoryArchitectureTest {

    private val mainSources: File = File("src/main/kotlin")
    private val repositoryPath = "net/kigawa/kivra/db/SelfDevMemoryRepository.kt"

    private fun sourceFiles(): List<File> = mainSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.relative(): String = relativeTo(mainSources).invariantSeparatorsPath

    @Test
    fun `only the repository file names the memory tables`() {
        assertTrue(mainSources.isDirectory, "run the test from the kivra-app module directory: ${mainSources.absolutePath}")
        val mentioning = sourceFiles().filter { it.readText().contains("selfdev_memory_") }.map { it.relative() }
        assertEquals(listOf(repositoryPath), mentioning)
    }

    @Test
    fun `there is exactly one INSERT into each memory table, and it is in the repository`() {
        val text = File(mainSources, repositoryPath).readText()
        assertEquals(1, Regex("INSERT INTO selfdev_memory_records").findAll(text).count())
        assertEquals(1, Regex("INSERT INTO selfdev_memory_events").findAll(text).count())
        // The record insert is reached only from save, which runs the validator first.
        assertEquals(2, Regex("insertRecord\\(").findAll(text).count(), "one definition and one call")
        assertEquals(1, Regex("\\n\\s+insertRecord\\(id, candidate").findAll(text).count())
    }

    @Test
    fun `the only INSERT, UPDATE and DELETE on the memory tables are in the repository`() {
        val writes = Regex("(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+selfdev_memory_")
        val hits = sourceFiles().filter { writes.containsMatchIn(it.readText()) }.map { it.relative() }
        assertEquals(listOf(repositoryPath), hits)
    }

    @Test
    fun `the write helpers are private, and the public surface has no raw write`() {
        val methods = SelfDevMemoryRepository::class.java.declaredMethods
        for (name in listOf("insertRecord", "insertEvent", "removeRecords")) {
            val helpers = methods.filter { it.name == name }
            assertTrue(helpers.isNotEmpty(), "$name exists")
            assertTrue(helpers.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) }, "$name is private")
        }
        // Kotlin default-argument bridges ($default) are not public API.
        val public = SelfDevMemoryRepository::class.java.methods.map { it.name }.filter { it !in OBJECT_METHODS && !it.contains('$') }.toSet()
        // count and auditSummary are reads for the operator's list (ADR 0016 P2); neither writes, and the prefix check below still applies.
        assertEquals(setOf("save", "search", "count", "auditSummary", "events", "deleteOne", "deleteByCondition", "purgeRetention"), public)
        assertTrue(public.none { it.startsWith("insert") || it.startsWith("update") || it.startsWith("raw") || it.startsWith("write") })
    }

    private companion object {
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait")
    }
}
