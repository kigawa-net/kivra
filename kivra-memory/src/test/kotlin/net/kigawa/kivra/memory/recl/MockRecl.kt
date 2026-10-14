package net.kigawa.kivra.memory.recl

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.DeleteMode
import net.kigawa.kivra.memory.FakeMemoryPort
import net.kigawa.kivra.memory.MemoryEntry
import net.kigawa.kivra.memory.MemoryErrorKind
import net.kigawa.kivra.memory.MemoryException
import net.kigawa.kivra.memory.MemoryId
import net.kigawa.kivra.memory.MemoryKind
import net.kigawa.kivra.memory.MemoryPatch
import net.kigawa.kivra.memory.MemoryProvenance
import net.kigawa.kivra.memory.MemoryQuery
import net.kigawa.kivra.memory.NewMemoryEntry
import net.kigawa.kivra.memory.ScopeRef
import net.kigawa.kivra.memory.Sensitivity
import net.kigawa.kivra.memory.SourceRef
import net.kigawa.kivra.memory.SourceType
import net.kigawa.kivra.memory.WriteProvenance
import java.io.IOException
import java.time.Instant

val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun reclContext(e: MemoryEntry) = ReclContext(
    id = e.id.value, version = e.version, status = e.status.name.lowercase(), kind = e.kind.name.lowercase(), scopes = e.scope.map { it.value },
    title = e.title, summary = e.summary, body = e.body,
    sources = e.sources.map { ReclSource(it.type.name.lowercase(), it.uri, it.locator, it.retrievedAt.toString(), it.contentHash) },
    provenance = e.provenance.name.lowercase(), confidence = e.confidence, importance = e.importance, sensitivity = e.sensitivity.name.lowercase(),
    validFrom = e.validFrom?.toString(), validUntil = e.validUntil?.toString(), retention = e.retention, supersedes = e.supersedes.map { it.value },
    pinned = e.pinned, createdAt = e.createdAt.toString(), updatedAt = e.updatedAt.toString(), createdBy = e.writtenBy.createdBy,
    taskId = e.writtenBy.taskId, executionId = e.writtenBy.executionId,
)

private fun newEntry(n: ReclNewContext) = NewMemoryEntry(
    kind = MemoryKind.valueOf(n.kind.uppercase()), scope = n.scopes.map(ScopeRef::parse), title = n.title, summary = n.summary, body = n.body,
    sources = n.sources.map { SourceRef(SourceType.valueOf(it.type.uppercase()), it.uri, it.locator, Instant.parse(it.retrievedAt), it.contentHash) },
    provenance = MemoryProvenance.valueOf(n.provenance.uppercase()), confidence = n.confidence, importance = n.importance,
    sensitivity = Sensitivity.valueOf(n.sensitivity.uppercase()), validFrom = n.validFrom?.let(Instant::parse), validUntil = n.validUntil?.let(Instant::parse),
    retention = n.retention, supersedes = n.supersedes.map(::MemoryId), pinned = n.pinned, writtenBy = WriteProvenance(n.taskId, n.executionId, n.createdBy),
)

/** A stand-in for recl implementing the PLANNED contract on top of [FakeMemoryPort]. Test-only. */
class MockRecl(val fake: FakeMemoryPort, private val ctx: AccessContext) {
    val requests = mutableListOf<HttpRequestData>()

    /** Fault switch: the next N `POST /contexts` are APPLIED and then the connection drops (response lost). */
    var responsesLostAfterApply = 0

    /** Fault switch: the next N `POST /contexts` are APPLIED and then answered 429 with `Retry-After`. */
    var rateLimitedAfterApply = 0

    val engine = MockEngine { request ->
        requests += request
        try {
            handle(request)
        } catch (e: MemoryException) {
            val status = when (e.kind) {
                MemoryErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
                MemoryErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
                MemoryErrorKind.VERSION_CONFLICT -> HttpStatusCode.Conflict
                else -> HttpStatusCode.UnprocessableEntity
            }
            respond("{}", status, jsonHeaders)
        }
    }

    private fun body(r: HttpRequestData) = (r.body as TextContent).text

    private suspend fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(r: HttpRequestData): io.ktor.client.request.HttpResponseData {
        val path = r.url.encodedPath.removePrefix("/api/v1")
        val m = r.method
        return when {
            m == HttpMethod.Get && path == "/health" -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            m == HttpMethod.Post && path == "/contexts/search" -> {
                val q = testJson.decodeFromString<ReclSearchRequest>(body(r))
                val hits = fake.search(
                    MemoryQuery(q.query, q.scopes.map(ScopeRef::parse), q.kinds.map { MemoryKind.valueOf(it.uppercase()) }.toSet(), q.limit, Sensitivity.valueOf(q.maxSensitivity.uppercase())),
                    ctx,
                )
                respond(testJson.encodeToString(ReclSearchResponse(hits.map { ReclSearchHit(reclContext(it.entry), it.score) })), HttpStatusCode.OK, jsonHeaders)
            }
            m == HttpMethod.Get && path == "/contexts/pinned" -> {
                val scopes = r.url.parameters.getAll("scope").orEmpty().map(ScopeRef::parse)
                respond(testJson.encodeToString(ReclPinnedResponse(fake.pinned(scopes, ctx).map(::reclContext))), HttpStatusCode.OK, jsonHeaders)
            }
            m == HttpMethod.Post && path == "/contexts" -> {
                val n = testJson.decodeFromString<ReclNewContext>(body(r))
                val id = fake.save(newEntry(n), IdempotencyKey(r.headers["Idempotency-Key"]!!), ctx)
                if (responsesLostAfterApply > 0) {
                    responsesLostAfterApply--
                    throw IOException("connection reset after the server applied the POST")
                }
                if (rateLimitedAfterApply > 0) {
                    rateLimitedAfterApply--
                    return respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("1")))
                }
                respond(testJson.encodeToString(ReclIdResponse(id.value)), HttpStatusCode.Created, jsonHeaders)
            }
            m == HttpMethod.Post && path.endsWith("/supersede") -> {
                val req = testJson.decodeFromString<ReclSupersedeRequest>(body(r))
                val old = MemoryId(path.removePrefix("/contexts/").removeSuffix("/supersede"))
                val id = fake.supersede(old, req.expectedVersion, newEntry(req.new), IdempotencyKey(r.headers["Idempotency-Key"]!!), ctx)
                respond(testJson.encodeToString(ReclIdResponse(id.value)), HttpStatusCode.OK, jsonHeaders)
            }
            m == HttpMethod.Get && path.startsWith("/contexts/") -> {
                val e = fake.get(MemoryId(path.removePrefix("/contexts/")), ctx) ?: return respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                respond(testJson.encodeToString(reclContext(e)), HttpStatusCode.OK, jsonHeaders)
            }
            m == HttpMethod.Patch && path.startsWith("/contexts/") -> {
                val req = testJson.decodeFromString<ReclUpdateRequest>(body(r))
                val p = req.patch
                val e = fake.update(
                    MemoryId(path.removePrefix("/contexts/")), req.expectedVersion,
                    MemoryPatch(p.summary, p.body, p.importance, p.pinned, p.validUntil?.let(Instant::parse)), ctx,
                )
                respond(testJson.encodeToString(reclContext(e)), HttpStatusCode.OK, jsonHeaders)
            }
            m == HttpMethod.Delete && path.startsWith("/contexts/") -> {
                fake.delete(MemoryId(path.removePrefix("/contexts/")), DeleteMode.valueOf(r.url.parameters["mode"]!!.uppercase()), ctx)
                respond("", HttpStatusCode.NoContent)
            }
            else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
        }
    }
}
