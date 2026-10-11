package net.kigawa.kivra.modelgateway

import java.io.IOException

/**
 * Error taxonomy (design 05 section 3.5). [retrySameModel] / [fallback] drive the Gateway's
 * retry and fallback decisions.
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
}

data class GatewayError(
    val kind: ErrorKind,
    val message: String,
    /** Finer-grained reason, e.g. BUDGET / NO_ROUTE / CONTEXT_EXCEEDED. */
    val code: String? = null,
    val retryAfterMs: Long? = null,
    val providerStatus: Int? = null,
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

    fun fromThrowable(t: Throwable, secrets: Collection<String> = emptyList()): GatewayError {
        val name = t::class.simpleName.orEmpty()
        val msg = redact("${t::class.simpleName}: ${t.message.orEmpty()}", secrets)
        return when {
            t is GatewayException -> t.error
            name.contains("Timeout", ignoreCase = true) -> GatewayError(ErrorKind.TIMEOUT, msg)
            t is IOException -> GatewayError(ErrorKind.TRANSIENT, msg)
            else -> GatewayError(ErrorKind.TRANSIENT, msg, code = "UNEXPECTED")
        }
    }

    /** Remove credentials from text and bound its size (credentials must never reach logs/errors). */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (s in secrets) if (s.isNotEmpty()) out = out.replace(s, "[REDACTED]")
        return if (out.length > MAX_MESSAGE) out.take(MAX_MESSAGE) + "..." else out
    }
}
