package net.kigawa.kivra.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope

/**
 * One chat turn at a time per session. A new turn stops the turn before it, and waits until that turn has finished (its partial reply is
 * stored by then), before its own work starts. So the stored order is always: first message, first (partial) reply, second message.
 *
 * In memory: it orders turns within one server process. With several replicas a turn on another replica is not seen.
 */
class ChatTurnRegistry {
    private val active = ConcurrentHashMap<String, Job>()

    /** Runs [block] as the session's only turn: the previous one is cancelled and joined first. A turn that is itself replaced is cancelled. */
    suspend fun <T> exclusive(sessionId: String, block: suspend () -> T): T = coroutineScope {
        val me = coroutineContext[Job]!!
        // Registering first makes a chain of waiting turns cancel one another in order: each only waits for the one it replaced.
        val previous = active.put(sessionId, me)
        try {
            previous?.cancelAndJoin()
            block()
        } finally {
            active.remove(sessionId, me)
        }
    }
}
