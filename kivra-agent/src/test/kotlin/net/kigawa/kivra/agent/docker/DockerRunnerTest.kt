package net.kigawa.kivra.agent.docker

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.slf4j.Logger as SlfLogger

class DockerRunnerTest {
    private val fixture = DockerFixture()
    private val containerName = "kivra-run-$TEST_RUN_ID-$TEST_ATTEMPT_ID"

    private fun runner(fake: FakeDocker, outputLimit: Int = 1024 * 1024) = DockerRunner(
        settings = fixture.settings,
        launcher = fake,
        nanoTime = { fake.clockNanos },
        pollMillis = 1_000,
        outputLimitBytes = outputLimit,
    )

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(SlfLogger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.context = root.loggerContext; it.start(); root.addAppender(it) }
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list
    }

    @Test
    fun successfulRunGivesExitCodeAndOnlyDockerHostInEnvironment() {
        val fake = FakeDocker().apply {
            runExitCode = 0
            runStdout = "stream-output".toByteArray()
        }
        val result = runner(fake).run(fixture.spec())
        assertEquals(DockerRunOutcome.EXITED, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals("stream-output", result.stdout)
        val run = fake.calls.first { it.getOrNull(1) == "run" }
        assertEquals("/usr/bin/docker", run[0])
        assertEquals("run", run[1])
        assertFalse(run.joinToString(" ").contains("docker.sock"))
        assertEquals(mapOf("DOCKER_HOST" to TEST_DOCKER_HOST), fake.envs.first { it.isNotEmpty() })
    }

    @Test
    fun runWithoutDockerHostPassesNoEnvironment() {
        val fake = FakeDocker()
        val settings = DockerFixture.settings(fixture.root, dockerHost = null)
        DockerRunner(settings, fake, nanoTime = { fake.clockNanos }, pollMillis = 1_000).run(fixture.spec())
        assertTrue(fake.envs.all { it.isEmpty() })
    }

    @Test
    fun timeoutKillsTheContainerByItsValidatedName() {
        val fake = FakeDocker().apply { runBlocks = true }
        val result = runner(fake).run(fixture.spec(timeout = Duration.ofSeconds(5)))
        assertEquals(DockerRunOutcome.TIMEOUT, result.outcome)
        assertEquals(listOf(containerName), fake.killedNames)
        assertTrue(fake.calls.any { it.drop(1) == listOf("kill", containerName) })
    }

    @Test
    fun cancelRequestKillsTheContainer() {
        val fake = FakeDocker().apply { runBlocks = true }
        var polls = 0
        val result = runner(fake).run(fixture.spec(timeout = Duration.ofMinutes(30))) { ++polls > 3 }
        assertEquals(DockerRunOutcome.CANCELLED, result.outcome)
        assertEquals(listOf(containerName), fake.killedNames)
    }

    @Test
    fun endlessOutputIsCutAtTheLimitAndTheContainerIsKilled() {
        val fake = FakeDocker().apply {
            runBlocks = true
            endlessStdout = true
        }
        val result = runner(fake, outputLimit = 1024).run(fixture.spec(timeout = Duration.ofMinutes(30)))
        assertEquals(DockerRunOutcome.OUTPUT_LIMIT, result.outcome)
        assertTrue(result.outputTruncated)
        assertTrue(result.stdout.length <= 1024, "stdout kept ${result.stdout.length} chars")
        assertEquals(listOf(containerName), fake.killedNames)
    }

    @Test
    fun outputOverLimitBeforeExitIsReportedAsOutputLimit() {
        val fake = FakeDocker().apply { runStdout = ByteArray(5000) { 'a'.code.toByte() } }
        val result = runner(fake, outputLimit = 1024).run(fixture.spec())
        assertEquals(DockerRunOutcome.OUTPUT_LIMIT, result.outcome)
        assertEquals(1024, result.stdout.length)
    }

    @Test
    fun orphanCleanupRemovesOnlyStaleLabelledContainers() {
        val fake = FakeDocker().apply {
            containers += FakeDocker.Container("aaaaaaaaaaaa", "old-agent", mapOf("kivra-agent" to "1", "kivra-run" to "old-run"))
            containers += FakeDocker.Container("bbbbbbbbbbbb", "active-agent", mapOf("kivra-agent" to "1", "kivra-run" to "active-run"))
            containers += FakeDocker.Container("cccccccccccc", "other-label", mapOf("kivra-run" to "old-run"))
            containers += FakeDocker.Container("dddddddddddd", "agent-zero", mapOf("kivra-agent" to "0", "kivra-run" to "old-run"))
        }
        val removed = runner(fake).cleanupOrphans(activeRunId = "active-run")
        assertEquals(1, removed)
        val removedIds = fake.calls.filter { it.getOrNull(1) == "rm" }.map { it.last() }
        assertEquals(listOf("aaaaaaaaaaaa"), removedIds)
        assertEquals(listOf("bbbbbbbbbbbb", "cccccccccccc", "dddddddddddd"), fake.containers.map { it.id })
    }

    @Test
    fun startupCleanupWithoutActiveRunRemovesEveryLabelledContainer() {
        val fake = FakeDocker().apply {
            containers += FakeDocker.Container("aaaaaaaaaaaa", "a", mapOf("kivra-agent" to "1", "kivra-run" to "x"))
            containers += FakeDocker.Container("bbbbbbbbbbbb", "b", mapOf("kivra-run" to "x"))
        }
        assertEquals(1, runner(fake).cleanupOrphans(activeRunId = null))
        assertEquals(listOf("bbbbbbbbbbbb"), fake.containers.map { it.id })
    }

    @Test
    fun cleanupIgnoresListingsThatAreNotContainerIds() {
        val fake = object : DockerLauncher {
            override fun start(argv: List<String>, env: Map<String, String>): DockerProcess {
                val inner = FakeDocker().apply {
                    containers += FakeDocker.Container("not-an-id; rm -rf /", "x", mapOf("kivra-agent" to "1"))
                }
                return inner.start(argv, env)
            }
        }
        assertEquals(0, DockerRunner(fixture.settings, fake, nanoTime = { 0L }).cleanupOrphans(null))
    }

    @Test
    fun rejectedSpecStartsNothing() {
        val fake = FakeDocker()
        val spec = fixture.spec(image = "other/image:1")
        assertFailsWith<DockerSpecException> { runner(fake).run(spec) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun logsNeverContainArgumentsEnvironmentOrOutput() {
        val fake = FakeDocker().apply {
            runExitCode = 1
            runStdout = "SECRET-OUTPUT-7731 sk-ant-api03-LEAK".toByteArray()
        }
        val events = captureLogs {
            runner(fake).run(fixture.spec())
            runner(fake).cleanupOrphans(null)
        }
        assertTrue(events.isNotEmpty(), "the runner logs its outcome")
        val forbidden = listOf(
            "SECRET-OUTPUT",
            "sk-ant",
            DockerConstants.DUMMY_API_KEY,
            TEST_PROXY,
            "docker.sock",
            "/run/user",
            fixture.workDir.toString(),
            "--env",
            "--mount",
        )
        for (event in events) {
            val text = event.formattedMessage + event.argumentArray.orEmpty().joinToString(" ") { it.toString() }
            for (value in forbidden) assertFalse(text.contains(value), "log line contains a forbidden value")
        }
    }

    @Test
    fun resultToStringShowsNoOutput() {
        val result = DockerRunResult(DockerRunOutcome.EXITED, 0, "SECRET-OUT", "SECRET-ERR", false)
        assertFalse(result.toString().contains("SECRET"))
    }
}
