package net.kigawa.kivra.chat

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ChatTurnRegistryTest {
    @Test
    fun `a new turn waits until the cancelled turn has finished its cleanup`() = runBlocking {
        val registry = ChatTurnRegistry()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstRunning = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                registry.exclusive("s1") {
                    events += "first-start"
                    firstRunning.complete(Unit)
                    try {
                        delay(60_000)
                    } finally {
                        // The partial reply is stored here, even though the turn is cancelled.
                        withContext(NonCancellable) {
                            delay(50)
                            events += "first-partial-stored"
                        }
                    }
                }
            }
        }
        firstRunning.await()
        val second = async { registry.exclusive("s1") { events += "second-start" } }
        awaitAll(first, second)
        assertEquals(listOf("first-start", "first-partial-stored", "second-start"), events.toList())
    }

    @Test
    fun `turns of different sessions do not stop each other`() = runBlocking {
        val registry = ChatTurnRegistry()
        val aRunning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = async { registry.exclusive("a") { aRunning.complete(Unit); release.await(); "a-done" } }
        aRunning.await()
        val b = registry.exclusive("b") { "b-done" }
        release.complete(Unit)
        assertEquals("b-done", b)
        assertEquals("a-done", a.await())
    }
}
