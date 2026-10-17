package net.kigawa.kivra.agent.docker

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.AgentLimits
import net.kigawa.kivra.agent.AgentTask
import net.kigawa.kivra.agent.ClaimedAttempt
import net.kigawa.kivra.agent.TRUST_UNTRUSTED

class DockerCommandBuilderTest {
    private val fixture = DockerFixture()

    @Test
    fun builtArgumentsMatchTheFixedAllowlistShape() {
        val workDir = fixture.workDir.toRealPath()
        val expected = listOf(
            "run",
            "--rm",
            "--name=kivra-run-$TEST_RUN_ID-$TEST_ATTEMPT_ID",
            "--user=10001:10001",
            "--read-only",
            "--tmpfs=/tmp:rw,nosuid,size=512m",
            "--tmpfs=/home/agent:rw,nosuid,mode=0700,uid=10001,gid=10001,size=2g",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--pids-limit=512",
            "--memory=6g",
            "--memory-swap=6g",
            "--cpus=4",
            "--network=$TEST_NETWORK",
            "--mount=type=bind,source=$workDir,target=/work",
            "--workdir=/work",
            "--label=kivra-agent=1",
            "--label=kivra-run=$TEST_RUN_ID",
            "--stop-timeout=10",
            "--env=ANTHROPIC_BASE_URL=$TEST_PROXY/anthropic",
            "--env=ANTHROPIC_API_KEY=kivra-agent-dummy-not-a-credential",
            "--env=HTTPS_PROXY=$TEST_PROXY",
            "--env=HTTP_PROXY=$TEST_PROXY",
            "--env=NO_PROXY=localhost,127.0.0.1",
            "--env=JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=kivra-agent-proxy -Dhttp.proxyPort=8080 " +
                "-Dhttps.proxyHost=kivra-agent-proxy -Dhttps.proxyPort=8080",
            TEST_IMAGE,
            "claude",
            "-p",
            CliCommand.PROMPT,
            "--output-format",
            "stream-json",
            "--verbose",
            "--permission-mode",
            "dontAsk",
            "--no-session-persistence",
            "--max-turns",
            "40",
            "--allowedTools",
            "Read",
            "Edit",
            "Write",
            "Glob",
            "Grep",
            "Bash",
        )
        assertEquals(expected, fixture.golden())
    }

    @Test
    fun dockerSocketAndHostPathsNeverAppearInArguments() {
        val args = fixture.golden().joinToString("\n")
        assertFalse(args.contains("docker.sock"), "docker.sock in arguments")
        assertFalse(args.contains("/run/user"), "rootless socket directory in arguments")
        assertFalse(args.contains("/var/run"), "/var/run in arguments")
        assertFalse(args.contains("source=/home"), "a home directory is mounted")
        assertFalse(args.contains("--privileged"))
    }

    @Test
    fun taskTextFromAClaimedAttemptNeverReachesArguments() {
        val marker = "TASK-MARKER-9d1f --privileged -v /:/host"
        val attempt = ClaimedAttempt(
            runId = TEST_RUN_ID,
            attemptId = TEST_ATTEMPT_ID,
            generation = 1,
            baseSha = "0123456789abcdef0123456789abcdef01234567",
            task = AgentTask("title", marker, TRUST_UNTRUSTED, truncated = false),
            limits = AgentLimits(1800, 30, 1_048_576, 20, 200_000, 1_000_000, 5),
            allowedPathPatterns = listOf("src/**"),
            deniedPathPatterns = listOf(".github/**"),
        )
        val spec = DockerRunSpec(
            attempt.runId,
            attempt.attemptId,
            TEST_IMAGE,
            fixture.workDir,
            TEST_NETWORK,
            TEST_PROXY,
            Duration.ofMinutes(30),
        )
        val args = DockerCommandBuilder(fixture.settings).build(spec).joinToString("\n")
        assertFalse(args.contains("TASK-MARKER"), "task text in arguments")
        assertFalse(args.contains("/:/host"))
    }

    @Test
    fun environmentNamesAreExactlyTheAllowlistAndTheApiKeyIsADummy() {
        val env = fixture.golden().filter { it.startsWith("--env=") }.map { it.removePrefix("--env=") }
        assertEquals(DockerConstants.ENV_NAMES, env.map { it.substringBefore('=') }.toSet())
        assertEquals(env.size, DockerConstants.ENV_NAMES.size)
        assertTrue(env.contains("ANTHROPIC_API_KEY=kivra-agent-dummy-not-a-credential"))
        assertFalse(env.any { it.contains("sk-ant-") })
    }

    @Test
    fun imageOrNetworkOutsideTheSettingsIsRefused() {
        val builder = DockerCommandBuilder(fixture.settings)
        assertFailsWith<DockerSpecException> { builder.build(fixture.spec(image = "other/image:1")) }
        val otherNetwork = fixture.spec(network = "other-internal")
        assertFailsWith<DockerSpecException> { builder.build(otherNetwork) }
    }

    @Test
    fun workDirOutsideTheRootIsRefusedByTheBuilder() {
        val outside = Files.createTempDirectory("kivra-docker-outside").toRealPath()
        assertFailsWith<DockerSpecException> { DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = outside)) }
    }

    @Test
    fun specWithReservedNetworkCannotBeCreated() {
        for (network in listOf("host", "none", "bridge", "container:other")) {
            assertFailsWith<DockerSpecException>("network $network") { fixture.spec(network = network) }
        }
    }
}
