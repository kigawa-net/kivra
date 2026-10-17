package net.kigawa.kivra.agent.docker

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.claude.AgentRunOutcome
import net.kigawa.kivra.agent.claude.CliOutcomeClassifier
import net.kigawa.kivra.agent.claude.StreamJsonParser
import org.slf4j.LoggerFactory
import org.slf4j.Logger as SlfLogger

/**
 * End to end with the fake docker: the task text reaches the work directory and nowhere else, the output is not logged, and a timeout
 * is classified. No real docker or CLI is used.
 */
class CliRunIsolationTest {
    private val fixture = DockerFixture()
    private val marker = "TASK-MARKER-5c0e --privileged -v /:/host SECRET-IN-TASK"
    private val body = "Please change the parser. $marker ; rm -rf / \$(id)"

    private fun runner(fake: FakeDocker) = DockerRunner(
        settings = fixture.settings,
        launcher = fake,
        nanoTime = { fake.clockNanos },
        pollMillis = 1_000,
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
    fun taskTextIsInTheWorkDirectoryOnlyNotInArgumentsOrEnvironment() {
        val path = TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "title $marker", body, truncated = false)
        assertTrue(Files.readString(path).contains("TASK-MARKER-5c0e"), "the task file has the text")

        val fake = FakeDocker().apply {
            runExitCode = 0
            runStdout = """{"type":"result","subtype":"success","is_error":false}""".plus("\n").toByteArray()
        }
        runner(fake).run(fixture.spec())

        val everything = fake.calls.flatten() + fake.envs.flatMap { it.entries.flatMap { e -> listOf(e.key, e.value) } }
        for (token in everything) {
            assertFalse(token.contains("TASK-MARKER"), "task marker in a token")
            assertFalse(token.contains("SECRET-IN-TASK"), "task secret in a token")
            assertFalse(token.contains("rm -rf"), "task text in a token")
        }
        assertFalse(fake.calls.flatten().any { it == "--privileged" || it == "-v" })
        // The fixed prompt is the only text the command carries besides the tokens of its shape.
        assertTrue(fake.calls.flatten().contains(CliCommand.PROMPT))
    }

    @Test
    fun javaToolOptionsIsInTheRunArgumentsInTheFixedForm() {
        val fake = FakeDocker()
        runner(fake).run(fixture.spec())
        val run = fake.calls.first { it.getOrNull(1) == "run" }
        assertTrue(
            run.contains(
                "--env=JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=kivra-agent-proxy -Dhttp.proxyPort=8080 " +
                    "-Dhttps.proxyHost=kivra-agent-proxy -Dhttps.proxyPort=8080",
            ),
        )
        // The fake docker's own environment carries only DOCKER_HOST.
        assertTrue(fake.envs.all { it.keys.all { k -> k == "DOCKER_HOST" } })
    }

    @Test
    fun successfulRunWithResultIsSucceeded() {
        val fake = FakeDocker().apply {
            runExitCode = 0
            runStdout = """{"type":"result","subtype":"success","is_error":false}""".plus("\n").toByteArray()
        }
        val run = runner(fake).run(fixture.spec())
        assertEquals(AgentRunOutcome.SUCCEEDED, CliOutcomeClassifier.classify(run, StreamJsonParser().parse(run.stdout)))
    }

    @Test
    fun timeoutIsClassifiedAsTimeout() {
        val fake = FakeDocker().apply { runBlocks = true }
        val run = runner(fake).run(fixture.spec(timeout = Duration.ofSeconds(2)))
        assertEquals(DockerRunOutcome.TIMEOUT, run.outcome)
        assertEquals(AgentRunOutcome.TIMEOUT, CliOutcomeClassifier.classify(run, StreamJsonParser().parse(run.stdout)))
    }

    @Test
    fun outputOfAFailedRunIsNeverLogged() {
        val secret = "SECRET-OUTPUT-PAYLOAD-9f3a"
        val output = listOf(
            """{"type":"system","subtype":"init","session_id":"$secret"}""",
            "not json $secret",
            """{"type":"result","subtype":"success","is_error":true,"result":"$secret"}""",
        ).joinToString("\n", postfix = "\n")
        val fake = FakeDocker().apply {
            runExitCode = 1
            runStdout = output.toByteArray()
        }
        val logs = captureLogs {
            val run = runner(fake).run(fixture.spec())
            val report = StreamJsonParser().parse(run.stdout)
            val outcome = CliOutcomeClassifier.classify(run, report)
            assertEquals(AgentRunOutcome.MALFORMED_OUTPUT, outcome, "an invalid line makes the output malformed")
            assertFalse(run.toString().contains(secret))
            assertFalse(report.toString().contains(secret))
        }
        val text = logs.joinToString("\n") { it.formattedMessage + " " + it.throwableProxy?.message.orEmpty() }
        assertFalse(text.contains(secret), "logs must not carry the output")
        assertFalse(text.contains("--privileged"))
        assertTrue(logs.isNotEmpty(), "the outcome is still logged by code")
    }
}
