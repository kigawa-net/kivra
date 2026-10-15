package net.kigawa.kivra.acceptance

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent

/**
 * Sits between the exkes adapter and [FakeExkes] as a Ktor MockEngine handler and injects delivery faults. Each rule is
 * matched by method and path fragment and consumed by its first matching request (deterministic, no timing).
 *
 * - [persistEffectThenDropResponse]: the provider persists the effect, then the connection is cut (timeout). The
 *   adapter sees a transport exception on a mutating call, which is Unknown.
 * - [persistEffectThenStatus]: the provider persists the effect and answers with [status] (e.g. 429) instead.
 * - [delayBefore]: the request is held at [barrier] before it reaches the provider; the effect happens after release.
 * - [rejectBefore]: the provider answers [status] without persisting anything (no effect).
 */
class FaultProxy(
    private val fake: FakeExkes,
    private val barriers: NamedBarriers,
    private val trace: ReplayTrace,
) {
    private sealed interface Action {
        data object Deliver : Action
        data object DropAfterEffect : Action
        data class StatusAfterEffect(val status: HttpStatusCode) : Action
        data class DelayBefore(val barrier: String) : Action
        data class RejectBefore(val status: HttpStatusCode) : Action
    }

    private class Rule(val method: HttpMethod, val pathFragment: String, val action: Action)

    private val rules = ArrayDeque<Rule>()

    fun persistEffectThenDropResponse(method: HttpMethod = HttpMethod.Post, pathFragment: String = "/runtimes") =
        add(Rule(method, pathFragment, Action.DropAfterEffect))

    fun persistEffectThenStatus(status: HttpStatusCode, method: HttpMethod = HttpMethod.Post, pathFragment: String = "/runtimes") =
        add(Rule(method, pathFragment, Action.StatusAfterEffect(status)))

    fun delayBefore(barrier: String, method: HttpMethod = HttpMethod.Post, pathFragment: String = "/runtimes") =
        add(Rule(method, pathFragment, Action.DelayBefore(barrier)))

    fun rejectBefore(status: HttpStatusCode, method: HttpMethod = HttpMethod.Post, pathFragment: String = "/runtimes") =
        add(Rule(method, pathFragment, Action.RejectBefore(status)))

    private fun add(rule: Rule) {
        rules += rule
    }

    /** The engine to give to [net.kigawa.kivra.exkes.ExkesAdapter.newClient]. Only this engine ever answers. */
    fun engine(): HttpClientEngine = MockEngine { req -> handle(req) }

    private suspend fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val path = req.url.encodedPath
        val body = bodyOf(req)
        if (req.method == HttpMethod.Post) fake.recordPost(path, body)
        val rule = rules.firstOrNull { it.method == req.method && path.contains(it.pathFragment) }
        val action = rule?.also { rules.remove(it) }?.action ?: Action.Deliver
        if (action is Action.DelayBefore) {
            trace.record("fault: request held at barrier")
            barriers.reach(action.barrier)
            trace.record("fault: request released from barrier")
        }
        if (action is Action.RejectBefore) {
            trace.record("fault: rejected ${action.status.value} without effect")
            return respond("{}", action.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val served = fake.serve(req.method, path, body)
        return when (action) {
            Action.DropAfterEffect -> {
                trace.record("fault: effect persisted, response dropped")
                throw HttpRequestTimeoutException(req.url.toString(), 1000)
            }
            is Action.StatusAfterEffect -> {
                trace.record("fault: effect persisted, answered ${action.status.value}")
                respond("{}", action.status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            else -> respond(served.body, served.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun bodyOf(req: HttpRequestData): String? = when (val c = req.body) {
        is TextContent -> c.text
        is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
        else -> null
    }
}
