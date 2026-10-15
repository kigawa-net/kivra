package net.kigawa.kivra.acceptance

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.kigawa.kivra.core.OperationStatus
import kotlinx.coroutines.runBlocking

/**
 * Negative test of the safety checker itself: a deliberately unsafe provider path (a raw POST that bypasses the Journal,
 * i.e. an automatic replay) must make the invariant fail. If this test ever passes without the failure, the checker is broken.
 */
class SafetyCheckerNegativeTest {

    @Test
    fun `an automatic replay outside the journal is caught by the invariant checker`() {
        AcceptanceGuard.require()
        val failure = assertFailsWith<IllegalStateException> {
            deterministicScenario("negative-rogue-replay", runs = 1) {
                val runId = newRun()
                val ws = seedWorkspace(runId)
                val l1 = acquire(runId, "worker-1")
                perform(executor(), l1, runtimeCreateOp(runId, ws))
                // The rogue path: the same provider POST again, without any journal dispatch.
                val raw = HttpClient(proxy.engine())
                runBlocking {
                    raw.post("http://fake-exkes.invalid/v1/workspaces/$ws/runtimes") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"templateId":"tpl-harness","labels":{"kivra.run-id":"${runId.value}"}}""")
                    }
                }
                expect(runId) { operationStatus(OperationStatus.CONFIRMED) }
            }
        }
        assertTrue(failure.message.orEmpty().contains("INVARIANT VIOLATED"), "checker must name the violated invariant: ${failure.message}")
        assertTrue(failure.message.orEmpty().contains("sent 1 time"), "checker must report the replayed send: ${failure.message}")
    }
}
