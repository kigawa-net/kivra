package net.kigawa.kivra.modelgateway

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorClassifierTest {
    @Test fun httpStatusMapping() {
        fun k(s: Int) = ErrorClassifier.fromHttp(s, "").kind
        assertEquals(ErrorKind.RATE_LIMITED, k(429))
        assertEquals(ErrorKind.TRANSIENT, k(500)); assertEquals(ErrorKind.TRANSIENT, k(503)); assertEquals(ErrorKind.TRANSIENT, k(529))
        assertEquals(ErrorKind.AUTH, k(401)); assertEquals(ErrorKind.AUTH, k(403))
        assertEquals(ErrorKind.INVALID_REQUEST, k(400)); assertEquals(ErrorKind.INVALID_REQUEST, k(404)); assertEquals(ErrorKind.INVALID_REQUEST, k(422))
    }

    @Test fun contextExceededIsInvalidRequestWithCode() {
        val e = ErrorClassifier.fromHttp(400, "prompt is too long: 300000 tokens")
        assertEquals(ErrorKind.INVALID_REQUEST, e.kind); assertEquals("CONTEXT_EXCEEDED", e.code)
        assertEquals("CONTEXT_EXCEEDED", ErrorClassifier.fromHttp(413, "").code)
    }

    @Test fun retryAfterIsParsedToMillis() {
        assertEquals(7000L, ErrorClassifier.fromHttp(429, "", "7").retryAfterMs)
        assertEquals(null, ErrorClassifier.fromHttp(429, "", "soon").retryAfterMs)
    }

    @Test fun throwableMapping() {
        class ConnectTimeoutException : IOException("x")
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.fromThrowable(ConnectTimeoutException()).kind)
        assertEquals(ErrorKind.TRANSIENT, ErrorClassifier.fromThrowable(IOException("reset")).kind)
        assertEquals(ErrorKind.POLICY_DENIED, ErrorClassifier.fromThrowable(GatewayException(GatewayError(ErrorKind.POLICY_DENIED, "x"))).kind)
    }

    @Test fun secretsAreRedactedAndMessagesBounded() {
        val e = ErrorClassifier.fromHttp(401, "bad key sk-secret-123 " + "z".repeat(2000), secrets = listOf("sk-secret-123"))
        assertFalse(e.message.contains("sk-secret-123")); assertTrue(e.message.contains("[REDACTED]")); assertTrue(e.message.length < 600)
    }

    @Test fun fallbackAndRetryPolicyMatchesDesignTable() {
        assertTrue(ErrorKind.RATE_LIMITED.retrySameModel && ErrorKind.RATE_LIMITED.fallback)
        assertTrue(ErrorKind.TRANSIENT.retrySameModel && ErrorKind.TRANSIENT.fallback)
        for (k in listOf(ErrorKind.INVALID_REQUEST, ErrorKind.CONTENT_FILTER, ErrorKind.POLICY_DENIED, ErrorKind.CANCELLED)) assertFalse(k.fallback, "$k")
        assertTrue(ErrorKind.AUTH.fallback && !ErrorKind.AUTH.retrySameModel)
        assertTrue(ErrorKind.CAPABILITY_UNSUPPORTED.fallback)
    }
}
