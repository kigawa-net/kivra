package net.kigawa.kivra.agent.k8s

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * [KubeApi] over plain HTTPS to the API server, with the in-cluster ServiceAccount token (ADR 0010 追補 7 PR 4). It uses the JDK's
 * `java.net.http` client and WebSocket (for exec), so it adds no dependency. The token is read from its file on each request (the
 * kubelet rotates it). The API server is trusted through the cluster CA file only.
 *
 * Errors are [KubeApiException] with a fixed code (the HTTP status class or a short word). The URL, the token and the response body are
 * never in an error.
 *
 * [baseUrl] is `https://host:port` of the API server. Tests pass a local `http://` server; there is no real cluster in any test.
 */
class HttpKubeApi(
    private val baseUrl: URI,
    private val token: () -> String,
    sslContext: SSLContext? = null,
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : KubeApi {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .apply { if (sslContext != null) sslContext(sslContext) }
        .build()

    override fun createJob(namespace: String, manifest: JsonObject) {
        val response = send("POST", "/apis/batch/v1/namespaces/$namespace/jobs", manifest.toString())
        when {
            response.status == 409 -> throw KubeApiException("JOB_EXISTS")
            response.status !in 200..299 -> throw KubeApiException("HTTP_${response.status / 100}XX")
        }
    }

    override fun jobState(namespace: String, name: String): JobState? {
        val response = send("GET", "/apis/batch/v1/namespaces/$namespace/jobs/${seg(name)}")
        if (response.status == 404) return null
        if (response.status !in 200..299) throw KubeApiException("HTTP_${response.status / 100}XX")
        val status = parse(response.body).obj("status")
        val conditions = status?.get("conditions") as? JsonArray ?: JsonArray(emptyList())
        val deadline = conditions.any {
            val c = it as? JsonObject
            c?.str("type") == "Failed" && c.str("reason") == "DeadlineExceeded"
        }
        return JobState(
            succeeded = (status?.int("succeeded") ?: 0) > 0,
            failed = (status?.int("failed") ?: 0) > 0 || conditions.any { (it as? JsonObject)?.str("type") == "Failed" },
            deadlineExceeded = deadline,
        )
    }

    override fun listJobs(namespace: String, labelSelector: String): List<JobRef> {
        val response = send("GET", "/apis/batch/v1/namespaces/$namespace/jobs?labelSelector=${enc(labelSelector)}")
        if (response.status !in 200..299) throw KubeApiException("HTTP_${response.status / 100}XX")
        val items = parse(response.body).get("items") as? JsonArray ?: throw KubeApiException("BAD_RESPONSE")
        return items.map { item ->
            val metadata = (item as? JsonObject)?.obj("metadata") ?: throw KubeApiException("BAD_RESPONSE")
            val name = metadata.str("name") ?: throw KubeApiException("BAD_RESPONSE")
            JobRef(name, metadata.obj("labels")?.str(K8sConstants.LABEL_RUN_KEY))
        }
    }

    override fun deleteJob(namespace: String, name: String) {
        val response = send("DELETE", "/apis/batch/v1/namespaces/$namespace/jobs/${seg(name)}?propagationPolicy=Background")
        if (response.status == 404) return
        if (response.status !in 200..299) throw KubeApiException("HTTP_${response.status / 100}XX")
    }

    override fun podOf(namespace: String, jobName: String): PodState? {
        val selector = "job-name=$jobName"
        val response = send("GET", "/api/v1/namespaces/$namespace/pods?labelSelector=${enc(selector)}")
        if (response.status !in 200..299) throw KubeApiException("HTTP_${response.status / 100}XX")
        val items = parse(response.body).get("items") as? JsonArray ?: throw KubeApiException("BAD_RESPONSE")
        val pod = items.firstOrNull() as? JsonObject ?: return null
        val name = pod.obj("metadata")?.str("name") ?: throw KubeApiException("BAD_RESPONSE")
        val statuses = pod.obj("status")?.get("containerStatuses") as? JsonArray
        val container = statuses?.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { s -> s.str("name") == K8sConstants.CONTAINER_NAME } }
        val state = container?.obj("state")
        val terminated = state?.obj("terminated")
        return PodState(
            name = name,
            running = state?.obj("running") != null,
            terminated = terminated != null || pod.obj("status")?.str("phase").let { it == "Succeeded" || it == "Failed" },
            exitCode = terminated?.int("exitCode"),
        )
    }

    override fun openLog(namespace: String, pod: String, container: String): InputStream {
        val request = builder("/api/v1/namespaces/$namespace/pods/${seg(pod)}/log?container=${enc(container)}&follow=true").GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw KubeApiException("IO")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KubeApiException("INTERRUPTED")
        }
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw KubeApiException("HTTP_${response.statusCode() / 100}XX")
        }
        return response.body()
    }

    override fun exec(
        namespace: String,
        pod: String,
        container: String,
        command: List<String>,
        stdin: ByteArray?,
        maxStdoutBytes: Int,
        timeoutMillis: Long,
    ): ExecResult {
        val query = buildString {
            append("container=").append(enc(container))
            for (c in command) append("&command=").append(enc(c))
            append("&stdin=").append(stdin != null).append("&stdout=true&stderr=false&tty=false")
        }
        val wsBase = if (baseUrl.scheme == "https") "wss" else "ws"
        val uri = URI.create("$wsBase://${baseUrl.authority}/api/v1/namespaces/$namespace/pods/${seg(pod)}/exec?$query")
        val demux = ExecProtocol.Demux(maxStdoutBytes)
        val done = CompletableFuture<Unit>()
        val listener = object : WebSocket.Listener {
            private val partial = ByteArrayOutputStream()

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                partial.write(bytes)
                if (partial.size() > ExecProtocol.MAX_FRAME_BYTES) {
                    done.completeExceptionally(KubeApiException("EXEC_FRAME_TOO_LARGE"))
                } else if (last) {
                    val frame = partial.toByteArray()
                    partial.reset()
                    if (demux.feed(frame)) done.complete(Unit)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
                done.complete(Unit)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable?) {
                done.completeExceptionally(KubeApiException("EXEC_IO"))
            }
        }
        val ws = try {
            client.newWebSocketBuilder()
                .subprotocols(ExecProtocol.SUBPROTOCOL)
                .header("Authorization", "Bearer ${token()}")
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, listener)
                .get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw KubeApiException("EXEC_CONNECT")
        }
        try {
            if (stdin != null) {
                for (frame in ExecProtocol.stdinFrames(stdin)) ws.sendBinary(ByteBuffer.wrap(frame), true).get(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            try {
                done.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                return ExecResult(null, demux.stdout(), demux.truncated)
            }
            return ExecResult(demux.exitCode, demux.stdout(), demux.truncated)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? KubeApiException) ?: KubeApiException("EXEC_IO")
        } catch (e: TimeoutException) {
            throw KubeApiException("EXEC_IO")
        } finally {
            ws.abort()
        }
    }

    private class Response(val status: Int, val body: String)

    private fun builder(pathAndQuery: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(baseUrl.toString().trimEnd('/') + pathAndQuery))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/json")

    private fun send(method: String, pathAndQuery: String, body: String? = null): Response {
        val builder = builder(pathAndQuery)
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        if (body != null) builder.header("Content-Type", "application/json")
        val request = builder.method(method, publisher).build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            Response(response.statusCode(), response.body())
        } catch (e: IOException) {
            throw KubeApiException("IO")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KubeApiException("INTERRUPTED")
        }
    }

    private fun parse(body: String): JsonObject = try {
        Json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw KubeApiException("BAD_RESPONSE")
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    /** A path segment (a Job or Pod name, which the callers have validated as DNS names). Encoded anyway. */
    private fun seg(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"

        /**
         * The client of the Pod the agent runs in: the API server from `KUBERNETES_SERVICE_HOST` and `KUBERNETES_SERVICE_PORT`, the token
         * and the CA from the ServiceAccount directory. Throws [KubeApiException] when this is not a cluster.
         */
        fun inCluster(env: Map<String, String> = System.getenv(), serviceAccountDir: Path = Path.of(SA_DIR)): HttpKubeApi {
            val host = env["KUBERNETES_SERVICE_HOST"] ?: throw KubeApiException("NOT_IN_CLUSTER")
            val port = env["KUBERNETES_SERVICE_PORT"] ?: "443"
            if (!Regex("[0-9a-zA-Z:.\\-]{1,253}").matches(host) || !Regex("[0-9]{1,5}").matches(port)) throw KubeApiException("NOT_IN_CLUSTER")
            val hostPart = if (host.contains(':')) "[$host]" else host
            val tokenFile = serviceAccountDir.resolve("token")
            val ca = serviceAccountDir.resolve("ca.crt")
            if (!Files.isRegularFile(tokenFile) || !Files.isRegularFile(ca)) throw KubeApiException("NOT_IN_CLUSTER")
            // Fail at startup (before any attempt is claimed) on an unreadable or empty token. It is still read on every request, for rotation.
            if (Files.readString(tokenFile).isBlank()) throw KubeApiException("NOT_IN_CLUSTER")
            return HttpKubeApi(
                baseUrl = URI.create("https://$hostPart:$port"),
                token = { Files.readString(tokenFile).trim() },
                sslContext = sslContextTrusting(ca),
            )
        }

        /** An SSL context that trusts only the certificates of [caFile]. */
        internal fun sslContextTrusting(caFile: Path): SSLContext {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            Files.newInputStream(caFile).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input).forEachIndexed { i, cert -> store.setCertificateEntry("ca-$i", cert) }
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }
    }
}

/**
 * The channel framing of the Kubernetes exec WebSocket (`v4.channel.k8s.io`): the first byte of every frame is the channel. 0 is stdin,
 * 1 stdout, 2 stderr, 3 the final status (a JSON `Status` object, whose `ExitCode` cause carries the exit code), 4 resize.
 * Kept apart from the socket so that it can be tested without one.
 */
internal object ExecProtocol {
    const val SUBPROTOCOL = "v4.channel.k8s.io"
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    private const val STDIN_CHUNK = 32 * 1024
    private const val CH_STDOUT = 1
    private const val CH_STATUS = 3

    /** The frames that carry [bytes] on the stdin channel. */
    fun stdinFrames(bytes: ByteArray): List<ByteArray> =
        bytes.toList().chunked(STDIN_CHUNK).map { part -> ByteArray(part.size + 1).also { frame -> part.forEachIndexed { i, b -> frame[i + 1] = b } } }

    /** Collects the stdout (up to [max] bytes) and the status of one exec. */
    class Demux(private val max: Int) {
        private val out = ByteArrayOutputStream()

        var truncated: Boolean = false
            private set

        /** The exit code, or -1 when the status said failure without a code. Null until the status frame came. */
        var exitCode: Int? = null
            private set

        /** Returns true when the status frame was read (the exec has ended). */
        fun feed(frame: ByteArray): Boolean {
            if (frame.isEmpty()) return false
            when (frame[0].toInt()) {
                CH_STDOUT -> {
                    val room = max - out.size()
                    val take = minOf(frame.size - 1, room.coerceAtLeast(0))
                    out.write(frame, 1, take)
                    if (frame.size - 1 > take) truncated = true
                }
                CH_STATUS -> {
                    exitCode = exitCodeOf(String(frame, 1, frame.size - 1, Charsets.UTF_8))
                    return true
                }
            }
            return false
        }

        fun stdout(): ByteArray = out.toByteArray()
    }

    /** `Success` is 0. A `Failure` with an `ExitCode` cause is that code. Anything else is -1. */
    fun exitCodeOf(status: String): Int {
        val obj = try {
            Json.parseToJsonElement(status) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return -1
        if ((obj["status"] as? JsonPrimitive)?.contentOrNull == "Success") return 0
        val causes = ((obj["details"] as? JsonObject)?.get("causes") as? JsonElement)?.let { runCatching { it.jsonArray }.getOrNull() } ?: return -1
        for (cause in causes) {
            val c = cause as? JsonObject ?: continue
            if ((c["reason"] as? JsonPrimitive)?.contentOrNull == "ExitCode") {
                return (c["message"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
            }
        }
        return -1
    }
}
