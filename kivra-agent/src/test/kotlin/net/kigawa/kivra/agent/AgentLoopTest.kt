package net.kigawa.kivra.agent

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class AgentLoopTest {
    private val dir = Files.createTempDirectory("kivra-agent-loop")
    private val secret = dir.resolve("secret").writeSecret("s")

    private fun config(poll: Long = 5_000, heartbeat: Long = 5_000, stop: java.nio.file.Path? = null): AgentConfig =
        AgentConfig.fromEnv(
            testConfigEnv(dir, secret, poll, heartbeat) + (stop?.let { mapOf(AgentConfig.ENV_STOP_FILE to it.toString()) } ?: emptyMap()),
        )

    /** Serves one attempt (200) on the first claim and 204 afterwards, unless [claimReply] says otherwise. */
    private fun kivraHandler(claimReply: (Int) -> Canned = { n -> if (n == 1) Canned(200, CLAIM_BODY) else Canned(204) }, extra: (Recorded) -> Canned? = { null }): (Recorded) -> Canned {
        var claims = 0
        return { rec ->
            when {
                rec.path.endsWith("/claim") -> { claims++; claimReply(claims) }
                else -> extra(rec) ?: when {
                    rec.path.endsWith("/status") -> Canned(200, STATUS_OK)
                    rec.path.endsWith("/heartbeat") -> Canned(200, STATE_RUNNING)
                    rec.path.endsWith("/fail") -> Canned(200, """{"attemptId":"att-1","state":"FAILED"}""")
                    else -> Canned(404)
                }
            }
        }
    }

    private fun TestScope.loopFor(
        recorded: MutableList<Recorded>,
        config: AgentConfig,
        executor: AttemptExecutor = NotImplementedExecutor,
        handler: (Recorded) -> Canned,
    ): AgentLoop {
        val http = fakeKivraHttp(recorded, nowMillis = { testScheduler.currentTime }, handler = handler)
        return AgentLoop(config, KivraAgentClient(http, KIVRA_BASE, FixedTokens()), executor)
    }

    private fun List<Recorded>.paths(suffix: String) = count { it.path.endsWith(suffix) }

    @Test
    fun stubExecutorFailsTheClaimedAttemptWithNeedsHuman() = runTest {
        val recorded = synchronizedList()
        val loop = loopFor(recorded, config(), handler = kivraHandler())
        val job = launch { loop.run() }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        val fail = recorded.single { it.path.endsWith("/fail") }
        assertEquals("""{"generation":3,"errorCode":"NEEDS_HUMAN"}""", fail.body)
        assertTrue(fail.authorization.single().startsWith("Bearer tok-"))
    }

    @Test
    fun heartbeatsCarryTheGenerationWhileTheAttemptRuns() = runTest {
        val recorded = synchronizedList()
        val slow = AttemptExecutor { delay(12_000); AgentErrorCode.NEEDS_HUMAN }
        val loop = loopFor(recorded, config(heartbeat = 5_000), slow, kivraHandler())
        val job = launch { loop.run() }
        advanceTimeBy(11_000)
        assertEquals(2, recorded.paths("/heartbeat"), "heartbeats at 5 s and 10 s")
        assertEquals(2, recorded.paths("/status"))
        assertEquals("""{"generation":3}""", recorded.first { it.path.endsWith("/heartbeat") }.body)
        assertEquals(0, recorded.paths("/fail"), "no fail before the executor ends")
        advanceTimeBy(2_000)
        assertEquals(1, recorded.paths("/fail"))
        job.cancelAndJoin()
    }

    @Test
    fun conflictOnHeartbeatStopsTheWorkAndSendsNoFail() = runTest {
        val recorded = synchronizedList()
        var executorCancelled = false
        val slow = AttemptExecutor {
            try {
                delay(60_000)
                AgentErrorCode.NEEDS_HUMAN
            } finally {
                executorCancelled = true
            }
        }
        val loop = loopFor(recorded, config(heartbeat = 5_000), slow, kivraHandler(extra = { rec ->
            if (rec.path.endsWith("/heartbeat")) Canned(409, """{"error":"conflict"}""") else null
        }))
        val job = launch { loop.run() }
        advanceTimeBy(6_000)
        assertTrue(executorCancelled, "the work must stop at once on 409")
        assertEquals(0, recorded.paths("/fail"), "a stale generation must not report a fail")
        advanceTimeBy(20_000)
        assertEquals(1, recorded.paths("/heartbeat"), "no heartbeat after the 409")
        job.cancelAndJoin()
    }

    @Test
    fun stopFileMeansNoClaimUntilItIsRemoved() = runTest {
        val recorded = synchronizedList()
        val stop = dir.resolve("STOP")
        Files.createFile(stop)
        val loop = loopFor(recorded, config(poll = 5_000, stop = stop), handler = kivraHandler())
        val job = launch { loop.run() }
        advanceTimeBy(30_000)
        assertEquals(0, recorded.paths("/claim"), "no claim while STOP exists")
        Files.delete(stop)
        advanceTimeBy(6_000)
        assertTrue(recorded.paths("/claim") >= 1, "claims resume when STOP is removed")
        job.cancelAndJoin()
    }

    @Test
    fun noAssignmentPollsAtTheIntervalWithoutBackoff() = runTest {
        val recorded = synchronizedList()
        val loop = loopFor(recorded, config(poll = 5_000), handler = kivraHandler(claimReply = { Canned(204) }))
        val job = launch { loop.run() }
        advanceTimeBy(26_000)
        assertEquals(6, recorded.paths("/claim"))
        job.cancelAndJoin()
    }

    @Test
    fun repeatedClaimFailuresBackOffAndAreNotAHotLoop() = runTest {
        val recorded = synchronizedList()
        val loop = loopFor(recorded, config(poll = 5_000), handler = kivraHandler(claimReply = { Canned(401) }))
        val job = launch { loop.run() }
        advanceTimeBy(Duration.ofMinutes(30).toMillis())
        job.cancelAndJoin()
        val claims = recorded.filter { it.path.endsWith("/claim") }.map { it.atMillis }
        assertTrue(claims.size in 4..20, "backoff keeps the claim count small, was ${claims.size}")
        val gaps = claims.zipWithNext { a, b -> b - a }
        assertTrue(gaps.zipWithNext().all { (a, b) -> b >= a }, "gaps never shrink: $gaps")
        assertTrue(gaps.last() <= Duration.ofMinutes(5).toMillis(), "gaps are capped at five minutes")
        assertEquals(Duration.ofMillis(5_000), loop.backoff(1))
        assertEquals(Duration.ofMinutes(5), loop.backoff(50))
    }

    @Test
    fun cancelingTheLoopReportsCancelledForARunningAttempt() = runTest {
        val recorded = synchronizedList()
        val forever = AttemptExecutor { delay(Long.MAX_VALUE / 4); AgentErrorCode.NEEDS_HUMAN }
        val loop = loopFor(recorded, config(heartbeat = 5_000), forever, kivraHandler())
        val job = launch { loop.run() }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        val fail = recorded.single { it.path.endsWith("/fail") }
        assertEquals("""{"generation":3,"errorCode":"CANCELLED"}""", fail.body)
    }

    @Test
    fun cancelRequestedFromKivraStopsTheWorkAndReportsCancelled() = runTest {
        val recorded = synchronizedList()
        val forever = AttemptExecutor { delay(Long.MAX_VALUE / 4); AgentErrorCode.NEEDS_HUMAN }
        val loop = loopFor(recorded, config(heartbeat = 5_000), forever, kivraHandler(extra = { rec ->
            if (rec.path.endsWith("/status")) Canned(200, STATUS_CANCEL) else null
        }))
        val job = launch { loop.run() }
        advanceTimeBy(6_000)
        job.cancelAndJoin()
        assertEquals("""{"generation":3,"errorCode":"CANCELLED"}""", recorded.single { it.path.endsWith("/fail") }.body)
    }
}
