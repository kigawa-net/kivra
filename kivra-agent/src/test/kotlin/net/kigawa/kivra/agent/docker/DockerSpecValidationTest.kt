package net.kigawa.kivra.agent.docker

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

/** Injection strings for every value that reaches the docker command line, and the work-directory path rules. */
class DockerSpecValidationTest {
    private val fixture = DockerFixture()
    private val hostile = listOf(
        "a;b",
        "\$(id)",
        "a b",
        "a\nb",
        "--privileged",
        "a:b",
        "a,b",
        "a=b",
        "a/b",
        "a.b",
        "",
        "x".repeat(65),
    )

    @Test
    fun runIdAndAttemptIdAcceptOnlyTheIdShape() {
        for (value in hostile) {
            assertFailsWith<DockerSpecException>("runId $value") { fixture.spec(runId = value) }
            assertFailsWith<DockerSpecException>("attemptId $value") { fixture.spec(attemptId = value) }
        }
        fixture.spec(runId = "01M545K02Q6YB4M5ZHAM5792CS", attemptId = "att-1")
    }

    @Test
    fun imageNetworkAndProxyRejectInjection() {
        val images = listOf("img;rm", "\$(id)", " img", "img\n", "--privileged", "Upper/case", "img tag", "img:a:b")
        for (image in images) assertFailsWith<DockerSpecException>("image $image") { fixture.spec(image = image) }
        val networks = listOf("kivra net", "host", "none", "container:x", "bridge", "default", "net,x", "-x", "a\nb")
        for (network in networks) assertFailsWith<DockerSpecException>("network $network") { fixture.spec(network = network) }
        val proxies = listOf(
            "http://h:8080/x",
            "https://h:8080",
            "http://h:8080\n",
            "http://h:8080@evil",
            "http://a b:8080",
            "http://h.example:8080",
            "h:8080",
            "http://-h:8080",
        )
        for (proxy in proxies) assertFailsWith<DockerSpecException>("proxy $proxy") { fixture.spec(proxy = proxy) }
        fixture.spec(image = "kivra-agent-runtime:local", network = "kivra-agent-internal", proxy = "http://kivra-agent-proxy:8080")
    }

    @Test
    fun resourceLimitsAreChecked() {
        assertFailsWith<DockerSpecException> { fixture.spec(timeout = Duration.ZERO) }
        assertFailsWith<DockerSpecException> { fixture.spec(timeout = Duration.ofHours(25)) }
        for (memory in listOf("6g; rm", "0g", "6x", "")) {
            assertFailsWith<DockerSpecException>("memory $memory") { DockerRunSpec("r1", "a1", "img", fixture.workDir, "net", "http://p:1", Duration.ofMinutes(1), memory = memory) }
        }
        for (cpus in listOf("0", "0.0", "abc", "4 --privileged")) {
            assertFailsWith<DockerSpecException>("cpus $cpus") { DockerRunSpec("r1", "a1", "img", fixture.workDir, "net", "http://p:1", Duration.ofMinutes(1), cpus = cpus) }
        }
        assertFailsWith<DockerSpecException> { DockerRunSpec("r1", "a1", "img", fixture.workDir, "net", "http://p:1", Duration.ofMinutes(1), pidsLimit = 0) }
        assertFailsWith<DockerSpecException> { DockerRunSpec("r1", "a1", "img", fixture.workDir, "net", "http://p:1", Duration.ofMinutes(1), pidsLimit = 5000) }
        assertFailsWith<DockerSpecException> { DockerRunSpec("r1", "a1", "img", fixture.workDir, "net", "http://p:1", Duration.ofMinutes(1), stopTimeoutSeconds = 121) }
    }

    @Test
    fun containerNameIsDerivedFromRunAndAttempt() {
        assertEquals("kivra-run-run-1-att-1", DockerRunSpec("run-1", "att-1", "img", fixture.workDir, "net", "http://p:1", Duration.ofMinutes(1)).containerName)
    }

    @Test
    fun workDirMustBeAnAbsoluteCleanPathWithoutSeparators() {
        val bad = listOf(
            Path.of("relative/work"),
            Path.of(fixture.root.toString(), "..", "escape"),
            Path.of(fixture.root.toString(), "a:b"),
            Path.of(fixture.root.toString(), "a,b"),
            Path.of(fixture.root.toString(), "a\nb"),
            Path.of(fixture.root.toString(), "a\\b"),
        )
        for (path in bad) assertFailsWith<DockerSpecException>("workDir $path") { fixture.spec(workDir = path) }
    }

    @Test
    fun workDirOutsideTheRootOrEqualToItIsRefused() {
        val outside = Files.createTempDirectory("kivra-docker-other").toRealPath()
        assertFailsWith<DockerSpecException> { DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = outside)) }
        assertFailsWith<DockerSpecException> { DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = fixture.root)) }
        assertFailsWith<DockerSpecException> {
            DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = fixture.root.resolve("missing")))
        }
    }

    @Test
    fun workDirThatIsAFileIsRefused() {
        val file = Files.createFile(fixture.root.resolve("plain-file"))
        assertFailsWith<DockerSpecException> { DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = file)) }
    }

    @Test
    fun symlinkAsLeafIsRefused() {
        val target = Files.createDirectories(fixture.root.resolve("real-target"))
        val leaf = fixture.root.resolve("leaf-link")
        Files.createSymbolicLink(leaf, target)
        assertFailsWith<DockerSpecException> { DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = leaf)) }
    }

    @Test
    fun symlinkInsideTheRootToAnotherPartOfItIsRefused() {
        val real = Files.createDirectories(fixture.root.resolve("real").resolve("work"))
        val link = fixture.root.resolve("alias")
        Files.createSymbolicLink(link, fixture.root.resolve("real"))
        assertFailsWith<DockerSpecException> { DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = link.resolve("work"))) }
        DockerCommandBuilder(fixture.settings).build(fixture.spec(workDir = real))
    }

    @Test
    fun settingsRejectBroadOrReservedValues() {
        val home = Path.of(System.getProperty("user.home")).toRealPath()
        val roots = listOf(Path.of("/"), Path.of("/home"), Path.of("/etc"), Path.of("/proc"), Path.of("relative"), home, home.parent)
        for (root in roots) {
            assertFailsWith<DockerSpecException>("workRoot $root") { DockerFixture.settings(root) }
        }
        val real = fixture.root
        for (networks in listOf(setOf("host"), setOf("none"), setOf("bridge"), setOf("container:x"), emptySet<String>())) {
            assertFailsWith<DockerSpecException>("networks $networks") {
                DockerSettings(Path.of("/usr/bin/docker"), null, real, networks, setOf(TEST_IMAGE))
            }
        }
        assertFailsWith<DockerSpecException> {
            DockerSettings(Path.of("/usr/bin/docker"), null, real, setOf(TEST_NETWORK), setOf("img;rm"))
        }
        assertFailsWith<DockerSpecException> { DockerSettings(Path.of("docker"), null, real, setOf(TEST_NETWORK), setOf(TEST_IMAGE)) }
        for (host in listOf("tcp://127.0.0.1:2375", "unix:///run/../etc/x", "unix:///a b")) {
            assertFailsWith<DockerSpecException>("dockerHost $host") { DockerFixture.settings(real, host) }
        }
    }

    @Test
    fun settingsToStringShowsNoValue() {
        val text = fixture.settings.toString()
        assertEquals(false, text.contains(fixture.root.toString()))
        assertEquals(false, text.contains("docker.sock"))
    }
}
