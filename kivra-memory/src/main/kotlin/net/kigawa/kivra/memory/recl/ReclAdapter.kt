package net.kigawa.kivra.memory.recl

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.DeleteMode
import net.kigawa.kivra.memory.MemoryCapability
import net.kigawa.kivra.memory.MemoryEntry
import net.kigawa.kivra.memory.MemoryErrorKind
import net.kigawa.kivra.memory.MemoryException
import net.kigawa.kivra.memory.MemoryHit
import net.kigawa.kivra.memory.MemoryId
import net.kigawa.kivra.memory.MemoryKind
import net.kigawa.kivra.memory.MemoryPatch
import net.kigawa.kivra.memory.MemoryProvenance
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryQuery
import net.kigawa.kivra.memory.MemoryStatus
import net.kigawa.kivra.memory.NewMemoryEntry
import net.kigawa.kivra.memory.ProviderHealth
import net.kigawa.kivra.memory.ScopeRef
import net.kigawa.kivra.memory.SecretFilter
import net.kigawa.kivra.memory.Sensitivity
import net.kigawa.kivra.memory.SourceRef
import net.kigawa.kivra.memory.SourceType
import net.kigawa.kivra.memory.WriteProvenance
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Instant
import kotlin.random.Random

/**
 * [MemoryProvider] over the PLANNED recl REST API (design #6 section 4.2). SKELETON: recl is an empty repository
 * (only Issues #1 to #8 exist), so the endpoints below are Kivra's expected contract and NOTHING has been verified
 * against a real server. 要確認 items are collected in docs/recl-requests.md and the README.
 *
 * Planned endpoints (all 要確認, see [ReclConfig.apiPrefix]):
 *  - `POST   /contexts/search`            search (recl#6)
 *  - `GET    /contexts/{id}`              get (recl#4)
 *  - `GET    /contexts/pinned?scope=..`   pinned (recl#4)
 *  - `POST   /contexts`                   save, `Idempotency-Key` header (idempotency support unconfirmed, section 4.3-1)
 *  - `PATCH  /contexts/{id}`              update with `expected_version` (recl#4)
 *  - `POST   /contexts/{id}/supersede`    supersede (recl#4)
 *  - `DELETE /contexts/{id}?mode=soft|redact`  delete (REDACT unplanned, section 4.3-3)
 *  - `GET    /health`                     health (unconfirmed)
 *
 * Safety rules enforced here (lessons from earlier adapter reviews):
 *  - https only, bearer token only to the configured host; redirects are NEVER followed (3xx = PROTOCOL error);
 *  - only GET is retried; POST / PATCH / DELETE are never auto re-sent. Re-delivery of a failed save is the job of
 *    the Write Queue with the same idempotency key, and only when [MemoryCapability.IDEMPOTENT_SAVE] is declared;
 *  - response identity is verified (requested id == returned id, version advanced, scopes within the caller's
 *    allowed scopes); search hits outside the requested scopes / sensitivity / kinds are dropped (defence in depth);
 *  - request / response bodies, memory text and the token never appear in exception messages;
 *  - secrets are refused before anything is sent.
 */
class ReclAdapter(
    private val config: ReclConfig,
    engine: HttpClientEngine,
    private val credentials: ReclCredentials,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() },
) : MemoryProvider, AutoCloseable {

    override val capabilities: Set<MemoryCapability> = buildSet {
        if (config.idempotentSaveConfirmed) add(MemoryCapability.IDEMPOTENT_SAVE)
        if (config.redactSupported) add(MemoryCapability.REDACT)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // Built here (not injected) so that redirect-following can never be re-enabled by a caller.
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMs
            requestTimeoutMillis = config.requestTimeoutMs
            socketTimeoutMillis = config.requestTimeoutMs
        }
    }

    override fun close() = client.close()

    // ---- operations ----

    override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> {
        requirePermitted(q.scopes, ctx)
        val req = ReclSearchRequest(q.text, q.scopes.map { it.value }, q.kinds.map { it.name.lowercase() }, q.limit, q.maxSensitivity.name.lowercase())
        val res = call(HttpMethod.Post, "/contexts/search", ctx, body = json.encodeToString(req))
        val parsed = decode<ReclSearchResponse>(res)
        val seen = HashSet<String>()
        val out = ArrayList<MemoryHit>()
        for (h in parsed.results) {
            val e = runCatching { toEntry(h.context) }.getOrNull() ?: continue
            val ok = e.status == MemoryStatus.ACTIVE &&
                h.score.isFinite() &&
                e.sensitivity <= q.maxSensitivity &&
                (q.kinds.isEmpty() || e.kind in q.kinds) &&
                e.scope.all { s -> ctx.permits(s) } &&
                e.scope.any { s -> q.scopes.any { s.isWithin(it) } } &&
                seen.add(e.id.value)
            if (ok) out += MemoryHit(e.copy(body = null), h.score)
            if (out.size >= q.limit) break
        }
        return out
    }

    override suspend fun get(id: MemoryId, ctx: AccessContext): MemoryEntry? {
        val res = try {
            call(HttpMethod.Get, "/contexts/${id.value.encodeURLPathPart()}", ctx, attempts = config.maxReadAttempts)
        } catch (e: MemoryException) {
            if (e.kind == MemoryErrorKind.NOT_FOUND) return null else throw e
        }
        val e = toEntry(decode<ReclContext>(res))
        if (e.id != id) protocol("response id does not match the requested id")
        if (e.scope.any { !ctx.permits(it) }) protocol("response is outside the allowed scopes")
        return if (e.status == MemoryStatus.DELETED) null else e
    }

    override suspend fun pinned(scopes: List<ScopeRef>, ctx: AccessContext): List<MemoryEntry> {
        requirePermitted(scopes, ctx)
        val query = scopes.joinToString("&") { "scope=" + it.value.encodeURLParameter() }
        val res = call(HttpMethod.Get, "/contexts/pinned?$query", ctx, attempts = config.maxReadAttempts)
        val seen = HashSet<String>()
        return decode<ReclPinnedResponse>(res).results.mapNotNull { runCatching { toEntry(it) }.getOrNull() }.filter { e ->
            e.status == MemoryStatus.ACTIVE && e.pinned && e.scope.all { ctx.permits(it) } &&
                e.scope.any { s -> scopes.any { s.isWithin(it) } } && seen.add(e.id.value)
        }
    }

    override suspend fun save(e: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId {
        guard(e, ctx)
        val res = call(HttpMethod.Post, "/contexts", ctx, body = json.encodeToString(toNew(e, key)), idempotencyKey = key.value)
        return parseId(decode<ReclIdResponse>(res).id)
    }

    override suspend fun update(id: MemoryId, expectedVersion: Int, patch: MemoryPatch, ctx: AccessContext): MemoryEntry {
        requireNoSecret(listOfNotNull(patch.summary, patch.body))
        val req = ReclUpdateRequest(expectedVersion, ReclPatch(patch.summary, patch.body, patch.importance, patch.pinned, patch.validUntil?.toString()))
        val res = call(HttpMethod.Patch, "/contexts/${id.value.encodeURLPathPart()}", ctx, body = json.encodeToString(req))
        val e = toEntry(decode<ReclContext>(res))
        if (e.id != id) protocol("response id does not match the requested id")
        if (e.version <= expectedVersion) protocol("response version did not advance")
        if (e.scope.any { !ctx.permits(it) }) protocol("response is outside the allowed scopes")
        return e
    }

    override suspend fun supersede(oldId: MemoryId, expectedVersion: Int, new: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId {
        guard(new, ctx)
        val req = ReclSupersedeRequest(expectedVersion, toNew(new, key))
        val res = call(HttpMethod.Post, "/contexts/${oldId.value.encodeURLPathPart()}/supersede", ctx, body = json.encodeToString(req), idempotencyKey = key.value)
        val id = parseId(decode<ReclIdResponse>(res).id)
        if (id == oldId) protocol("supersede returned the old id")
        return id
    }

    override suspend fun delete(id: MemoryId, mode: DeleteMode, ctx: AccessContext) {
        // Fail closed: never silently downgrade a Redact request to a soft delete.
        if (mode == DeleteMode.REDACT && !config.redactSupported) throw MemoryException(MemoryErrorKind.UNSUPPORTED, "recl redact API is unconfirmed (要確認)")
        call(HttpMethod.Delete, "/contexts/${id.value.encodeURLPathPart()}?mode=${mode.name.lowercase()}", ctx)
    }

    override suspend fun health(): ProviderHealth = try {
        call(HttpMethod.Get, "/health", null)
        ProviderHealth.UP
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProviderHealth.DOWN
    }

    // ---- transport ----

    private suspend fun call(
        method: HttpMethod,
        path: String,
        ctx: AccessContext?,
        body: String? = null,
        idempotencyKey: String? = null,
        attempts: Int = 1,
    ): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return callOnce(method, path, ctx, body, idempotencyKey)
            } catch (e: MemoryException) {
                val retry = method == HttpMethod.Get && attempt < attempts &&
                    (e.kind == MemoryErrorKind.UNAVAILABLE || e.kind == MemoryErrorKind.OUTCOME_UNKNOWN)
                if (!retry) throw e
                sleep((100L shl (attempt - 1)).coerceAtMost(2_000L) + (jitter() * 100).toLong())
            }
        }
    }

    private suspend fun callOnce(method: HttpMethod, path: String, ctx: AccessContext?, body: String?, idempotencyKey: String?): ByteArray {
        val token = try {
            credentials.bearerToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MemoryException(MemoryErrorKind.UNAVAILABLE, "credential provider failed")
        }
        if (token.isBlank() || token.any { it.isWhitespace() || it.isISOControl() }) throw MemoryException(MemoryErrorKind.FORBIDDEN, "invalid credential")
        val response = try {
            client.request(config.baseUrl + config.apiPrefix + path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                ctx?.let { header("X-Trace-Id", it.traceId) }
                idempotencyKey?.let { header("Idempotency-Key", it) }
                if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectTimeoutException) {
            throw MemoryException(MemoryErrorKind.UNAVAILABLE, "recl connect timeout")
        } catch (e: ConnectException) {
            throw MemoryException(MemoryErrorKind.UNAVAILABLE, "recl connection refused")
        } catch (e: UnknownHostException) {
            throw MemoryException(MemoryErrorKind.UNAVAILABLE, "recl host not found")
        } catch (e: IOException) {
            throw MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "recl request failed: ${e.javaClass.simpleName}")
        }
        val code = response.status.value
        if (code !in 200..299) throw statusError(code)
        val declared = response.contentLength()
        if (declared != null && declared > config.maxResponseBytes) protocol("response too large")
        val bytes = response.bodyAsBytes()
        if (bytes.size > config.maxResponseBytes) protocol("response too large")
        if (bytes.isNotEmpty() && response.contentType()?.match(ContentType.Application.Json) != true) protocol("unexpected content type")
        return bytes
    }

    private fun statusError(code: Int): MemoryException = when (code) {
        in 300..399 -> MemoryException(MemoryErrorKind.PROTOCOL, "unexpected redirect (not followed)")
        401, 403 -> MemoryException(MemoryErrorKind.FORBIDDEN, "recl denied the request ($code)")
        404 -> MemoryException(MemoryErrorKind.NOT_FOUND, "recl: not found")
        409, 412 -> MemoryException(MemoryErrorKind.VERSION_CONFLICT, "recl: version conflict")
        405, 501 -> MemoryException(MemoryErrorKind.UNSUPPORTED, "recl: operation not supported ($code)")
        429 -> MemoryException(MemoryErrorKind.UNAVAILABLE, "recl: rate limited")
        in 400..499 -> MemoryException(MemoryErrorKind.REJECTED, "recl rejected the request ($code)")
        in 500..599 -> MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "recl server error ($code)")
        else -> MemoryException(MemoryErrorKind.PROTOCOL, "unexpected status $code")
    }

    // ---- guards / mapping ----

    private fun requirePermitted(scopes: List<ScopeRef>, ctx: AccessContext) {
        if (scopes.any { !ctx.permits(it) }) throw MemoryException(MemoryErrorKind.FORBIDDEN, "scope not permitted for this access context")
    }

    private fun guard(e: NewMemoryEntry, ctx: AccessContext) {
        requirePermitted(e.scope, ctx)
        requireNoSecret(listOfNotNull(e.title, e.summary, e.body) + e.sources.mapNotNull { it.locator })
    }

    private fun requireNoSecret(texts: List<String>) {
        val kinds = texts.flatMap { SecretFilter.scan(it) }.map { it.kind }.toSet()
        if (kinds.isNotEmpty()) throw MemoryException(MemoryErrorKind.REJECTED, "secret detected, refusing to send (kinds=$kinds)")
    }

    private fun parseId(raw: String): MemoryId =
        if (MemoryId.isValid(raw)) MemoryId(raw) else protocol("invalid id in response")

    private fun protocol(msg: String): Nothing = throw MemoryException(MemoryErrorKind.PROTOCOL, msg)

    private inline fun <reified T> decode(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.decodeToString())
    } catch (e: SerializationException) {
        protocol("malformed response")
    } catch (e: IllegalArgumentException) {
        protocol("malformed response")
    }

    private fun toNew(e: NewMemoryEntry, key: IdempotencyKey) = ReclNewContext(
        kind = e.kind.name.lowercase(), scopes = e.scope.map { it.value }, title = e.title, summary = e.summary, body = e.body,
        sources = e.sources.map { ReclSource(it.type.name.lowercase(), it.uri, it.locator, it.retrievedAt.toString(), it.contentHash) },
        provenance = e.provenance.name.lowercase(), confidence = e.confidence, importance = e.importance,
        sensitivity = e.sensitivity.name.lowercase(), validFrom = e.validFrom?.toString(), validUntil = e.validUntil?.toString(),
        retention = e.retention, supersedes = e.supersedes.map { it.value }, pinned = e.pinned,
        createdBy = e.writtenBy.createdBy, taskId = e.writtenBy.taskId, executionId = e.writtenBy.executionId, idempotencyKey = key.value,
    )

    private fun toEntry(c: ReclContext): MemoryEntry = try {
        MemoryEntry(
            id = MemoryId(c.id), version = c.version.also { require(it >= 1) }, status = enumOf<MemoryStatus>(c.status),
            kind = enumOf<MemoryKind>(c.kind), scope = c.scopes.map(ScopeRef::parse).also { require(it.isNotEmpty()) },
            title = c.title, summary = c.summary, body = c.body,
            sources = c.sources.map { SourceRef(enumOf<SourceType>(it.type), it.uri, it.locator, Instant.parse(it.retrievedAt), it.contentHash) },
            provenance = enumOf<MemoryProvenance>(c.provenance), confidence = c.confidence.also { require(it in 0.0..1.0) },
            importance = c.importance.also { require(it in 0.0..1.0) }, sensitivity = enumOf<Sensitivity>(c.sensitivity),
            validFrom = c.validFrom?.let(Instant::parse), validUntil = c.validUntil?.let(Instant::parse), retention = c.retention,
            supersedes = c.supersedes.map(::MemoryId), pinned = c.pinned,
            createdAt = Instant.parse(c.createdAt), updatedAt = Instant.parse(c.updatedAt),
            writtenBy = WriteProvenance(c.taskId, c.executionId, c.createdBy),
        )
    } catch (e: MemoryException) {
        throw e
    } catch (e: RuntimeException) {
        // IllegalArgumentException / DateTimeParseException: the response violates the expected contract.
        protocol("response does not match the expected schema")
    }

    private inline fun <reified E : Enum<E>> enumOf(raw: String): E = enumValueOf<E>(raw.uppercase())
}
