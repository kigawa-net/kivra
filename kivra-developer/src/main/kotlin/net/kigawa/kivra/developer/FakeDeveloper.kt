package net.kigawa.kivra.developer

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test and Acceptance Harness double for [DeveloperPort]. It returns what [responder] decides and records every call.
 * It never calls a model. Kept in main sources so that kivra-app tests can use it without a test-jar dependency.
 */
class FakeDeveloper(private val responder: suspend (DeveloperRequest) -> DeveloperResult) : DeveloperPort {
    private val calls = AtomicInteger()
    private val seen = CopyOnWriteArrayList<DeveloperRequest>()

    val callCount: Int get() = calls.get()

    /** Requests in call order. */
    val requests: List<DeveloperRequest> get() = seen.toList()

    override suspend fun propose(request: DeveloperRequest): DeveloperResult {
        calls.incrementAndGet()
        seen.add(request)
        return responder(request)
    }

    companion object {
        /** Always returns [result]. */
        fun returning(result: DeveloperResult): FakeDeveloper = FakeDeveloper { result }
    }
}
