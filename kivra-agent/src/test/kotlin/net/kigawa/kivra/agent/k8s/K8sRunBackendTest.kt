package net.kigawa.kivra.agent.k8s

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import net.kigawa.kivra.agent.backend.RunOutcome
import net.kigawa.kivra.agent.backend.RunResult
import net.kigawa.kivra.agent.backend.RunSpec
import net.kigawa.kivra.agent.backend.StdoutLine
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.k8s.pod.PodInput
import net.kigawa.kivra.agent.k8s.pod.PodReadout
import net.kigawa.kivra.agent.k8s.pod.PodResultCodec
import net.kigawa.kivra.agent.workdir.AgentChange
import net.kigawa.kivra.agent.workdir.AgentChangeKind
import net.kigawa.kivra.agent.workdir.AgentResult
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffRejectReason

/** A log that serves [bytes] and then stays open (a followed log) until it is closed. */
internal class HangingStream(private val bytes: ByteArray) : InputStream() {
    private var pos = 0
    private val closed = CountDownLatch(1)

    @Volatile var wasClosed = false

    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos < bytes.size) {
            val n = minOf(len, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
        closed.await()
        return -1
    }

    override fun close() {
        wasClosed = true
        closed.countDown()
    }
}

internal class FakeKubeApi : KubeApi {
    val created = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val deleted = CopyOnWriteArrayList<String>()
    val execs = CopyOnWriteArrayList<ExecCall>()
    val listSelectors = CopyOnWriteArrayList<String>()

    class ExecCall(val command: List<String>, val stdin: ByteArray?, val maxStdout: Int)

    /** Called for each poll of the Pod, with the number of the call (from 0). */
    var pod: (Int) -> PodState? = { PodState("pod-1", running = true, terminated = false, exitCode = null) }
    var job: () -> JobState? = { JobState(succeeded = false, failed = false, deadlineExceeded = false) }
    var jobs: () -> List<JobRef> = { emptyList() }
    var deleteFails: Set<String> = emptySet()
    var logBytes: ByteArray = ByteArray(0)
    var execHandler: (ExecCall) -> ExecResult = { ExecResult(0, ByteArray(0), false) }
    val logStreams = CopyOnWriteArrayList<HangingStream>()
    private val podCalls = AtomicInteger()

    override fun createJob(namespace: String, manifest: JsonObject) {
        created += namespace to manifest
    }

    override fun jobState(namespace: String, name: String): JobState? = job()

    override fun listJobs(namespace: String, labelSelector: String): List<JobRef> {
        listSelectors += labelSelector
        return jobs()
    }

    override fun deleteJob(namespace: String, name: String) {
        if (name in deleteFails) throw KubeApiException("HTTP_5XX")
        deleted += name
    }

    override fun podOf(namespace: String, jobName: String): PodState? = pod(podCalls.getAndIncrement())

    override fun openLog(namespace: String, pod: String, container: String): InputStream =
        HangingStream(logBytes).also { logStreams += it }

    override fun exec(
        namespace: String,
        pod: String,
        container: String,
        command: List<String>,
        stdin: ByteArray?,
        maxStdoutBytes: Int,
        timeoutMillis: Long,
    ): ExecResult {
        val call = ExecCall(command, stdin, maxStdoutBytes)
        execs += call
        return execHandler(call)
    }
}

class K8sRunBackendTest {
    private val api = FakeKubeApi()
    private val body = "TOP-SECRET-TASK-BODY"

    private fun spec(timeout: Duration = Duration.ofSeconds(30), outputLimit: Int = 1024 * 1024) = RunSpec(
        runId = K8S_RUN_ID,
        attemptId = K8S_ATTEMPT_ID,
        image = K8S_IMAGE,
        workDir = Path.of("/unused"),
        network = "unused",
        proxyUrl = K8S_PROXY,
        timeout = timeout,
        maxTurns = CliCommand.DEFAULT_MAX_TURNS,
        model = CliCommand.DEFAULT_MODEL,
        budgetUsd = CliCommand.DEFAULT_BUDGET_USD,
        outputLimitBytes = outputLimit,
    )

    private fun input() = PodInput(K8S_RUN_ID, K8S_ATTEMPT_ID, "kigawa-net", "kivra", "c".repeat(40), "Fix docs", body, false)

    private fun backend(
        manifestOf: ((K8sJobSpec) -> JsonObject)? = null,
        input: PodInput = input(),
    ): K8sRunBackend =
        if (manifestOf == null) {
            K8sRunBackend(K8sFixture.settings, api, { input }, pollMillis = 5, drainIdleMillis = 20, drainMaxMillis = 500)
        } else {
            K8sRunBackend(K8sFixture.settings, api, { input }, pollMillis = 5, drainIdleMillis = 20, drainMaxMillis = 500, manifestOf = manifestOf)
        }

    private val goodResult: ByteArray = PodResultCodec.encode(
        0,
        CollectOutcome.Accepted(AgentResult("x", listOf(AgentChange("docs/a.md", AgentChangeKind.WRITE, "hi\n")), 0, null)),
    )

    /** An exec handler: the input is delivered, and the result file exists from the [readyAfter]-th read. */
    private fun podWrites(result: ByteArray = goodResult, readyAfter: Int = 0): (FakeKubeApi.ExecCall) -> ExecResult {
        val reads = AtomicInteger()
        return { call ->
            if (call.command.firstOrNull() == "cat") {
                if (reads.getAndIncrement() >= readyAfter) ExecResult(0, result, false) else ExecResult(1, ByteArray(0), false)
            } else {
                ExecResult(0, ByteArray(0), false)
            }
        }
    }

    // ---- the happy path ----

    @Test
    fun runsOneJobSendsTheInputReadsTheResultAndDeletesTheJob() {
        api.execHandler = podWrites(readyAfter = 2)
        api.logBytes = "line one\nline two\n".toByteArray()
        val lines = CopyOnWriteArrayList<String>()
        val result = backend().run(spec(), { line -> lines += (line as StdoutLine.Text).value }, { false })

        assertEquals(RunOutcome.EXITED, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals("line one\nline two\n", result.stdout)
        assertEquals(listOf("line one", "line two"), lines)
        val readout = assertIs<PodReadout.Collected>(result.podReadout)
        assertEquals("docs/a.md", readout.changes!!.single().path)

        assertEquals(1, api.created.size)
        assertEquals(K8S_NAMESPACE, api.created[0].first)
        val jobName = K8sNames.jobName(K8S_RUN_ID, K8S_ATTEMPT_ID)
        assertEquals(listOf(jobName), api.deleted)
        assertTrue(api.logStreams.single().wasClosed)
    }

    @Test
    fun theCreatedManifestHasPassedTheGuard() {
        api.execHandler = podWrites()
        backend().run(spec(), null) { false }
        K8sJobGuard(K8sFixture.settings).check(api.created.single().second)
    }

    @Test
    fun theInputIsSentOnceByStdinAndTheTaskIsNotInTheCommand() {
        api.execHandler = podWrites(readyAfter = 3)
        backend().run(spec(), null) { false }
        val sends = api.execs.filter { it.command.firstOrNull() == "sh" }
        assertEquals(1, sends.size)
        val send = sends.single()
        assertContentEquals(PodInput.encode(input()), send.stdin!!)
        assertTrue(send.command.joinToString(" ").contains("head -c ${send.stdin!!.size} "))
        assertTrue(api.execs.none { body in it.command.joinToString(" ") })
        // The result is read after the input, never before.
        assertEquals("sh", api.execs.first().command.first())
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) = assertTrue(expected.contentEquals(actual))

    @Test
    fun theResultReadIsBoundedByTheResultFileLimit() {
        api.execHandler = podWrites()
        backend().run(spec(), null) { false }
        val read = api.execs.first { it.command.firstOrNull() == "cat" }
        assertEquals(PodResultCodec.MAX_FILE_BYTES, read.maxStdout)
        assertEquals(listOf("cat", "/work/.kivra/result.json"), read.command)
    }

    // ---- the guard is the last step ----

    @Test
    fun aManifestThatTheGuardRefusesIsNeverSent() {
        val golden = K8sFixture.golden()
        val tampered: JsonObject = golden.set("spec", "template", "spec", "containers", 0, "securityContext", "privileged", value = kotlinx.serialization.json.JsonPrimitive(true))
        val b = backend(manifestOf = { tampered })
        val e = assertFailsWith<K8sJobRejectedException> { b.run(spec(), null) { false } }
        assertEquals(K8sGuardRule.PRIVILEGED_NOT_ALLOWED, e.rule)
        assertTrue(api.created.isEmpty())
        assertTrue(api.deleted.isEmpty())
        assertTrue(api.execs.isEmpty())
    }

    @Test
    fun validateAppliesTheGuardToo() {
        val tampered = K8sFixture.golden().set("spec", "template", "spec", "hostNetwork", value = kotlinx.serialization.json.JsonPrimitive(true))
        assertFailsWith<K8sJobRejectedException> { backend(manifestOf = { tampered }).validate(spec()) }
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun anImageOutsideTheAllowlistIsRefusedBeforeAnythingIsSent() {
        val s = RunSpec(
            K8S_RUN_ID, K8S_ATTEMPT_ID, "evil.example/agent:latest", Path.of("/unused"), "n", K8S_PROXY,
            Duration.ofSeconds(30), CliCommand.DEFAULT_MAX_TURNS, CliCommand.DEFAULT_MODEL, CliCommand.DEFAULT_BUDGET_USD, 1024,
        )
        assertFailsWith<K8sSpecException> { backend().run(s, null) { false } }
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun anInputForAnotherRunIsRefused() {
        val other = PodInput("01M545K02Q6YB4M5ZHAM5792CT", K8S_ATTEMPT_ID, "kigawa-net", "kivra", "c".repeat(40), "t", "b", false)
        val e = assertFailsWith<K8sRunException> { backend(input = other).run(spec(), null) { false } }
        assertEquals("INPUT_MISMATCH", e.code)
        assertTrue(api.created.isEmpty())
    }

    // ---- limits ----

    @Test
    fun theOutputLimitEndsTheRunAndKeepsAtMostTheLimit() {
        api.execHandler = { ExecResult(if (it.command.first() == "cat") 1 else 0, ByteArray(0), false) } // no result: it never ends by itself
        api.logBytes = ("0123456789\n".repeat(100)).toByteArray()
        val result = backend().run(spec(outputLimit = 64), null) { false }
        assertEquals(RunOutcome.OUTPUT_LIMIT, result.outcome)
        assertTrue(result.outputTruncated)
        assertEquals(64, result.stdout.length)
        assertEquals(1, api.deleted.size)
    }

    @Test
    fun anOutputAtExactlyTheLimitIsNotTruncated() {
        api.execHandler = podWrites()
        api.logBytes = ByteArray(64) { 'a'.code.toByte() }
        val result = backend().run(spec(outputLimit = 64), null) { false }
        assertEquals(RunOutcome.EXITED, result.outcome)
        assertFalse(result.outputTruncated)
        assertEquals(64, result.stdout.length)
    }

    @Test
    fun aLongLineIsReportedAsOversizedAndNotKept() {
        api.execHandler = podWrites()
        api.logBytes = ("x".repeat(1024 * 1024 + 5) + "\nshort\n").toByteArray()
        val lines = CopyOnWriteArrayList<StdoutLine>()
        backend().run(spec(outputLimit = 4 * 1024 * 1024), { lines += it }, { false })
        assertEquals(StdoutLine.Oversized, lines[0])
        assertEquals("short", (lines[1] as StdoutLine.Text).value)
    }

    @Test
    fun theTimeLimitEndsARunThatNeverProducesAResult() {
        api.execHandler = { ExecResult(if (it.command.first() == "cat") 1 else 0, ByteArray(0), false) }
        val started = System.nanoTime()
        val result = backend().run(spec(timeout = Duration.ofSeconds(1)), null) { false }
        assertEquals(RunOutcome.TIMEOUT, result.outcome)
        assertNull(result.exitCode)
        assertNull(result.podReadout)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10))
        assertEquals(1, api.deleted.size)
    }

    @Test
    fun theTimeLimitAlsoCoversAPodThatNeverStarts() {
        api.pod = { null }
        val result = backend().run(spec(timeout = Duration.ofSeconds(1)), null) { false }
        assertEquals(RunOutcome.TIMEOUT, result.outcome)
        assertEquals(1, api.deleted.size)
        assertTrue(api.execs.isEmpty())
    }

    @Test
    fun aCancelRequestEndsTheRunAndDeletesTheJob() {
        api.execHandler = { ExecResult(if (it.command.first() == "cat") 1 else 0, ByteArray(0), false) }
        val polls = AtomicInteger()
        val result = backend().run(spec(), null) { polls.incrementAndGet() > 5 }
        assertEquals(RunOutcome.CANCELLED, result.outcome)
        assertEquals(1, api.deleted.size)
        assertTrue(api.logStreams.single().wasClosed)
    }

    @Test
    fun aCancelBeforeAnythingIsStillDeletesTheCreatedJob() {
        val result = backend().run(spec(), null) { true }
        assertEquals(RunOutcome.CANCELLED, result.outcome)
        assertEquals(1, api.created.size)
        assertEquals(1, api.deleted.size)
    }

    // ---- the Pod ends by itself ----

    @Test
    fun aPodThatDiedWithoutAResultIsAnExitWithItsCode() {
        api.pod = { PodState("pod-1", running = false, terminated = true, exitCode = 137) }
        val result = backend().run(spec(), null) { false }
        assertEquals(RunOutcome.EXITED, result.outcome)
        assertEquals(137, result.exitCode)
        assertNull(result.podReadout)
        assertEquals(1, api.deleted.size)
    }

    @Test
    fun aJobThatPassedItsDeadlineIsATimeout() {
        api.pod = { PodState("pod-1", running = false, terminated = true, exitCode = 137) }
        api.job = { JobState(succeeded = false, failed = true, deadlineExceeded = true) }
        val result = backend().run(spec(), null) { false }
        assertEquals(RunOutcome.TIMEOUT, result.outcome)
        assertNull(result.exitCode)
    }

    // ---- the Pod's file is untrusted ----

    @Test
    fun aMalformedResultFileBecomesAnErrorReadout() {
        api.execHandler = podWrites(result = "{\"not\":\"a result\"}".toByteArray())
        val result = backend().run(spec(), null) { false }
        assertEquals(RunOutcome.EXITED, result.outcome)
        assertNull(result.exitCode)
        assertIs<PodReadout.Error>(result.podReadout)
    }

    @Test
    fun aTruncatedResultReadIsAnErrorReadout() {
        api.execHandler = { call -> if (call.command.first() == "cat") ExecResult(0, ByteArray(10), true) else ExecResult(0, ByteArray(0), false) }
        val result = backend().run(spec(), null) { false }
        assertEquals("RESULT_TOO_LARGE", assertIs<PodReadout.Error>(result.podReadout).reason)
    }

    @Test
    fun theReadoutIsRefusedAgainWhenItsChangesBreakTheAgentsRules() {
        val evil = PodResultCodec.encode(
            0,
            CollectOutcome.Accepted(AgentResult("x", listOf(AgentChange(".github/workflows/ci.yml", AgentChangeKind.WRITE, "on: push\n")), 0, null)),
        )
        api.execHandler = podWrites(result = evil)
        val readout = assertIs<PodReadout.Collected>(backend().run(spec(), null) { false }.podReadout)
        val outcome = readout.finish("done", null, net.kigawa.kivra.agent.workdir.DiffLimits(), net.kigawa.kivra.agent.workdir.PathRules.BUILT_IN_ONLY)
        assertTrue(DiffRejectReason.PATH_DENIED in assertIs<CollectOutcome.Rejected>(outcome).reasons)
    }

    // ---- API failures ----

    @Test
    fun aFailedInputDeliveryEndsTheRunWithACodeAndStillDeletesTheJob() {
        api.execHandler = { ExecResult(1, ByteArray(0), false) }
        val e = assertFailsWith<K8sRunException> { backend().run(spec(), null) { false } }
        assertEquals("INPUT_NOT_DELIVERED", e.code)
        assertEquals(1, api.deleted.size)
    }

    @Test
    fun anExecThatTimedOutDuringDeliveryIsNotTakenAsDelivered() {
        api.execHandler = { ExecResult(null, ByteArray(0), false) }
        assertFailsWith<K8sRunException> { backend().run(spec(), null) { false } }
    }

    @Test
    fun aShortApiFailureIsToleratedButALongOneEndsTheRun() {
        val polls = AtomicInteger()
        api.execHandler = podWrites()
        api.pod = { n ->
            polls.incrementAndGet()
            if (n < 4) throw KubeApiException("IO") else PodState("pod-1", true, false, null)
        }
        assertEquals(RunOutcome.EXITED, backend().run(spec(), null) { false }.outcome)

        val api2 = FakeKubeApi()
        api2.pod = { throw KubeApiException("IO") }
        val e = assertFailsWith<K8sRunException> {
            K8sRunBackend(K8sFixture.settings, api2, { input() }, pollMillis = 5).run(spec(), null) { false }
        }
        assertEquals("API_UNAVAILABLE", e.code)
        assertEquals(1, api2.deleted.size)
    }

    @Test
    fun aFailedDeleteAtTheEndDoesNotHideTheResult() {
        api.execHandler = podWrites()
        api.deleteFails = setOf(K8sNames.jobName(K8S_RUN_ID, K8S_ATTEMPT_ID))
        val result: RunResult = backend().run(spec(), null) { false }
        assertNotNull(result.podReadout)
    }

    // ---- cleanupOrphans ----

    @Test
    fun cleanupRemovesTheAgentsJobsExceptTheActiveRuns() {
        api.jobs = { listOf(JobRef("job-a", "RUN-A"), JobRef("job-b", "RUN-B"), JobRef("job-c", null)) }
        val removed = backend().cleanupOrphans("RUN-B")
        assertEquals(2, removed)
        assertEquals(listOf("job-a", "job-c"), api.deleted.toList())
        assertEquals(listOf("kivra-agent=1,kivra-owner=$K8S_OWNER"), api.listSelectors.toList())
    }

    @Test
    fun cleanupAtStartupRemovesEveryJobOfTheAgent() {
        api.jobs = { listOf(JobRef("job-a", "RUN-A"), JobRef("job-b", "RUN-B")) }
        assertEquals(2, backend().cleanupOrphans(null))
        assertEquals(listOf("job-a", "job-b"), api.deleted.toList())
    }

    @Test
    fun cleanupReturnsNullWhenTheListingFailsAndCountsOnlyRemovedJobs() {
        api.jobs = { throw KubeApiException("IO") }
        assertNull(backend().cleanupOrphans(null))
        api.jobs = { listOf(JobRef("job-a", "RUN-A"), JobRef("job-b", "RUN-B")) }
        api.deleteFails = setOf("job-a")
        assertEquals(1, backend().cleanupOrphans(null))
    }
}
