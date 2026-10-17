package net.kigawa.kivra.selfdev

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting

/**
 * The CLI runtime branch of the self-development runner (ADR 0010). The coding source is a fake; the rest (run store, lease, GitHub,
 * repository reader) is the same as in [SelfDevRunnerTest]. The model developer must never be called on this path.
 */
class SelfDevCodingRuntimeTest {
    private val defaultFiles = mapOf(
        "README.md" to "# kivra\n",
        "docs/a.md" to "old text\n",
        "docs/big.md" to "x".repeat(40 * 1024),
        "kivra-core/src/Foo.kt" to "fun a() = Unit\n", // outside the LLM context (README and docs only)
    )

    private class FixedCodingSource(var next: CodingProposal) : CodingPatchSource {
        var calls = 0
            private set

        override fun proposal(run: net.kigawa.kivra.db.DevelopmentRun, baseSha: String): CodingProposal {
            calls++
            return next
        }
    }

    private inner class Harness(
        val db: TestDb,
        val source: CodingPatchSource?,
        val runtime: SelfDevRuntime = SelfDevRuntime.CLI,
        val files: Map<String, String> = defaultFiles,
        val developer: FakeDeveloper = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "from the model\n")),
    ) {
        val github = FakeGitHub()

        fun runner() = SelfDevRunner(
            config = SelfDevConfig(enabled = true, runtime = runtime),
            runs = DbDevelopmentRunStore(db.runs),
            leases = db.leases,
            developer = developer,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
            clock = Clock.systemUTC(),
            coding = source,
        )

        fun newRun(): RunId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId

        fun state(runId: RunId): DevelopmentRunState = db.runs.get(runId)!!.state
    }

    private fun ready(vararg writes: Pair<String, String>) = CodingProposal.Ready(
        PatchSet(writes.map { (path, content) -> Write(path, content) }, "docs を更新する"),
    )

    @Test
    fun `the CLI runtime waits while the attempt is pending, with no model call and no GitHub write`() = runBlocking {
        TestDb().use { db ->
            val source = FixedCodingSource(CodingProposal.Pending)
            val h = Harness(db, source)
            val runner = h.runner()
            val runId = h.newRun()
            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING), runner.advance(runId))
            repeat(3) { assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), runner.advance(runId)) }
            assertEquals(3, source.calls)
            assertEquals(0, h.developer.callCount)
            assertTrue(h.github.writes.isEmpty())
            assertEquals(DevelopmentRunState.DEVELOPING, h.state(runId))
        }
    }

    @Test
    fun `a ready change set goes through the PatchPolicy and the usual commit path to one pull request`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, FixedCodingSource(ready("docs/a.md" to "new text\n")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
            assertEquals(0, h.developer.callCount)
        }
    }

    @Test
    fun `the agent may change a file outside the LLM context, because the agent saw the whole base tree`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, FixedCodingSource(ready("kivra-core/src/Foo.kt" to "fun a() = println(1)\n")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
        }
    }

    @Test
    fun `a change set the PatchPolicy refuses against the base snapshot fails the run and writes nothing`() = runBlocking {
        TestDb().use { db ->
            // docs/big.md is 40 KiB; replacing it with one byte is a LARGE_SHRINK, which only the base snapshot can see.
            val shrink = Harness(db, FixedCodingSource(ready("docs/big.md" to "x")))
            val runner = shrink.runner()
            val runId = shrink.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), runner.advance(runId))
            assertTrue(shrink.github.writes.isEmpty())
        }
        TestDb().use { db ->
            // Deleting a file that is not in the base tree is DELETE_MISSING_FILE.
            val missing = Harness(db, FixedCodingSource(CodingProposal.Ready(PatchSet(listOf(Delete("docs/missing.md")), "x"))))
            val runner = missing.runner()
            val runId = missing.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), runner.advance(runId))
            assertTrue(missing.github.writes.isEmpty())
        }
    }

    @Test
    fun `a failed attempt moves the run to the state its code names, with that code as the reason`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, FixedCodingSource(CodingProposal.Failed(DevelopmentRunState.FAILED, "CODING_TIMEOUT")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "CODING_TIMEOUT"), runner.advance(runId))
            assertTrue(h.github.writes.isEmpty())
        }
        TestDb().use { db ->
            val h = Harness(db, FixedCodingSource(CodingProposal.Failed(DevelopmentRunState.UNKNOWN, "CODING_ORPHANED")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "CODING_ORPHANED"), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), runner.advance(runId))
        }
    }

    @Test
    fun `a PATCH_READY run resumes from the stored result of the CLI runtime, not from a new model call`() = runBlocking {
        TestDb().use { db ->
            val source = FixedCodingSource(ready("docs/a.md" to "new text\n"))
            val h = Harness(db, source)
            val runner = h.runner()
            val runId = h.newRun()
            db.forceState(runId.value, DevelopmentRunState.PATCH_READY)
            assertEquals(Advanced(DevelopmentRunState.PATCH_READY, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(1, source.calls)
            assertEquals(0, h.developer.callCount)
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
        }
    }

    @Test
    fun `the CLI runtime without a coding source is refused at construction, so it cannot fall back to the model`() = TestDb().use { db ->
        val h = Harness(db, source = null)
        assertFailsWith<IllegalArgumentException> { h.runner() }
    }

    @Test
    fun `the default API runtime still asks the model and never touches the coding source`() = runBlocking {
        TestDb().use { db ->
            assertEquals(SelfDevRuntime.API, SelfDevConfig().runtime)
            val source = FixedCodingSource(CodingProposal.Pending)
            val h = Harness(db, source, runtime = SelfDevRuntime.API)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(1, h.developer.callCount)
            assertEquals(0, source.calls)
        }
    }
}
