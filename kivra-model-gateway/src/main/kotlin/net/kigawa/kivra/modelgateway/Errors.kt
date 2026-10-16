package net.kigawa.kivra.modelgateway

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * Error taxonomy (design 05 section 3.5). [retrySameModel] / [fallback] drive the Gateway's
 * retry and fallback decisions. [OUTCOME_UNKNOWN] is what callers see when a provider call may have been
 * made and failed: it is never retried or fallen back under the same requestId.
 */
enum class ErrorKind(val retrySameModel: Boolean, val fallback: Boolean) {
    RATE_LIMITED(true, true),
    TRANSIENT(true, true),
    TIMEOUT(true, true),
    INVALID_REQUEST(false, false),
    AUTH(false, true),
    CONTENT_FILTER(false, false),
    POLICY_DENIED(false, false),
    CAPABILITY_UNSUPPORTED(false, true),
    CANCELLED(false, false),
    OUTPUT_INVALID(false, true),
    OUTCOME_UNKNOWN(false, false),
}

/**
 * Whether the provider request could have left the Gateway. [NOT_SENT] is only for failures proven to occur
 * before transmission (credential resolution, connection refused, DNS). Everything else is [MAYBE_SENT].
 */
enum class SendState { NOT_SENT, MAYBE_SENT }

data class GatewayError(
    val kind: ErrorKind,
    val message: String,
    /** Finer-grained reason, e.g. BUDGET / NO_ROUTE / CONTEXT_EXCEEDED. */
    val code: String? = null,
    val retryAfterMs: Long? = null,
    val providerStatus: Int? = null,
    /** Conservative default: an error is treated as possibly billed unless proven otherwise. */
    val sendState: SendState = SendState.MAYBE_SENT,
    /** Why the cost could not be established, when [code] is UNKNOWN_COST or the failure left a billed call unpriced. Fixed codes only. */
    val costReason: UnknownCostReason? = null,
)

class GatewayException(val error: GatewayError, cause: Throwable? = null) :
    RuntimeException("${error.kind}: ${error.message}", cause)

object ErrorClassifier {
    private const val MAX_MESSAGE = 500

    /** Classify an HTTP error from a provider. [body] is redacted/truncated before being kept. */
    fun fromHttp(status: Int, body: String, retryAfterHeader: String? = null, secrets: Collection<String> = emptyList()): GatewayError {
        val msg = redact("HTTP $status: $body", secrets)
        val retryAfter = retryAfterHeader?.trim()?.toLongOrNull()?.let { it * 1000 }
        return when {
            status == 429 -> GatewayError(ErrorKind.RATE_LIMITED, msg, retryAfterMs = retryAfter, providerStatus = status)
            status == 401 || status == 403 -> GatewayError(ErrorKind.AUTH, msg, providerStatus = status)
            status == 408 || status == 409 || status >= 500 -> GatewayError(ErrorKind.TRANSIENT, msg, retryAfterMs = retryAfter, providerStatus = status)
            status == 413 || (status == 400 && body.contains("too long", ignoreCase = true)) ->
                GatewayError(ErrorKind.INVALID_REQUEST, msg, code = "CONTEXT_EXCEEDED", providerStatus = status)
            status in 400..499 -> GatewayError(ErrorKind.INVALID_REQUEST, msg, providerStatus = status)
            else -> GatewayError(ErrorKind.TRANSIENT, msg, providerStatus = status)
        }
    }

    /**
     * Classify a thrown failure. Only connection-phase failures (the TCP/DNS step failed, so no request bytes
     * left) are [SendState.NOT_SENT]; any other transport or unexpected failure is [SendState.MAYBE_SENT].
     */
    fun fromThrowable(t: Throwable, secrets: Collection<String> = emptyList()): GatewayError {
        if (t is GatewayException) return t.error
        val name = t::class.simpleName.orEmpty()
        val msg = redact("$name: ${t.message.orEmpty()}", secrets)
        val send = if (isConnectionPhaseFailure(t)) SendState.NOT_SENT else SendState.MAYBE_SENT
        return when {
            name.contains("Timeout", ignoreCase = true) -> GatewayError(ErrorKind.TIMEOUT, msg, sendState = send)
            t is IOException -> GatewayError(ErrorKind.TRANSIENT, msg, sendState = send)
            else -> GatewayError(ErrorKind.TRANSIENT, msg, code = "UNEXPECTED", sendState = send)
        }
    }

    private fun isConnectionPhaseFailure(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(MAX_CAUSE_DEPTH).any {
            it is ConnectException || it is UnknownHostException || it is NoRouteToHostException ||
                it is UnresolvedAddressException || it::class.simpleName == "ConnectTimeoutException"
        }

    private const val MAX_CAUSE_DEPTH = 8

    /** Remove credentials from text and bound its size (credentials must never reach logs/errors). */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (s in secrets) if (s.isNotEmpty()) out = out.replace(s, "[REDACTED]")
        return if (out.length > MAX_MESSAGE) out.take(MAX_MESSAGE) + "..." else out
    }
}
