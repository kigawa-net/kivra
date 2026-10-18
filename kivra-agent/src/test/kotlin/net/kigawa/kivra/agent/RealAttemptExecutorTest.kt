package net.kigawa.kivra.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.agent.docker.DockerLauncher
import net.kigawa.kivra.agent.docker.DockerProcess
import net.kigawa.kivra.agent.workdir.DiffRejectReason
import net.kigawa.kivra.agent.workdir.GIT_BIN
import net.kigawa.kivra.agent.workdir.LocalPath
import net.kigawa.kivra.agent.workdir.LocalRepo
import net.kigawa.kivra.agent.workdir.makeRepo
import org.slf4j.LoggerFactory

private const val RUN = "01M545K02Q6YB4M5ZHAM5792CS"
private const val ATTEMPT = "att-1"
private const val NETWORK = "kivra-agent-internal"
private const val IMAGE = "kivra-agent-runtime:local"
private const val PROXY = "http://kivra-agent-proxy:8080"
private const val TITLE = "TASKSECRET-title-text"
private const val SUMMARY = "SUMMARYSECRET done"
private const val CONTENT = "CONTENTSECRET fun a() = 2"
private const val CHANGED = "kivra-agent/src/Hygiene.kt"

/** A fake docker CLI. `run` calls [onRun] with the host work directory (the mount source) and then writes [output] and ends with [exitCode]. */
private class ScriptedDocker(
    private val output: () -> String = { "" },
    private val onRun: (Path) -> Unit = {},
    private val blocks: Boolean = false,
    private val exitCode: Int = 0,
) : DockerLauncher {
    val argvs: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val envs: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var killed = false

    override fun start(argv: List<String>, env: Map<String, String>): DockerProcess {
        argvs += argv
        envs += env
        return when (argv[1]) {
            "run" -> {
                onRun(mountSource(argv))
                Run(output().toByteArray())
            }
            "kill" -> {
                killed = true
                Immediate(0)
            }
            "ps" -> Immediate(0)
            else -> Immediate(0)
        }
    }

    private fun mountSource(argv: List<String>): Path {
        val mount = argv.first { it.startsWith("--mount=") }
        return Path.of(mount.split(',').first { it.startsWith("source=") }.removePrefix("source="))
    }

    private inner class Run(bytes: ByteArray) : DockerProcess {
        override val stdout: InputStream = ByteArrayInputStream(bytes)
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(millis: Long): Int? {
            if (killed) return 137
            if (blocks) {
                Thread.sleep(minOf(millis, 20L))
                return null
            }
            return exitCode
        }

        override fun destroy() {
            killed = true
        }
    }

    private class Immediate(private val code: Int) : DockerProcess {
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(millis: Long): Int? = code
        override fun destroy() {}
    }
}

private fun resultLine(summary: String = SUMMARY): String =
    """{"type":"result","subtype":"success","is_error":false,"num_turns":3,"result":"$summary","session_id":"sess-42"}"""

private fun lines(vararg items: String): String = items.joinToString("\n", postfix = "\n")

/** One test world: a work root, a local repository as the base, a scripted docker, and a fake kivra. */
private class World(
    val root: Path = Files.createTempDirectory("kivra-real-exec").toRealPath(),
    val repo: LocalRepo = makeRepo(mapOf("kivra-agent/src/Main.kt" to "fun a() = 1\n", "kivra-agent/src/Hygiene.kt" to "fun h() = 1\n")),
    val docker: ScriptedDocker,
    handler: (Recorded) -> Canned = { defaultReply(it) },
) {
    val recorded: MutableList<Recorded> = synchronizedList()
    private var claims = 0
    val client = KivraAgentClient(
        fakeKivraHttp(recorded, handler = { rec ->
            if (rec.path.endsWith("/claim")) {
                synchronized(this) { claims++ }
                if (claims == 1) Canned(200, claimBody(repo.sha)) else Canned(204)
            } else {
                handler(rec)
            }
        }),
        KIVRA_BASE,
        FixedTokens(),
    )
    val stopFile: Path = root.resolve(AgentConfig.DEFAULT_STOP_FILE_NAME)

    fun executor(debugKeep: Boolean = false, newErrorCodes: Boolean = false, monitorEvents: Boolean = false): RealAttemptExecutor = RealAttemptExecutor(
        client = client,
        workRoot = root,
        stopFile = stopFile,
        settings = RealExecutorSettings(
            dockerBinary = Path.of("/usr/bin/docker"),
            dockerHost = null,
            network = NETWORK,
            image = IMAGE,
            proxyUrl = PROXY,
            gitBinary = GIT_BIN,
            repo = LocalPath(repo.repo),
            debugKeep = debugKeep,
            newErrorCodes = newErrorCodes,
            monitorEvents = monitorEvents,
        ),
        launcher = docker,
        pollMillis = 20,
    )

    fun attempt(
        baseSha: String = repo.sha,
        maxRuntimeSeconds: Long = 600,
        maxOutputBytes: Long = 1_048_576,
        body: String = "",
        bodyTruncated: Boolean = false,
        allowed: List<String> = listOf("kivra-*/src/**"),
        denied: List<String> = listOf(".github/**"),
    ) = ClaimedAttempt(
        runId = RUN,
        attemptId = ATTEMPT,
        generation = 3,
        baseSha = baseSha,
        task = AgentTask("task_title", TITLE, TRUST_UNTRUSTED, false, body, bodyTruncated),
        limits = AgentLimits(
            maxRuntimeSeconds = maxRuntimeSeconds,
            maxTurns = 30,
            maxOutputBytes = maxOutputBytes,
            maxChangedFiles = 20,
            maxFileBytes = 200_000,
            maxTotalBytes = 1_000_000,
            maxDeletes = 5,
        ),
        allowedPathPatterns = allowed,
        deniedPathPatterns = denied,
    )

    fun paths(suffix: String) = recorded.count { it.path.endsWith(suffix) }

    /** No run directory and no manifest is left in the work root. */
    fun assertNoRunLeft() {
        Files.list(root).use { files ->
            assertTrue(files.toList().none { it.fileName.toString().startsWith(RUN) }, "a run directory was left behind")
        }
    }

    private fun claimBody(sha: String): String = """{"attemptId":"$ATTEMPT","runId":"$RUN","generation":3,"input":{""" +
        """"baseSha":"$sha","task":{"origin":"task_title","value":"$TITLE","trust":"UNTRUSTED","truncated":false},""" +
        """"limits":{"maxRuntimeSeconds":600,"maxTurns":30,"maxOutputBytes":1048576,"maxChangedFiles":20,""" +
        """"maxFileBytes":200000,"maxTotalBytes":1000000,"maxDeletes":5},""" +
        """"allowedPathPatterns":["kivra-*/src/**"],"deniedPathPatterns":[".github/**"]}}"""

    companion object {
        fun defaultReply(rec: Recorded): Canned = when {
            rec.path.endsWith("/heartbeat") -> Canned(200, STATE_RUNNING)
            rec.path.endsWith("/status") -> Canned(200, STATUS_OK)
            rec.path.endsWith("/result") -> Canned(200, """{"attemptId":"att-1","state":"RESULT_RECEIVED"}""")
            rec.path.endsWith("/fail") -> Canned(200, """{"attemptId":"att-1","state":"FAILED"}""")
            else -> Canned(404)
        }
    }
}

class RealAttemptExecutorTest {
    @Test
    fun successSendsTheChangeSetAndRemovesTheWorkDirectory() = runBlocking {
        val world = World(docker = ScriptedDocker(
            output = { lines(resultLine("Changed a.")) },
            onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") },
        ))
        val outcome = world.executor().execute(world.attempt())

        assertEquals(AttemptOutcome.ResultSubmitted, outcome)
        val result = world.recorded.single { it.path.endsWith("/result") }
        assertTrue(result.body.startsWith("""{"generation":3,"result":{"summary":"Changed a.","exitStatus":0,"sessionId":"sess-42""""))
        assertTrue(result.body.contains(""""path":"kivra-agent/src/Main.kt","kind":"WRITE","fileKind":"REGULAR","content":"fun a() = 2\n""""))
        assertEquals(0, world.paths("/fail"), "a result that was sent must not be followed by a fail")
        world.assertNoRunLeft()

        val run = world.docker.argvs.single { it[1] == "run" }
        assertTrue("--network=$NETWORK" in run)
        assertFalse(run.any { it.startsWith("--privileged") })
        assertEquals(1, run.count { it.startsWith("--mount=") }, "only the work directory is mounted")
        assertTrue(run.any { it.startsWith("--mount=type=bind,source=") && it.endsWith(",target=/work") })
    }

    @Test
    fun anEmptyChangeSetIsAFailureNotASuccess() = runBlocking {
        val world = World(docker = ScriptedDocker(output = { lines(resultLine("Nothing to do.")) }))
        val outcome = world.executor().execute(world.attempt())

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.CLI_NONZERO), outcome)
        assertEquals(0, world.paths("/result"))
        assertEquals(0, world.paths("/fail"), "the loop reports the fail; the executor must not send it")
        world.assertNoRunLeft()
    }

    @Test
    fun cliOutcomesAreMappedToTheirCodes() = runBlocking {
        val cases = listOf(
            Triple("no result, exit 1", "", 1) to AgentErrorCode.CLI_NONZERO,
            Triple("authentication", lines("""{"type":"system","subtype":"api_retry","error":"authentication_failed"}"""), 1) to AgentErrorCode.AUTH_REQUIRED,
            Triple("rate limit", lines("""{"type":"system","subtype":"api_retry","error":"rate_limit"}"""), 1) to AgentErrorCode.RATE_LIMITED,
            Triple("overloaded", lines("""{"type":"system","subtype":"api_retry","error":"overloaded"}"""), 1) to AgentErrorCode.RATE_LIMITED,
            Triple("is_error result", lines("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":"x"}"""), 1) to AgentErrorCode.CLI_NONZERO,
            Triple("invalid line before result", lines("not json", resultLine()), 0) to AgentErrorCode.BAD_OUTPUT,
            Triple("cut stream, exit 0", lines("""{"type":"assistant"}""") + "{\"type\":\"resu", 0) to AgentErrorCode.BAD_OUTPUT,
        )
        for ((case, expected) in cases) {
            val (name, output, exit) = case
            val world = World(docker = ScriptedDocker(output = { output }, exitCode = exit))
            assertEquals(AttemptOutcome.Failed(expected), world.executor().execute(world.attempt()), name)
            assertEquals(0, world.paths("/result"), name)
            world.assertNoRunLeft()
        }
    }

    @Test
    fun timeoutKillsTheContainerAndFailsWithTimeout() = runBlocking {
        val world = World(docker = ScriptedDocker(blocks = true))
        val outcome = world.executor().execute(world.attempt(maxRuntimeSeconds = 1))

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.TIMEOUT), outcome)
        assertTrue(world.docker.argvs.any { it[1] == "kill" })
        world.assertNoRunLeft()
    }

    @Test
    fun stopFileKillsTheContainerAndFailsWithCancelled() = runBlocking {
        val world = World(docker = ScriptedDocker(blocks = true))
        Files.createFile(world.stopFile)
        val outcome = world.executor().execute(world.attempt())

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.CANCELLED), outcome)
        assertTrue(world.docker.killed)
        world.assertNoRunLeft()
    }

    @Test
    fun cancelRequestedFromKivraStopsTheRunAndReportsCancelled() = runBlocking {
        val world = World(
            docker = ScriptedDocker(blocks = true),
            handler = { rec ->
                if (rec.path.endsWith("/status")) Canned(200, STATUS_CANCEL) else World.defaultReply(rec)
            },
        )
        val config = AgentConfig.fromEnv(testConfigEnv(world.root, world.root.resolve("secret").writeSecret("s"), heartbeat = 5_000))
        val loop = AgentLoop(config, world.client, world.executor())
        val job = launch { loop.run() }
        withTimeout(30_000) { while (world.paths("/fail") == 0) delay(50) }
        job.cancelAndJoin()

        val fail = world.recorded.single { it.path.endsWith("/fail") }
        assertEquals("""{"generation":3,"errorCode":"CANCELLED"}""", fail.body)
        assertEquals(0, world.paths("/result"))
        assertTrue(world.docker.killed)
        world.assertNoRunLeft()
    }

    @Test
    fun staleLeaseOrMissingAttemptOnResultDiscardsTheResultWithoutAFail() = runBlocking {
        for (status in listOf(409, 404)) {
            val world = World(
                docker = ScriptedDocker(output = { lines(resultLine()) }, onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") }),
                handler = { rec -> if (rec.path.endsWith("/result")) Canned(status, """{"error":"conflict"}""") else World.defaultReply(rec) },
            )
            val config = AgentConfig.fromEnv(testConfigEnv(world.root, world.root.resolve("secret").writeSecret("s"), poll = 1_000))
            val loop = AgentLoop(config, world.client, world.executor())
            val job = launch { loop.run() }
            withTimeout(30_000) { while (world.paths("/result") == 0) delay(20) }
            delay(300)
            job.cancelAndJoin()

            assertEquals(1, world.paths("/result"), "status $status: the result is sent once")
            assertEquals(0, world.paths("/fail"), "status $status: a stale attempt must not get a fail")
            world.assertNoRunLeft()
        }
    }

    @Test
    fun resultRefusedByKivraIsReportedAsBadOutput() = runBlocking {
        val world = World(
            docker = ScriptedDocker(output = { lines(resultLine()) }, onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") }),
            handler = { rec -> if (rec.path.endsWith("/result")) Canned(422, """{"error":"rejected","reasons":["POLICY_REJECTED"],"policyReasons":[]}""") else World.defaultReply(rec) },
        )
        val outcome = world.executor().execute(world.attempt())

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.BAD_OUTPUT), outcome)
        world.assertNoRunLeft()
    }

    @Test
    fun aSecretLikeSummaryIsRefusedAndNothingIsSent() = runBlocking {
        val secret = "ghp_" + "a".repeat(36)
        val world = World(docker = ScriptedDocker(
            output = { lines(resultLine(secret)) },
            onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") },
        ))
        val outcome = world.executor().execute(world.attempt())

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.BAD_OUTPUT), outcome)
        assertEquals(0, world.paths("/result"))
        assertEquals(0, world.paths("/heartbeat"), "nothing is sent for a refused change set")
        world.assertNoRunLeft()
    }

    @Test
    fun rejectionCodeIsCliFailedOnlyForAnEmptyPatch() {
        assertEquals(AgentErrorCode.CLI_NONZERO, RealAttemptExecutor.rejectionCode(listOf(DiffRejectReason.EMPTY_PATCH)))
        assertEquals(
            AgentErrorCode.BAD_OUTPUT,
            RealAttemptExecutor.rejectionCode(listOf(DiffRejectReason.EMPTY_PATCH, DiffRejectReason.PATH_DENIED)),
        )
        assertEquals(AgentErrorCode.BAD_OUTPUT, RealAttemptExecutor.rejectionCode(listOf(DiffRejectReason.SECRET_LIKE_CONTENT)))
    }

    @Test
    fun rejectionCodeUsesEmptyPatchOnlyWithTheNewCodes() {
        assertEquals(
            AgentErrorCode.EMPTY_PATCH,
            RealAttemptExecutor.rejectionCode(listOf(DiffRejectReason.EMPTY_PATCH), newErrorCodes = true),
        )
        assertEquals(
            AgentErrorCode.CLI_NONZERO,
            RealAttemptExecutor.rejectionCode(listOf(DiffRejectReason.EMPTY_PATCH), newErrorCodes = false),
        )
        assertEquals(
            AgentErrorCode.BAD_OUTPUT,
            RealAttemptExecutor.rejectionCode(listOf(DiffRejectReason.EMPTY_PATCH, DiffRejectReason.PATH_DENIED), newErrorCodes = true),
        )
    }

    @Test
    fun theServerDenylistIsCheckedBeforeAnythingIsSent() = runBlocking {
        // Hygiene.kt is allowed by the built-in rules and by the claim's allowlist, but the claim denies it.
        val world = World(docker = ScriptedDocker(
            output = { lines(resultLine()) },
            onRun = { dir -> Files.writeString(dir.resolve(CHANGED), "fun h() = 2\n") },
        ))
        val outcome = world.executor().execute(world.attempt(denied = listOf("**/Hygiene.kt")))

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.BAD_OUTPUT), outcome)
        assertEquals(0, world.paths("/result"), "a change the claim denies is never sent")
        world.assertNoRunLeft()
    }

    @Test
    fun invalidClaimPatternsFailAsNeedsHumanBeforeTheCliRuns() = runBlocking {
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.agent") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val marker = "PATTERNMARKER\u0001"
            val world = World(docker = ScriptedDocker(output = { lines(resultLine()) }))
            val attempts = listOf(
                world.attempt(allowed = List(101) { "docs/$it/**" }),
                world.attempt(denied = listOf("docs/$marker/**")),
            )
            for (attempt in attempts) {
                assertEquals(AttemptOutcome.Failed(AgentErrorCode.NEEDS_HUMAN), world.executor().execute(attempt))
            }
            assertEquals(emptyList(), world.docker.argvs.filter { it[1] == "run" }, "the CLI is not started")
            assertEquals(0, world.paths("/heartbeat"))
            val text = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(text.contains("claim path patterns refused"), "the refusal is logged")
            assertFalse(text.contains("PATTERNMARKER"), "a pattern is never logged")
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun theNewCodesAreSentOnlyWhenTheGateIsOn() = runBlocking {
        fun emptyPatch() = World(docker = ScriptedDocker(output = { lines(usageLine()) }))
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO",$usageObject}""", failBodyFromLoop(emptyPatch()))
        assertEquals("""{"generation":3,"errorCode":"EMPTY_PATCH",$usageObject}""", failBodyFromLoop(emptyPatch(), newErrorCodes = true))

        fun budget() = World(docker = ScriptedDocker(output = { lines(usageLine(subtype = "error_max_budget_usd", isError = true)) }, exitCode = 1))
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO",$usageObject}""", failBodyFromLoop(budget()))
        assertEquals("""{"generation":3,"errorCode":"BUDGET_EXCEEDED",$usageObject}""", failBodyFromLoop(budget(), newErrorCodes = true))

        fun overloaded() = World(docker = ScriptedDocker(
            output = { lines("""{"type":"system","subtype":"api_retry","error":"overloaded"}""") },
            exitCode = 1,
        ))
        assertEquals("""{"generation":3,"errorCode":"RATE_LIMITED"}""", failBodyFromLoop(overloaded()))
        assertEquals("""{"generation":3,"errorCode":"OVERLOADED"}""", failBodyFromLoop(overloaded(), newErrorCodes = true))
    }

    @Test
    fun aServerErrorOnTheLeaseCheckRemovesTheWorkDirectoryAndPropagates() = runBlocking {
        val world = World(
            docker = ScriptedDocker(output = { lines(resultLine()) }, onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") }),
            handler = { rec -> if (rec.path.endsWith("/heartbeat")) Canned(500, "") else World.defaultReply(rec) },
        )
        val error = assertFailsWith<AgentApiException> { world.executor().execute(world.attempt()) }

        assertEquals(AgentApiErrorKind.SERVER, error.kind)
        assertEquals(0, world.paths("/result"))
        world.assertNoRunLeft()
    }

    @Test
    fun aFailedPreparationLeavesNoWorkDirectory() = runBlocking {
        val world = World(docker = ScriptedDocker())
        assertFailsWith<Exception> { world.executor().execute(world.attempt(baseSha = "f".repeat(40))) }

        assertEquals(0, world.docker.argvs.count { it[1] == "run" }, "no container runs without a base")
        world.assertNoRunLeft()
    }

    @Test
    fun logsCarryIdsAndCodesOnlyNeverTheTaskPathContentSummaryOrToken() = runBlocking {
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.agent") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val world = World(docker = ScriptedDocker(
                output = { lines(resultLine(SUMMARY)) },
                onRun = { dir -> Files.writeString(dir.resolve(CHANGED), CONTENT + "\n") },
            ))
            // Any run of the same worker, with a refusal, to exercise the warn paths too.
            world.executor().execute(world.attempt())
            val refused = World(docker = ScriptedDocker(output = { lines(resultLine("ghp_" + "b".repeat(36))) }, onRun = { dir -> Files.writeString(dir.resolve(CHANGED), CONTENT + "\n") }))
            refused.executor().execute(refused.attempt())

            val text = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(text.isNotEmpty(), "the executor logs its outcome")
            assertTrue(text.contains("numTurns=3"), "the log carries the turn count")
            assertTrue(text.contains("permissionDenials="), "the log carries the denial count field")
            for (forbidden in listOf(TITLE, SUMMARY, CONTENT, CHANGED, "kivra-agent/", "tok-", "ghp_", "/tmp", world.root.toString())) {
                assertFalse(text.contains(forbidden), "log must not contain: $forbidden")
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    private fun debugEntries(world: World): List<String> {
        val debug = world.root.resolve("debug")
        if (!Files.isDirectory(debug)) return emptyList()
        return Files.list(debug).use { s -> s.map { it.fileName.toString() }.toList() }
    }

    @Test
    fun debugKeepIsOffByDefault() = runBlocking {
        val world = World(docker = ScriptedDocker(output = { lines(resultLine("Nothing to do.")) }))
        world.executor().execute(world.attempt())

        assertEquals(emptyList(), debugEntries(world))
        world.assertNoRunLeft()
    }

    @Test
    fun debugKeepStoresTheStreamTheTaskAndTheOutcomeOfAnEmptyPatch() = runBlocking {
        val world = World(docker = ScriptedDocker(output = { lines(resultLine("Nothing to do.")) }))
        val outcome = world.executor(debugKeep = true).execute(world.attempt())

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.CLI_NONZERO), outcome)
        val dir = world.root.resolve("debug").resolve("$RUN-$ATTEMPT")
        assertEquals("code=CLI_NONZERO\nreasons=EMPTY_PATCH\n", Files.readString(dir.resolve("outcome.txt")))
        assertTrue(Files.readString(dir.resolve("stream.jsonl")).contains("Nothing to do."))
        assertTrue(Files.readString(dir.resolve("task.json")).contains(TITLE), "the task file shows the title that was passed")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        for (name in listOf("outcome.txt", "stream.jsonl", "task.json")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(name))), name)
        }
        world.assertNoRunLeft()
    }

    @Test
    fun debugKeepStoresAFailureWithoutAStreamWithoutReasons() = runBlocking {
        val world = World(docker = ScriptedDocker(output = { "" }, exitCode = 1))
        assertEquals(AttemptOutcome.Failed(AgentErrorCode.CLI_NONZERO), world.executor(debugKeep = true).execute(world.attempt()))

        val dir = world.root.resolve("debug").resolve("$RUN-$ATTEMPT")
        assertEquals("code=CLI_NONZERO\n", Files.readString(dir.resolve("outcome.txt")))
        assertEquals("", Files.readString(dir.resolve("stream.jsonl")))
        world.assertNoRunLeft()
    }

    @Test
    fun debugKeepStoresNothingForASuccess() = runBlocking {
        val world = World(docker = ScriptedDocker(
            output = { lines(resultLine("Changed a.")) },
            onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") },
        ))
        assertEquals(AttemptOutcome.ResultSubmitted, world.executor(debugKeep = true).execute(world.attempt()))

        assertEquals(emptyList(), debugEntries(world))
        world.assertNoRunLeft()
    }

    @Test
    fun debugKeepLogsOnlyIdsAndFacts() = runBlocking {
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.agent") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val world = World(docker = ScriptedDocker(output = { lines(resultLine("Nothing to do.")) }))
            world.executor(debugKeep = true).execute(world.attempt())

            val text = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(text.contains("debug output kept: runId=$RUN attemptId=$ATTEMPT"), "the keep is logged with its ids")
            for (forbidden in listOf(TITLE, "Nothing to do.", "kivra-agent/", "tok-", "ghp_", "/tmp", world.root.toString())) {
                assertFalse(text.contains(forbidden), "log must not contain: $forbidden")
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun startupPrunesExpiredDebugEntries() {
        val world = World(docker = ScriptedDocker())
        val stale = Files.createDirectories(world.root.resolve("debug").resolve("stale-entry"))
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(48))))

        world.executor(debugKeep = true)

        assertFalse(Files.exists(stale), "an entry older than 24 hours is removed when the executor starts")
    }

    @Test
    fun invalidExecutorSettingsFailBeforeAnyWork() {
        val root = Files.createTempDirectory("kivra-real-exec-bad").toRealPath()
        assertFailsWith<net.kigawa.kivra.agent.docker.DockerSpecException> {
            RealAttemptExecutor(
                client = KivraAgentClient(fakeKivraHttp(synchronizedList()) { World.defaultReply(it) }, KIVRA_BASE, FixedTokens()),
                workRoot = root,
                stopFile = root.resolve("STOP"),
                settings = RealExecutorSettings(
                    dockerBinary = Path.of("docker"),
                    dockerHost = null,
                    network = NETWORK,
                    image = IMAGE,
                    proxyUrl = PROXY,
                    gitBinary = GIT_BIN,
                    repo = LocalPath(root),
                ),
            )
        }
    }

    /** The `usage` the CLI's final result gives for the cost fields; [model] is the modelUsage key. */
    private fun usageLine(
        cost: String = "0.004268",
        subtype: String = "success",
        isError: Boolean = false,
        model: String = "claude-haiku-5-5",
    ): String = """{"type":"result","subtype":"$subtype","is_error":$isError,"num_turns":3,"result":"$SUMMARY",""" +
        """"session_id":"sess-42","total_cost_usd":$cost,"usage":{"input_tokens":120,"output_tokens":45,""" +
        """"cache_creation_input_tokens":10,"cache_read_input_tokens":300},""" +
        """"modelUsage":{"$model":{"costUSD":0.004268,"inputTokens":120}}}"""

    private val usageObject = """"usage":{"totalCostUsd":"0.004268","model":"claude-haiku-5-5","numTurns":3,""" +
        """"inputTokens":120,"outputTokens":45,"cacheCreationTokens":10,"cacheReadTokens":300}"""

    /** Runs the loop until kivra gets a fail, and returns that fail's body. */
    private suspend fun failBodyFromLoop(world: World, newErrorCodes: Boolean = false): String = coroutineScope {
        val config = AgentConfig.fromEnv(testConfigEnv(world.root, world.root.resolve("secret").writeSecret("s"), poll = 1_000))
        val job = launch { AgentLoop(config, world.client, world.executor(newErrorCodes = newErrorCodes)).run() }
        withTimeout(30_000) { while (world.paths("/fail") == 0) delay(20) }
        job.cancelAndJoin()
        world.recorded.single { it.path.endsWith("/fail") }.body
    }

    @Test
    fun successSendsTheUsageWithTheResult() = runBlocking {
        val world = World(docker = ScriptedDocker(
            output = { lines(usageLine()) },
            onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") },
        ))
        assertEquals(AttemptOutcome.ResultSubmitted, world.executor().execute(world.attempt()))
        val body = world.recorded.single { it.path.endsWith("/result") }.body
        assertTrue(body.endsWith("}," + usageObject + "}"), body)
    }

    @Test
    fun aFailureAfterAResultCarriesTheUsage() = runBlocking {
        val emptyPatch = World(docker = ScriptedDocker(output = { lines(usageLine()) }))
        assertEquals(AgentErrorCode.CLI_NONZERO, assertIs<AttemptOutcome.Failed>(emptyPatch.executor().execute(emptyPatch.attempt())).code)
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO",$usageObject}""", failBodyFromLoop(emptyPatch))

        val budget = World(docker = ScriptedDocker(output = { lines(usageLine(subtype = "error_max_budget_usd", isError = true)) }, exitCode = 1))
        assertEquals(
            """{"generation":3,"errorCode":"CLI_NONZERO",$usageObject}""",
            failBodyFromLoop(budget),
            "the budget stop reports the cost too",
        )

        val turns = World(docker = ScriptedDocker(output = { lines(usageLine(subtype = "error_max_turns", isError = true)) }, exitCode = 1))
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO",$usageObject}""", failBodyFromLoop(turns))

        val rejected = World(
            docker = ScriptedDocker(output = { lines(usageLine()) }, onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") }),
            handler = { rec -> if (rec.path.endsWith("/result")) Canned(422, "{}") else World.defaultReply(rec) },
        )
        assertEquals(AgentErrorCode.BAD_OUTPUT, assertIs<AttemptOutcome.Failed>(rejected.executor().execute(rejected.attempt())).code)
        assertEquals("""{"generation":3,"errorCode":"BAD_OUTPUT",$usageObject}""", failBodyFromLoop(rejected))
    }

    @Test
    fun noResultOrMalformedUsageSendsNoUsage() = runBlocking {
        val noResult = World(docker = ScriptedDocker(output = { "" }, exitCode = 1))
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO"}""", failBodyFromLoop(noResult))

        val negativeCost = World(docker = ScriptedDocker(output = { lines(usageLine(cost = "-1")) }, exitCode = 1))
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO"}""", failBodyFromLoop(negativeCost), "a malformed value drops the whole usage")

        val badModel = World(docker = ScriptedDocker(output = { lines(usageLine(model = "Not A Model")) }, exitCode = 1))
        assertEquals("""{"generation":3,"errorCode":"CLI_NONZERO"}""", failBodyFromLoop(badModel))
    }

    @Test
    fun usageIsSentButNeverLogged() = runBlocking {
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.agent") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val world = World(docker = ScriptedDocker(output = { lines(usageLine(model = "claude-marker-9")) }))
            val body = failBodyFromLoop(world)
            assertTrue(body.contains("claude-marker-9"), "the model name goes to kivra in the body")
            val text = appender.list.joinToString("\n") { it.formattedMessage }
            // numTurns and totalCostUsd are logged already (CliResultLog), so only the usage body and the model name are checked here.
            for (forbidden in listOf("claude-marker-9", SUMMARY, "usage", "cacheReadTokens")) {
                assertFalse(text.contains(forbidden), "log must not contain: $forbidden")
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    /** A body marker with Japanese, a newline, quotes, a closing tag and a backslash: it must reach task.json and nowhere else. */
    private val bodyMarker = "BODYSECRET-日本語\n\"引用\" </x> \\ end"

    @Test
    fun theBodyReachesTheTaskFileAndNeverTheLogsArgvOrEnvironment() = runBlocking {
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.agent") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val world = World(docker = ScriptedDocker(
                output = { lines(resultLine("Nothing to do.")) },
            ))
            world.executor(debugKeep = true).execute(world.attempt(body = bodyMarker, bodyTruncated = true))

            val dir = world.root.resolve("debug").resolve("$RUN-$ATTEMPT")
            val doc = Json.parseToJsonElement(Files.readString(dir.resolve("task.json"))).jsonObject
            assertEquals(bodyMarker, doc["body"]!!.jsonPrimitive.content, "the body is in task.json, unchanged")
            assertEquals(TITLE, doc["title"]!!.jsonPrimitive.content)
            assertEquals(true, doc["truncated"]!!.jsonPrimitive.booleanOrNull, "a body cut by the claim marks the task truncated")

            val text = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(text.isNotEmpty(), "the executor logs its outcome")
            assertFalse(text.contains("BODYSECRET"), "log must not contain the body")
            val argv = world.docker.argvs.flatten().joinToString("\n")
            assertFalse(argv.contains("BODYSECRET"), "argv must not contain the body")
            val env = world.docker.envs.flatMap { it.entries }.joinToString("\n") { "${it.key}=${it.value}" }
            assertFalse(env.contains("BODYSECRET"), "env must not contain the body")
            assertFalse(world.recorded.any { it.body.contains("BODYSECRET") && !it.path.endsWith("/result") }, "kivra gets no body")
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun aTitleOnlyAttemptWritesAnEmptyBodyAndTheTitleTruncationFlagOnly() = runBlocking {
        val world = World(docker = ScriptedDocker(output = { lines(resultLine("Nothing to do.")) }))
        world.executor(debugKeep = true).execute(world.attempt())

        val doc = Json.parseToJsonElement(Files.readString(world.root.resolve("debug").resolve("$RUN-$ATTEMPT").resolve("task.json"))).jsonObject
        assertEquals("", doc["body"]!!.jsonPrimitive.content)
        assertEquals(false, doc["truncated"]!!.jsonPrimitive.booleanOrNull)
    }
}

/** The monitor (ADR 0012): the events go out before the result or the fail, carry no content, and never change the outcome. */
class RealAttemptExecutorMonitorTest {
    private val stream = lines(
        """{"type":"system","subtype":"init","model":"claude-haiku-5-5","claude_code_version":"2.1.3","tools":["Read"]}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"$CONTENT"},""" +
            """{"type":"tool_use","name":"Edit","input":{"file_path":"kivra-agent/src/Main.kt","new_string":"$TITLE"}}]}}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","content":"$SUMMARY","is_error":false}]}}""",
        resultLine("Changed a."),
    )

    private val acceptEvents: (Recorded) -> Canned = { rec ->
        if (rec.path.endsWith("/events")) Canned(200, "{}") else World.defaultReply(rec)
    }

    private fun changedWorld(handler: (Recorded) -> Canned = acceptEvents) = World(
        docker = ScriptedDocker(
            output = { stream },
            onRun = { dir -> Files.writeString(dir.resolve("kivra-agent/src/Main.kt"), "fun a() = 2\n") },
        ),
        handler = handler,
    )

    @Test
    fun theEventsGoBeforeTheResultAndCarryNoContent() = runBlocking {
        val world = changedWorld()
        val outcome = world.executor(monitorEvents = true).execute(world.attempt())

        assertEquals(AttemptOutcome.ResultSubmitted, outcome)
        val lastEvents = world.recorded.indexOfLast { it.path.endsWith("/events") }
        val result = world.recorded.indexOfFirst { it.path.endsWith("/result") }
        assertTrue(lastEvents in 0 until result, "the events must be sent before the result")

        val bodies = world.recorded.filter { it.path.endsWith("/events") }.joinToString("\n") { it.body }
        for (kind in listOf("ATTEMPT_STARTED", "CLI_INIT", "TOOL_USE", "TURN", "PROCESS_EXIT_CONFIRMED")) {
            assertTrue(bodies.contains(""""kind":"$kind""""), kind)
        }
        assertFalse(bodies.contains("claude-haiku"), "the model id is not sent (kivra stores none yet)")
        assertTrue(bodies.contains(""""toolName":"Edit""""))
        for (secret in listOf(CONTENT, TITLE, SUMMARY, "kivra-agent/src/Main.kt", "file_path", "new_string")) {
            assertFalse(bodies.contains(secret), "the events must not carry '$secret'")
        }
        world.assertNoRunLeft()
    }

    @Test
    fun withTheMonitorOffNoEventIsSentAtAll() = runBlocking {
        val world = changedWorld()
        val outcome = world.executor(monitorEvents = false).execute(world.attempt())

        assertEquals(AttemptOutcome.ResultSubmitted, outcome)
        assertEquals(0, world.paths("/events"))
        assertEquals(1, world.paths("/result"))
    }

    @Test
    fun aRefusedEventsCallDoesNotChangeTheResult() = runBlocking {
        val world = changedWorld(handler = { rec ->
            if (rec.path.endsWith("/events")) Canned(500, "{}") else World.defaultReply(rec)
        })
        val outcome = world.executor(monitorEvents = true).execute(world.attempt())

        assertEquals(AttemptOutcome.ResultSubmitted, outcome)
        assertEquals(1, world.paths("/result"))
        assertEquals(0, world.paths("/fail"))
    }

    @Test
    fun aFailureSendsItsEventsBeforeTheFail() = runBlocking {
        val world = World(
            docker = ScriptedDocker(output = { stream.lines().take(1).joinToString("\n") }, exitCode = 1),
            handler = acceptEvents,
        )
        val outcome = world.executor(monitorEvents = true).execute(world.attempt())

        assertEquals(AttemptOutcome.Failed(AgentErrorCode.CLI_NONZERO), outcome)
        // The loop sends the fail after execute returns, so the events must already be out when it does (flushed in execute).
        assertTrue(world.paths("/events") >= 1, "the events must be sent before execute returns")
        assertEquals(0, world.paths("/fail"), "the executor does not send the fail itself")
        assertTrue(world.recorded.filter { it.path.endsWith("/events") }.joinToString { it.body }.contains("PROCESS_EXIT_CONFIRMED"))
    }

    companion object {
        private const val CONTENT = "CONTENTSECRET fun a() = 2"
        private const val TITLE = "TASKSECRET-title-text"
        private const val SUMMARY = "SUMMARYSECRET done"
        private fun lines(vararg items: String): String = items.joinToString("\n", postfix = "\n")
        private fun resultLine(summary: String): String =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":3,"result":"$summary","session_id":"sess-42"}"""
    }
}
