package net.kigawa.kivra.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import kotlinx.coroutines.Dispatchers

internal const val TEST_SHA = "0123456789abcdef0123456789abcdef01234567"
internal const val KIVRA_BASE = "https://kivra.example"
internal const val TOKEN_URL = "https://auth.example/realms/r/protocol/openid-connect/token"

internal val JSON_HEADERS: Headers = headersOf(HttpHeaders.ContentType, "application/json")

/** A claim body with an unknown field (the agent must ignore it). */
internal val CLAIM_BODY = """
{"attemptId":"att-1","runId":"run-1","generation":3,"input":{
  "baseSha":"$TEST_SHA",
  "task":{"origin":"title","value":"do the thing","trust":"UNTRUSTED","truncated":false},
  "limits":{"maxRuntimeSeconds":1800,"maxTurns":30,"maxOutputBytes":1048576,"maxChangedFiles":20,
    "maxFileBytes":200000,"maxTotalBytes":1000000,"maxDeletes":5},
  "allowedPathPatterns":["src/**"],"deniedPathPatterns":[".github/**"],"futureField":true}}
""".trimIndent()

internal const val STATE_RUNNING = """{"attemptId":"att-1","state":"RUNNING"}"""
internal const val STATUS_OK = """{"attemptId":"att-1","state":"RUNNING","cancelRequested":false}"""
internal const val STATUS_CANCEL = """{"attemptId":"att-1","state":"RUNNING","cancelRequested":true}"""

/** One request as the fake kivra saw it. */
internal class Recorded(
    val method: String,
    val path: String,
    val authorization: List<String>,
    val contentType: String?,
    val body: String,
    val atMillis: Long,
)

/** A canned reply. */
internal class Canned(val status: Int, val body: String = "", val location: String? = null)

/** A token source that hands out tok-1, tok-2, ... and counts invalidations. */
internal class FixedTokens : TokenSource {
    var invalidations = 0
    var issued = 0
    override suspend fun token(): AccessToken = AccessToken("tok-${++issued}")
    override fun invalidate() {
        invalidations++
    }
}

/**
 * An HTTP client on MockEngine with the agent's settings. Every request is recorded into [recorded]; [handler] decides the reply.
 * [clock] gives the virtual time (ms) for the record.
 */
internal fun fakeKivraHttp(
    recorded: MutableList<Recorded>,
    nowMillis: () -> Long = { 0L },
    handler: (Recorded) -> Canned,
): HttpClient {
    // Unconfined: the reply is produced on the calling thread, so runTest's virtual time decides the order of events.
    val config = MockEngineConfig().apply {
        dispatcher = Dispatchers.Unconfined
        addHandler { request: HttpRequestData ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            val rec = Recorded(
                method = request.method.value,
                path = request.url.encodedPath,
                authorization = request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                contentType = request.body.contentType?.toString(),
                body = body,
                atMillis = nowMillis(),
            )
            synchronized(recorded) { recorded += rec }
            val canned = handler(rec)
            val headers = if (canned.location != null) headersOf(HttpHeaders.Location, canned.location) else JSON_HEADERS
            respond(content = canned.body, status = HttpStatusCode.fromValue(canned.status), headers = headers)
        }
    }
    return HttpClient(MockEngine(config)) { agentClientConfig() }
}

internal fun Path.writeSecret(content: String, mode: String = "rw-------"): Path {
    Files.writeString(this, content)
    Files.setPosixFilePermissions(this, PosixFilePermissions.fromString(mode))
    return this
}

/** A clock the test moves by hand. */
internal class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

internal fun testConfigEnv(dir: Path, secretFile: Path, poll: Long = 5_000, heartbeat: Long = 5_000): Map<String, String> = mapOf(
    AgentConfig.ENV_KIVRA_URL to KIVRA_BASE,
    AgentConfig.ENV_TOKEN_URL to TOKEN_URL,
    AgentConfig.ENV_CLIENT_ID to "kivra-agent-test",
    AgentConfig.ENV_CLIENT_SECRET_FILE to secretFile.toString(),
    AgentConfig.ENV_WORK_DIR to dir.toString(),
    AgentConfig.ENV_POLL_INTERVAL_MS to poll.toString(),
    AgentConfig.ENV_HEARTBEAT_INTERVAL_MS to heartbeat.toString(),
)

internal fun synchronizedList(): MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
