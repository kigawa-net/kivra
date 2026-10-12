package net.kigawa.kivra.exkes

import io.ktor.client.engine.cio.CIO
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * Real Ktor CIO engine (no MockEngine): proves which transport failures happen before the request leaves the process.
 * Only refused / DNS / connect timeout may be [PortResult.Transient] (= NotSent proof upstream); a loss after the
 * request was written must be [PortResult.Unknown] for a mutating call.
 */
class ExkesTransportClassificationTest {
    private val spec = WorkspaceSpec("kivra-run-1")
    private val runtime = RuntimeId(WorkspaceId("ws-1"), "rt-1")

    private fun adapter(baseUrl: String, connect: kotlin.time.Duration = 2.seconds, request: kotlin.time.Duration = 5.seconds): ExkesAdapter {
        val cfg = ExkesConfig(baseUrl, requestTimeout = request, connectTimeout = connect)
        return ExkesAdapter(ExkesAdapter.newClient(CIO.create(), cfg), cfg)
    }

    @Test
    fun `connection refused before send is Transient`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort } // nothing listens on it any more
        assertIs<PortResult.Transient>(adapter("http://127.0.0.1:$port").stopRuntime(runtime), "refused on a POST")
    }

    @Test
    fun `refused on the pre-POST lookup is Transient and no POST is attempted`() = runBlocking<Unit> {
        // createWorkspace looks the name up first (GET); a failure there means the POST was never sent.
        val port = ServerSocket(0).use { it.localPort }
        assertIs<PortResult.Transient>(adapter("http://127.0.0.1:$port").createWorkspace(spec))
    }

    @Test
    fun `DNS failure before send is Transient`() = runBlocking<Unit> {
        assertIs<PortResult.Transient>(adapter("http://kivra-no-such-host.invalid").stopRuntime(runtime), "DNS on a POST")
    }

    @Test
    fun `connect timeout before send is Transient`() = runBlocking<Unit> {
        // Listen with backlog 1 and fill the accept queue without accepting: further SYNs are dropped, so the
        // connect phase hangs until the connect timeout (no request bytes are ever written).
        val server = ServerSocket(0, 1)
        val filler = List(4) { java.net.Socket().apply { runCatching { connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 200) } } }
        try {
            val r = adapter("http://127.0.0.1:${server.localPort}", connect = 300.milliseconds).stopRuntime(runtime)
            assertIs<PortResult.Transient>(r, "connect timeout on a POST: ${r.kindCode()}")
        } finally {
            filler.forEach { it.close() }
            server.close()
        }
    }

    @Test
    fun `peer closes after the request was written is Unknown, not NotSent`() = runBlocking<Unit> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s -> s.getInputStream().read(ByteArray(4096)); /* close without a response */ }
        }
        val r = adapter("http://127.0.0.1:${server.localPort}").stopRuntime(runtime)
        server.close()
        assertIs<PortResult.Unknown>(r, "got $r")
    }

    @Test
    fun `response timeout after the request was written is Unknown, not NotSent`() = runBlocking<Unit> {
        val server = ServerSocket(0)
        val held = AtomicReference<Any?>()
        thread(isDaemon = true) {
            val s = server.accept()
            held.set(s)
            s.getInputStream().read(ByteArray(4096))
            Thread.sleep(5_000) // never answers
        }
        val r = adapter("http://127.0.0.1:${server.localPort}", request = 500.milliseconds).stopRuntime(runtime)
        server.close()
        (held.get() as? java.net.Socket)?.close()
        assertIs<PortResult.Unknown>(r, "got $r")
    }
}
