package net.kigawa.kivra.agent.docker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The guard is the last check before a container starts (ADR 0010 invariant 2). Each forbidden item is tested on its own, with the
 * exact rule it must produce. The mutation check (see the report) removes one explicit rule and expects a failure here.
 */
class DockerArgsGuardTest {
    private val fixture = DockerFixture()
    private val guard = DockerArgsGuard(fixture.settings)
    private val golden = fixture.golden()
    private val workMount = "--mount=type=bind,source=${fixture.workDir.toRealPath()},target=/work"

    private fun rule(args: List<String>): GuardRule {
        val e = assertFailsWith<DockerArgsRejectedException> { guard.check(args) }
        return e.rule
    }

    @Test
    fun goldenArgumentsPass() {
        guard.check(golden)
    }

    @Test
    fun forbiddenItemsAreRefusedWithTheirOwnRule() {
        val cases: List<Pair<String, Pair<List<String>, GuardRule>>> = listOf(
            "--privileged" to (golden.withExtra("--privileged") to GuardRule.FORBIDDEN_OPTION),
            "--cap-add=SYS_ADMIN" to (golden.withExtra("--cap-add=SYS_ADMIN") to GuardRule.FORBIDDEN_OPTION),
            "--cap-add=ALL" to (golden.withExtra("--cap-add=ALL") to GuardRule.FORBIDDEN_OPTION),
            "--cap-add=NET_ADMIN" to (golden.withExtra("--cap-add=NET_ADMIN") to GuardRule.FORBIDDEN_OPTION),
            "--device=/dev/fuse" to (golden.withExtra("--device=/dev/fuse") to GuardRule.FORBIDDEN_OPTION),
            "--pid=host" to (golden.withExtra("--pid=host") to GuardRule.FORBIDDEN_OPTION),
            "--ipc=host" to (golden.withExtra("--ipc=host") to GuardRule.FORBIDDEN_OPTION),
            "--uts=host" to (golden.withExtra("--uts=host") to GuardRule.FORBIDDEN_OPTION),
            "--net=host" to (golden.withExtra("--net=host") to GuardRule.FORBIDDEN_OPTION),
            "--userns=host" to (golden.withExtra("--userns=host") to GuardRule.FORBIDDEN_OPTION),
            "--cgroupns=host" to (golden.withExtra("--cgroupns=host") to GuardRule.FORBIDDEN_OPTION),
            "--cgroup-parent=/x" to (golden.withExtra("--cgroup-parent=/x") to GuardRule.FORBIDDEN_OPTION),
            "--add-host=x:1.2.3.4" to (golden.withExtra("--add-host=x:1.2.3.4") to GuardRule.FORBIDDEN_OPTION),
            "--dns=8.8.8.8" to (golden.withExtra("--dns=8.8.8.8") to GuardRule.FORBIDDEN_OPTION),
            "--entrypoint=/bin/sh" to (golden.withExtra("--entrypoint=/bin/sh") to GuardRule.FORBIDDEN_OPTION),
            "--gpus=all" to (golden.withExtra("--gpus=all") to GuardRule.FORBIDDEN_OPTION),
            "--volume=docker.sock" to (
                golden.withExtra("--volume=/var/run/docker.sock:/var/run/docker.sock") to GuardRule.FORBIDDEN_OPTION
                ),
            "-v (short)" to (golden.withExtra("-v", "/var/run/docker.sock:/var/run/docker.sock") to GuardRule.FORBIDDEN_OPTION),
            "--volumes-from" to (golden.withExtra("--volumes-from=other") to GuardRule.FORBIDDEN_OPTION),
            "--env-file" to (golden.withExtra("--env-file=/home/kigawa/.env") to GuardRule.FORBIDDEN_OPTION),
            "-e NAME (short env)" to (golden.withExtra("-e", "GITHUB_TOKEN") to GuardRule.FORBIDDEN_OPTION),
            "--network=host" to (golden.replacing("--network=", "--network=host") to GuardRule.FORBIDDEN_VALUE),
            "--network=none" to (golden.replacing("--network=", "--network=none") to GuardRule.FORBIDDEN_VALUE),
            "--network=bridge" to (golden.replacing("--network=", "--network=bridge") to GuardRule.FORBIDDEN_VALUE),
            "--network=container:x" to (
                golden.replacing("--network=", "--network=container:other") to GuardRule.FORBIDDEN_VALUE
                ),
            "--security-opt seccomp" to (
                golden.replacing("--security-opt=", "--security-opt=seccomp=unconfined") to GuardRule.FORBIDDEN_VALUE
                ),
            "--security-opt apparmor" to (
                golden.replacing("--security-opt=", "--security-opt=apparmor=unconfined") to GuardRule.FORBIDDEN_VALUE
                ),
            "--security-opt no-new-privileges=false" to (
                golden.replacing("--security-opt=", "--security-opt=no-new-privileges=false") to GuardRule.FORBIDDEN_VALUE
                ),
            "--cap-drop=NET_RAW" to (golden.replacing("--cap-drop=", "--cap-drop=NET_RAW") to GuardRule.FORBIDDEN_VALUE),
            "--user=0:0" to (golden.replacing("--user=", "--user=0:0") to GuardRule.FORBIDDEN_VALUE),
            "--user=root" to (golden.replacing("--user=", "--user=root") to GuardRule.FORBIDDEN_VALUE),
            "--user=0" to (golden.replacing("--user=", "--user=0") to GuardRule.FORBIDDEN_VALUE),
            "--user=0:10001" to (golden.replacing("--user=", "--user=0:10001") to GuardRule.FORBIDDEN_VALUE),
            "--user=10001:0" to (golden.replacing("--user=", "--user=10001:0") to GuardRule.FORBIDDEN_VALUE),
            "--workdir=/" to (golden.replacing("--workdir=", "--workdir=/") to GuardRule.FORBIDDEN_VALUE),
            "--workdir=/home/agent" to (golden.replacing("--workdir=", "--workdir=/home/agent") to GuardRule.FORBIDDEN_VALUE),
            "--tmpfs=/" to (golden.withExtra("--tmpfs=/:rw") to GuardRule.FORBIDDEN_VALUE),
            "--label owner" to (golden.withExtra("--label=owner=me") to GuardRule.FORBIDDEN_VALUE),
            "--init" to (golden.withExtra("--init") to GuardRule.UNKNOWN_OPTION),
            "unknown --foo" to (golden.withExtra("--foo=bar") to GuardRule.UNKNOWN_OPTION),
            "--sig-proxy" to (golden.withExtra("--sig-proxy=false") to GuardRule.UNKNOWN_OPTION),
            "trailing command" to (golden + listOf("/bin/sh", "-c", "id") to GuardRule.TRAILING_ARGUMENT),
            "image not allowed" to (golden.dropLast(1) + "evil/image:latest" to GuardRule.FORBIDDEN_VALUE),
            "space inside name value" to (
                golden.replacing("--name=", "--name=kivra-run-$TEST_RUN_ID-att-1 --privileged") to GuardRule.FORBIDDEN_VALUE
                ),
            "name with shell" to (golden.replacing("--name=", "--name=kivra-run-a;rm-b") to GuardRule.FORBIDDEN_VALUE),
            "label run mismatch" to (
                golden.replacing("--label=kivra-run=", "--label=kivra-run=other-run") to GuardRule.FORBIDDEN_VALUE
                ),
            "memory-swap differs" to (golden.replacing("--memory-swap=", "--memory-swap=-1") to GuardRule.FORBIDDEN_VALUE),
            "pids-limit zero" to (golden.replacing("--pids-limit=", "--pids-limit=0") to GuardRule.FORBIDDEN_VALUE),
            "space form of an option" to (golden.replacing("--cpus=", "--cpus", "4") to GuardRule.TRAILING_ARGUMENT),
        )
        val failures = mutableListOf<String>()
        for ((label, c) in cases) {
            val (args, expected) = c
            val actual = try {
                guard.check(args)
                null
            } catch (e: DockerArgsRejectedException) {
                e.rule
            }
            if (actual != expected) failures += "$label: expected $expected, got $actual"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun workMountMustBeTheRunWorkDirectoryOnly() {
        val outside = listOf(
            "/var/run/docker.sock",
            "/run",
            "/run/user/1001",
            "/etc",
            "/etc/kivra-agent",
            "/proc",
            "/sys",
            "/dev",
            "/home/kigawa",
            "/home/kigawa/.ssh",
            "/home/kigawa/.claude",
            "/home/kigawa/.config",
            "/home/kigawa/dev",
        )
        for (source in outside) {
            val mount = "--mount=type=bind,source=$source,target=/work"
            assertEquals(GuardRule.PATH_REJECTED, rule(golden.replacing("--mount=", mount)), "source $source")
        }
        val rootItself = "--mount=type=bind,source=${fixture.root},target=/work"
        assertEquals(GuardRule.PATH_REJECTED, rule(golden.replacing("--mount=", rootItself)), "the work root itself")
        val wrongTarget = "--mount=type=bind,source=${fixture.workDir},target=/etc"
        assertEquals(GuardRule.FORBIDDEN_VALUE, rule(golden.replacing("--mount=", wrongTarget)), "wrong target")
        val readonly = "$workMount,readonly"
        assertEquals(GuardRule.FORBIDDEN_VALUE, rule(golden.replacing("--mount=", readonly)), "extra mount option")
        val docker = "--mount=type=bind,source=/var/run/docker.sock,target=/work"
        assertEquals(GuardRule.PATH_REJECTED, rule(golden.replacing("--mount=", docker)))
    }

    @Test
    fun mountWithCommaOrColonInPathIsRefused() {
        val base = fixture.workDir.toRealPath().toString()
        assertEquals(GuardRule.PATH_REJECTED, rule(golden.replacing("--mount=", "--mount=type=bind,source=$base,x,target=/work")))
        assertEquals(GuardRule.PATH_REJECTED, rule(golden.replacing("--mount=", "--mount=type=bind,source=$base:/etc,target=/work")))
    }

    @Test
    fun environmentOutsideTheAllowlistAndSecretsAreRefused() {
        val cases = listOf(
            "--env=GITHUB_TOKEN=ghp_example",
            "--env=GH_TOKEN=gho_example",
            "--env=BWS_ACCESS_TOKEN=bws_example",
            "--env=SSH_AUTH_SOCK=/tmp/agent.sock",
            "--env=KEYCLOAK_CLIENT_SECRET=example",
            "--env=CLIENT_SECRET=example",
            "--env=HOME=/home/kigawa",
            "--env=PATH=/evil",
            "--env=JAVA_TOOL_OPTIONS=-javaagent:/x.jar",
            "--env=GITHUB_TOKEN",
            "--env=ANTHROPIC_API_KEY=sk-ant-api03-EXAMPLE",
            "--env=ANTHROPIC_AUTH_TOKEN=example",
        )
        for (entry in cases) {
            assertEquals(GuardRule.ENV_REJECTED, rule(golden.withExtra(entry)), "added $entry")
        }
        val replacedKey = golden.replacing("--env=ANTHROPIC_API_KEY=", "--env=ANTHROPIC_API_KEY=sk-ant-api03-EXAMPLE")
        assertEquals(GuardRule.ENV_REJECTED, rule(replacedKey), "real key in place of the dummy")
        val otherDummy = golden.replacing("--env=ANTHROPIC_API_KEY=", "--env=ANTHROPIC_API_KEY=not-the-dummy")
        assertEquals(GuardRule.ENV_REJECTED, rule(otherDummy), "a value that is not the dummy")
        val baseUrl = golden.replacing("--env=ANTHROPIC_BASE_URL=", "--env=ANTHROPIC_BASE_URL=https://api.anthropic.com/v1")
        assertEquals(GuardRule.ENV_REJECTED, rule(baseUrl), "base URL that bypasses the proxy")
        val proxy = golden.replacing("--env=HTTPS_PROXY=", "--env=HTTPS_PROXY=http://evil.example:3128")
        assertEquals(GuardRule.ENV_REJECTED, rule(proxy), "proxy outside the internal host form")
    }

    @Test
    fun environmentValueWithSecretPrefixAnywhereIsRefused() {
        val leaking = golden.replacing("--env=NO_PROXY=", "--env=NO_PROXY=localhost,sk-ant-api03-EXAMPLE")
        assertEquals(GuardRule.ENV_REJECTED, rule(leaking))
    }

    @Test
    fun missingRequiredOptionIsRefused() {
        for (index in 1 until golden.size - 1) {
            val without = golden.filterIndexed { i, _ -> i != index }
            assertEquals(GuardRule.MISSING_OPTION, rule(without), "without ${golden[index]}")
        }
    }

    @Test
    fun repeatedSingleOptionIsRefused() {
        assertEquals(GuardRule.DUPLICATE_OPTION, rule(golden.withExtra("--rm")))
        assertEquals(GuardRule.DUPLICATE_OPTION, rule(golden.withExtra("--network=$TEST_NETWORK")))
        assertEquals(GuardRule.DUPLICATE_OPTION, rule(golden.withExtra("--memory=8g")))
        assertEquals(GuardRule.DUPLICATE_OPTION, rule(golden.withExtra(workMount)))
        assertEquals(GuardRule.DUPLICATE_OPTION, rule(golden.withExtra("--label=kivra-agent=1")))
    }

    @Test
    fun optionsMustUseTheEqualsFormAndFlagsTakeNoValue() {
        assertEquals(GuardRule.MALFORMED, rule(golden.replacing("--rm", "--rm=true")))
        assertEquals(GuardRule.MALFORMED, rule(golden.replacing("--rm", "--rm=false")))
        assertEquals(GuardRule.MALFORMED, rule(golden.replacing("--cpus=", "--cpus")))
    }

    @Test
    fun argumentListWithoutRunOrImageIsRefused() {
        assertEquals(GuardRule.MALFORMED, rule(emptyList()))
        assertEquals(GuardRule.MALFORMED, rule(golden.drop(1).let { listOf("ps") + it.dropLast(1) }))
        assertEquals(GuardRule.MALFORMED, rule(golden.dropLast(1)))
    }

    @Test
    fun refusalMessageNeverEchoesAValue() {
        val secret = "sk-ant-api03-MUST-NOT-APPEAR"
        val e = assertFailsWith<DockerArgsRejectedException> {
            guard.check(golden.withExtra("--env=ANTHROPIC_API_KEY=$secret"))
        }
        assertFalse(e.message.orEmpty().contains(secret))
        assertFalse(e.message.orEmpty().contains("sk-ant"))
    }

    @Test
    fun workDirectoryThatIsASymlinkOutOfTheRootIsRefused() {
        val outside: Path = Files.createTempDirectory("kivra-docker-outside").toRealPath()
        val link = fixture.root.resolve("link")
        Files.createSymbolicLink(link, outside)
        val mount = "--mount=type=bind,source=${link.resolve("x")},target=/work"
        Files.createDirectories(outside.resolve("x"))
        assertEquals(GuardRule.PATH_REJECTED, rule(golden.replacing("--mount=", mount)))
    }
}
