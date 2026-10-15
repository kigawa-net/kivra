package net.kigawa.kivra.acceptance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Named rendezvous points that make the interleaving of Actor A and Actor B explicit (no sleeps).
 *
 * Worker side: [reach] announces "I am at this point" and blocks until the test calls [release].
 * Test side: [awaitReached] blocks until a worker has reached the point, then the test decides when to [release] it.
 * Every wait is bounded by [timeout]; a timeout is a test failure, never a silent pass.
 */
class NamedBarriers(private val timeoutSeconds: Long = 10) {
    private class Gate {
        val reached = CountDownLatch(1)
        val released = CountDownLatch(1)
    }

    private val gates = ConcurrentHashMap<String, Gate>()
    private fun gate(name: String): Gate = gates.computeIfAbsent(name) { Gate() }

    fun reach(name: String) {
        val g = gate(name)
        g.reached.countDown()
        check(g.released.await(timeoutSeconds, TimeUnit.SECONDS)) { "barrier '$name' was not released within ${timeoutSeconds}s" }
    }

    fun awaitReached(name: String) {
        check(gate(name).reached.await(timeoutSeconds, TimeUnit.SECONDS)) { "barrier '$name' was not reached within ${timeoutSeconds}s" }
    }

    fun release(name: String) = gate(name).released.countDown()
}
