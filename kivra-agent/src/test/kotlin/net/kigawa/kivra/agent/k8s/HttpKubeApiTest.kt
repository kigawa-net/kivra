package net.kigawa.kivra.agent.k8s

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talks to a local HTTP server that plays the API server. No cluster is involved. */
class HttpKubeApiTest {
    private class Seen(val method: String, val uri: String, val auth: String?, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 200
    private var response = "{}"

    init {
        server.createContext("/") { ex ->
            seen += Seen(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization"), String(ex.requestBody.readAllBytes()))
            val bytes = response.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val api: HttpKubeApi get() = HttpKubeApi(URI.create("http://127.0.0.1:${server.address.port}"), { "tok-1" })

    @Test
    fun createsAJobWithTheBearerToken() {
        api.createJob("ns1", K8sFixture.golden())
        val call = seen.single()
        assertEquals("POST", call.method)
        assertEquals("/apis/batch/v1/namespaces/ns1/jobs", call.uri)
        assertEquals("Bearer tok-1", call.auth)
        assertTrue("\"kind\":\"Job\"" in call.body)
    }

    @Test
    fun anExistingJobIsAFixedCodeAndNoBodyIsInTheError() {
        status = 409
        response = "{\"message\":\"secret detail\"}"
        val e = assertFailsWith<KubeApiException> { api.createJob("ns1", K8sFixture.golden()) }
        assertEquals("JOB_EXISTS", e.code)
        status = 403
        val forbidden = assertFailsWith<KubeApiException> { api.createJob("ns1", K8sFixture.golden()) }
        assertEquals("HTTP_4XX", forbidden.code)
        assertFalse("secret" in forbidden.toString())
    }

    @Test
    fun readsTheStateOfAJob() {
        response = """{"status":{"failed":1,"conditions":[{"type":"Failed","reason":"DeadlineExceeded"}]}}"""
        val state = api.jobState("ns1", "job-a")!!
        assertTrue(state.failed)
        assertTrue(state.deadlineExceeded)
        assertFalse(state.succeeded)
        assertEquals("/apis/batch/v1/namespaces/ns1/jobs/job-a", seen.single().uri)
    }

    @Test
    fun aMissingJobIsNull() {
        status = 404
        assertNull(api.jobState("ns1", "job-a"))
    }

    @Test
    fun listsJobsByTheLabelSelector() {
        response = """{"items":[{"metadata":{"name":"job-a","labels":{"kivra-run":"R1"}}},{"metadata":{"name":"job-b"}}]}"""
        val jobs = api.listJobs("ns1", "kivra-agent=1")
        assertEquals(listOf("job-a", "job-b"), jobs.map { it.name })
        assertEquals("R1", jobs[0].runId)
        assertNull(jobs[1].runId)
        assertEquals("/apis/batch/v1/namespaces/ns1/jobs?labelSelector=kivra-agent%3D1", seen.single().uri)
    }

    @Test
    fun aBadListingIsAnError() {
        response = "{}"
        assertFailsWith<KubeApiException> { api.listJobs("ns1", "kivra-agent=1") }
        response = "not json"
        assertFailsWith<KubeApiException> { api.listJobs("ns1", "kivra-agent=1") }
    }

    @Test
    fun deletesAJobInTheBackgroundAndAMissingJobIsNotAnError() {
        api.deleteJob("ns1", "job-a")
        assertEquals("DELETE", seen.single().method)
        assertEquals("/apis/batch/v1/namespaces/ns1/jobs/job-a?propagationPolicy=Background", seen.single().uri)
        status = 404
        api.deleteJob("ns1", "job-a")
        status = 500
        assertFailsWith<KubeApiException> { api.deleteJob("ns1", "job-a") }
    }

    @Test
    fun readsThePodOfAJob() {
        response = """{"items":[{"metadata":{"name":"pod-1"},"status":{"phase":"Running","containerStatuses":[{"name":"agent","state":{"running":{}}}]}}]}"""
        val pod = api.podOf("ns1", "job-a")!!
        assertEquals("pod-1", pod.name)
        assertTrue(pod.running)
        assertFalse(pod.terminated)
        assertEquals("/api/v1/namespaces/ns1/pods?labelSelector=job-name%3Djob-a", seen.single().uri)
    }

    @Test
    fun aTerminatedPodHasItsExitCodeAndAPodWithoutStatusIsNotRunning() {
        response = """{"items":[{"metadata":{"name":"pod-1"},"status":{"containerStatuses":[{"name":"agent","state":{"terminated":{"exitCode":137}}}]}}]}"""
        val dead = api.podOf("ns1", "job-a")!!
        assertTrue(dead.terminated)
        assertEquals(137, dead.exitCode)
        response = """{"items":[{"metadata":{"name":"pod-1"},"status":{"phase":"Pending"}}]}"""
        val pending = api.podOf("ns1", "job-a")!!
        assertFalse(pending.running)
        assertFalse(pending.terminated)
        response = """{"items":[]}"""
        assertNull(api.podOf("ns1", "job-a"))
    }

    @Test
    fun opensTheFollowedLogOfTheNamedContainer() {
        response = "log text"
        val text = api.openLog("ns1", "pod-1", "agent").use { String(it.readAllBytes()) }
        assertEquals("log text", text)
        assertEquals("/api/v1/namespaces/ns1/pods/pod-1/log?container=agent&follow=true", seen.single().uri)
        status = 404
        assertFailsWith<KubeApiException> { api.openLog("ns1", "pod-1", "agent") }
    }

    @Test
    fun anUnreachableServerIsAFixedCode() {
        val dead = HttpKubeApi(URI.create("http://127.0.0.1:1"), { "t" })
        assertEquals("IO", assertFailsWith<KubeApiException> { dead.deleteJob("ns1", "job-a") }.code)
    }

    @Test
    fun inClusterNeedsTheServiceAccountFiles() {
        assertFailsWith<KubeApiException> { HttpKubeApi.inCluster(emptyMap()) }
        assertFailsWith<KubeApiException> {
            HttpKubeApi.inCluster(mapOf("KUBERNETES_SERVICE_HOST" to "10.0.0.1"), java.nio.file.Path.of("/nonexistent"))
        }
        assertFailsWith<KubeApiException> {
            HttpKubeApi.inCluster(mapOf("KUBERNETES_SERVICE_HOST" to "bad host/x"), java.nio.file.Path.of("/nonexistent"))
        }
    }

    @Test
    fun inClusterRejectsAnEmptyTokenAtStartup() {
        val dir = java.nio.file.Files.createTempDirectory("sa")
        try {
            java.nio.file.Files.writeString(dir.resolve("token"), " \n")
            java.nio.file.Files.writeString(dir.resolve("ca.crt"), "not a certificate")
            val code = assertFailsWith<KubeApiException> {
                HttpKubeApi.inCluster(mapOf("KUBERNETES_SERVICE_HOST" to "10.0.0.1"), dir)
            }.code
            assertEquals("NOT_IN_CLUSTER", code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- exec framing ----

    @Test
    fun stdinFramesCarryTheStdinChannelAndKeepAllBytes() {
        val data = ByteArray(70_000) { (it % 251).toByte() }
        val frames = ExecProtocol.stdinFrames(data)
        assertEquals(3, frames.size)
        assertTrue(frames.all { it[0] == 0.toByte() })
        assertContentEquals(data, frames.flatMap { it.drop(1) }.toByteArray())
        assertTrue(ExecProtocol.stdinFrames(ByteArray(0)).isEmpty())
    }

    @Test
    fun demuxCollectsStdoutAndTheExitCode() {
        val demux = ExecProtocol.Demux(100)
        assertFalse(demux.feed(byteArrayOf(1, 'a'.code.toByte(), 'b'.code.toByte())))
        assertFalse(demux.feed(byteArrayOf(2, 'x'.code.toByte())))
        assertFalse(demux.feed(ByteArray(0)))
        val status = """{"status":"Failure","details":{"causes":[{"reason":"ExitCode","message":"3"}]}}"""
        assertTrue(demux.feed(byteArrayOf(3) + status.toByteArray()))
        assertEquals("ab", String(demux.stdout()))
        assertEquals(3, demux.exitCode)
        assertFalse(demux.truncated)
    }

    @Test
    fun demuxKeepsAtMostTheLimitAndSaysSo() {
        val demux = ExecProtocol.Demux(4)
        demux.feed(byteArrayOf(1) + "abc".toByteArray())
        demux.feed(byteArrayOf(1) + "defg".toByteArray())
        assertEquals("abcd", String(demux.stdout()))
        assertTrue(demux.truncated)
    }

    @Test
    fun exitCodeOfTheStatus() {
        assertEquals(0, ExecProtocol.exitCodeOf("""{"status":"Success"}"""))
        assertEquals(1, ExecProtocol.exitCodeOf("""{"status":"Failure","details":{"causes":[{"reason":"ExitCode","message":"1"}]}}"""))
        assertEquals(-1, ExecProtocol.exitCodeOf("""{"status":"Failure","reason":"InternalError"}"""))
        assertEquals(-1, ExecProtocol.exitCodeOf("garbage"))
        assertNotNull(ExecProtocol.SUBPROTOCOL)
    }
}
