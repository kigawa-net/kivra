package net.kigawa.kivra.agent.docker

import java.time.Duration
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The line reading of [DockerRunner.run] (`onStdoutLine`): the lines arrive while the run goes on, and nothing about the run changes. */
class DockerRunLinesTest {
    private val fixture = DockerFixture()

    private fun runner(fake: FakeDocker, outputLimit: Int = 4 * 1024 * 1024) = DockerRunner(
        settings = fixture.settings,
        launcher = fake,
        nanoTime = { fake.clockNanos },
        pollMillis = 1_000,
        outputLimitBytes = outputLimit,
    )

    private fun texts(lines: List<StdoutLine>): List<String> = lines.map { (it as StdoutLine.Text).value }

    @Test
    fun eachLineIsDeliveredInOrderAndTheLastLineWithoutANewlineToo() {
        val fake = FakeDocker().apply { runStdout = "one\ntwo\nthree".toByteArray() }
        val lines = Collections.synchronizedList(mutableListOf<StdoutLine>())
        val result = runner(fake).run(fixture.spec(), onStdoutLine = { lines += it })

        assertEquals(listOf("one", "two", "three"), texts(lines.toList()))
        assertEquals("one\ntwo\nthree", result.stdout)
    }

    @Test
    fun aLineSplitAcrossReadsIsJoinedAndKeepsItsMultiByteCharacters() {
        val long = "あ".repeat(10_000)
        val fake = FakeDocker().apply { runStdout = "first\n$long\nlast\n".toByteArray() }
        val lines = Collections.synchronizedList(mutableListOf<StdoutLine>())
        runner(fake).run(fixture.spec(), onStdoutLine = { lines += it })

        assertEquals(listOf("first", long, "last"), texts(lines.toList()))
    }

    @Test
    fun aLineOverTheLimitIsOversizedAndTheNextLineIsStillDelivered() {
        val huge = "x".repeat(StdoutLineLimit + 10)
        val fake = FakeDocker().apply { runStdout = "before\n$huge\nafter\n".toByteArray() }
        val lines = Collections.synchronizedList(mutableListOf<StdoutLine>())
        runner(fake, outputLimit = 8 * 1024 * 1024).run(fixture.spec(), onStdoutLine = { lines += it })

        val all = lines.toList()
        assertEquals(3, all.size)
        assertEquals("before", (all[0] as StdoutLine.Text).value)
        assertEquals(StdoutLine.Oversized, all[1])
        assertEquals("after", (all[2] as StdoutLine.Text).value)
    }

    @Test
    fun aListenerThatThrowsDoesNotChangeTheRun() {
        val fake = FakeDocker().apply {
            runExitCode = 0
            runStdout = "a\nb\nc\n".toByteArray()
        }
        val result = runner(fake).run(fixture.spec(), onStdoutLine = { throw IllegalStateException("listener broke") })

        assertEquals(DockerRunOutcome.EXITED, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals("a\nb\nc\n", result.stdout)
    }

    @Test
    fun aListenerThatBlocksDoesNotDelayTheRunForLong() {
        val fake = FakeDocker().apply { runStdout = "a\nb\n".toByteArray() }
        val started = System.nanoTime()
        val result = runner(fake).run(fixture.spec(), onStdoutLine = { Thread.sleep(60_000) })
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(DockerRunOutcome.EXITED, result.outcome)
        assertEquals("a\nb\n", result.stdout)
        assertTrue(elapsedMillis < 10_000, "the run took $elapsedMillis ms")
    }

    @Test
    fun aBlockedListenerDoesNotStopTheKillOnTimeout() {
        val fake = FakeDocker().apply {
            runBlocks = true
            runStdout = "a\n".toByteArray()
        }
        val result = runner(fake).run(fixture.spec(timeout = Duration.ofSeconds(5)), onStdoutLine = { Thread.sleep(60_000) })

        assertEquals(DockerRunOutcome.TIMEOUT, result.outcome)
        assertEquals(listOf("kivra-run-$TEST_RUN_ID-$TEST_ATTEMPT_ID"), fake.killedNames)
    }

    @Test
    fun withoutAListenerTheStdoutIsTheSame() {
        val fake = FakeDocker().apply { runStdout = "one\ntwo\n".toByteArray() }
        assertEquals("one\ntwo\n", runner(fake).run(fixture.spec()).stdout)
    }

    @Test
    fun aStdoutLineDoesNotShowItsValueWhenPrinted() {
        assertEquals("StdoutLine.Text(***)", StdoutLine.Text("sk-ant-secret").toString())
    }

    private companion object {
        const val StdoutLineLimit = 1024 * 1024
    }
}
