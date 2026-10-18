package net.kigawa.kivra.selfdev

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.coding.ChangeKind
import net.kigawa.kivra.coding.CodingChange
import net.kigawa.kivra.coding.CodingClaimOutcome
import net.kigawa.kivra.coding.CodingErrorCode
import net.kigawa.kivra.coding.CodingInput
import net.kigawa.kivra.coding.CodingResult
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.UnmeteredCodingCost
import net.kigawa.kivra.coding.CodingSubmitOutcome
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.RejectReason
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The CLI runtime end to end, with the real coding service, the database result store and the attempt repository. Only the agent
 * (calls made by the test as the agent would make them), GitHub and the model are fakes. The model developer must never be called.
 */
class SelfDevCodingEndToEndTest {
    private val files = mapOf(
        "README.md" to "# kivra\n",
        "docs/a.md" to "old text\n",
        "kivra-core/src/Foo.kt" to "fun a() = Unit\n",
    )

    private inner class Harness(val db: TestDb) {
        val github = FakeGitHub()
        val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "from the model\n"))
        val service = CodingRuntimeService(
            attempts = CodingAttemptRepository(db.database),
            runs = DbCodingRunPort(db.runs, db.leases),
            results = DbCodingResultStore(db.database),
            tasks = CodingTaskSource { it.taskTitle },
            cost = UnmeteredCodingCost,
        )
        val runner = SelfDevRunner(
            config = SelfDevConfig(enabled = true, runtime = SelfDevRuntime.CLI),
            runs = DbDevelopmentRunStore(db.runs),
            leases = db.leases,
            developer = developer,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
            clock = Clock.systemUTC(),
            coding = CodingAttemptPatchSource(service),
        )

        fun newRun(): RunId {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId
            exec("UPDATE development_runs SET task_title = ? WHERE run_id = ?", "README を直す", runId.value)
            return runId
        }

        fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

        fun state(runId: RunId): DevelopmentRunState = db.runs.get(runId)!!.state

        /** Advances the Run to DEVELOPING and then queues its attempt (the second call enqueues it). */
        suspend fun queued(runId: RunId) {
            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), runner.advance(runId))
        }

        /** The agent claims the queued attempt of this Run. */
        fun claim(): CodingInput = assertIs<CodingClaimOutcome.Claimed>(service.claim(AGENT)).input

        fun submit(input: CodingInput, changes: List<CodingChange>, summary: String = "docs を更新する"): CodingSubmitOutcome =
            service.submitResult(input.attemptId, AGENT, input.leaseGeneration, CodingResult(summary, changes, 0, "session-1"))
    }

    private fun write(path: String, content: String) = CodingChange(path, ChangeKind.WRITE, content)

    @Test
    fun `a good result from the agent reaches one pull request through the PatchPolicy and the usual commit path`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.newRun()
            h.queued(runId)
            val input = h.claim()
            assertIs<CodingSubmitOutcome.Accepted>(h.submit(input, listOf(write("docs/a.md", "new text\n"))))

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), h.runner.advance(runId))
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
            assertEquals(0, h.developer.callCount)
        }
    }

    @Test
    fun `a result the PatchPolicy refuses is stored nowhere, the attempt stays claimed, and a corrected result goes through`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.newRun()
            h.queued(runId)
            val input = h.claim()

            // A path under .github/ is on the denylist. The service refuses it before anything is stored.
            val refused = h.submit(input, listOf(write(".github/workflows/x.yml", "name: x\n")))
            assertIs<CodingSubmitOutcome.Rejected>(refused)
            assertEquals(listOf(RejectReason.PATH_DENIED), refused.policyReasons)
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), h.runner.advance(runId))
            assertTrue(h.github.writes.isEmpty())

            assertIs<CodingSubmitOutcome.Accepted>(h.submit(input, listOf(write("docs/a.md", "new text\n"))))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), h.runner.advance(runId))
        }
    }

    @Test
    fun `an empty result is refused as EMPTY_PATCH, and the attempt stays claimed without any GitHub write`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.newRun()
            h.queued(runId)
            val input = h.claim()

            val refused = h.submit(input, emptyList())
            assertIs<CodingSubmitOutcome.Rejected>(refused)
            assertEquals(listOf(RejectReason.EMPTY_PATCH), refused.policyReasons)
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), h.runner.advance(runId))
            assertTrue(h.github.writes.isEmpty())
            assertEquals(DevelopmentRunState.DEVELOPING, h.state(runId))
        }
    }

    @Test
    fun `an attempt whose queue time ran out holds its Run as UNKNOWN for an operator, with no GitHub write`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.newRun()
            h.queued(runId)
            h.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE run_id = ?", runId.value)

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "CODING_QUEUE_EXPIRED"), h.runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), h.runner.advance(runId))
            assertEquals("CODING_QUEUE_EXPIRED", db.runs.get(runId)!!.stateReason)
            assertTrue(h.github.writes.isEmpty())
        }
    }

    @Test
    fun `an attempt whose lease ran out holds its Run as UNKNOWN for an operator, and nothing is written`() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.newRun()
            h.queued(runId)
            val input = h.claim()
            h.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", input.attemptId)

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "CODING_ORPHANED"), h.runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), h.runner.advance(runId))
            assertEquals("CODING_ORPHANED", db.runs.get(runId)!!.stateReason)
            // The orphaned attempt is not retried: the agent may still be running it.
            assertTrue(h.github.writes.isEmpty())
        }
    }

    /** What the Run does for each fixed agent failure code (ADR 0010, design 15 section 6.6). */
    @Test
    fun `each agent failure code moves the Run to the state its code names, with that code as the reason`() = runBlocking {
        val expected = mapOf(
            CodingErrorCode.AUTH_REQUIRED to Pair(DevelopmentRunState.UNKNOWN, "CODING_AUTH_REQUIRED"),
            CodingErrorCode.NEEDS_HUMAN to Pair(DevelopmentRunState.UNKNOWN, "CODING_NEEDS_HUMAN"),
            CodingErrorCode.RATE_LIMITED to Pair(DevelopmentRunState.FAILED, "CODING_RATE_LIMITED"),
            CodingErrorCode.TIMEOUT to Pair(DevelopmentRunState.FAILED, "CODING_TIMEOUT"),
            CodingErrorCode.OUTPUT_LIMIT to Pair(DevelopmentRunState.FAILED, "CODING_OUTPUT_LIMIT"),
            CodingErrorCode.CLI_NONZERO to Pair(DevelopmentRunState.FAILED, "CODING_CLI_NONZERO"),
            CodingErrorCode.BAD_OUTPUT to Pair(DevelopmentRunState.FAILED, "CODING_BAD_OUTPUT"),
            CodingErrorCode.CANCELLED to Pair(DevelopmentRunState.CANCELLED, "CODING_CANCELLED"),
            CodingErrorCode.EMPTY_PATCH to Pair(DevelopmentRunState.FAILED, "CODING_EMPTY_PATCH"),
            CodingErrorCode.BUDGET_EXCEEDED to Pair(DevelopmentRunState.FAILED, "CODING_BUDGET_EXCEEDED"),
            CodingErrorCode.OVERLOADED to Pair(DevelopmentRunState.FAILED, "CODING_OVERLOADED"),
        )
        assertEquals(CodingErrorCode.entries.toSet(), expected.keys, "every agent error code is covered")
        for ((code, want) in expected) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.newRun()
                h.queued(runId)
                val input = h.claim()
                assertIs<CodingAttemptResult.Ok>(h.service.fail(input.attemptId, AGENT, input.leaseGeneration, code))
                assertEquals(Advanced(DevelopmentRunState.DEVELOPING, want.first, want.second), h.runner.advance(runId), code.name)
                assertTrue(h.github.writes.isEmpty(), code.name)
            }
        }
    }

    private companion object {
        const val AGENT = "agent-1"
    }
}
