package net.kigawa.kivra.agent.docker

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.backend.RunOutcome
import net.kigawa.kivra.agent.backend.RunSpec

/**
 * The docker implementation of the run backend must give the same results as [DockerRunner] used directly (ADR 0010 追補 7, PR 2).
 * Each pair runs the same spec against its own fake docker, and compares the outcome and the docker arguments.
 */
class DockerRunBackendTest {
    private val fixture = DockerFixture()

    private fun runSpec(maxTurns: Int = CliCommand.DEFAULT_MAX_TURNS, budgetUsd: String = CliCommand.DEFAULT_BUDGET_USD, runId: String = TEST_RUN_ID) =
        RunSpec(
            runId = runId,
            attemptId = TEST_ATTEMPT_ID,
            image = TEST_IMAGE,
            workDir = fixture.workDir,
            network = TEST_NETWORK,
            proxyUrl = TEST_PROXY,
            timeout = Duration.ofMinutes(30),
            maxTurns = maxTurns,
            model = CliCommand.DEFAULT_MODEL,
            budgetUsd = budgetUsd,
            outputLimitBytes = 1024 * 1024,
        )

    private fun directRunner(fake: FakeDocker) = DockerRunner(
        settings = fixture.settings,
        launcher = fake,
        nanoTime = { fake.clockNanos },
        pollMillis = 1_000,
        outputLimitBytes = 1024 * 1024,
    )

    private fun backend(fake: FakeDocker) = DockerRunBackend(fixture.settings, launcher = fake, pollMillis = 1_000)

    @Test
    fun aFinishedRunGivesTheSameResultAndArgumentsAsTheRunner() {
        val direct = FakeDocker().apply { runExitCode = 0; runStdout = "stream-output".toByteArray() }
        val viaBackend = FakeDocker().apply { runExitCode = 0; runStdout = "stream-output".toByteArray() }

        val a = directRunner(direct).run(fixture.spec())
        val b = backend(viaBackend).run(runSpec())

        assertEquals(a.outcome, b.outcome)
        assertEquals(a.exitCode, b.exitCode)
        assertEquals(a.stdout, b.stdout)
        assertEquals(a.stderr, b.stderr)
        assertEquals(a.outputTruncated, b.outputTruncated)
        assertEquals(direct.calls, viaBackend.calls)
        assertEquals(direct.envs, viaBackend.envs)
    }

    @Test
    fun aCancelledRunGivesTheSameOutcomeAsTheRunner() {
        val direct = FakeDocker().apply { runBlocks = true }
        val viaBackend = FakeDocker().apply { runBlocks = true }

        val a = directRunner(direct).run(fixture.spec(), cancelRequested = { true })
        val b = backend(viaBackend).run(runSpec(), cancelRequested = { true })

        assertEquals(RunOutcome.CANCELLED, b.outcome)
        assertEquals(a.outcome, b.outcome)
        assertEquals(a.exitCode, b.exitCode)
        assertEquals(direct.calls, viaBackend.calls)
        assertEquals(direct.killedNames, viaBackend.killedNames)
    }

    @Test
    fun theSpecFieldsReachTheDockerArgumentsAsTheSpecDescribes() {
        val direct = FakeDocker().apply { runExitCode = 0 }
        val viaBackend = FakeDocker().apply { runExitCode = 0 }
        val explicit = DockerRunSpec(
            runId = TEST_RUN_ID,
            attemptId = TEST_ATTEMPT_ID,
            image = TEST_IMAGE,
            workDir = fixture.workDir,
            network = TEST_NETWORK,
            proxyUrl = TEST_PROXY,
            timeout = Duration.ofMinutes(30),
            maxTurns = 7,
            budgetUsd = "1.25",
        )
        directRunner(direct).run(explicit)
        backend(viaBackend).run(runSpec(maxTurns = 7, budgetUsd = "1.25"))

        assertEquals(direct.calls, viaBackend.calls)
        assertTrue(viaBackend.calls.first { it.getOrNull(1) == "run" }.contains("1.25"))
    }

    @Test
    fun cleanupOrphansGivesTheSameResultAsTheRunner() {
        fun withOrphan() = FakeDocker().apply {
            containers += FakeDocker.Container("abcdef012345", "kivra-run-old", mapOf("kivra-agent" to "1", "kivra-run" to "old-run"))
            containers += FakeDocker.Container("fedcba543210", "other", mapOf("other" to "1", "kivra-run" to "old-run"))
        }
        val direct = withOrphan()
        val viaBackend = withOrphan()

        val a = directRunner(direct).cleanupOrphans(TEST_RUN_ID)
        val b = backend(viaBackend).cleanupOrphans(TEST_RUN_ID)

        assertEquals(1, a)
        assertEquals(a, b)
        assertEquals(direct.calls, viaBackend.calls)
        assertEquals(direct.containers.map { it.id }, viaBackend.containers.map { it.id })
    }

    @Test
    fun aRefusedSpecThrowsBeforeAnyDockerCommand() {
        val fake = FakeDocker()
        val bad = runSpec(runId = "not a valid id!")

        assertFailsWith<DockerSpecException> { backend(fake).validate(bad) }
        assertFailsWith<DockerSpecException> { backend(fake).run(bad) }
        assertTrue(fake.calls.isEmpty(), "nothing may be started for a refused spec")
    }
}
