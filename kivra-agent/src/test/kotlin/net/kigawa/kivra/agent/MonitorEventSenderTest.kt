@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package net.kigawa.kivra.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.kigawa.kivra.agent.claude.CliVersion
import net.kigawa.kivra.agent.claude.ModelId
import net.kigawa.kivra.agent.claude.MonitorEvent
import net.kigawa.kivra.agent.claude.MonitorTool
import net.kigawa.kivra.agent.claude.MonitorWarning
import net.kigawa.kivra.agent.docker.DockerRunOutcome

private const val GENERATION = 3L

/** Answers each request with the next scripted reply, then [EventsReply.Accepted]. Records every body. */
private class ScriptedTransport(vararg replies: EventsReply) : MonitorTransport {
    private val replies = ArrayDeque(replies.toList())
    val bodies = mutableListOf<String>()

    override suspend fun post(body: String): EventsReply {
        bodies += body
        return if (replies.isEmpty()) EventsReply.Accepted else replies.removeFirst()
    }
}

/** The seqs of the events in a body, in order. */
private fun seqsOf(body: String): List<Long> = Json.parseToJsonElement(body).jsonObject["events"]!!.jsonArray
    .map { it.jsonObject["seq"]!!.jsonPrimitive.long }

private fun kindsOf(body: String): List<String> = Json.parseToJsonElement(body).jsonObject["events"]!!.jsonArray
    .map { it.jsonObject["kind"]!!.jsonPrimitive.content }

private fun sender(
    transport: MonitorTransport,
    maxEvents: Int = MonitorEventSender.MAX_EVENTS,
    queueCapacity: Int = MonitorEventSender.QUEUE_CAPACITY,
) = MonitorEventSender(
    attemptId = "att-1",
    generation = GENERATION,
    transport = transport,
    pacingMillis = 0,
    retryBackoffMillis = listOf(10, 10, 10),
    maxEvents = maxEvents,
    queueCapacity = queueCapacity,
)

class MonitorEventSenderTest {
    @Test
    fun eventsAreSentInBatchesOfAtMost50WithContiguousSeqsAndTheGeneration() = runTest {
        val transport = ScriptedTransport()
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        repeat(120) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()

        assertTrue(transport.bodies.size >= 3)
        val all = transport.bodies.flatMap { seqsOf(it) }
        assertEquals((1L..120L).toList(), all)
        for (body in transport.bodies) {
            assertTrue(seqsOf(body).size <= MonitorWire.MAX_EVENTS_PER_REQUEST)
            assertTrue(body.length <= MonitorWire.MAX_BODY_BYTES, "body is ${body.length} bytes")
            assertEquals(GENERATION, Json.parseToJsonElement(body).jsonObject["generation"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun everyBatchFitsEightKibEvenOfTheLargestEventsAndNoEventIsLost() = runTest {
        val transport = ScriptedTransport()
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        repeat(50) { sender.enqueue(MonitorEvent.CliInit(ModelId.of("a".repeat(64)), CliVersion.of("999.999.999"))) }
        sender.finish()

        assertEquals((1L..50L).toList(), transport.bodies.flatMap { seqsOf(it) })
        assertTrue(transport.bodies.all { it.length <= MonitorWire.MAX_BODY_BYTES })
    }

    @Test
    fun eachEventIsAtMost256BytesAtItsLargestValues() {
        val largest = listOf(
            MonitorEvent.AttemptStarted,
            MonitorEvent.CliInit(ModelId.of("a".repeat(64)), CliVersion.of("999.999.999")),
            MonitorEvent.Phase(net.kigawa.kivra.agent.ProgressPhase.ROUND_COMPLETED),
            MonitorEvent.ToolUse(MonitorTool.TODO_WRITE),
            MonitorEvent.ToolResult(isError = true),
            MonitorEvent.Turn,
            MonitorEvent.PermissionDenied(MonitorEvent.MAX_COUNT),
            MonitorEvent.ApiRetry(net.kigawa.kivra.agent.claude.ApiErrorKind.AUTHENTICATION),
            MonitorEvent.Cost(MonitorEvent.MAX_COST_MICROS),
            MonitorEvent.StopRequested,
            MonitorEvent.ProcessExitConfirmed(DockerRunOutcome.OUTPUT_LIMIT),
            MonitorEvent.Warning(MonitorWarning.BLOCK_LIMIT),
            MonitorEvent.Dropped(MonitorEvent.MAX_COUNT),
            MonitorEvent.Truncated,
        )
        for (event in largest) {
            val size = MonitorWire.event(2_000L, event).toString().length
            assertTrue(size <= 256, "$event is $size bytes")
        }
    }

    @Test
    fun droppedEventsAreCountedAndReportedInTheNextBatch() = runTest {
        val transport = ScriptedTransport()
        val sender = sender(transport, queueCapacity = 500)
        // The loop is not started yet, so the queue fills: 600 events, 500 kept, 100 dropped.
        repeat(600) { sender.enqueue(MonitorEvent.Turn) }
        backgroundScope.launch { sender.runLoop() }
        sender.finish()

        val first = transport.bodies.first()
        assertEquals("DROPPED", kindsOf(first).first())
        val marker = Json.parseToJsonElement(first).jsonObject["events"]!!.jsonArray.first().jsonObject
        assertEquals(100L, marker["count"]!!.jsonPrimitive.long)
        assertEquals(500, transport.bodies.sumOf { seqsOf(it).size } - 1)
    }

    @Test
    fun theAttemptIsCappedAtMaxEventsWithTruncatedAsTheLastOne() = runTest {
        val transport = ScriptedTransport()
        val sender = sender(transport, maxEvents = 20, queueCapacity = 10_000)
        backgroundScope.launch { sender.runLoop() }
        repeat(50) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()

        val seqs = transport.bodies.flatMap { seqsOf(it) }
        assertEquals((1L..20L).toList(), seqs)
        assertEquals("TRUNCATED", transport.bodies.flatMap { kindsOf(it) }.last())
    }

    @Test
    fun aConflictWithLastAcceptedSeqResendsTheRestOfTheBatchWithTheSameSeqs() = runTest {
        val transport = ScriptedTransport(EventsReply.Conflict(lastAcceptedSeq = 2))
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        repeat(5) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seqsOf(transport.bodies[0]))
        assertEquals(listOf(3L, 4L, 5L), seqsOf(transport.bodies[1]))
        assertEquals(2, transport.bodies.size)
    }

    @Test
    fun aConflictWithoutLastAcceptedSeqStopsTheSender() = runTest {
        val transport = ScriptedTransport(EventsReply.Conflict(lastAcceptedSeq = null))
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        repeat(3) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()
        repeat(3) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()

        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun aGapThatTheSenderNoLongerHoldsStopsTheSender() = runTest {
        val transport = ScriptedTransport(EventsReply.Accepted, EventsReply.Conflict(lastAcceptedSeq = 0))
        val sender = sender(transport)
        val loop = launch { sender.runLoop() }
        repeat(2) { sender.enqueue(MonitorEvent.Turn) }
        advanceUntilIdle()
        // The second batch starts at seq 3, and kivra says it has nothing; seq 1 and 2 are not held any more.
        repeat(2) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()
        loop.cancel()

        assertEquals(2, transport.bodies.size)
        assertEquals(listOf(3L, 4L), seqsOf(transport.bodies[1]))
    }

    @Test
    fun aRateLimitWaitsForRetryAfterAndResendsTheSameBatch() = runTest {
        val transport = ScriptedTransport(EventsReply.RateLimited(retryAfterSeconds = 2))
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertEquals(2, transport.bodies.size)
        assertEquals(transport.bodies[0], transport.bodies[1])
        assertTrue(testScheduler.currentTime >= 2_000L, "waited $testScheduler.currentTime ms")
    }

    @Test
    fun aBadRequestDiscardsTheBatchAndItsSeqsAreReused() = runTest {
        val transport = ScriptedTransport(EventsReply.Rejected)
        val sender = sender(transport)
        val loop = launch { sender.runLoop() }
        repeat(3) { sender.enqueue(MonitorEvent.Turn) }
        advanceUntilIdle()
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()
        loop.cancel()

        assertEquals(listOf(1L, 2L, 3L), seqsOf(transport.bodies[0]))
        assertEquals(listOf(1L), seqsOf(transport.bodies[1]))
    }

    @Test
    fun aServerErrorRetriesTheSameBatchAndThenSucceeds() = runTest {
        val transport = ScriptedTransport(EventsReply.Unavailable, EventsReply.Unavailable)
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertEquals(3, transport.bodies.size)
        assertEquals(setOf(transport.bodies[0]), transport.bodies.toSet())
    }

    @Test
    fun aServerErrorThatLastsStopsTheSender() = runTest {
        val transport = ScriptedTransport(*Array(10) { EventsReply.Unavailable })
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertEquals(4, transport.bodies.size)
    }

    @Test
    fun aRefusalStopsTheSenderAndNothingIsSentAfterIt() = runTest {
        val transport = ScriptedTransport(EventsReply.Refused)
        val sender = sender(transport)
        backgroundScope.launch { sender.runLoop() }
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun finishSendsWhatIsQueuedBeforeItReturns() = runTest {
        val transport = ScriptedTransport()
        val sender = sender(transport)
        repeat(7) { sender.enqueue(MonitorEvent.Turn) }
        sender.finish()

        assertEquals((1L..7L).toList(), transport.bodies.flatMap { seqsOf(it) })
    }

    @Test
    fun finishGivesUpAfterThreeSecondsWhenTheServerDoesNotAnswer() = runTest {
        var calls = 0
        val hanging = MonitorTransport {
            calls++
            awaitCancellation()
        }
        val sender = sender(hanging)
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(1, calls)
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()
        assertEquals(1, calls)
    }

    @Test
    fun anEventAfterFinishIsNotQueued() = runTest {
        val transport = ScriptedTransport()
        val sender = sender(transport)
        sender.finish()
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun aTransportThatThrowsIsTreatedAsAServerError() = runTest {
        var calls = 0
        val throwing = MonitorTransport {
            calls++
            throw IllegalStateException("network")
        }
        val sender = sender(throwing)
        sender.enqueue(MonitorEvent.Turn)
        sender.finish()

        assertEquals(4, calls)
    }
}
