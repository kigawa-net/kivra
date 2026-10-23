package net.kigawa.kivra.selfdev.automerge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0014 S1: the shadow judgement reads GitHub and writes the audit, and nothing else. These tests pin the public surface to reads and
 * scan the production sources of the package for any write call, so a merge, a label, a comment or a branch update fails the build.
 */
class AutoMergeShadowArchitectureTest {

    private val automergeSources = File("src/main/kotlin/net/kigawa/kivra/selfdev/automerge")

    private val reads = setOf("pullRequest", "pullRequestFiles", "tree", "checkRuns", "branchHead")

    private fun publicMethods(type: Class<*>): Set<String> = type.methods.map { it.name.substringBefore("-") }
        .filter { it !in OBJECT_METHODS && !it.contains('$') }
        .toSet()

    @Test
    fun `the port interface has only reads`() {
        assertEquals(reads, publicMethods(AutoMergeShadowPort::class.java))
    }

    @Test
    fun `the production reader exposes only the reads`() {
        assertEquals(reads, publicMethods(GitHubAutoMergeShadowPort::class.java))
    }

    @Test
    fun `the watch exposes only tick and start, and the store's only writes are the judgement and the paused records`() {
        assertEquals(setOf("tick", "start"), publicMethods(AutoMergeShadowWatch::class.java))
        assertEquals(
            setOf("pullRequestRunIds", "runSnapshot", "globalSnapshot", "recordJudgement", "recordPaused"),
            publicMethods(AutoMergeShadowStore::class.java),
        )
        assertEquals(
            setOf("pullRequestRunIds", "runSnapshot", "globalSnapshot", "recordJudgement", "recordPaused"),
            publicMethods(DbAutoMergeShadowStore::class.java),
        )
    }

    @Test
    fun `no source of the package names a GitHub write, a label, a comment or the merge call`() {
        assertTrue(automergeSources.isDirectory, "run the test from the kivra-app module directory: ${automergeSources.absolutePath}")
        val writes = Regex(
            "\\b(updatePrBranch|commitFiles|createPullRequest|ensurePullRequest|ensureRunBranch|createBranch|mutate|" +
                "mergePullRequest|addLabel|createComment|FileWrite|HttpMethod)\\b|\\bmerge\\(|\"(PUT|POST|PATCH|DELETE)\"",
        )
        val hits = automergeSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { writes.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), hits)
    }

    private companion object {
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait")
    }
}
