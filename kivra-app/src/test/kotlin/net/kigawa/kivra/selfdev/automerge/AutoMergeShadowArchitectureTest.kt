package net.kigawa.kivra.selfdev.automerge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0014 S1 and the active addendum. The shadow judgement reads GitHub and writes the audit, and nothing else. These tests pin the public
 * surface to reads and scan the production sources of the package for any write call, so a merge, a label, a comment or a branch update
 * fails the build.
 *
 * The one exception is the automatic merge (ADR 0014 active addendum). Its squash merge is the single write of the package, and it is allowed
 * only in [ACTIVE_FILE]: that file is the only one that may name `squashMerge`, and it names it at exactly two call sites (the port's
 * production implementation and the watch's one request per tick). The exception is narrow on purpose, so that a second merge path cannot
 * appear unnoticed. Relaxing it needs a new decision in the ADR.
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
        // The shadow store's reads are the Runs, the judgement's facts, the head's AI review and the attempt counts. Its writes are the two audit rows.
        val shadowStore = setOf(
            "pullRequestRunIds", "runSnapshot", "globalSnapshot", "reviewOf", "attemptsSince", "inFlightAttempts",
            "recordJudgement", "recordPaused",
        )
        assertEquals(shadowStore, publicMethods(AutoMergeShadowStore::class.java))
        assertEquals(shadowStore, publicMethods(DbAutoMergeShadowStore::class.java))
    }

    @Test
    fun `the active watch exposes only tick and start, and its store writes only the attempts, the audit and the halt`() {
        assertEquals(setOf("tick", "start"), publicMethods(AutoMergeActiveWatch::class.java))
        val activeStore = setOf("attemptState", "claim", "finish", "recentStates", "staleClaim", "audit", "halt")
        assertEquals(activeStore, publicMethods(AutoMergeActiveStore::class.java))
        assertEquals(activeStore, publicMethods(DbAutoMergeActiveStore::class.java))
        assertEquals(setOf("squashMerge"), publicMethods(AutoMergeActivePort::class.java))
        assertEquals(setOf("squashMerge"), publicMethods(GitHubAutoMergeActivePort::class.java))
    }

    @Test
    fun `no source of the package names a GitHub write, a label, a comment or the merge call, except the active file`() {
        assertTrue(automergeSources.isDirectory, "run the test from the kivra-app module directory: ${automergeSources.absolutePath}")
        val writes = Regex(
            "\\b(updatePrBranch|commitFiles|createPullRequest|ensurePullRequest|ensureRunBranch|createBranch|mutate|" +
                "mergePullRequest|addLabel|createComment|FileWrite|HttpMethod|squashMerge)\\b|\\bmerge\\(|\"(PUT|POST|PATCH|DELETE)\"",
        )
        val hits = automergeSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != ACTIVE_FILE }
            .filter { writes.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the only merge call of the package is the active file's, at its two named sites`() {
        val active = File(automergeSources, ACTIVE_FILE)
        assertTrue(active.isFile, "the active file exists: ${active.path}")
        val text = active.readText()
        // `.squashMerge(` appears once in the port's production implementation (over PrBranchPort) and once in the watch's merge request.
        assertEquals(2, Regex("\\.squashMerge\\(").findAll(text).count(), "call sites of squashMerge in $ACTIVE_FILE")
        // Only the active file names the merge at all.
        val naming = automergeSources.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("squashMerge") }
        assertEquals(listOf(ACTIVE_FILE), naming.map { it.name }.toList())
    }

    private companion object {
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait")

        /** The only file of the package that may name the merge (see the class comment). */
        const val ACTIVE_FILE = "AutoMergeActive.kt"
    }
}
