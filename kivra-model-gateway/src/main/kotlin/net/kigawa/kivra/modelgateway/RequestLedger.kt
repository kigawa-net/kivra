package net.kigawa.kivra.modelgateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

sealed interface LedgerClaim {
    /** A new record was created (or a never-sent record was re-opened). The caller may send. */
    data object Claimed : LedgerClaim

    /** The requestId already finished. [response] is null when the original call was not replayable (stream). */
    data class Completed(val response: ModelResponse?) : LedgerClaim

    /** Refused. [code] is REQUEST_ID_REUSED, DUPLICATE_REQUEST (in flight / sent / outcome unknown) or LEDGER_FULL. */
    data class Rejected(val code: String, val message: String) : LedgerClaim
}

/**
 * Authoritative store for requestId idempotency and provider-call state. Contract:
 * - [begin] binds a requestId to a [RequestDigest]; the same requestId with another digest is always refused.
 * - Records are never evicted. A completed or outcome-unknown requestId stays sealed for the life of the store.
 * - When the store cannot accept a new record it refuses ([LedgerClaim.Rejected] LEDGER_FULL), never drops one.
 * - Transitions are idempotent and guarded: a terminal record is never moved back to a sendable state.
 *
 * Only a record in [releaseUnsent] state may be re-claimed; that is reserved for failures proven to occur before
 * the provider request was transmitted.
 */
interface RequestLedger {
    fun begin(requestId: String, digest: String): LedgerClaim
    /** Called immediately before each provider call. */
    fun markSending(requestId: String)
    /** Terminal success. A null [response] marks a finished but non-replayable (stream) call. */
    fun complete(requestId: String, response: ModelResponse?)
    /** Terminal: a provider call may have been made and its outcome is not known. Never re-sent. */
    fun markOutcomeUnknown(requestId: String)
    /** Only valid when no provider request was transmitted; the requestId may be claimed again. */
    fun releaseUnsent(requestId: String)
}

/**
 * Process-local ledger for development and tests ONLY. Lost on restart and not shared between instances, so it
 * does not provide idempotency in production. [ModelGateway] refuses to use it unless explicitly allowed.
 */
class InMemoryRequestLedger(private val capacity: Int = DEFAULT_CAPACITY) : RequestLedger {
    private enum class State { RESERVED, SENDING, RELEASED, COMPLETED, UNKNOWN }

    private class Record(val digest: String, var state: State, var response: ModelResponse?)

    private val records = HashMap<String, Record>()

    init { require(capacity > 0) { "capacity must be positive" } }

    @Synchronized
    override fun begin(requestId: String, digest: String): LedgerClaim {
        val r = records[requestId]
        if (r == null) {
            if (records.size >= capacity) return LedgerClaim.Rejected("LEDGER_FULL", "requestId ledger is full; new requests are refused")
            records[requestId] = Record(digest, State.RESERVED, null)
            return LedgerClaim.Claimed
        }
        if (r.digest != digest) return LedgerClaim.Rejected("REQUEST_ID_REUSED", "requestId was already used for a different request")
        return when (r.state) {
            State.RELEASED -> { r.state = State.RESERVED; LedgerClaim.Claimed }
            State.COMPLETED -> LedgerClaim.Completed(r.response)
            State.RESERVED, State.SENDING, State.UNKNOWN ->
                LedgerClaim.Rejected("DUPLICATE_REQUEST", "requestId is already in flight or its outcome is unknown")
        }
    }

    @Synchronized
    override fun markSending(requestId: String) {
        val r = records[requestId] ?: return
        if (r.state == State.RESERVED) r.state = State.SENDING
    }

    @Synchronized
    override fun complete(requestId: String, response: ModelResponse?) {
        val r = records[requestId] ?: return
        if (r.state == State.RESERVED || r.state == State.SENDING) { r.state = State.COMPLETED; r.response = response }
    }

    @Synchronized
    override fun markOutcomeUnknown(requestId: String) {
        val r = records[requestId] ?: return
        if (r.state == State.RESERVED || r.state == State.SENDING) r.state = State.UNKNOWN
    }

    @Synchronized
    override fun releaseUnsent(requestId: String) {
        val r = records[requestId] ?: return
        if (r.state == State.RESERVED || r.state == State.SENDING) r.state = State.RELEASED
    }

    private companion object { const val DEFAULT_CAPACITY = 10_000 }
}

/**
 * Request fingerprint: SHA-256 over an unambiguous canonical encoding of every field that can influence the
 * outcome (everything except requestId). Strings are length-prefixed and hashed per UTF-16 unit, so distinct
 * requests never share an encoding. Only the digest is stored; the request body is never logged or kept.
 */
internal object RequestDigest {
    fun of(req: ModelRequest): String {
        val c = Canon()
        c.s(req.principal); c.s(req.purpose.name); c.s(req.dataClass.name)
        c.list(req.budgetScopes) { c.s(it) }
        c.s(req.taskId); c.s(req.executionId); c.s(req.agentId)
        c.list(req.messages) { m -> c.s(m.role.name); c.list(m.parts) { part(c, it) } }
        c.list(req.tools) { t -> c.s(t.name); c.s(t.description); c.json(t.inputSchema) }
        c.s(req.toolChoice.name)
        when (val f = req.responseFormat) {
            ResponseFormat.Text -> c.s("text")
            is ResponseFormat.JsonSchema -> { c.s("json_schema"); c.s(f.name); c.json(f.schema) }
        }
        c.s(req.generation.maxOutputTokens.toString())
        c.s(req.generation.temperature?.toString())
        c.list(req.generation.stopSequences) { c.s(it) }
        // Appended only when set, so every request without thinking keeps its existing digest.
        req.generation.thinking?.let { c.s("thinking"); c.s(it.name) }
        val r = req.requirements
        c.list(r.requiredCapabilities.map { it.name }.sorted()) { c.s(it) }
        c.s(r.minContextTokens?.toString()); c.s(r.minQualityTier?.name)
        c.s(r.allowedProviders?.let { "set" })
        c.list(r.allowedProviders.orEmpty().sorted()) { c.s(it) }
        c.list(r.deniedProviders.sorted()) { c.s(it) }
        c.s(r.locality.name)
        c.s(req.preference.quality.toString()); c.s(req.preference.cost.toString())
        c.s(req.timeout.inWholeNanoseconds.toString())
        return sha256Hex(c.text())
    }

    private fun part(c: Canon, p: Part) {
        when (p) {
            is Part.Text -> { c.s("text"); c.s(p.text) }
            is Part.Image -> { c.s("image"); c.s(p.mediaType); c.s(p.base64Data) }
            is Part.ToolCall -> { c.s("tool_call"); c.s(p.id); c.s(p.name); c.json(p.arguments) }
            is Part.ToolResult -> { c.s("tool_result"); c.s(p.callId); c.s(p.content); c.s(p.isError.toString()) }
        }
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (ch in text) { md.update((ch.code ushr 8).toByte()); md.update(ch.code.toByte()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class Canon {
        private val sb = StringBuilder()

        fun text(): String = sb.toString()

        fun s(v: String?) {
            if (v == null) sb.append("-;") else sb.append(v.length).append(':').append(v).append(';')
        }

        fun <T> list(xs: Collection<T>, item: (T) -> Unit) {
            sb.append('[').append(xs.size).append(';')
            xs.forEach(item)
            sb.append(']')
        }

        fun json(e: JsonElement) {
            when (e) {
                is JsonNull -> sb.append('z')
                is JsonPrimitive -> { sb.append(if (e.isString) 'S' else 'N'); s(e.content) }
                is JsonArray -> { sb.append('a'); list(e) { json(it) } }
                is JsonObject -> { sb.append('o'); list(e.keys.sorted()) { k -> s(k); json(e.getValue(k)) } }
            }
        }
    }
}
